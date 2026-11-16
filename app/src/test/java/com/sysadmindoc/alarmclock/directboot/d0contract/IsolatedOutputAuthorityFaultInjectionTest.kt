package com.sysadmindoc.alarmclock.directboot.d0contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Isolated D0-P0 Fault Injection Suite (FI-01 .. FI-14):
 * Covers SharedOutputAuthorityGate, ClaimNonce Handoff, WakeConfirm Lineage,
 * Cross-Lane Arbitration (1001 vs 1011 & Room38 Consumer Race), Fail-Closed Persistence,
 * Terminal Retirement Proof, and Old Ledger Migration Crash Recovery.
 */
class IsolatedOutputAuthorityFaultInjectionTest {

    private lateinit var store: InMemoryLegacyClaimStore
    private lateinit var arbiter: IsolatedOutputResourceArbiter
    private lateinit var gate: IsolatedSharedOutputAuthorityGate

    private val trig1 = 1_700_000_100_000L
    private val trig2 = 1_700_000_200_000L

    @Before
    fun setUp() {
        store = InMemoryLegacyClaimStore()
        arbiter = IsolatedOutputResourceArbiter()
        gate = IsolatedSharedOutputAuthorityGate(
            claimStore = store,
            arbiter = arbiter,
            currentProcessSessionToken = "proc-A",
            currentBootCount = 10L
        )
        gate.laneSnapshot = CrossLaneSnapshot(
            alarmEnabledById = mapOf(1L to true, 2L to true, 10L to true),
            alarmRecurringById = mapOf(1L to true, 2L to false, 10L to false)
        )
    }

    @Test
    fun `FI-01 simultaneous Service vs Fallback claim allows only one sink`() {
        val key = OutputOccurrenceKey(1L, trig1, 0)
        val res = gate.reserveForServiceStart(key, ClaimSource.ALARM_RECEIVER)
        val nonce = (res as OutputAuthorizationResult.Authorized).activeNonce

        val serviceClaim = gate.claimOutput(key, nonce, OutputSinkType.ALARM_SERVICE_1001)
        assertTrue(serviceClaim is OutputAuthorizationResult.Authorized)

        val fallbackRace = gate.claimOutputForFallback(key, nonce)
        assertTrue(
            fallbackRace is OutputAuthorizationResult.StaleOrConflictRejected ||
                fallbackRace is OutputAuthorizationResult.DuplicateRejected
        )
        assertEquals(OutputSinkType.ALARM_SERVICE_1001, arbiter.activeHandle?.sink)
    }

    @Test
    fun `FI-02 replayed or stolen claimNonce and raw intent extras without claim are rejected`() {
        val key = OutputOccurrenceKey(1L, trig1, 0)
        val res = gate.reserveForServiceStart(key, ClaimSource.ALARM_RECEIVER)
        val handoverNonce = (res as OutputAuthorizationResult.Authorized).activeNonce

        val claimed = gate.claimOutput(key, handoverNonce, OutputSinkType.ALARM_SERVICE_1001)
        val activeNonce = (claimed as OutputAuthorizationResult.Authorized).activeNonce
        assertNotEquals(handoverNonce, activeNonce)

        val replay = gate.claimOutput(key, handoverNonce, OutputSinkType.ALARM_SERVICE_1001)
        assertTrue(
            replay is OutputAuthorizationResult.StaleOrConflictRejected ||
                replay is OutputAuthorizationResult.DuplicateRejected
        )

        val forged = gate.claimOutput(
            key = OutputOccurrenceKey(1L, trig1, 2),
            incomingNonce = "forged-nonce",
            sink = OutputSinkType.ALARM_SERVICE_1001
        )
        assertTrue(
            forged is OutputAuthorizationResult.StaleOrConflictRejected ||
                forged is OutputAuthorizationResult.WakeConfirmUnauthorized
        )
    }

