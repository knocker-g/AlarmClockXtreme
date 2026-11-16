package com.sysadmindoc.alarmclock.directboot.outputauthority

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.entity.DirectBootOccurrenceReceipt
import com.sysadmindoc.alarmclock.directboot.BootProvenance
import com.sysadmindoc.alarmclock.directboot.DirectBootDurableOwner
import com.sysadmindoc.alarmclock.directboot.DirectBootDurableOwnerStore
import com.sysadmindoc.alarmclock.directboot.DirectBootEventConsumerEngine
import com.sysadmindoc.alarmclock.directboot.DirectBootLegacyClaimStore
import com.sysadmindoc.alarmclock.directboot.DirectBootStorageLock
import com.sysadmindoc.alarmclock.directboot.HandoverClaim
import com.sysadmindoc.alarmclock.directboot.LegacyClaimRecord
import com.sysadmindoc.alarmclock.directboot.LegacyClaimRetirementResult
import com.sysadmindoc.alarmclock.directboot.LegacyClaimSource
import com.sysadmindoc.alarmclock.directboot.LegacyClaimState
import com.sysadmindoc.alarmclock.directboot.LegacyClaimStorageState
import com.sysadmindoc.alarmclock.directboot.LegacyTerminalOutcome
import com.sysadmindoc.alarmclock.directboot.OutputOccurrenceKey
import com.sysadmindoc.alarmclock.directboot.OwnerState
import io.mockk.every
import io.mockk.spyk
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SharedOutputAuthorityGateTest {

    private lateinit var context: Context
    private lateinit var db: AlarmDatabase
    private lateinit var gate: SharedOutputAuthorityGate

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        clearStorage()
        gate = SharedOutputAuthorityGate(
            processSessionToken = "proc-test",
            bootCount = 10L
        )
    }

    @After
    fun tearDown() {
        DirectBootLegacyClaimStore.resetSeamsForTesting()
        gate.arbiter.clearForTesting()
        db.close()
        clearStorage()
    }

    private fun clearStorage() {
        DirectBootLegacyClaimStore.prefs(context)
            .edit()
            .remove(DirectBootLegacyClaimStore.KEY_LEGACY_CLAIM_STORE_PAYLOAD)
            .remove("direct_boot_v2_alarm_queue_payload")
            .remove(DirectBootDurableOwnerStore.KEY_DIRECT_BOOT_DURABLE_OWNER)
            .commit()
    }

    // =========================================================================
    // 1. Feature Flag OFF Behavior
    // =========================================================================

    @Test
    fun testFeatureFlag_defaultOff_returnsGateDisabled() {
        assertFalse(gate.isEnabled)
        val key = OutputOccurrenceKey(1L, 1_000L, 0)
        val reserveRes = gate.reserveForServiceStart(context, key, LegacyClaimSource.ALARM_RECEIVER)
        assertTrue(reserveRes is OutputAuthorizationResult.GateDisabled)

        val claimRes = gate.claimOutput(context, key, "nonce-1", OutputSinkType.ALARM_SERVICE_1001)
        assertTrue(claimRes is OutputAuthorizationResult.GateDisabled)
    }

    // =========================================================================
    // 2. Reserve for Service Start & Nonce Emission
    // =========================================================================

    @Test
    fun testReserveForServiceStart_emitsHandoverNonceAndFailsClosedOnDuplicate() {
        gate.isEnabled = true
        val key = OutputOccurrenceKey(10L, 1_000L, 0)

        val res1 = gate.reserveForServiceStart(context, key, LegacyClaimSource.ALARM_RECEIVER)
        assertTrue(res1 is OutputAuthorizationResult.Authorized)
        val nonce = (res1 as OutputAuthorizationResult.Authorized).activeNonce
        assertTrue(nonce.startsWith("handover-"))

        // Duplicate reservation must be rejected
        val res2 = gate.reserveForServiceStart(context, key, LegacyClaimSource.ALARM_RECEIVER)
        assertTrue(res2 is OutputAuthorizationResult.DuplicateRejected)
    }

    @Test
    fun testReserveForServiceStart_rejectsWhenOwnedByV2() {
        gate.isEnabled = true
        val key = OutputOccurrenceKey(20L, 2_000L, 0)

        // Seed V2 durable owner
        val owner = DirectBootDurableOwner(
            state = OwnerState.HANDOVER_PENDING,
            ownerSeq = 1L,
            bootProvenance = BootProvenance(bootCount = 10),
            handoverClaim = HandoverClaim("h-1", key.alarmId, key.scheduledAt, 1, "tok-1", 10L),
            updatedAtEpochMs = System.currentTimeMillis()
        )
        DirectBootDurableOwnerStore.write(context, owner, expectedOwnerSeq = 0L)

        val res = gate.reserveForServiceStart(context, key, LegacyClaimSource.ALARM_RECEIVER)
        assertTrue(res is OutputAuthorizationResult.V2LaneOwnedRejected)
    }

    // =========================================================================
    // 3. Claim Output: CAS Nonce & Preemption
    // =========================================================================

    @Test
    fun testClaimOutput_rotatesNonceAndPreventsDoubleConsumption() {
        gate.isEnabled = true
        val key = OutputOccurrenceKey(30L, 3_000L, 0)

        val reserveRes = gate.reserveForServiceStart(context, key, LegacyClaimSource.ALARM_RECEIVER)
        val handoverNonce = (reserveRes as OutputAuthorizationResult.Authorized).activeNonce

        // First claim: succeeds and rotates nonce
        val claim1 = gate.claimOutput(
            context = context,
            key = key,
            incomingNonce = handoverNonce,
            sink = OutputSinkType.ALARM_SERVICE_1001,
            roomReceiptChecker = { _, _ -> false }
        )
        assertTrue(claim1 is OutputAuthorizationResult.Authorized)
        val activeNonce = (claim1 as OutputAuthorizationResult.Authorized).activeNonce
        assertTrue(activeNonce.startsWith("active-"))

        // Second claim with old handoverNonce (e.g. racing Fallback): rejected!
        val claim2 = gate.claimOutput(
            context = context,
            key = key,
            incomingNonce = handoverNonce,
            sink = OutputSinkType.FULLSCREEN_FALLBACK_1001,
            roomReceiptChecker = { _, _ -> false }
        )
        assertTrue(claim2 is OutputAuthorizationResult.DuplicateRejected || claim2 is OutputAuthorizationResult.StaleOrConflictRejected)
    }

    @Test
    fun testClaimOutput_provenanceMismatch_marksUnknownAndBlocksOutput() {
        gate.isEnabled = true
        val key = OutputOccurrenceKey(40L, 4_000L, 0)

        val reserveRes = gate.reserveForServiceStart(context, key, LegacyClaimSource.ALARM_RECEIVER)
        val handoverNonce = (reserveRes as OutputAuthorizationResult.Authorized).activeNonce

        // Switch gate provenance (simulating unexpected process restart or boot count drift)
        gate.processSessionToken = "proc-different"

        val claim = gate.claimOutput(
            context = context,
            key = key,
            incomingNonce = handoverNonce,
            sink = OutputSinkType.ALARM_SERVICE_1001,
            roomReceiptChecker = { _, _ -> false }
        )
        assertTrue(claim is OutputAuthorizationResult.UnknownStateBlocked)

        val claimState = DirectBootLegacyClaimStore.inspectStorageState(context)
        val record = (claimState as LegacyClaimStorageState.ValidWithRecords).records.first { it.key == key }
        assertEquals(LegacyClaimState.UNKNOWN, record.state)
    }

    @Test
    fun testPreemption_preemptsPreviousActiveHandleAndTransitionsToTerminal() {
        gate.isEnabled = true
        val key1 = OutputOccurrenceKey(51L, 5_100L, 0)
        val key2 = OutputOccurrenceKey(52L, 5_200L, 0)

        val res1 = gate.reserveForServiceStart(context, key1, LegacyClaimSource.ALARM_RECEIVER)
        val nonce1 = (res1 as OutputAuthorizationResult.Authorized).activeNonce
        val claim1 = gate.claimOutput(
            context = context,
            key = key1,
            incomingNonce = nonce1,
            sink = OutputSinkType.ALARM_SERVICE_1001,
            roomReceiptChecker = { _, _ -> false }
        )
        assertTrue(claim1 is OutputAuthorizationResult.Authorized)
        assertEquals(key1, gate.arbiter.activeHandle?.key)

        // Candidate 2 arrives with confirmed stop
        var stopCalled = false
        val res2 = gate.reserveForServiceStart(context, key2, LegacyClaimSource.ALARM_RECEIVER)
        val nonce2 = (res2 as OutputAuthorizationResult.Authorized).activeNonce
        val claim2 = gate.claimOutput(
            context = context,
            key = key2,
            incomingNonce = nonce2,
            sink = OutputSinkType.ALARM_SERVICE_1001,
            roomReceiptChecker = { _, _ -> false },
            stopPreemptedOwner = { handle ->
                assertEquals(key1, handle.key)
                stopCalled = true
                true
            }
        )
        assertTrue(claim2 is OutputAuthorizationResult.Authorized)
        assertTrue(stopCalled)

        // Occurrence 2 is now active owner in arbiter
        assertEquals(key2, gate.arbiter.activeHandle?.key)

        // Occurrence 1 has been transitioned to TERMINAL(CONSUMED_PREEMPTED) in DE store
        val state = DirectBootLegacyClaimStore.inspectStorageState(context)
        val rec1 = (state as LegacyClaimStorageState.ValidWithRecords).records.first { it.key == key1 }
        assertEquals(LegacyClaimState.TERMINAL, rec1.state)
        assertEquals(LegacyTerminalOutcome.CONSUMED_PREEMPTED, rec1.terminalOutcome)
    }

    // =========================================================================
    // 4. Physical Output Observation & Abortion
    // =========================================================================

    @Test
    fun testObservePhysicalOutput_advancesToObservedWhenNoConflict() {
        gate.isEnabled = true
        val key = OutputOccurrenceKey(60L, 6_000L, 0)

        val res = gate.reserveForServiceStart(context, key, LegacyClaimSource.ALARM_RECEIVER)
        val nonce = (res as OutputAuthorizationResult.Authorized).activeNonce
        val claim = gate.claimOutput(
            context = context,
            key = key,
            incomingNonce = nonce,
            sink = OutputSinkType.ALARM_SERVICE_1001,
            roomReceiptChecker = { _, _ -> false }
        )
        val activeNonce = (claim as OutputAuthorizationResult.Authorized).activeNonce

        val obs = gate.observePhysicalOutput(
            context = context,
            key = key,
            activeNonce = activeNonce,
            sink = OutputSinkType.ALARM_SERVICE_1001,
            roomReceiptChecker = { _, _ -> false }
        )
        assertTrue(obs is OutputAuthorizationResult.Authorized)

        val state = DirectBootLegacyClaimStore.inspectStorageState(context)
        val rec = (state as LegacyClaimStorageState.ValidWithRecords).records.first { it.key == key }
        assertEquals(LegacyClaimState.OUTPUT_OBSERVED, rec.state)
    }

    @Test
    fun testObservePhysicalOutput_abortsWhenRoomReceiptCommittedPriorToAudio() {
        gate.isEnabled = true
        val key = OutputOccurrenceKey(70L, 7_000L, 0)

        val res = gate.reserveForServiceStart(context, key, LegacyClaimSource.ALARM_RECEIVER)
        val nonce = (res as OutputAuthorizationResult.Authorized).activeNonce
        val claim = gate.claimOutput(
            context = context,
            key = key,
            incomingNonce = nonce,
            sink = OutputSinkType.ALARM_SERVICE_1001,
            roomReceiptChecker = { _, _ -> false }
        )
        val activeNonce = (claim as OutputAuthorizationResult.Authorized).activeNonce

        // Simulate concurrent Room receipt committed right before audio
        val obs = gate.observePhysicalOutput(
            context = context,
            key = key,
            activeNonce = activeNonce,
            sink = OutputSinkType.ALARM_SERVICE_1001,
            roomReceiptChecker = { alarmId, schedAt -> alarmId == key.alarmId && schedAt == key.scheduledAt }
        )
        assertTrue(obs is OutputAuthorizationResult.DuplicateRejected)

        // Active handle must be released from arbiter
        assertNull(gate.arbiter.activeHandle)

        // Claim must be transitioned to TERMINAL(DUPLICATE_ABORTED)
        val state = DirectBootLegacyClaimStore.inspectStorageState(context)
        val rec = (state as LegacyClaimStorageState.ValidWithRecords).records.first { it.key == key }
        assertEquals(LegacyClaimState.TERMINAL, rec.state)
        assertEquals(LegacyTerminalOutcome.DUPLICATE_ABORTED, rec.terminalOutcome)
    }

    // =========================================================================
    // 5. Qualified Retirement via Verified Evidence
    // =========================================================================

    @Test
    fun testRetireClaimWithProof_retiresOnlyWithVerifiedEvidence() {
        gate.isEnabled = true
        val key = OutputOccurrenceKey(80L, 8_000L, 0)

        val res = gate.reserveForServiceStart(context, key, LegacyClaimSource.ALARM_RECEIVER)
        val nonce = (res as OutputAuthorizationResult.Authorized).activeNonce
        val claim = gate.claimOutput(
            context = context,
            key = key,
            incomingNonce = nonce,
            sink = OutputSinkType.ALARM_SERVICE_1001,
            roomReceiptChecker = { _, _ -> false }
        )
        val activeNonce = (claim as OutputAuthorizationResult.Authorized).activeNonce
        gate.observePhysicalOutput(
            context = context,
            key = key,
            activeNonce = activeNonce,
            sink = OutputSinkType.ALARM_SERVICE_1001,
            roomReceiptChecker = { _, _ -> false }
        )
        gate.completeTerminal(context, key, activeNonce, LegacyTerminalOutcome.CONSUMED_DISMISSED)

        // Attempt retirement before Room receipt exists: rejected
        val retireFail = gate.retireClaimWithProof(context, db, key)
        assertTrue(retireFail is LegacyClaimRetirementResult.Rejected)

        // Insert valid Room 38 receipt using Canonical Payload Hash V2
        val completedAt = 8_000_100L
        val receipt = DirectBootOccurrenceReceipt(
            occurrenceKey = "occ_${key.alarmId}_${key.scheduledAt}",
            sourceRecordId = "rec-80",
            recordType = "DELIVERED",
            alarmId = key.alarmId,
            triggerTime = key.scheduledAt,
            generation = 1,
            instanceToken = "tok-80",
            restoreEpoch = 10L,
            sourcePayloadHash = DirectBootEventConsumerEngine.calculateCanonicalPayloadHashV2(
                sourceRecordId = "rec-80",
                recordType = "DELIVERED",
                alarmId = key.alarmId,
                triggerTime = key.scheduledAt,
                eventTimestamp = completedAt,
                generation = 1,
                instanceToken = "tok-80",
                restoreEpoch = 10L,
                metadataString = ""
            ),
            consumerOutcome = "CONSUMED_DELIVERED",
            completedAt = completedAt
        )
        db.directBootOccurrenceReceiptDao().insert(receipt)

        // Attempt retirement now: succeeds and removes record from store
        val retireSuccess = gate.retireClaimWithProof(context, db, key)
        assertTrue(retireSuccess is LegacyClaimRetirementResult.Retired)
        assertEquals(0, (retireSuccess as LegacyClaimRetirementResult.Retired).remainingCount)
    }

    // =========================================================================
    // S5-A1 Safety Correction GREEN Tests
    // =========================================================================

    @Test
    fun testClaimOutput_nullRoomCheckerInCeMode_failsClosed() {
        gate.isEnabled = true
        val key = OutputOccurrenceKey(91L, 9_100L, 0)
        val res = gate.reserveForServiceStart(context, key, LegacyClaimSource.ALARM_RECEIVER)
        val nonce = (res as OutputAuthorizationResult.Authorized).activeNonce

        // In CE available mode, null roomReceiptChecker must be rejected fail-closed
        val claim = gate.claimOutput(
            context = context,
            key = key,
            incomingNonce = nonce,
            sink = OutputSinkType.ALARM_SERVICE_1001,
            directBootModeOnly = false,
            roomReceiptChecker = null
        )
        assertTrue("Null roomReceiptChecker in CE mode must fail closed", claim is OutputAuthorizationResult.StorageUnverifiableFailClosed)
    }

    @Test
    fun testPreemption_unconfirmedStopFailsAuthorization() {
        gate.isEnabled = true
        val key1 = OutputOccurrenceKey(92L, 9_200L, 0)
        val key2 = OutputOccurrenceKey(93L, 9_300L, 0)

        val res1 = gate.reserveForServiceStart(context, key1, LegacyClaimSource.ALARM_RECEIVER)
        val nonce1 = (res1 as OutputAuthorizationResult.Authorized).activeNonce
        val claim1 = gate.claimOutput(context, key1, nonce1, OutputSinkType.ALARM_SERVICE_1001, roomReceiptChecker = { _, _ -> false })
        assertTrue(claim1 is OutputAuthorizationResult.Authorized)

        val res2 = gate.reserveForServiceStart(context, key2, LegacyClaimSource.ALARM_RECEIVER)
        val nonce2 = (res2 as OutputAuthorizationResult.Authorized).activeNonce

        // Candidate 2 attempts preemption, but stopPreemptedOwner returns false
        val claim2 = gate.claimOutput(
            context = context,
            key = key2,
            incomingNonce = nonce2,
            sink = OutputSinkType.FULLSCREEN_FALLBACK_1001,
            roomReceiptChecker = { _, _ -> false },
            stopPreemptedOwner = { false }
        )
        assertFalse("New owner must not be authorized when stop confirmation fails", claim2 is OutputAuthorizationResult.Authorized)
        assertEquals("Prior owner must remain active in arbiter", key1, gate.arbiter.activeHandle?.key)
    }

    @Test
    fun testBootCount_unobtainableFailsClosed() {
        gate.isEnabled = true
        gate.bootCount = null
        gate.bootCountProvider = { null } // Unobtainable boot count

        val key = OutputOccurrenceKey(94L, 9_400L, 0)
        val res = gate.reserveForServiceStart(context, key, LegacyClaimSource.ALARM_RECEIVER)
        assertTrue("Unobtainable boot count must fail closed", res is OutputAuthorizationResult.StorageUnverifiableFailClosed)
    }

    @Test
    fun testProcessSessionToken_uniquePerLifetime() {
        val t1 = SharedOutputAuthorityGate.generateProcessSessionToken()
        val t2 = SharedOutputAuthorityGate.generateProcessSessionToken()
        assertNotEquals(t1, t2)
        assertTrue(t1.startsWith("proc-"))
    }

    // =========================================================================
    // S5-C1 Lock Separation & Abort Cleanup Falsification Tests
    // =========================================================================

    @Test
    fun testAbortClaim_neverCallsArbiterWhileHoldingDeLock() {
        val arbiterSpy = spyk(OutputResourceArbiter())
        var arbiterCalled = false
        var lockHeldDuringArbiterCall: Boolean? = null
        every { arbiterSpy.releaseIfMatches(any(), any()) } answers {
            arbiterCalled = true
            lockHeldDuringArbiterCall = Thread.holdsLock(DirectBootStorageLock.lock)
            callOriginal()
        }

        val localGate = SharedOutputAuthorityGate(
            arbiter = arbiterSpy,
            processSessionToken = "proc-test",
            bootCount = 10L
        ).apply { isEnabled = true }

        val key = OutputOccurrenceKey(201L, 20_100L, 0)
        val res = localGate.reserveForServiceStart(context, key, LegacyClaimSource.ALARM_RECEIVER)
        val handoverNonce = (res as OutputAuthorizationResult.Authorized).activeNonce
        val claim = localGate.claimOutput(
            context = context,
            key = key,
            incomingNonce = handoverNonce,
            sink = OutputSinkType.ALARM_SERVICE_1001,
            roomReceiptChecker = { _, _ -> false }
        )
        val activeNonce = (claim as OutputAuthorizationResult.Authorized).activeNonce

        val aborted = localGate.abortClaim(context, key, activeNonce, LegacyTerminalOutcome.REJECTED_NO_OUTPUT)
        assertTrue("abortClaim should succeed", aborted)
        assertTrue("Arbiter releaseIfMatches must be invoked", arbiterCalled)
        assertEquals("Arbiter MUST NOT be called while holding DirectBootStorageLock.lock", false, lockHeldDuringArbiterCall)
        assertNull("Arbiter handle must be released after DE commit", arbiterSpy.activeHandle)

        val storage = DirectBootLegacyClaimStore.inspectStorageState(context) as LegacyClaimStorageState.ValidWithRecords
        val rec = storage.records.first { it.key == key }
        assertEquals(LegacyClaimState.TERMINAL, rec.state)
        assertEquals(LegacyTerminalOutcome.REJECTED_NO_OUTPUT, rec.terminalOutcome)
    }

    @Test
    fun testAbortClaim_commitFalse_doesNotReleaseOwnerAndReturnsFalse() {
        gate.isEnabled = true
        val key = OutputOccurrenceKey(202L, 20_200L, 0)
        val res = gate.reserveForServiceStart(context, key, LegacyClaimSource.ALARM_RECEIVER)
        val handoverNonce = (res as OutputAuthorizationResult.Authorized).activeNonce
        val claim = gate.claimOutput(
            context = context,
            key = key,
            incomingNonce = handoverNonce,
            sink = OutputSinkType.ALARM_SERVICE_1001,
            roomReceiptChecker = { _, _ -> false }
        )
        val activeNonce = (claim as OutputAuthorizationResult.Authorized).activeNonce
        assertEquals(key, gate.arbiter.activeHandle?.key)

        DirectBootLegacyClaimStore.commitSeam = { _, _ -> false }
        val aborted = try {
            gate.abortClaim(context, key, activeNonce, LegacyTerminalOutcome.REJECTED_NO_OUTPUT)
        } finally {
            DirectBootLegacyClaimStore.resetSeamsForTesting()
        }

        assertFalse("abortClaim MUST return false when commitSeam returns false", aborted)
        assertNotNull("Arbiter owner MUST NOT be released on persistence failure", gate.arbiter.activeHandle)
        assertEquals(activeNonce, gate.arbiter.activeHandle?.activeNonce)

        val storage = DirectBootLegacyClaimStore.inspectStorageState(context) as LegacyClaimStorageState.ValidWithRecords
        val rec = storage.records.first { it.key == key }
        assertEquals(LegacyClaimState.OUTPUT_CLAIMING, rec.state)
    }

    @Test
    fun testAbortClaim_commitExceptionAndReadbackMismatch_failClosed() {
        gate.isEnabled = true
        val key1 = OutputOccurrenceKey(203L, 20_300L, 0)
        val res1 = gate.reserveForServiceStart(context, key1, LegacyClaimSource.ALARM_RECEIVER)
        val handover1 = (res1 as OutputAuthorizationResult.Authorized).activeNonce
        val claim1 = gate.claimOutput(
            context = context,
            key = key1,
            incomingNonce = handover1,
            sink = OutputSinkType.ALARM_SERVICE_1001,
            roomReceiptChecker = { _, _ -> false }
        )
        val activeNonce1 = (claim1 as OutputAuthorizationResult.Authorized).activeNonce

        // Subcase A: commitSeam throws exception
        DirectBootLegacyClaimStore.commitSeam = { _, _ -> throw RuntimeException("Simulated DE commit failure") }
        val abortOnException = try {
            gate.abortClaim(context, key1, activeNonce1, LegacyTerminalOutcome.REJECTED_NO_OUTPUT)
        } finally {
            DirectBootLegacyClaimStore.resetSeamsForTesting()
        }
        assertFalse("abortClaim MUST return false on commit exception", abortOnException)
        assertEquals(key1, gate.arbiter.activeHandle?.key)

        // Subcase B: readbackSeam returns stale payload (readback mismatch)
        val stalePayload = DirectBootLegacyClaimStore.prefs(context)
            .getString(DirectBootLegacyClaimStore.KEY_LEGACY_CLAIM_STORE_PAYLOAD, null)
        assertNotNull(stalePayload)
        DirectBootLegacyClaimStore.readbackSeam = { _ -> stalePayload }
        val abortOnMismatch = try {
            gate.abortClaim(context, key1, activeNonce1, LegacyTerminalOutcome.REJECTED_NO_OUTPUT)
        } finally {
            DirectBootLegacyClaimStore.resetSeamsForTesting()
        }
        assertFalse("abortClaim MUST return false on readback mismatch", abortOnMismatch)
        assertEquals("Arbiter owner MUST NOT be released on readback mismatch", activeNonce1, gate.arbiter.activeHandle?.activeNonce)
    }

    @Test
    fun testAbortClaim_staleNonce_refusesToReleaseCompetingNewOwner() {
        gate.isEnabled = true
        val key1 = OutputOccurrenceKey(204L, 20_400L, 0)
        val res1 = gate.reserveForServiceStart(context, key1, LegacyClaimSource.ALARM_RECEIVER)
        val handover1 = (res1 as OutputAuthorizationResult.Authorized).activeNonce
        val claim1 = gate.claimOutput(
            context = context,
            key = key1,
            incomingNonce = handover1,
            sink = OutputSinkType.ALARM_SERVICE_1001,
            roomReceiptChecker = { _, _ -> false }
        )
        val activeNonce1 = (claim1 as OutputAuthorizationResult.Authorized).activeNonce

        // Stale handover nonce cannot abort active OUTPUT_CLAIMING owner
        val staleSameKey = gate.abortClaim(context, key1, handover1, LegacyTerminalOutcome.REJECTED_NO_OUTPUT)
        assertFalse("Stale handover nonce MUST be rejected", staleSameKey)
        assertEquals(activeNonce1, gate.arbiter.activeHandle?.activeNonce)

        // Competing new owner (key2) preempts key1
        val key2 = OutputOccurrenceKey(205L, 20_500L, 0)
        val res2 = gate.reserveForServiceStart(context, key2, LegacyClaimSource.FIRE_WATCHDOG)
        val handover2 = (res2 as OutputAuthorizationResult.Authorized).activeNonce
        val claim2 = gate.claimOutput(
            context = context,
            key = key2,
            incomingNonce = handover2,
            sink = OutputSinkType.FULLSCREEN_FALLBACK_1001,
            roomReceiptChecker = { _, _ -> false },
            stopPreemptedOwner = { true }
        )
        val activeNonce2 = (claim2 as OutputAuthorizationResult.Authorized).activeNonce
        assertEquals(key2, gate.arbiter.activeHandle?.key)

        // Stale nonce1 or handover2 cannot release competing owner key2
        assertFalse(gate.abortClaim(context, key1, activeNonce1, LegacyTerminalOutcome.REJECTED_NO_OUTPUT))
        assertFalse(gate.abortClaim(context, key2, activeNonce1, LegacyTerminalOutcome.REJECTED_NO_OUTPUT))
        assertFalse(gate.abortClaim(context, key2, handover2, LegacyTerminalOutcome.REJECTED_NO_OUTPUT))
        assertEquals("Competing owner key2 MUST remain active in arbiter", key2, gate.arbiter.activeHandle?.key)
        assertEquals(activeNonce2, gate.arbiter.activeHandle?.activeNonce)

        // Provenance mismatch cannot abort active owner
        val originalToken = gate.processSessionToken
        gate.processSessionToken = "proc-other-session"
        assertFalse("Provenance mismatch MUST reject abortClaim fail-closed", gate.abortClaim(context, key2, activeNonce2))
        gate.processSessionToken = originalToken
        assertEquals(activeNonce2, gate.arbiter.activeHandle?.activeNonce)
    }

    @Test
    fun testAbortClaim_concurrentAbortAndNewClaim_serializesSafely() {
        gate.isEnabled = true
        val executor = Executors.newFixedThreadPool(2)
        try {
            for (iter in 1..10) {
                clearStorage()
                gate.arbiter.clearForTesting()

                val key1 = OutputOccurrenceKey(300L + iter, 30_000L + iter, 0)
                val res1 = gate.reserveForServiceStart(context, key1, LegacyClaimSource.ALARM_RECEIVER)
                val h1 = (res1 as OutputAuthorizationResult.Authorized).activeNonce
                val c1 = gate.claimOutput(
                    context = context,
                    key = key1,
                    incomingNonce = h1,
                    sink = OutputSinkType.ALARM_SERVICE_1001,
                    roomReceiptChecker = { _, _ -> false }
                )
                val active1 = (c1 as OutputAuthorizationResult.Authorized).activeNonce

                val key2 = OutputOccurrenceKey(400L + iter, 40_000L + iter, 0)
                val res2 = gate.reserveForServiceStart(context, key2, LegacyClaimSource.FIRE_WATCHDOG)
                val h2 = (res2 as OutputAuthorizationResult.Authorized).activeNonce

                val barrier = CyclicBarrier(2)
                val fAbort = executor.submit<Boolean> {
                    barrier.await()
                    gate.abortClaim(context, key1, active1, LegacyTerminalOutcome.REJECTED_NO_OUTPUT)
                }
                val fClaim = executor.submit<OutputAuthorizationResult> {
                    barrier.await()
                    gate.claimOutput(
                        context = context,
                        key = key2,
                        incomingNonce = h2,
                        sink = OutputSinkType.ALARM_SERVICE_1001,
                        roomReceiptChecker = { _, _ -> false },
                        stopPreemptedOwner = { handle -> gate.arbiter.requestStop(handle) }
                    )
                }

                fAbort.get(5, TimeUnit.SECONDS)
                val claimRes2 = fClaim.get(5, TimeUnit.SECONDS)
                assertTrue("Competing claim for key2 must succeed cleanly: $claimRes2", claimRes2 is OutputAuthorizationResult.Authorized)
                val active2 = (claimRes2 as OutputAuthorizationResult.Authorized).activeNonce

                // key2 MUST remain active in arbiter and key1 MUST be TERMINAL in DE store
                assertEquals(key2, gate.arbiter.activeHandle?.key)
                assertEquals(active2, gate.arbiter.activeHandle?.activeNonce)
                val storage = DirectBootLegacyClaimStore.inspectStorageState(context) as LegacyClaimStorageState.ValidWithRecords
                assertEquals(LegacyClaimState.TERMINAL, storage.records.first { it.key == key1 }.state)
                assertEquals(LegacyClaimState.OUTPUT_CLAIMING, storage.records.first { it.key == key2 }.state)
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun testAbortClaim_unknownCorruptAndUnsupportedSchema_rejectsFailClosed() {
        gate.isEnabled = true

        // Subcase A: UNKNOWN record in DE store
        val keyUnknown = OutputOccurrenceKey(501L, 50_100L, 0)
        val unknownNonce = "nonce-unk-501"
        DirectBootLegacyClaimStore.writeInitialClaim(
            context = context,
            record = LegacyClaimRecord(
                alarmId = keyUnknown.alarmId,
                scheduledAt = keyUnknown.scheduledAt,
                refireCount = 0,
                source = LegacyClaimSource.ALARM_RECEIVER,
                claimNonce = unknownNonce,
                processSessionToken = "proc-test",
                bootCount = 10L,
                state = LegacyClaimState.START_REQUESTED
            )
        )
        DirectBootLegacyClaimStore.transition(
            context = context,
            key = keyUnknown,
            expectedFrom = LegacyClaimState.START_REQUESTED,
            toState = LegacyClaimState.UNKNOWN,
            expectedNonce = unknownNonce,
            newNonce = unknownNonce,
            processSessionToken = "proc-test",
            bootCount = 10L
        )
        assertFalse("UNKNOWN state MUST fail closed on abortClaim", gate.abortClaim(context, keyUnknown, unknownNonce))
        val afterUnknown = DirectBootLegacyClaimStore.inspectStorageState(context) as LegacyClaimStorageState.ValidWithRecords
        assertEquals(LegacyClaimState.UNKNOWN, afterUnknown.records.first { it.key == keyUnknown }.state)

        // Subcase B: Corrupt DE store payload
        val keyCorrupt = OutputOccurrenceKey(502L, 50_200L, 0)
        gate.arbiter.acquirePending(keyCorrupt, OutputSinkType.ALARM_SERVICE_1001, "nonce-corrupt")
        val corruptRaw = "{not-valid-json"
        DirectBootLegacyClaimStore.prefs(context)
            .edit()
            .putString(DirectBootLegacyClaimStore.KEY_LEGACY_CLAIM_STORE_PAYLOAD, corruptRaw)
            .commit()
        assertFalse("Corrupt storage MUST reject abortClaim fail-closed", gate.abortClaim(context, keyCorrupt, "nonce-corrupt"))
        assertEquals("Arbiter handle MUST NOT be released on corrupt storage", keyCorrupt, gate.arbiter.activeHandle?.key)
        assertEquals(
            corruptRaw,
            DirectBootLegacyClaimStore.prefs(context).getString(DirectBootLegacyClaimStore.KEY_LEGACY_CLAIM_STORE_PAYLOAD, null)
        )

        // Subcase C: UnsupportedSchema DE store payload
        val unsupportedRaw = """{"schemaVersion":99,"records":[]}"""
        DirectBootLegacyClaimStore.prefs(context)
            .edit()
            .putString(DirectBootLegacyClaimStore.KEY_LEGACY_CLAIM_STORE_PAYLOAD, unsupportedRaw)
            .commit()
        assertFalse("UnsupportedSchema MUST reject abortClaim fail-closed", gate.abortClaim(context, keyCorrupt, "nonce-corrupt"))
        assertEquals("Arbiter handle MUST NOT be released on unsupported schema", keyCorrupt, gate.arbiter.activeHandle?.key)
        assertEquals(
            unsupportedRaw,
            DirectBootLegacyClaimStore.prefs(context).getString(DirectBootLegacyClaimStore.KEY_LEGACY_CLAIM_STORE_PAYLOAD, null)
        )
    }
}
