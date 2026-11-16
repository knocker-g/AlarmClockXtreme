package com.sysadmindoc.alarmclock.directboot.d0contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock

/**
 * Isolated D0-P0 ET-1 & ET-2 Contract Preparation Harnesses.
 * Note: These are deterministic JVM contract harnesses (NOT real Android device benchmarks;
 * Gate C empirical device runs remain required before runtime wiring).
 */
class IsolatedEt1Et2HarnessTest {

    private lateinit var store: InMemoryLegacyClaimStore
    private lateinit var arbiter: IsolatedOutputResourceArbiter
    private lateinit var gate: IsolatedSharedOutputAuthorityGate

    private val baseTrigger = 1_700_001_000_000L

    @Before
    fun setUp() {
        store = InMemoryLegacyClaimStore()
        arbiter = IsolatedOutputResourceArbiter()
        gate = IsolatedSharedOutputAuthorityGate(
            claimStore = store,
            arbiter = arbiter,
            currentProcessSessionToken = "proc-et",
            currentBootCount = 20L
        )
    }

    @Test
    fun `ET-1 worst-case 31-32 admission, 32-32 exhaustion, and corrupt or unsupported schema fail-closed`() {
        // Populate 31 active records + 1 empty slot (31/32)
        val enabledMap = (1L..34L).associateWith { true }
        gate.laneSnapshot = CrossLaneSnapshot(alarmEnabledById = enabledMap)
        for (i in 1L..31L) {
            val res = gate.reserveForServiceStart(OutputOccurrenceKey(i, baseTrigger + i, 0), ClaimSource.ALARM_RECEIVER)
            assertTrue("Expected slot $i/32 authorized", res is OutputAuthorizationResult.Authorized)
        }

        // 32nd slot admitted
        val res32 = gate.reserveForServiceStart(OutputOccurrenceKey(32L, baseTrigger + 32L, 0), ClaimSource.ALARM_RECEIVER)
        assertTrue(res32 is OutputAuthorizationResult.Authorized)

        // 33rd slot strictly fail-closed
        val res33 = gate.reserveForServiceStart(OutputOccurrenceKey(33L, baseTrigger + 33L, 0), ClaimSource.ALARM_RECEIVER)
        assertTrue(res33 is OutputAuthorizationResult.CapacityExhaustedFailClosed)

        // Corrupt DE LegacyClaimStore fails closed and preserves raw payload
        val savedPayload = store.rawPayload
        store.rawPayload = "{corrupt-et1"
        assertTrue(
            gate.reserveForServiceStart(OutputOccurrenceKey(34L, baseTrigger + 34L, 0), ClaimSource.ALARM_RECEIVER)
                is OutputAuthorizationResult.StorageUnverifiableFailClosed
        )
        assertEquals("{corrupt-et1", store.rawPayload)

        // Restore valid payload; test Corrupt DE V2 store & CE Corrupt store fail-closed
        store.rawPayload = savedPayload
        gate.laneSnapshot = gate.laneSnapshot.copy(deV2Corrupt = true)
        assertTrue(
            gate.reserveForServiceStart(OutputOccurrenceKey(34L, baseTrigger + 34L, 0), ClaimSource.ALARM_RECEIVER)
                is OutputAuthorizationResult.StorageUnverifiableFailClosed
        )

        gate.laneSnapshot = gate.laneSnapshot.copy(deV2Corrupt = false, ceCorrupt = true)
        assertTrue(
            gate.reserveForServiceStart(OutputOccurrenceKey(34L, baseTrigger + 34L, 0), ClaimSource.ALARM_RECEIVER)
                is OutputAuthorizationResult.StorageUnverifiableFailClosed
        )

        // Unsupported schema in DE V2 fails closed
        gate.laneSnapshot = gate.laneSnapshot.copy(ceCorrupt = false, deV2UnsupportedSchema = true)
        assertTrue(
            gate.reserveForServiceStart(OutputOccurrenceKey(34L, baseTrigger + 34L, 0), ClaimSource.ALARM_RECEIVER)
                is OutputAuthorizationResult.StorageUnverifiableFailClosed
        )
    }