    @Test
    fun `FI-03 DirectBoot 1011 vs AlarmService 1001 same occurrence rejects duplicate while different occurrence preempts cleanly`() {
        val keyA = OutputOccurrenceKey(1L, trig1, 0)
        // Direct Boot mode starts 1011 for keyA
        gate.laneSnapshot = gate.laneSnapshot.copy(ceLocked = true)
        val nA = (gate.reserveForServiceStart(keyA, ClaimSource.DIRECT_BOOT_RECEIVER, directBootModeOnly = true)
            as OutputAuthorizationResult.Authorized).activeNonce
        val actA = (gate.claimOutput(keyA, nA, OutputSinkType.DIRECT_BOOT_SERVICE_1011, directBootModeOnly = true)
            as OutputAuthorizationResult.Authorized).activeNonce
        assertTrue(gate.confirmOutputObserved(keyA, actA, OutputSinkType.DIRECT_BOOT_SERVICE_1011) is OutputAuthorizationResult.Authorized)
        assertEquals(OutputSinkType.DIRECT_BOOT_SERVICE_1011, arbiter.activeHandle?.sink)

        // CE unlocks; AlarmService 1001 / FireWatchdog attempts SAME occurrence keyA -> rejected
        gate.laneSnapshot = gate.laneSnapshot.copy(ceLocked = false)
        val sameOccReserve = gate.reserveForServiceStart(keyA, ClaimSource.FIRE_WATCHDOG)
        assertTrue(sameOccReserve is OutputAuthorizationResult.DuplicateRejected)
        assertEquals(keyA, arbiter.activeHandle?.key)
        assertEquals(OutputSinkType.DIRECT_BOOT_SERVICE_1011, arbiter.activeHandle?.sink)

        // Different occurrence keyB arrives on AlarmService 1001 -> cleanly preempts 1011
        val keyB = OutputOccurrenceKey(2L, trig2, 0)
        val nB = (gate.reserveForServiceStart(keyB, ClaimSource.ALARM_RECEIVER)
            as OutputAuthorizationResult.Authorized).activeNonce
        val actB = (gate.claimOutput(keyB, nB, OutputSinkType.ALARM_SERVICE_1001)
            as OutputAuthorizationResult.Authorized).activeNonce
        assertTrue(gate.confirmOutputObserved(keyB, actB, OutputSinkType.ALARM_SERVICE_1001) is OutputAuthorizationResult.Authorized)
        assertEquals(keyB, arbiter.activeHandle?.key)
        assertEquals(OutputSinkType.ALARM_SERVICE_1001, arbiter.activeHandle?.sink)
        assertTrue(arbiter.preemptedHandles.any { it.key == keyA && it.sink == OutputSinkType.DIRECT_BOOT_SERVICE_1011 })
        val recA = (store.inspect() as LegacyClaimStorageState.ValidWithRecords).records.first { it.key == keyA }
        assertEquals(LegacyClaimState.TERMINAL, recA.state)
        assertEquals(TerminalOutcome.CONSUMED_PREEMPTED, recA.terminalOutcome)
    }

    @Test
    fun `FI-04 cross-lane V2 and Room38 receipt collision blocks V1 output and consumer race produces zero duplicate output`() {
        val key = OutputOccurrenceKey(1L, trig1, 0)
        gate.laneSnapshot = gate.laneSnapshot.copy(v2LedgerKeys = setOf(1L to trig1))
        assertTrue(gate.reserveForServiceStart(key, ClaimSource.ALARM_RECEIVER) is OutputAuthorizationResult.V2LaneOwnedRejected)

        // Consumer race simulation: Phase 1 sees CeTakeover released, Phase 2 sees Room38 receipt committed
        gate.laneSnapshot = CrossLaneSnapshot(
            alarmEnabledById = mapOf(1L to true),
            ceTakeoverKeys = emptySet(),
            room38ReceiptKeys = emptySet()
        )
        val n0 = (gate.reserveForServiceStart(key, ClaimSource.ALARM_RECEIVER) as OutputAuthorizationResult.Authorized).activeNonce
        val res = gate.claimOutput(
            key = key,
            incomingNonce = n0,
            sink = OutputSinkType.ALARM_SERVICE_1001,
            beforePhase2RoomRead = {
                gate.laneSnapshot = gate.laneSnapshot.copy(room38ReceiptKeys = setOf(1L to trig1))
            }
        )
        assertTrue(res is OutputAuthorizationResult.DuplicateRejected)
        val rec = (store.inspect() as LegacyClaimStorageState.ValidWithRecords).records.single()
        assertEquals(LegacyClaimState.TERMINAL, rec.state)
        assertEquals(TerminalOutcome.DUPLICATE_ABORTED, rec.terminalOutcome)
        assertEquals(null, arbiter.activeHandle)
    }

