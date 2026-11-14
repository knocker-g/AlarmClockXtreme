package com.sysadmindoc.alarmclock.directboot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectBootOverduePolicyEngineTest {

    private val baseTriggerTime = 1_000_000L
    private val baseAlarmId = 42L
    private val baseGeneration = 2
    private val baseToken = "test-token-uuid-1234"

    private val standardIdentity = DirectBootV2NormalIntentIdentity(
        alarmId = baseAlarmId,
        generation = baseGeneration,
        instanceToken = baseToken,
        triggerTime = baseTriggerTime
    )

    private val standardQueueItem = DirectBootQueueItem(
        kind = DirectBootQueueItem.KIND_NORMAL,
        alarmId = baseAlarmId,
        triggerTime = baseTriggerTime,
        generation = baseGeneration,
        instanceToken = baseToken
    )

    private val standardValidQueue = QueueStorageState.ValidWithItems(
        items = listOf(standardQueueItem)
    )

    private val standardEmptyLedger = LedgerStorageState.ValidWithRecords(
        records = emptyList()
    )

    private val normalFacts = OverdueQualifiedFacts.of(
        rebootDetected = false,
        clockRollbackDetected = false,
        isCancelled = false
    )

    // 01 Normal on-time dispatch (now == T)
    @Test
    fun test01_normalOnTimeDispatch() {
        val result = DirectBootOverduePolicyEngine.evaluate(
            candidate = standardIdentity,
            queueState = standardValidQueue,
            ledgerState = standardEmptyLedger,
            missedState = MissedStorageState.VerifiedEmpty,
            facts = normalFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch,
            nowEpochMs = baseTriggerTime
        )
        assertTrue(result is OverduePolicyOutcome.NormalDispatchCandidate)
        val candidate = result as OverduePolicyOutcome.NormalDispatchCandidate
        assertEquals(0L, candidate.latenessMs)
        assertEquals(standardIdentity, candidate.identity)
        assertFalse(result.audioAuthorized)
    }

    // 02 Normal dispatch +1ms (now == T + 1)
    @Test
    fun test02_normalDispatchPlusOneMs() {
        val result = DirectBootOverduePolicyEngine.evaluate(
            candidate = standardIdentity,
            queueState = standardValidQueue,
            ledgerState = standardEmptyLedger,
            missedState = MissedStorageState.VerifiedEmpty,
            facts = normalFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch,
            nowEpochMs = baseTriggerTime + 1L
        )
        assertTrue(result is OverduePolicyOutcome.NormalDispatchCandidate)
        val candidate = result as OverduePolicyOutcome.NormalDispatchCandidate
        assertEquals(1L, candidate.latenessMs)
        assertFalse(result.audioAuthorized)
    }

    // 03 Normal dispatch >300000ms (now == T + 300001)
    @Test
    fun test03_normalDispatchBeyondCeiling() {
        val result = DirectBootOverduePolicyEngine.evaluate(
            candidate = standardIdentity,
            queueState = standardValidQueue,
            ledgerState = standardEmptyLedger,
            missedState = MissedStorageState.VerifiedEmpty,
            facts = normalFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch,
            nowEpochMs = baseTriggerTime + 300_001L
        )
        assertTrue(result is OverduePolicyOutcome.OverdueMissedCandidate)
        val missed = result as OverduePolicyOutcome.OverdueMissedCandidate
        assertEquals(300_001L, missed.latenessMs)
        assertFalse(result.audioAuthorized)
    }

    // 04 Recovery at 299999ms (now == T + 299999, monotonic == 299999)
    @Test
    fun test04_recoveryAt299999ms() {
        val scheduledElapsed = 50_000L
        val currentElapsed = scheduledElapsed + 299_999L
        val result = DirectBootOverduePolicyEngine.evaluate(
            candidate = standardIdentity,
            queueState = standardValidQueue,
            ledgerState = standardEmptyLedger,
            missedState = MissedStorageState.VerifiedEmpty,
            facts = normalFacts,
            provenance = OverdueTriggerProvenance.QualifiedRecovery(scheduledElapsed),
            nowEpochMs = baseTriggerTime + 299_999L,
            currentElapsedRealtimeMs = currentElapsed
        )
        assertTrue(result is OverduePolicyOutcome.RecoveryDeliveryCandidate)
        val rec = result as OverduePolicyOutcome.RecoveryDeliveryCandidate
        assertEquals(299_999L, rec.latenessMs)
        assertEquals(299_999L, rec.monotonicAgeMs)
        assertFalse(result.audioAuthorized)
    }

    // 05 Recovery at 300000ms (now == T + 300000, monotonic == 300000)
    @Test
    fun test05_recoveryAt300000msExactCeiling() {
        val scheduledElapsed = 50_000L
        val currentElapsed = scheduledElapsed + 300_000L
        val result = DirectBootOverduePolicyEngine.evaluate(
            candidate = standardIdentity,
            queueState = standardValidQueue,
            ledgerState = standardEmptyLedger,
            missedState = MissedStorageState.VerifiedEmpty,
            facts = normalFacts,
            provenance = OverdueTriggerProvenance.QualifiedRecovery(scheduledElapsed),
            nowEpochMs = baseTriggerTime + 300_000L,
            currentElapsedRealtimeMs = currentElapsed
        )
        assertTrue(result is OverduePolicyOutcome.RecoveryDeliveryCandidate)
        val rec = result as OverduePolicyOutcome.RecoveryDeliveryCandidate
        assertEquals(300_000L, rec.latenessMs)
        assertEquals(300_000L, rec.monotonicAgeMs)
        assertFalse(result.audioAuthorized)
    }

    // 06 Recovery at 300001ms (now == T + 300001, monotonic == 300001)
    @Test
    fun test06_recoveryAt300001msBeyondCeiling() {
        val scheduledElapsed = 50_000L
        val currentElapsed = scheduledElapsed + 300_001L
        val result = DirectBootOverduePolicyEngine.evaluate(
            candidate = standardIdentity,
            queueState = standardValidQueue,
            ledgerState = standardEmptyLedger,
            missedState = MissedStorageState.VerifiedEmpty,
            facts = normalFacts,
            provenance = OverdueTriggerProvenance.QualifiedRecovery(scheduledElapsed),
            nowEpochMs = baseTriggerTime + 300_001L,
            currentElapsedRealtimeMs = currentElapsed
        )
        assertTrue(result is OverduePolicyOutcome.OverdueMissedCandidate)
        val missed = result as OverduePolicyOutcome.OverdueMissedCandidate
        assertEquals(300_001L, missed.latenessMs)
        assertFalse(result.audioAuthorized)
    }

    // 07 Premature T-1ms (now == T - 1)
    @Test
    fun test07_prematureTrigger() {
        val result = DirectBootOverduePolicyEngine.evaluate(
            candidate = standardIdentity,
            queueState = standardValidQueue,
            ledgerState = standardEmptyLedger,
            missedState = MissedStorageState.VerifiedEmpty,
            facts = normalFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch,
            nowEpochMs = baseTriggerTime - 1L
        )
        assertTrue(result is OverduePolicyOutcome.PrematureBlock)
        val premature = result as OverduePolicyOutcome.PrematureBlock
        assertEquals(baseTriggerTime - 1L, premature.nowEpochMs)
        assertEquals(baseTriggerTime, premature.triggerTimeMs)
        assertFalse(result.audioAuthorized)
    }

    // 08 Cold boot overdue (rebootDetected == true, now >= T)
    @Test
    fun test08_coldBootOverdue() {
        val result = DirectBootOverduePolicyEngine.evaluate(
            candidate = standardIdentity,
            queueState = standardValidQueue,
            ledgerState = standardEmptyLedger,
            missedState = MissedStorageState.VerifiedEmpty,
            facts = OverdueQualifiedFacts.of(rebootDetected = true, clockRollbackDetected = false, isCancelled = false),
            provenance = OverdueTriggerProvenance.ColdBoot,
            nowEpochMs = baseTriggerTime + 10_000L
        )
        assertTrue(result is OverduePolicyOutcome.ColdBootMissedCandidate)
        val cold = result as OverduePolicyOutcome.ColdBootMissedCandidate
        assertEquals(10_000L, cold.latenessMs)
        assertFalse(result.audioAuthorized)
    }

    // 09 Cold boot future occurrence (rebootDetected == true, now < T)
    @Test
    fun test09_coldBootFutureOccurrence() {
        val result = DirectBootOverduePolicyEngine.evaluate(
            candidate = standardIdentity,
            queueState = standardValidQueue,
            ledgerState = standardEmptyLedger,
            missedState = MissedStorageState.VerifiedEmpty,
            facts = OverdueQualifiedFacts.of(rebootDetected = true, clockRollbackDetected = false, isCancelled = false),
            provenance = OverdueTriggerProvenance.ColdBoot,
            nowEpochMs = baseTriggerTime - 10_000L
        )
        assertTrue(result is OverduePolicyOutcome.UnknownProvenanceBlock)
        assertFalse(result.audioAuthorized)
    }

    // 10 Unknown boot provenance (reboot detected with unverified provenance)
    @Test
    fun test10_unknownBootProvenance() {
        val result = DirectBootOverduePolicyEngine.evaluate(
            candidate = standardIdentity,
            queueState = standardValidQueue,
            ledgerState = standardEmptyLedger,
            missedState = MissedStorageState.VerifiedEmpty,
            facts = OverdueQualifiedFacts.of(rebootDetected = true, clockRollbackDetected = false, isCancelled = false),
            provenance = OverdueTriggerProvenance.Unknown,
            nowEpochMs = baseTriggerTime + 5_000L
        )
        assertTrue(result is OverduePolicyOutcome.UnknownProvenanceBlock)
        assertFalse(result.audioAuthorized)
    }

    // 11 Unknown recovery provenance (UnanchoredRecovery)
    @Test
    fun test11_unknownRecoveryProvenance() {
        val result = DirectBootOverduePolicyEngine.evaluate(
            candidate = standardIdentity,
            queueState = standardValidQueue,
            ledgerState = standardEmptyLedger,
            missedState = MissedStorageState.VerifiedEmpty,
            facts = normalFacts,
            provenance = OverdueTriggerProvenance.UnanchoredRecovery,
            nowEpochMs = baseTriggerTime + 50_000L,
            currentElapsedRealtimeMs = 100_000L
        )
        assertTrue(result is OverduePolicyOutcome.UnknownClockBlock)
        assertFalse(result.audioAuthorized)
    }

    // 12 Invalid generation (candidate generation < queue head generation)
    @Test
    fun test12_invalidGenerationSuperseded() {
        val queueItemGen3 = standardQueueItem.copy(generation = 3)
        val queueGen3 = QueueStorageState.ValidWithItems(listOf(queueItemGen3))
        val candidateGen2 = standardIdentity.copy(generation = 2)

        val result = DirectBootOverduePolicyEngine.evaluate(
            candidate = candidateGen2,
            queueState = queueGen3,
            ledgerState = standardEmptyLedger,
            missedState = MissedStorageState.VerifiedEmpty,
            facts = normalFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch,
            nowEpochMs = baseTriggerTime + 100L
        )
        assertTrue(result is OverduePolicyOutcome.SupersededReject)
        assertFalse(result.audioAuthorized)
    }

    // 13 Identity mismatch (alarmId differs from queue head)
    @Test
    fun test13_identityMismatch() {
        val candidateOtherId = standardIdentity.copy(alarmId = 999L)
        val result = DirectBootOverduePolicyEngine.evaluate(
            candidate = candidateOtherId,
            queueState = standardValidQueue,
            ledgerState = standardEmptyLedger,
            missedState = MissedStorageState.VerifiedEmpty,
            facts = normalFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch,
            nowEpochMs = baseTriggerTime + 100L
        )
        assertTrue(result is OverduePolicyOutcome.SupersededReject)
        assertFalse(result.audioAuthorized)
    }

    // 14 Previously delivered (recordId already in Ledger)
    @Test
    fun test14_previouslyDeliveredInLedger() {
        val record = DirectBootDeliveryRecord.createNormal(
            alarmId = baseAlarmId,
            triggerTime = baseTriggerTime,
            deliveredAt = baseTriggerTime + 50L,
            generation = baseGeneration,
            instanceToken = baseToken
        )
        val ledgerWithRecord = LedgerStorageState.ValidWithRecords(
            records = listOf(record)
        )

        val result = DirectBootOverduePolicyEngine.evaluate(
            candidate = standardIdentity,
            queueState = standardValidQueue,
            ledgerState = ledgerWithRecord,
            missedState = MissedStorageState.VerifiedEmpty,
            facts = normalFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch,
            nowEpochMs = baseTriggerTime + 100L
        )
        assertTrue(result is OverduePolicyOutcome.DuplicateReject)
        val dup = result as OverduePolicyOutcome.DuplicateReject
        assertEquals(record.recordId, dup.recordId)
        assertFalse(result.audioAuthorized)
    }

    // 15 Previously missed (recordId in MissedStorageState.VerifiedRecords)
    @Test
    fun test15_previouslyMissed() {
        val missedId = DirectBootOverduePolicyEngine.computeMissedRecordId(
            alarmId = baseAlarmId,
            triggerTime = baseTriggerTime,
            generation = baseGeneration,
            instanceToken = baseToken
        )
        val result = DirectBootOverduePolicyEngine.evaluate(
            candidate = standardIdentity,
            queueState = standardValidQueue,
            ledgerState = standardEmptyLedger,
            missedState = MissedStorageState.VerifiedRecords(setOf(missedId)),
            facts = normalFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch,
            nowEpochMs = baseTriggerTime + 100L
        )
        assertTrue(result is OverduePolicyOutcome.DuplicateReject)
        assertFalse(result.audioAuthorized)
    }

    // 16 Corrupt Queue (QueueStorageState.Corrupt)
    @Test
    fun test16_corruptQueueFailsClosed() {
        val corruptQueue = QueueStorageState.Corrupt("JSON parse syntax error")
        val result = DirectBootOverduePolicyEngine.evaluate(
            candidate = standardIdentity,
            queueState = corruptQueue,
            ledgerState = standardEmptyLedger,
            missedState = MissedStorageState.VerifiedEmpty,
            facts = normalFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch,
            nowEpochMs = baseTriggerTime
        )
        assertTrue(result is OverduePolicyOutcome.CorruptBlock)
        assertFalse(result.audioAuthorized)
    }

    // 17 Corrupt Ledger (LedgerStorageState.Corrupt)
    @Test
    fun test17_corruptLedgerFailsClosed() {
        val corruptLedger = LedgerStorageState.Corrupt("Checksum mismatch")
        val result = DirectBootOverduePolicyEngine.evaluate(
            candidate = standardIdentity,
            queueState = standardValidQueue,
            ledgerState = corruptLedger,
            missedState = MissedStorageState.VerifiedEmpty,
            facts = normalFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch,
            nowEpochMs = baseTriggerTime
        )
        assertTrue(result is OverduePolicyOutcome.CorruptBlock)
        assertFalse(result.audioAuthorized)
    }

    // 18 Storage uncertain (UnsupportedSchema)
    @Test
    fun test18_storageUncertainUnsupportedSchema() {
        val unsupportedQueue = QueueStorageState.UnsupportedSchema(99, 1)
        val result = DirectBootOverduePolicyEngine.evaluate(
            candidate = standardIdentity,
            queueState = unsupportedQueue,
            ledgerState = standardEmptyLedger,
            missedState = MissedStorageState.VerifiedEmpty,
            facts = normalFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch,
            nowEpochMs = baseTriggerTime
        )
        assertTrue(result is OverduePolicyOutcome.CorruptBlock)
        assertFalse(result.audioAuthorized)
    }

    // 19 Clock rollback (clockRollbackDetected == true)
    @Test
    fun test19_clockRollbackDetected() {
        val result = DirectBootOverduePolicyEngine.evaluate(
            candidate = standardIdentity,
            queueState = standardValidQueue,
            ledgerState = standardEmptyLedger,
            missedState = MissedStorageState.VerifiedEmpty,
            facts = OverdueQualifiedFacts.of(rebootDetected = false, clockRollbackDetected = true, isCancelled = false),
            provenance = OverdueTriggerProvenance.NormalOsDispatch,
            nowEpochMs = baseTriggerTime + 100L
        )
        assertTrue(result is OverduePolicyOutcome.UnknownClockBlock)
        assertFalse(result.audioAuthorized)
    }

    // 20 Same-boot monotonic anchor absent (UnanchoredRecovery)
    @Test
    fun test20_sameBootMonotonicAnchorAbsent() {
        val result = DirectBootOverduePolicyEngine.evaluate(
            candidate = standardIdentity,
            queueState = standardValidQueue,
            ledgerState = standardEmptyLedger,
            missedState = MissedStorageState.VerifiedEmpty,
            facts = normalFacts,
            provenance = OverdueTriggerProvenance.UnanchoredRecovery,
            nowEpochMs = baseTriggerTime + 10_000L
        )
        assertTrue(result is OverduePolicyOutcome.UnknownClockBlock)
        assertFalse(result.audioAuthorized)
    }

    // 21 Contradictory wall/monotonic ages (monotonic elapsed < scheduled elapsed)
    @Test
    fun test21_contradictoryMonotonicAge() {
        val scheduledElapsed = 100_000L
        val currentElapsed = 50_000L // current < scheduled: impossible monotonic anomaly
        val result = DirectBootOverduePolicyEngine.evaluate(
            candidate = standardIdentity,
            queueState = standardValidQueue,
            ledgerState = standardEmptyLedger,
            missedState = MissedStorageState.VerifiedEmpty,
            facts = normalFacts,
            provenance = OverdueTriggerProvenance.QualifiedRecovery(scheduledElapsed),
            nowEpochMs = baseTriggerTime + 1000L,
            currentElapsedRealtimeMs = currentElapsed
        )
        assertTrue(result is OverduePolicyOutcome.UnknownClockBlock)
        assertFalse(result.audioAuthorized)
    }

    // 22 Timezone change requiring reconcile (TimeMutation)
    @Test
    fun test22_timeMutationProvenance() {
        val result = DirectBootOverduePolicyEngine.evaluate(
            candidate = standardIdentity,
            queueState = standardValidQueue,
            ledgerState = standardEmptyLedger,
            missedState = MissedStorageState.VerifiedEmpty,
            facts = normalFacts,
            provenance = OverdueTriggerProvenance.TimeMutation,
            nowEpochMs = baseTriggerTime + 1000L
        )
        assertTrue(result is OverduePolicyOutcome.UnknownProvenanceBlock)
        assertFalse(result.audioAuthorized)
    }

    // 23 Cancelled occurrence (isCancelled == true)
    @Test
    fun test23_cancelledOccurrence() {
        val result = DirectBootOverduePolicyEngine.evaluate(
            candidate = standardIdentity,
            queueState = standardValidQueue,
            ledgerState = standardEmptyLedger,
            missedState = MissedStorageState.VerifiedEmpty,
            facts = OverdueQualifiedFacts.of(rebootDetected = false, clockRollbackDetected = false, isCancelled = true),
            provenance = OverdueTriggerProvenance.NormalOsDispatch,
            nowEpochMs = baseTriggerTime + 1000L
        )
        assertTrue(result is OverduePolicyOutcome.SupersededReject)
        assertFalse(result.audioAuthorized)
    }

    // 24 No side effects: Pure function verify identical queue and ledger before and after
    @Test
    fun test24_noSideEffects() {
        val queueItemsBefore = standardValidQueue.items.toList()
        val ledgerRecordsBefore = standardEmptyLedger.records.toList()

        DirectBootOverduePolicyEngine.evaluate(
            candidate = standardIdentity,
            queueState = standardValidQueue,
            ledgerState = standardEmptyLedger,
            missedState = MissedStorageState.VerifiedEmpty,
            facts = normalFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch,
            nowEpochMs = baseTriggerTime
        )

        assertEquals(queueItemsBefore, standardValidQueue.items)
        assertEquals(ledgerRecordsBefore, standardEmptyLedger.records)
    }

    // 25 All results audioAuthorized == false across all outcome types
    @Test
    fun test25_allResultsAudioAuthorizedFalse() {
        val outcomes: List<OverduePolicyOutcome> = listOf(
            OverduePolicyOutcome.NormalDispatchCandidate(standardIdentity, 0L),
            OverduePolicyOutcome.RecoveryDeliveryCandidate(standardIdentity, 0L, 0L),
            OverduePolicyOutcome.OverdueMissedCandidate(standardIdentity, 400_000L),
            OverduePolicyOutcome.ColdBootMissedCandidate(standardIdentity, 100L),
            OverduePolicyOutcome.SupersededReject("test"),
            OverduePolicyOutcome.DuplicateReject("test"),
            OverduePolicyOutcome.CorruptBlock("test"),
            OverduePolicyOutcome.UnknownProvenanceBlock(OverdueTriggerProvenance.Unknown),
            OverduePolicyOutcome.UnknownClockBlock("test"),
            OverduePolicyOutcome.PrematureBlock(100L, 200L),
            OverduePolicyOutcome.StorageUncertainBlock("test")
        )
        for (outcome in outcomes) {
            assertFalse(outcome.audioAuthorized)
        }
    }

    // 26 Deterministic repeated evaluation: repeated calls produce identical results
    @Test
    fun test26_deterministicRepeatedEvaluation() {
        val eval1 = DirectBootOverduePolicyEngine.evaluate(
            candidate = standardIdentity,
            queueState = standardValidQueue,
            ledgerState = standardEmptyLedger,
            missedState = MissedStorageState.VerifiedEmpty,
            facts = normalFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch,
            nowEpochMs = baseTriggerTime + 500L
        )
        val eval2 = DirectBootOverduePolicyEngine.evaluate(
            candidate = standardIdentity,
            queueState = standardValidQueue,
            ledgerState = standardEmptyLedger,
            missedState = MissedStorageState.VerifiedEmpty,
            facts = normalFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch,
            nowEpochMs = baseTriggerTime + 500L
        )
        assertEquals(eval1, eval2)
    }

    // 27 LedgerStorageState.ValidEmpty must be accepted as valid with 0 records
    @Test
    fun test27_validEmptyLedgerAccepted() {
        val result = DirectBootOverduePolicyEngine.evaluate(
            candidate = standardIdentity,
            queueState = standardValidQueue,
            ledgerState = LedgerStorageState.ValidEmpty,
            missedState = MissedStorageState.VerifiedEmpty,
            facts = normalFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch,
            nowEpochMs = baseTriggerTime
        )
        assertTrue(result is OverduePolicyOutcome.NormalDispatchCandidate)
        assertFalse(result.audioAuthorized)
    }

    // 28 LedgerStorageState.Absent must be accepted as clean 0 records
    @Test
    fun test28_absentLedgerAccepted() {
        val result = DirectBootOverduePolicyEngine.evaluate(
            candidate = standardIdentity,
            queueState = standardValidQueue,
            ledgerState = LedgerStorageState.Absent,
            missedState = MissedStorageState.VerifiedEmpty,
            facts = normalFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch,
            nowEpochMs = baseTriggerTime
        )
        assertTrue(result is OverduePolicyOutcome.NormalDispatchCandidate)
        assertFalse(result.audioAuthorized)
    }

    // 29 Queue head with kind KIND_QUICK must be rejected as SupersededReject
    @Test
    fun test29_queueHeadQuickKindRejected() {
        val quickQueueItem = standardQueueItem.copy(kind = DirectBootQueueItem.KIND_QUICK)
        val queueWithQuick = QueueStorageState.ValidWithItems(listOf(quickQueueItem))
        val result = DirectBootOverduePolicyEngine.evaluate(
            candidate = standardIdentity,
            queueState = queueWithQuick,
            ledgerState = standardEmptyLedger,
            missedState = MissedStorageState.VerifiedEmpty,
            facts = normalFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch,
            nowEpochMs = baseTriggerTime
        )
        assertTrue(result is OverduePolicyOutcome.SupersededReject)
        assertFalse(result.audioAuthorized)
    }

    // 30 Adversarial Missed: alarmId 42 must NOT match record for alarmId 142
    @Test
    fun test30_adversarialMissedId42Vs142() {
        val missedFor142 = DirectBootOverduePolicyEngine.computeMissedRecordId(
            alarmId = 142L,
            triggerTime = baseTriggerTime,
            generation = baseGeneration,
            instanceToken = baseToken
        )
        val result = DirectBootOverduePolicyEngine.evaluate(
            candidate = standardIdentity, // alarmId is 42L
            queueState = standardValidQueue,
            ledgerState = standardEmptyLedger,
            missedState = MissedStorageState.VerifiedRecords(setOf(missedFor142)),
            facts = normalFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch,
            nowEpochMs = baseTriggerTime
        )
        // Must NOT be DuplicateReject! It should qualify as NormalDispatchCandidate.
        assertTrue(result is OverduePolicyOutcome.NormalDispatchCandidate)
        assertFalse(result.audioAuthorized)
    }

    // 31 Adversarial Missed: triggerTime substring collision must NOT match
    @Test
    fun test31_adversarialMissedTriggerTimeSubstring() {
        val missedWithSubstringTime = DirectBootOverduePolicyEngine.computeMissedRecordId(
            alarmId = baseAlarmId,
            triggerTime = baseTriggerTime * 10, // 10_000_000L contains 1_000_000L as substring
            generation = baseGeneration,
            instanceToken = baseToken
        )
        val result = DirectBootOverduePolicyEngine.evaluate(
            candidate = standardIdentity,
            queueState = standardValidQueue,
            ledgerState = standardEmptyLedger,
            missedState = MissedStorageState.VerifiedRecords(setOf(missedWithSubstringTime)),
            facts = normalFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch,
            nowEpochMs = baseTriggerTime
        )
        assertTrue(result is OverduePolicyOutcome.NormalDispatchCandidate)
        assertFalse(result.audioAuthorized)
    }

    // 32 Adversarial Missed: instanceToken prefix/suffix collision must NOT match
    @Test
    fun test32_adversarialMissedInstanceTokenCollision() {
        val missedWithPrefixToken = DirectBootOverduePolicyEngine.computeMissedRecordId(
            alarmId = baseAlarmId,
            triggerTime = baseTriggerTime,
            generation = baseGeneration,
            instanceToken = "${baseToken}-extra"
        )
        val result = DirectBootOverduePolicyEngine.evaluate(
            candidate = standardIdentity,
            queueState = standardValidQueue,
            ledgerState = standardEmptyLedger,
            missedState = MissedStorageState.VerifiedRecords(setOf(missedWithPrefixToken)),
            facts = normalFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch,
            nowEpochMs = baseTriggerTime
        )
        assertTrue(result is OverduePolicyOutcome.NormalDispatchCandidate)
        assertFalse(result.audioAuthorized)
    }

    // 33 MissedStorageState.Corrupt fails closed as CorruptBlock
    @Test
    fun test33_missedStorageCorruptFailsClosed() {
        val result = DirectBootOverduePolicyEngine.evaluate(
            candidate = standardIdentity,
            queueState = standardValidQueue,
            ledgerState = standardEmptyLedger,
            missedState = MissedStorageState.Corrupt("Missed JSON corrupt"),
            facts = normalFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch,
            nowEpochMs = baseTriggerTime
        )
        assertTrue(result is OverduePolicyOutcome.CorruptBlock)
        assertFalse(result.audioAuthorized)
    }

    // 34 MissedStorageState.UnsupportedSchema fails closed as CorruptBlock
    @Test
    fun test34_missedStorageUnsupportedSchemaFailsClosed() {
        val result = DirectBootOverduePolicyEngine.evaluate(
            candidate = standardIdentity,
            queueState = standardValidQueue,
            ledgerState = standardEmptyLedger,
            missedState = MissedStorageState.UnsupportedSchema(99, 1),
            facts = normalFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch,
            nowEpochMs = baseTriggerTime
        )
        assertTrue(result is OverduePolicyOutcome.CorruptBlock)
        assertFalse(result.audioAuthorized)
    }

    // 35 MissedStorageState.UnknownUncertain fails closed as StorageUncertainBlock
    @Test
    fun test35_missedStorageUncertainFailsClosed() {
        val result = DirectBootOverduePolicyEngine.evaluate(
            candidate = standardIdentity,
            queueState = standardValidQueue,
            ledgerState = standardEmptyLedger,
            missedState = MissedStorageState.UnknownUncertain("Storage unread"),
            facts = normalFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch,
            nowEpochMs = baseTriggerTime
        )
        assertTrue(result is OverduePolicyOutcome.StorageUncertainBlock)
        assertFalse(result.audioAuthorized)
    }

    // 36 Contradictory boot: ColdBoot provenance with rebootDetected == false
    @Test
    fun test36_contradictoryColdBootWithoutRebootDetected() {
        val result = DirectBootOverduePolicyEngine.evaluate(
            candidate = standardIdentity,
            queueState = standardValidQueue,
            ledgerState = standardEmptyLedger,
            missedState = MissedStorageState.VerifiedEmpty,
            facts = OverdueQualifiedFacts.of(rebootDetected = false, clockRollbackDetected = false, isCancelled = false),
            provenance = OverdueTriggerProvenance.ColdBoot,
            nowEpochMs = baseTriggerTime + 1000L
        )
        assertTrue(result is OverduePolicyOutcome.UnknownProvenanceBlock)
        assertFalse(result.audioAuthorized)
    }

    // 37 Contradictory dispatch: NormalOsDispatch with rebootDetected == true
    @Test
    fun test37_contradictoryNormalDispatchWithRebootDetected() {
        val result = DirectBootOverduePolicyEngine.evaluate(
            candidate = standardIdentity,
            queueState = standardValidQueue,
            ledgerState = standardEmptyLedger,
            missedState = MissedStorageState.VerifiedEmpty,
            facts = OverdueQualifiedFacts.of(rebootDetected = true, clockRollbackDetected = false, isCancelled = false),
            provenance = OverdueTriggerProvenance.NormalOsDispatch,
            nowEpochMs = baseTriggerTime + 1000L
        )
        assertTrue(result is OverduePolicyOutcome.UnknownProvenanceBlock)
        assertFalse(result.audioAuthorized)
    }

    // 38 Uncertain cancellation fails closed with StorageUncertainBlock
    @Test
    fun test38_uncertainCancellationFailsClosed() {
        val facts = OverdueQualifiedFacts(
            rebootStatus = QualifiedFact.Verified(false),
            clockRollbackStatus = QualifiedFact.Verified(false),
            cancellationStatus = QualifiedFact.Uncertain("Lock contention during check")
        )
        val result = DirectBootOverduePolicyEngine.evaluate(
            candidate = standardIdentity,
            queueState = standardValidQueue,
            ledgerState = standardEmptyLedger,
            missedState = MissedStorageState.VerifiedEmpty,
            facts = facts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch,
            nowEpochMs = baseTriggerTime
        )
        assertTrue(result is OverduePolicyOutcome.StorageUncertainBlock)
        assertFalse(result.audioAuthorized)
    }

    // 39 Uncertain clock rollback fails closed with UnknownClockBlock
    @Test
    fun test39_uncertainClockRollbackFailsClosed() {
        val facts = OverdueQualifiedFacts(
            rebootStatus = QualifiedFact.Verified(false),
            clockRollbackStatus = QualifiedFact.Uncertain("NTP sync anomaly unverified"),
            cancellationStatus = QualifiedFact.Verified(false)
        )
        val result = DirectBootOverduePolicyEngine.evaluate(
            candidate = standardIdentity,
            queueState = standardValidQueue,
            ledgerState = standardEmptyLedger,
            missedState = MissedStorageState.VerifiedEmpty,
            facts = facts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch,
            nowEpochMs = baseTriggerTime
        )
        assertTrue(result is OverduePolicyOutcome.UnknownClockBlock)
        assertFalse(result.audioAuthorized)
    }

    // 40 Uncertain reboot status fails closed with UnknownProvenanceBlock
    @Test
    fun test40_uncertainRebootStatusFailsClosed() {
        val facts = OverdueQualifiedFacts(
            rebootStatus = QualifiedFact.Uncertain("Boot count unavailable"),
            clockRollbackStatus = QualifiedFact.Verified(false),
            cancellationStatus = QualifiedFact.Verified(false)
        )
        val result = DirectBootOverduePolicyEngine.evaluate(
            candidate = standardIdentity,
            queueState = standardValidQueue,
            ledgerState = standardEmptyLedger,
            missedState = MissedStorageState.VerifiedEmpty,
            facts = facts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch,
            nowEpochMs = baseTriggerTime
        )
        assertTrue(result is OverduePolicyOutcome.UnknownProvenanceBlock)
        assertFalse(result.audioAuthorized)
    }

    // 41 Negative or zero nowEpochMs rejected as UnknownClockBlock
    @Test
    fun test41_negativeNowEpochRejected() {
        val result = DirectBootOverduePolicyEngine.evaluate(
            candidate = standardIdentity,
            queueState = standardValidQueue,
            ledgerState = standardEmptyLedger,
            missedState = MissedStorageState.VerifiedEmpty,
            facts = normalFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch,
            nowEpochMs = -100L
        )
        assertTrue(result is OverduePolicyOutcome.UnknownClockBlock)
        assertFalse(result.audioAuthorized)
    }

    // 42 Arithmetic overflow / invalid monotonic anchor in recovery
    @Test
    fun test42_arithmeticOverflowMonotonicAge() {
        val scheduledElapsed = -1000L
        val result = DirectBootOverduePolicyEngine.evaluate(
            candidate = standardIdentity,
            queueState = standardValidQueue,
            ledgerState = standardEmptyLedger,
            missedState = MissedStorageState.VerifiedEmpty,
            facts = normalFacts,
            provenance = OverdueTriggerProvenance.QualifiedRecovery(scheduledElapsed),
            nowEpochMs = baseTriggerTime + 1000L,
            currentElapsedRealtimeMs = Long.MAX_VALUE
        )
        assertTrue(result is OverduePolicyOutcome.UnknownClockBlock)
        assertFalse(result.audioAuthorized)
    }

    // 43 Corrupt queue item triggerTime <= 0L
    @Test
    fun test43_corruptQueueItemTriggerTimeRejected() {
        val badQueueItem = standardQueueItem.copy(triggerTime = 0L)
        val badQueue = QueueStorageState.ValidWithItems(listOf(badQueueItem))
        val result = DirectBootOverduePolicyEngine.evaluate(
            candidate = standardIdentity,
            queueState = badQueue,
            ledgerState = standardEmptyLedger,
            missedState = MissedStorageState.VerifiedEmpty,
            facts = normalFacts,
            provenance = OverdueTriggerProvenance.NormalOsDispatch,
            nowEpochMs = baseTriggerTime
        )
        assertTrue(result is OverduePolicyOutcome.CorruptBlock || result is OverduePolicyOutcome.SupersededReject)
        assertFalse(result.audioAuthorized)
    }

    // 44 Negative monotonic elapsed rejected as UnknownClockBlock
    @Test
    fun test44_negativeMonotonicElapsedRejected() {
        val scheduledElapsed = 50_000L
        val result = DirectBootOverduePolicyEngine.evaluate(
            candidate = standardIdentity,
            queueState = standardValidQueue,
            ledgerState = standardEmptyLedger,
            missedState = MissedStorageState.VerifiedEmpty,
            facts = normalFacts,
            provenance = OverdueTriggerProvenance.QualifiedRecovery(scheduledElapsed),
            nowEpochMs = baseTriggerTime + 1000L,
            currentElapsedRealtimeMs = -500L
        )
        assertTrue(result is OverduePolicyOutcome.UnknownClockBlock)
        assertFalse(result.audioAuthorized)
    }

    // 45 Cross-boot QualifiedRecovery must be blocked as UnknownProvenanceBlock
    @Test
    fun test45_crossBootQualifiedRecoveryBlocked() {
        val scheduledElapsed = 50_000L
        val currentElapsed = scheduledElapsed + 10_000L
        val crossBootFacts = OverdueQualifiedFacts.of(
            rebootDetected = true,
            clockRollbackDetected = false,
            isCancelled = false
        )
        val result = DirectBootOverduePolicyEngine.evaluate(
            candidate = standardIdentity,
            queueState = standardValidQueue,
            ledgerState = standardEmptyLedger,
            missedState = MissedStorageState.VerifiedEmpty,
            facts = crossBootFacts,
            provenance = OverdueTriggerProvenance.QualifiedRecovery(scheduledElapsed),
            nowEpochMs = baseTriggerTime + 10_000L,
            currentElapsedRealtimeMs = currentElapsed
        )
        assertTrue(result is OverduePolicyOutcome.UnknownProvenanceBlock)
        assertFalse(result.audioAuthorized)
    }
}