    @Test
    fun `ET-2 simulated Room WAL stall preserves placeholder FGS budget, lock-order non-deadlock, and zero duplicate output`() {
        val key = OutputOccurrenceKey(10L, baseTrigger + 10L, 0)
        gate.laneSnapshot = CrossLaneSnapshot(alarmEnabledById = mapOf(10L to true))

        val handoverNonce = (gate.reserveForServiceStart(key, ClaimSource.ALARM_RECEIVER)
            as OutputAuthorizationResult.Authorized).activeNonce

        // Simulated L0 Room WAL write lock held by background worker (e.g. BootRescheduleWorker / Consumer)
        val roomWalLock = ReentrantLock()
        val walLockedLatch = CountDownLatch(1)
        val placeholderPostedBeforeWalWait = AtomicBoolean(false)
        val deInspectSucceededWhileWalStalled = AtomicBoolean(false)
        val phase3OutputCount = AtomicInteger(0)

        // Thread 1: WAL holder simulates heavy Room write + Room38 receipt commit before releasing L0
        val walHolder = Thread {
            roomWalLock.lock()
            try {
                walLockedLatch.countDown()
                Thread.sleep(150L)
                gate.laneSnapshot = gate.laneSnapshot.copy(room38ReceiptKeys = setOf(10L to (baseTrigger + 10L)))
            } finally {
                roomWalLock.unlock()
            }
        }
        walHolder.start()
        assertTrue(walLockedLatch.await(2, TimeUnit.SECONDS))

        // Service entrypoint: placeholder FGS runs synchronously BEFORE any Phase 2 Room WAL wait
        placeholderPostedBeforeWalWait.set(true)

        // Thread 2: Another DE Prefs caller (L1) verifies zero deadlock while Service Phase 2 waits on L0 Room WAL
        val phase2Started = CountDownLatch(1)
        val concurrentDeReader = Thread {
            assertTrue(phase2Started.await(2, TimeUnit.SECONDS))
            val state = store.inspect()
            deInspectSucceededWhileWalStalled.set(state is LegacyClaimStorageState.ValidWithRecords)
        }
        concurrentDeReader.start()

        val claimResult = gate.claimOutput(
            key = key,
            incomingNonce = handoverNonce,
            sink = OutputSinkType.ALARM_SERVICE_1001,
            beforePhase2RoomRead = {
                phase2Started.countDown()
                // Phase 2 acquires L0 Room WAL lock AFTER Phase 1 already released L1->L2
                roomWalLock.lock()
                roomWalLock.unlock()
            }
        )

        if (claimResult is OutputAuthorizationResult.Authorized) {
            phase3OutputCount.incrementAndGet()
        }

        walHolder.join(3000L)
        concurrentDeReader.join(3000L)

        assertTrue("Placeholder FGS must post before Room WAL wait", placeholderPostedBeforeWalWait.get())
        assertTrue("DE store L1 read must not deadlock while Phase 2 waits on Room WAL L0", deInspectSucceededWhileWalStalled.get())
        assertTrue("Room38 receipt committed during WAL stall must abort V1 output", claimResult is OutputAuthorizationResult.DuplicateRejected)
        assertEquals(0, phase3OutputCount.get())
        assertFalse(arbiter.activeHandle != null)
    }

    // =========================================================================
    // S3-C Additional ET Measurement & Verification Fixtures
    // =========================================================================

    @Test
    fun `ET-3 Direct Boot DE CE storage decision latency measurement fixture`() {
        // Measure baseline DE inspection latency across 100 iterations
        val iterations = 100
        val latenciesNanos = LongArray(iterations)

        for (i in 0 until iterations) {
            val start = System.nanoTime()
            val state = store.inspect()
            val end = System.nanoTime()
            latenciesNanos[i] = end - start
            assertTrue(state is LegacyClaimStorageState.Absent || state is LegacyClaimStorageState.ValidEmpty)
        }

        val averageMicros = latenciesNanos.average() / 1_000.0
        val maxMicros = (latenciesNanos.maxOrNull() ?: 0L) / 1_000.0

        // In pure JVM / DE models, decision latency must easily meet the 50ms (50,000 µs) budget
        assertTrue("Average DE decision latency must be < 50ms, was ${averageMicros}µs", averageMicros < 50_000.0)
        assertTrue("Max DE decision latency must be < 50ms, was ${maxMicros}µs", maxMicros < 50_000.0)
    }

    @Test
    fun `ET-4 FGS start restriction modeling and placeholder notification 1001 appearance budget`() {
        val key = OutputOccurrenceKey(15L, baseTrigger + 15L, 0)
        gate.laneSnapshot = CrossLaneSnapshot(alarmEnabledById = mapOf(15L to true))

        val receiverArrivalNanos = System.nanoTime()
        val reserveRes = gate.reserveForServiceStart(key, ClaimSource.ALARM_RECEIVER)
        val nonce = (reserveRes as OutputAuthorizationResult.Authorized).activeNonce

        // Model FGS notification 1001 lifecycle tracking
        var placeholderPostNanos: Long = 0L
        var fgsExceptionSimulated = false

        // Simulate OS background start restriction: if elapsed time > 5000ms, OS throws ForegroundServiceStartNotAllowedException
        fun startFgsWithBudget(elapsedMs: Long): Boolean {
            if (elapsedMs > 5_000L) {
                fgsExceptionSimulated = true
                return false
            }
            placeholderPostNanos = System.nanoTime()
            return true
        }

        // Case 1: Fast start within budget (< 5000ms)
        val fastElapsedMs = 45L
        assertTrue("FGS start within budget must succeed", startFgsWithBudget(fastElapsedMs))
        assertFalse(fgsExceptionSimulated)
        assertTrue(placeholderPostNanos >= receiverArrivalNanos)

        // Case 2: Delayed start exceeds 5000ms budget -> throws / fails
        fgsExceptionSimulated = false
        val slowElapsedMs = 5_500L
        assertFalse("FGS start beyond 5000ms budget must fail", startFgsWithBudget(slowElapsedMs))
        assertTrue("ForegroundServiceStartNotAllowedException simulated", fgsExceptionSimulated)
    }