    @Test
    fun `FI-05 WakeConfirm lineage verifies dismissed primary, single-use stepToken, monotonic 1-3, disabled ONE_TIME, and snooze isolation`() {
        val key0 = OutputOccurrenceKey(2L, trig1, 0)
        val n0 = (gate.reserveForServiceStart(key0, ClaimSource.ALARM_RECEIVER) as OutputAuthorizationResult.Authorized).activeNonce
        val active0 = (gate.claimOutput(key0, n0, OutputSinkType.ALARM_SERVICE_1001) as OutputAuthorizationResult.Authorized).activeNonce
        assertTrue(gate.confirmOutputObserved(key0, active0, OutputSinkType.ALARM_SERVICE_1001) is OutputAuthorizationResult.Authorized)

        // Snooze does NOT authorize WakeConfirm
        assertTrue(gate.completeTerminal(key0, active0, TerminalOutcome.CONSUMED_SNOOZED, enableWakeConfirm = false) is OutputAuthorizationResult.Authorized)
        assertTrue(gate.reserveWakeConfirmRefire(2L, trig1, 1, "any-token") is OutputAuthorizationResult.WakeConfirmUnauthorized)

        // Snoozed occurrence fires on distinct trigger trig2, gets dismissed with WakeConfirm enabled
        val snoozeKey = OutputOccurrenceKey(2L, trig2, 0)
        val ns = (gate.reserveForServiceStart(snoozeKey, ClaimSource.ALARM_RECEIVER) as OutputAuthorizationResult.Authorized).activeNonce
        val activeS = (gate.claimOutput(snoozeKey, ns, OutputSinkType.ALARM_SERVICE_1001) as OutputAuthorizationResult.Authorized).activeNonce
        assertTrue(gate.confirmOutputObserved(snoozeKey, activeS, OutputSinkType.ALARM_SERVICE_1001) is OutputAuthorizationResult.Authorized)
        assertTrue(gate.completeTerminal(snoozeKey, activeS, TerminalOutcome.CONSUMED_DISMISSED, enableWakeConfirm = true) is OutputAuthorizationResult.Authorized)

        // Disable ONE_TIME alarm 2 in Room (simulating handleAlarmFired)
        gate.laneSnapshot = gate.laneSnapshot.copy(
            alarmEnabledById = gate.laneSnapshot.alarmEnabledById + (2L to false),
            roomDismissedKeys = setOf(2L to trig2)
        )

        val record = (store.inspect() as LegacyClaimStorageState.ValidWithRecords).records.first { it.scheduledAt == trig2 }
        val step1Token = record.wakeConfirm!!.stepToken

        // Refire 1 succeeds even though ONE_TIME is disabled
        val refire1Reserve = gate.reserveWakeConfirmRefire(2L, trig2, 1, step1Token)
        assertTrue(refire1Reserve is OutputAuthorizationResult.Authorized)
        val n1 = (refire1Reserve as OutputAuthorizationResult.Authorized).activeNonce

        // Repeated step1Token fails
        assertTrue(gate.reserveWakeConfirmRefire(2L, trig2, 1, step1Token) is OutputAuthorizationResult.WakeConfirmUnauthorized)

        // Claim and observe refire 1 -> dismiss
        val active1 = (gate.claimOutput(OutputOccurrenceKey(2L, trig2, 1), n1, OutputSinkType.ALARM_SERVICE_1001) as OutputAuthorizationResult.Authorized).activeNonce
        assertTrue(gate.confirmOutputObserved(OutputOccurrenceKey(2L, trig2, 1), active1, OutputSinkType.ALARM_SERVICE_1001) is OutputAuthorizationResult.Authorized)
        assertTrue(gate.completeTerminal(OutputOccurrenceKey(2L, trig2, 1), active1, TerminalOutcome.CONSUMED_DISMISSED, enableWakeConfirm = true) is OutputAuthorizationResult.Authorized)

        // Regression back to refire 1 rejected
        assertTrue(gate.reserveWakeConfirmRefire(2L, trig2, 1, "bad") is OutputAuthorizationResult.WakeConfirmUnauthorized)

        // Refire 4 rejected (> MAX_REFIRES = 3)
        assertTrue(gate.reserveWakeConfirmRefire(2L, trig2, 4, "bad") is OutputAuthorizationResult.WakeConfirmUnauthorized)

        // User confirms awake on trig2 -> refire 2 is now unauthorized, while new normal occurrence T3 > trig2 remains independent
        assertTrue(gate.confirmWakeAwake(2L, trig2))
        val step2Token = (store.inspect() as LegacyClaimStorageState.ValidWithRecords).records
            .first { it.scheduledAt == trig2 && it.refireCount == 0 }.wakeConfirm!!.stepToken
        assertEquals("", step2Token)
        assertTrue(gate.reserveWakeConfirmRefire(2L, trig2, 2, "any") is OutputAuthorizationResult.WakeConfirmUnauthorized)

        val trig3 = trig2 + 86_400_000L
        gate.laneSnapshot = gate.laneSnapshot.copy(alarmEnabledById = gate.laneSnapshot.alarmEnabledById + (2L to true))
        val normalT3 = OutputOccurrenceKey(2L, trig3, 0)
        assertTrue(gate.reserveForServiceStart(normalT3, ClaimSource.ALARM_RECEIVER) is OutputAuthorizationResult.Authorized)
    }