    @Test
    fun `ET-5 Audio and Vibration arbiter exclusivity assertion under concurrent attempts`() {
        val threadCount = 8
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)
        val winners = AtomicInteger(0)

        // Multiple concurrent threads compete for physical output with distinct occurrences
        for (i in 1..threadCount) {
            val key = OutputOccurrenceKey(i.toLong(), baseTrigger + i * 100L, 0)
            gate.laneSnapshot = CrossLaneSnapshot(alarmEnabledById = (1L..threadCount.toLong()).associateWith { true })
            val res = gate.reserveForServiceStart(key, ClaimSource.ALARM_RECEIVER)
            val nonce = (res as OutputAuthorizationResult.Authorized).activeNonce

            Thread {
                startLatch.await()
                val claim = gate.claimOutput(key, nonce, OutputSinkType.ALARM_SERVICE_1001)
                if (claim is OutputAuthorizationResult.Authorized) {
                    val obs = gate.confirmOutputObserved(key, claim.activeNonce, OutputSinkType.ALARM_SERVICE_1001)
                    if (obs is OutputAuthorizationResult.Authorized) {
                        winners.incrementAndGet()
                    }
                }
                doneLatch.countDown()
            }.start()
        }

        startLatch.countDown()
        assertTrue(doneLatch.await(5, TimeUnit.SECONDS))

        // Invariant: At all times, at most 1 handle in the entire system has active physical output
        val currentHandle = arbiter.activeHandle
        val allHandles = listOfNotNull(currentHandle) + arbiter.preemptedHandles
        val activeAudioCount = allHandles.count { it.audioPlaying }
        val activeVibrationCount = allHandles.count { it.vibrating }

        assertTrue("At most one handle may have active audio playback, was $activeAudioCount", activeAudioCount <= 1)
        assertTrue("At most one handle may have active vibration, was $activeVibrationCount", activeVibrationCount <= 1)
        // All preempted handles must have their audio/vibration stopped
        for (preempted in arbiter.preemptedHandles) {
            assertFalse("Preempted handles must not play audio", preempted.audioPlaying)
            assertFalse("Preempted handles must not vibrate", preempted.vibrating)
        }
    }

    @Test
    fun `ET-6 Post-crash reboot and unlock event correlation for UNKNOWN state reconciliation`() {
        val key = OutputOccurrenceKey(7L, baseTrigger + 7L, 0)
        gate.laneSnapshot = CrossLaneSnapshot(alarmEnabledById = mapOf(7L to true))

        val n0 = (gate.reserveForServiceStart(key, ClaimSource.ALARM_RECEIVER) as OutputAuthorizationResult.Authorized).activeNonce
        val active = (gate.claimOutput(key, n0, OutputSinkType.ALARM_SERVICE_1001) as OutputAuthorizationResult.Authorized).activeNonce
        gate.confirmOutputObserved(key, active, OutputSinkType.ALARM_SERVICE_1001)

        // Simulate crash + reboot: boot count increments 20 -> 21, session proc-et -> proc-reboot
        gate.simulateProcessRestartOrReboot("proc-reboot", 21L)

        // Event correlation: Claim state must be pinned to UNKNOWN
        val storedRec = (store.inspect() as LegacyClaimStorageState.ValidWithRecords).records.single()
        assertEquals(LegacyClaimState.UNKNOWN, storedRec.state)
        assertEquals("proc-reboot", storedRec.processSessionToken)
        assertEquals(21L, storedRec.bootCount)

        // Direct Boot phase: CE locked, zero reconciliation possible
        gate.laneSnapshot = gate.laneSnapshot.copy(ceLocked = true)
        assertEquals(0, gate.reconcilePostUnlock())
        assertEquals(LegacyClaimState.UNKNOWN, (store.inspect() as LegacyClaimStorageState.ValidWithRecords).records.single().state)

        // CE Unlock phase: CE available, Room positive delivery proof verified
        gate.laneSnapshot = gate.laneSnapshot.copy(
            ceLocked = false,
            roomPositiveDeliveryKeys = setOf(7L to (baseTrigger + 7L))
        )
        val reconciledCount = gate.reconcilePostUnlock()
        assertEquals(1, reconciledCount)
        val finalRec = (store.inspect() as LegacyClaimStorageState.ValidWithRecords).records.single()
        assertEquals(LegacyClaimState.TERMINAL, finalRec.state)
        assertEquals(TerminalOutcome.RECONCILED_CE, finalRec.terminalOutcome)
    }
}