    @Test
    fun `FI-06 reboot with WakeConfirm AWAITING_CHECK resumes authorized refire while reboot during active refire pins UNKNOWN`() {
        // Case A: Primary dismissed, WakeConfirm AWAITING_CHECK across reboot
        val primary = OutputOccurrenceKey(2L, trig1, 0)
        val n0 = (gate.reserveForServiceStart(primary, ClaimSource.ALARM_RECEIVER) as OutputAuthorizationResult.Authorized).activeNonce
        val a0 = (gate.claimOutput(primary, n0, OutputSinkType.ALARM_SERVICE_1001) as OutputAuthorizationResult.Authorized).activeNonce
        gate.confirmOutputObserved(primary, a0, OutputSinkType.ALARM_SERVICE_1001)
        gate.completeTerminal(primary, a0, TerminalOutcome.CONSUMED_DISMISSED, enableWakeConfirm = true)

        val step1Token = (store.inspect() as LegacyClaimStorageState.ValidWithRecords).records
            .first { it.key == primary }.wakeConfirm!!.stepToken

        // Device reboots before WorkManager runs WakeConfirmWorker
        gate.simulateProcessRestartOrReboot("proc-boot2", 11L)
        val primaryPostReboot = (store.inspect() as LegacyClaimStorageState.ValidWithRecords).records.first { it.key == primary }
        assertEquals(LegacyClaimState.TERMINAL, primaryPostReboot.state)

        // Authorized refire 1 still succeeds post-reboot
        val refire1 = gate.reserveWakeConfirmRefire(2L, trig1, 1, step1Token)
        assertTrue(refire1 is OutputAuthorizationResult.Authorized)

        // Case B: Reboot while active refire 1 is START_REQUESTED -> transitions refire 1 to UNKNOWN, blocks replay & refire 2
        gate.simulateProcessRestartOrReboot("proc-boot3", 12L)
        val refire1Rec = (store.inspect() as LegacyClaimStorageState.ValidWithRecords).records
            .first { it.key == OutputOccurrenceKey(2L, trig1, 1) }
        assertEquals(LegacyClaimState.UNKNOWN, refire1Rec.state)

        val n1Stale = (refire1 as OutputAuthorizationResult.Authorized).activeNonce
        assertTrue(gate.claimOutput(OutputOccurrenceKey(2L, trig1, 1), n1Stale, OutputSinkType.ALARM_SERVICE_1001) is OutputAuthorizationResult.UnknownStateBlocked)
        assertTrue(gate.reserveWakeConfirmRefire(2L, trig1, 2, "any") is OutputAuthorizationResult.WakeConfirmUnauthorized)
    }

    @Test
    fun `FI-07 process death before and after claim transitions unresolved work to UNKNOWN`() {
        val key = OutputOccurrenceKey(1L, trig1, 0)
        val n0 = (gate.reserveForServiceStart(key, ClaimSource.ALARM_RECEIVER) as OutputAuthorizationResult.Authorized).activeNonce

        gate.simulateProcessRestartOrReboot("proc-B", 10L)
        val recAfterDeath = (store.inspect() as LegacyClaimStorageState.ValidWithRecords).records.single()
        assertEquals(LegacyClaimState.UNKNOWN, recAfterDeath.state)

        val staleRetry = gate.claimOutput(key, n0, OutputSinkType.ALARM_SERVICE_1001)
        assertTrue(staleRetry is OutputAuthorizationResult.UnknownStateBlocked)

        val blindRetry = gate.reserveForServiceStart(key, ClaimSource.FIRE_WATCHDOG)
        assertTrue(blindRetry is OutputAuthorizationResult.UnknownStateBlocked || blindRetry is OutputAuthorizationResult.DuplicateRejected)
    }

    @Test
    fun `FI-08 output starts before final confirmation stays OUTPUT_CLAIMING then UNKNOWN on restart`() {
        val key = OutputOccurrenceKey(1L, trig1, 0)
        val n0 = (gate.reserveForServiceStart(key, ClaimSource.ALARM_RECEIVER) as OutputAuthorizationResult.Authorized).activeNonce
        assertTrue(gate.claimOutput(key, n0, OutputSinkType.ALARM_SERVICE_1001) is OutputAuthorizationResult.Authorized)

        val midState = (store.inspect() as LegacyClaimStorageState.ValidWithRecords).records.single()
        assertEquals(LegacyClaimState.OUTPUT_CLAIMING, midState.state)

        gate.simulateProcessRestartOrReboot("proc-B", 10L)
        val rec = (store.inspect() as LegacyClaimStorageState.ValidWithRecords).records.single()
        assertEquals(LegacyClaimState.UNKNOWN, rec.state)
    }

    @Test
    fun `FI-09 commit false or exception fails closed without mutating durable state`() {
        store.commitSeam = { false }
        val key = OutputOccurrenceKey(1L, trig1, 0)
        val resFalse = gate.reserveForServiceStart(key, ClaimSource.ALARM_RECEIVER)
        assertTrue(resFalse is OutputAuthorizationResult.PersistenceFailed)
        assertTrue(store.inspect() is LegacyClaimStorageState.Absent)

        store.commitSeam = { throw IllegalStateException("disk full") }
        val resThrow = gate.reserveForServiceStart(key, ClaimSource.ALARM_RECEIVER)
        assertTrue(resThrow is OutputAuthorizationResult.PersistenceFailed)
        assertTrue(store.inspect() is LegacyClaimStorageState.Absent)
    }

    @Test
    fun `FI-10 readback mismatch fails closed as storage unverifiable and blocks Phase 3 output`() {
        store.commitSeam = { payload ->
            store.rawPayload = payload
            true
        }
        store.readbackSeam = { "{\"schemaVersion\":1,\"records\":[]}" }
        val key = OutputOccurrenceKey(1L, trig1, 0)
        val res = gate.reserveForServiceStart(key, ClaimSource.ALARM_RECEIVER)
        assertTrue(
            res is OutputAuthorizationResult.StorageUnverifiableFailClosed ||
                res is OutputAuthorizationResult.PersistenceFailed
        )
        assertEquals(null, arbiter.activeHandle)
    }

    @Test
    fun `FI-11 UNKNOWN outcome retained without CE proof and reconciles only after positive CE evidence`() {
        val key = OutputOccurrenceKey(1L, trig1, 0)
        val n0 = (gate.reserveForServiceStart(key, ClaimSource.ALARM_RECEIVER) as OutputAuthorizationResult.Authorized).activeNonce
        val active = (gate.claimOutput(key, n0, OutputSinkType.ALARM_SERVICE_1001) as OutputAuthorizationResult.Authorized).activeNonce
        gate.confirmOutputObserved(key, active, OutputSinkType.ALARM_SERVICE_1001)

        gate.simulateProcessRestartOrReboot("proc-boot2", 11L)
        assertEquals(0, gate.reconcilePostUnlock())
        assertEquals(LegacyClaimState.UNKNOWN, (store.inspect() as LegacyClaimStorageState.ValidWithRecords).records.single().state)

        // Even another reboot retains UNKNOWN when CE proof is still absent
        gate.simulateProcessRestartOrReboot("proc-boot3", 12L)
        assertEquals(0, gate.reconcilePostUnlock())
        assertEquals(LegacyClaimState.UNKNOWN, (store.inspect() as LegacyClaimStorageState.ValidWithRecords).records.single().state)

        // Once CE proof exists, reconciles to TERMINAL
        gate.laneSnapshot = gate.laneSnapshot.copy(roomPositiveDeliveryKeys = setOf(1L to trig1))
        assertEquals(1, gate.reconcilePostUnlock())
        val reconciled = (store.inspect() as LegacyClaimStorageState.ValidWithRecords).records.single()
        assertEquals(LegacyClaimState.TERMINAL, reconciled.state)
        assertEquals(TerminalOutcome.RECONCILED_CE, reconciled.terminalOutcome)
    }

    @Test
    fun `FI-12 terminal proof absent blocks retirement and preserves fail-closed 32-slot cap`() {
        for (i in 1L..32L) {
            val k = OutputOccurrenceKey(i, trig1 + i, 0)
            gate.laneSnapshot = gate.laneSnapshot.copy(alarmEnabledById = gate.laneSnapshot.alarmEnabledById + (i to true))
            val n = (gate.reserveForServiceStart(k, ClaimSource.ALARM_RECEIVER) as OutputAuthorizationResult.Authorized).activeNonce
            if (i == 1L) {
                val act = (gate.claimOutput(k, n, OutputSinkType.ALARM_SERVICE_1001) as OutputAuthorizationResult.Authorized).activeNonce
                gate.confirmOutputObserved(k, act, OutputSinkType.ALARM_SERVICE_1001)
                gate.completeTerminal(k, act, TerminalOutcome.CONSUMED_DISMISSED)
            }
        }
        val k1 = OutputOccurrenceKey(1L, trig1 + 1L, 0)
        // Missing CE proof -> rejected
        assertTrue(store.retireQualifiedTerminal(k1, hasCeDurableProof = false, isAbsentFromV2QueueAndOwner = true) is ClaimRetirementResult.Rejected)
        // 33rd claim still rejected
        assertTrue(gate.reserveForServiceStart(OutputOccurrenceKey(33L, trig1 + 33L, 0), ClaimSource.ALARM_RECEIVER) is OutputAuthorizationResult.CapacityExhaustedFailClosed)
        // With CE proof + V2 absence -> retired, 33rd now admitted
        assertTrue(store.retireQualifiedTerminal(k1, hasCeDurableProof = true, isAbsentFromV2QueueAndOwner = true) is ClaimRetirementResult.Retired)
        gate.laneSnapshot = gate.laneSnapshot.copy(alarmEnabledById = gate.laneSnapshot.alarmEnabledById + (33L to true))
        assertTrue(gate.reserveForServiceStart(OutputOccurrenceKey(33L, trig1 + 33L, 0), ClaimSource.ALARM_RECEIVER) is OutputAuthorizationResult.Authorized)
    }

    @Test
    fun `FI-13 stale Dismiss or AutoStop cannot tear down newer active alarm B`() {
        val keyA = OutputOccurrenceKey(1L, trig1, 0)
        val nA = (gate.reserveForServiceStart(keyA, ClaimSource.ALARM_RECEIVER) as OutputAuthorizationResult.Authorized).activeNonce
        val actA = (gate.claimOutput(keyA, nA, OutputSinkType.ALARM_SERVICE_1001) as OutputAuthorizationResult.Authorized).activeNonce
        gate.confirmOutputObserved(keyA, actA, OutputSinkType.ALARM_SERVICE_1001)

        val keyB = OutputOccurrenceKey(2L, trig2, 0)
        val nB = (gate.reserveForServiceStart(keyB, ClaimSource.ALARM_RECEIVER) as OutputAuthorizationResult.Authorized).activeNonce
        val actB = (gate.claimOutput(keyB, nB, OutputSinkType.ALARM_SERVICE_1001) as OutputAuthorizationResult.Authorized).activeNonce
        gate.confirmOutputObserved(keyB, actB, OutputSinkType.ALARM_SERVICE_1001)

        val staleDismiss = gate.completeTerminal(keyB, actA, TerminalOutcome.CONSUMED_AUTO_SILENCED)
        assertTrue(staleDismiss is OutputAuthorizationResult.StaleOrConflictRejected)
        assertEquals(keyB, arbiter.activeHandle?.key)
    }

    @Test
    fun `FI-14 old ledger LEGACY_DELIVERY crash mid-migration preserves CopiedPendingRetire and completes idempotently on retry`() {
        val oldLegacy = LegacyLedgerMigrationRecord(
            recordId = "delivery_NORMAL_99_gen1_legacy_claim_99_trig1700000999000",
            kind = "NORMAL",
            alarmId = 99L,
            triggerTime = 1_700_000_999_000L,
            deliveredAt = 1_700_000_999_005L,
            generation = 1,
            instanceToken = "legacy_claim_99",
            provenance = "LEGACY_DELIVERY",
            restoreEpoch = 1L
        )
        // Step 1: Crash before old ledger delete completes -> CopiedPendingRetire
        val midCrash = store.migrateOldLedgerLegacyClaim(
            ledgerRecord = oldLegacy,
            hasCeTerminalProof = false,
            isAbsentFromV2QueueAndOwner = true,
            retireFromOldLedger = { false }
        )
        assertTrue(midCrash is MigrationResult.CopiedPendingRetire)
        val afterCrashRecords = (store.inspect() as LegacyClaimStorageState.ValidWithRecords).records
        assertEquals(1, afterCrashRecords.size)
        assertEquals(LegacyClaimState.UNKNOWN, afterCrashRecords.single().state)

        // Step 2: Retry after recovery completes old-ledger retirement without duplicating claim
        var retiredId: String? = null
        val retry = store.migrateOldLedgerLegacyClaim(
            ledgerRecord = oldLegacy,
            hasCeTerminalProof = false,
            isAbsentFromV2QueueAndOwner = true,
            retireFromOldLedger = { id ->
                retiredId = id
                true
            }
        )
        assertTrue(retry is MigrationResult.MigratedAndRetired)
        assertEquals(oldLegacy.recordId, retiredId)
        val finalRecords = (store.inspect() as LegacyClaimStorageState.ValidWithRecords).records
        assertEquals(1, finalRecords.size)
        assertEquals(oldLegacy.recordId, finalRecords.single().migratedFromLedgerRecordId)
    }

    // =========================================================================
    // S3 Additional Counterexample Discriminator Tests: F1 .. F5
    // =========================================================================

    @Test
    fun `F1 V2 arrives between Phase 1 and Phase 2 of Legacy Gate and is rejected fail-closed`() {
        val key = OutputOccurrenceKey(1L, trig1, 0)
        val reserveRes = gate.reserveForServiceStart(key, ClaimSource.ALARM_RECEIVER)
        val handoverNonce = (reserveRes as OutputAuthorizationResult.Authorized).activeNonce

        // During beforePhase2RoomRead (i.e. between Phase 1 and Phase 2), V2 commits ownership in CE/DE
        val claimRes = gate.claimOutput(
            key = key,
            incomingNonce = handoverNonce,
            sink = OutputSinkType.ALARM_SERVICE_1001,
            beforePhase2RoomRead = {
                gate.laneSnapshot = gate.laneSnapshot.copy(v2LedgerKeys = setOf(1L to trig1))
            }
        )
        assertTrue("Expected V2LaneOwnedRejected when V2 arrives between Phase 1 and 2, but was $claimRes",
            claimRes is OutputAuthorizationResult.V2LaneOwnedRejected)
        val rec = (store.inspect() as LegacyClaimStorageState.ValidWithRecords).records.single()
        assertEquals(LegacyClaimState.TERMINAL, rec.state)
        assertEquals(TerminalOutcome.DUPLICATE_ABORTED, rec.terminalOutcome)
        assertEquals(null, arbiter.activeHandle)
    }

    @Test
    fun `F2 V2 arrives immediately prior to physical audio vibration output and is aborted cleanly`() {
        val key = OutputOccurrenceKey(1L, trig1, 0)
        val reserveRes = gate.reserveForServiceStart(key, ClaimSource.ALARM_RECEIVER)
        val handoverNonce = (reserveRes as OutputAuthorizationResult.Authorized).activeNonce

        val claimRes = gate.claimOutput(key, handoverNonce, OutputSinkType.ALARM_SERVICE_1001)
        assertTrue(claimRes is OutputAuthorizationResult.Authorized)
        val activeNonce = (claimRes as OutputAuthorizationResult.Authorized).activeNonce
        assertEquals(key, arbiter.activeHandle?.key)

        // Immediately before physical output (audio/vibration), V2 arrives or Room38 receipt commits
        val outputRes = gate.observePhysicalOutput(
            key = key,
            activeNonce = activeNonce,
            sink = OutputSinkType.ALARM_SERVICE_1001,
            beforePhysicalOutput = {
                gate.laneSnapshot = gate.laneSnapshot.copy(v2LedgerKeys = setOf(1L to trig1))
            }
        )
        assertTrue("Expected V2LaneOwnedRejected when V2 arrives before physical output, but was $outputRes",
            outputRes is OutputAuthorizationResult.V2LaneOwnedRejected)
        assertEquals("Arbiter must be released when physical output is aborted", null, arbiter.activeHandle)
        val rec = (store.inspect() as LegacyClaimStorageState.ValidWithRecords).records.single()
        assertEquals(LegacyClaimState.TERMINAL, rec.state)
        assertEquals(TerminalOutcome.DUPLICATE_ABORTED, rec.terminalOutcome)
    }

    @Test
    fun `F3 multi-threaded Service vs Fallback concurrent start race admits exactly one winner`() {
        val key = OutputOccurrenceKey(1L, trig1, 0)
        val reserveRes = gate.reserveForServiceStart(key, ClaimSource.ALARM_RECEIVER)
        val handoverNonce = (reserveRes as OutputAuthorizationResult.Authorized).activeNonce

        val startLatch = java.util.concurrent.CountDownLatch(1)
        val doneLatch = java.util.concurrent.CountDownLatch(2)
        var serviceResult: OutputAuthorizationResult? = null
        var fallbackResult: OutputAuthorizationResult? = null

        val tService = Thread {
            startLatch.await()
            serviceResult = gate.claimOutput(key, handoverNonce, OutputSinkType.ALARM_SERVICE_1001)
            doneLatch.countDown()
        }
        val tFallback = Thread {
            startLatch.await()
            fallbackResult = gate.claimOutputForFallback(key, handoverNonce)
            doneLatch.countDown()
        }

        tService.start()
        tFallback.start()
        startLatch.countDown()
        assertTrue(doneLatch.await(5, java.util.concurrent.TimeUnit.SECONDS))

        val serviceSuccess = serviceResult is OutputAuthorizationResult.Authorized
        val fallbackSuccess = fallbackResult is OutputAuthorizationResult.Authorized

        assertTrue("Exactly one sink must succeed", serviceSuccess xor fallbackSuccess)
        val winningSink = if (serviceSuccess) OutputSinkType.ALARM_SERVICE_1001 else OutputSinkType.FULLSCREEN_FALLBACK_1001
        assertEquals(winningSink, arbiter.activeHandle?.sink)
        val rec = (store.inspect() as LegacyClaimStorageState.ValidWithRecords).records.single()
        assertEquals(LegacyClaimState.OUTPUT_CLAIMING, rec.state)
    }

    @Test
    fun `F4 old ledger migration crash recovery pins UNKNOWN and prevents duplicate slot consumption`() {
        val oldLegacy = LegacyLedgerMigrationRecord(
            recordId = "delivery_NORMAL_88_gen1_legacy_claim_88_trig1700000888000",
            kind = "NORMAL",
            alarmId = 88L,
            triggerTime = 1_700_000_888_000L,
            deliveredAt = 1_700_000_888_005L,
            generation = 1,
            instanceToken = "legacy_claim_88",
            provenance = "LEGACY_DELIVERY",
            restoreEpoch = 1L
        )

        // Mid-crash migration: old ledger deletion fails
        val midCrash = store.migrateOldLedgerLegacyClaim(
            ledgerRecord = oldLegacy,
            hasCeTerminalProof = false,
            isAbsentFromV2QueueAndOwner = true,
            retireFromOldLedger = { false }
        )
        assertTrue(midCrash is MigrationResult.CopiedPendingRetire)
        val key = OutputOccurrenceKey(88L, 1_700_000_888_000L, 0)
        val rec = (store.inspect() as LegacyClaimStorageState.ValidWithRecords).records.single()
        assertEquals(LegacyClaimState.UNKNOWN, rec.state)

        // An attempt to output the UNKNOWN migrated claim without CE reconciliation is strictly blocked
        val blockedOutput = gate.claimOutput(key, rec.claimNonce, OutputSinkType.ALARM_SERVICE_1001)
        assertTrue(blockedOutput is OutputAuthorizationResult.UnknownStateBlocked)

        // Recovery retry succeeds in retiring from old ledger and leaves exactly 1 record in store
        val recovered = store.migrateOldLedgerLegacyClaim(
            ledgerRecord = oldLegacy,
            hasCeTerminalProof = false,
            isAbsentFromV2QueueAndOwner = true,
            retireFromOldLedger = { true }
        )
        assertTrue(recovered is MigrationResult.MigratedAndRetired)
        assertEquals(1, (store.inspect() as LegacyClaimStorageState.ValidWithRecords).records.size)
    }

    @Test
    fun `F5 retry on 32-slot capacity with UNKNOWN returns UnknownBlocked instead of CapacityExhausted`() {
        // Fill store to 32 slots: 30 TERMINAL, 2 UNKNOWN
        val enabledMap = (1L..35L).associateWith { true }
        gate.laneSnapshot = CrossLaneSnapshot(alarmEnabledById = enabledMap)

        for (i in 1L..30L) {
            val k = OutputOccurrenceKey(i, trig1 + i, 0)
            val n = (gate.reserveForServiceStart(k, ClaimSource.ALARM_RECEIVER) as OutputAuthorizationResult.Authorized).activeNonce
            val act = (gate.claimOutput(k, n, OutputSinkType.ALARM_SERVICE_1001) as OutputAuthorizationResult.Authorized).activeNonce
            gate.confirmOutputObserved(k, act, OutputSinkType.ALARM_SERVICE_1001)
            gate.completeTerminal(k, act, TerminalOutcome.CONSUMED_DISMISSED)
        }
        assertEquals(30, (store.inspect() as LegacyClaimStorageState.ValidWithRecords).records.size)

        // Add 2 UNKNOWN records (slots 31 and 32)
        val key31 = OutputOccurrenceKey(31L, trig1 + 31L, 0)
        val n31 = (gate.reserveForServiceStart(key31, ClaimSource.ALARM_RECEIVER) as OutputAuthorizationResult.Authorized).activeNonce
        store.transition(key31, LegacyClaimState.START_REQUESTED, LegacyClaimState.UNKNOWN, n31, "unk-31", "proc-A", 10L)

        val key32 = OutputOccurrenceKey(32L, trig1 + 32L, 0)
        val n32 = (gate.reserveForServiceStart(key32, ClaimSource.ALARM_RECEIVER) as OutputAuthorizationResult.Authorized).activeNonce
        store.transition(key32, LegacyClaimState.START_REQUESTED, LegacyClaimState.UNKNOWN, n32, "unk-32", "proc-A", 10L)

        assertEquals(32, (store.inspect() as LegacyClaimStorageState.ValidWithRecords).records.size)

        // Retry for key31 (which is currently UNKNOWN in slot 31):
        // Must return UnknownStateBlocked (occurrence identity recognized), NOT CapacityExhausted!
        val retryUnknown = gate.reserveForServiceStart(key31, ClaimSource.FIRE_WATCHDOG)
        assertTrue("Expected UnknownStateBlocked but was $retryUnknown", retryUnknown is OutputAuthorizationResult.UnknownStateBlocked)

        // Retry for key1 (which is TERMINAL): returns DuplicateRejected
        val retryTerminal = gate.reserveForServiceStart(OutputOccurrenceKey(1L, trig1 + 1L, 0), ClaimSource.FIRE_WATCHDOG)
        assertTrue(retryTerminal is OutputAuthorizationResult.DuplicateRejected)

        // Request for a completely NEW key33: strictly CapacityExhaustedFailClosed
        val newKey33 = OutputOccurrenceKey(33L, trig1 + 33L, 0)
        val capExhausted = gate.reserveForServiceStart(newKey33, ClaimSource.ALARM_RECEIVER)
        assertTrue(capExhausted is OutputAuthorizationResult.CapacityExhaustedFailClosed)

        // Age-only retirement of key31 UNKNOWN rejected
        assertTrue(store.retireQualifiedTerminal(key31, hasCeDurableProof = false, isAbsentFromV2QueueAndOwner = true, ageMsOnly = true) is ClaimRetirementResult.Rejected)

        // With explicit CE durable proof, key1 TERMINAL is retired, freeing slot 32 -> 31
        assertTrue(store.retireQualifiedTerminal(OutputOccurrenceKey(1L, trig1 + 1L, 0), hasCeDurableProof = true, isAbsentFromV2QueueAndOwner = true) is ClaimRetirementResult.Retired)
        assertEquals(31, (store.inspect() as LegacyClaimStorageState.ValidWithRecords).records.size)

        // Now new key33 is admitted
        assertTrue(gate.reserveForServiceStart(newKey33, ClaimSource.ALARM_RECEIVER) is OutputAuthorizationResult.Authorized)
    }
}

