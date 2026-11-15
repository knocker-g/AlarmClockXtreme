package com.sysadmindoc.alarmclock.directboot

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class DirectBootLegacyClaimStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DirectBootLegacyClaimStore.resetSeamsForTesting()
        clearStorage()
    }

    @After
    fun tearDown() {
        DirectBootLegacyClaimStore.resetSeamsForTesting()
        clearStorage()
    }

    private fun clearStorage() {
        DirectBootLegacyClaimStore.prefs(context)
            .edit()
            .remove(DirectBootLegacyClaimStore.KEY_LEGACY_CLAIM_STORE_PAYLOAD)
            .commit()
    }

    private fun sampleClaim(
        alarmId: Long = 100L,
        scheduledAt: Long = 1_700_000_000_000L,
        refireCount: Int = 0,
        source: LegacyClaimSource = LegacyClaimSource.ALARM_RECEIVER,
        nonce: String = "n1",
        proc: String = "proc-1",
        boot: Long = 10L,
        state: LegacyClaimState = LegacyClaimState.CLAIMED_RECEIVER,
        terminalOutcome: LegacyTerminalOutcome? = null
    ) = LegacyClaimRecord(
        alarmId = alarmId,
        scheduledAt = scheduledAt,
        refireCount = refireCount,
        source = source,
        claimNonce = nonce,
        processSessionToken = proc,
        bootCount = boot,
        state = state,
        terminalOutcome = terminalOutcome
    )

    // =========================================================================
    // 1. Initial State & Basic Lifecycle
    // =========================================================================

    @Test
    fun testInspectStorageState_whenAbsent_returnsAbsent() {
        val state = DirectBootLegacyClaimStore.inspectStorageState(context)
        assertTrue(state is LegacyClaimStorageState.Absent)
    }

    @Test
    fun testMonotonicLifecycleTransitions_succeedInOrder() {
        val claim = sampleClaim()
        val writeRes = DirectBootLegacyClaimStore.writeInitialClaim(context, claim)
        assertTrue(writeRes is LegacyClaimWriteResult.Committed)
        assertEquals(1, (writeRes as LegacyClaimWriteResult.Committed).activeCount)

        val t1 = DirectBootLegacyClaimStore.transition(
            context = context,
            key = claim.key,
            expectedFrom = LegacyClaimState.CLAIMED_RECEIVER,
            toState = LegacyClaimState.START_REQUESTED,
            expectedNonce = "n1",
            newNonce = "n2",
            processSessionToken = "proc-1",
            bootCount = 10L
        )
        assertTrue(t1 is LegacyClaimWriteResult.Committed)

        val t2 = DirectBootLegacyClaimStore.transition(
            context = context,
            key = claim.key,
            expectedFrom = LegacyClaimState.START_REQUESTED,
            toState = LegacyClaimState.SERVICE_ACCEPTED,
            expectedNonce = "n2",
            newNonce = "n3",
            processSessionToken = "proc-1",
            bootCount = 10L
        )
        assertTrue(t2 is LegacyClaimWriteResult.Committed)

        val t3 = DirectBootLegacyClaimStore.transition(
            context = context,
            key = claim.key,
            expectedFrom = LegacyClaimState.SERVICE_ACCEPTED,
            toState = LegacyClaimState.OUTPUT_CLAIMING,
            expectedNonce = "n3",
            newNonce = "n4",
            processSessionToken = "proc-1",
            bootCount = 10L
        )
        assertTrue(t3 is LegacyClaimWriteResult.Committed)

        val t4 = DirectBootLegacyClaimStore.transition(
            context = context,
            key = claim.key,
            expectedFrom = LegacyClaimState.OUTPUT_CLAIMING,
            toState = LegacyClaimState.OUTPUT_OBSERVED,
            expectedNonce = "n4",
            newNonce = "n5",
            processSessionToken = "proc-1",
            bootCount = 10L
        )
        assertTrue(t4 is LegacyClaimWriteResult.Committed)

        val t5 = DirectBootLegacyClaimStore.transition(
            context = context,
            key = claim.key,
            expectedFrom = LegacyClaimState.OUTPUT_OBSERVED,
            toState = LegacyClaimState.TERMINAL,
            expectedNonce = "n5",
            newNonce = "n6",
            processSessionToken = "proc-1",
            bootCount = 10L,
            terminalOutcome = LegacyTerminalOutcome.CONSUMED_DISMISSED
        )
        assertTrue(t5 is LegacyClaimWriteResult.Committed)
    }

    // =========================================================================
    // 2. Monotonicity & Nonce Verification
    // =========================================================================

    @Test
    fun testNonMonotonicRegressionAndTerminalReopen_areRejected() {
        val claim = sampleClaim()
        DirectBootLegacyClaimStore.writeInitialClaim(context, claim)
        DirectBootLegacyClaimStore.transition(context, claim.key, LegacyClaimState.CLAIMED_RECEIVER, LegacyClaimState.START_REQUESTED, "n1", "n2", "proc-1", 10L)
        DirectBootLegacyClaimStore.transition(context, claim.key, LegacyClaimState.START_REQUESTED, LegacyClaimState.OUTPUT_OBSERVED, "n2", "n3", "proc-1", 10L)

        // Attempt regression OUTPUT_OBSERVED -> CLAIMED_RECEIVER
        val regress = DirectBootLegacyClaimStore.transition(context, claim.key, LegacyClaimState.OUTPUT_OBSERVED, LegacyClaimState.CLAIMED_RECEIVER, "n3", "n4", "proc-1", 10L)
        assertTrue(regress is LegacyClaimWriteResult.RejectedInvalidTransition)

        DirectBootLegacyClaimStore.transition(
            context,
            claim.key,
            LegacyClaimState.OUTPUT_OBSERVED,
            LegacyClaimState.TERMINAL,
            "n3",
            "n4",
            "proc-1",
            10L,
            terminalOutcome = LegacyTerminalOutcome.CONSUMED_DISMISSED
        )

        // Attempt reopen TERMINAL -> START_REQUESTED
        val reopen = DirectBootLegacyClaimStore.transition(context, claim.key, LegacyClaimState.TERMINAL, LegacyClaimState.START_REQUESTED, "n4", "n5", "proc-1", 10L)
        assertTrue(reopen is LegacyClaimWriteResult.RejectedInvalidTransition)
    }

    @Test
    fun testTransitionWithNonceMismatch_isRejected() {
        val claim = sampleClaim()
        DirectBootLegacyClaimStore.writeInitialClaim(context, claim)
        val mismatch = DirectBootLegacyClaimStore.transition(
            context,
            claim.key,
            LegacyClaimState.CLAIMED_RECEIVER,
            LegacyClaimState.START_REQUESTED,
            expectedNonce = "wrong-nonce",
            newNonce = "n2",
            processSessionToken = "proc-1",
            bootCount = 10L
        )
        assertTrue(mismatch is LegacyClaimWriteResult.RejectedInvalidTransition)
    }

    // =========================================================================
    // 3. UNKNOWN State Preservation
    // =========================================================================

    @Test
    fun testUnknownState_neverSilentlyRecoversToActiveOrTerminalWithoutCeProof() {
        val claim = sampleClaim(state = LegacyClaimState.START_REQUESTED)
        DirectBootLegacyClaimStore.writeInitialClaim(context, claim)
        DirectBootLegacyClaimStore.transition(context, claim.key, LegacyClaimState.START_REQUESTED, LegacyClaimState.UNKNOWN, "n1", "n2", "proc-1", 10L)

        val silentRecover = DirectBootLegacyClaimStore.transition(context, claim.key, LegacyClaimState.UNKNOWN, LegacyClaimState.OUTPUT_OBSERVED, "n2", "n3", "proc-1", 10L)
        assertTrue(silentRecover is LegacyClaimWriteResult.RejectedInvalidTransition)

        val retireUnknown = DirectBootLegacyClaimStore.retireQualifiedTerminal(
            context,
            claim.key,
            hasCeDurableProof = true,
            isAbsentFromV2QueueAndOwner = true
        )
        assertTrue(retireUnknown is LegacyClaimRetirementResult.Rejected)
    }

    // =========================================================================
    // 4. Capacity Management: 31 -> 32 Succeeds, 33 Fails Closed
    // =========================================================================

    @Test
    fun testCapacity31to32Succeeds_and33rdFailsClosedWhenUnresolved() {
        for (i in 1L..31L) {
            val r = DirectBootLegacyClaimStore.writeInitialClaim(
                context,
                sampleClaim(alarmId = i, scheduledAt = 1_700_000_000_000L + i, nonce = "n-$i")
            )
            assertTrue(r is LegacyClaimWriteResult.Committed)
        }
        val r32 = DirectBootLegacyClaimStore.writeInitialClaim(
            context,
            sampleClaim(alarmId = 32L, scheduledAt = 1_700_000_000_032L, nonce = "n-32")
        )
        assertTrue(r32 is LegacyClaimWriteResult.Committed)
        assertEquals(32, (r32 as LegacyClaimWriteResult.Committed).activeCount)

        val r33 = DirectBootLegacyClaimStore.writeInitialClaim(
            context,
            sampleClaim(alarmId = 33L, scheduledAt = 1_700_000_000_033L, nonce = "n-33")
        )
        assertTrue(r33 is LegacyClaimWriteResult.RejectedCapacityExhausted)
    }

    // =========================================================================
    // 5. Retirement Verification & Slot Reclaim
    // =========================================================================

    @Test
    fun testRetirement_rejectsAgeOnly_andAllows33rdOnlyAfterQualifiedTerminalRetirement() {
        for (i in 1L..32L) {
            val claim = sampleClaim(alarmId = i, scheduledAt = 1_700_000_000_000L + i, nonce = "n-$i")
            DirectBootLegacyClaimStore.writeInitialClaim(context, claim)
            if (i == 1L) {
                DirectBootLegacyClaimStore.transition(context, claim.key, LegacyClaimState.CLAIMED_RECEIVER, LegacyClaimState.START_REQUESTED, "n-1", "n-1b", "proc-1", 10L)
                DirectBootLegacyClaimStore.transition(context, claim.key, LegacyClaimState.START_REQUESTED, LegacyClaimState.OUTPUT_OBSERVED, "n-1b", "n-1c", "proc-1", 10L)
                DirectBootLegacyClaimStore.transition(
                    context,
                    claim.key,
                    LegacyClaimState.OUTPUT_OBSERVED,
                    LegacyClaimState.TERMINAL,
                    "n-1c",
                    "n-1d",
                    "proc-1",
                    10L,
                    terminalOutcome = LegacyTerminalOutcome.CONSUMED_DISMISSED
                )
            }
        }

        val key1 = OutputOccurrenceKey(1L, 1_700_000_000_001L, 0)

        // Age-only retirement must be rejected
        assertTrue(
            DirectBootLegacyClaimStore.retireQualifiedTerminal(
                context, key1, hasCeDurableProof = false, isAbsentFromV2QueueAndOwner = true, ageMsOnly = true
            ) is LegacyClaimRetirementResult.Rejected
        )

        // Missing CE proof or present in V2 queue must be rejected
        assertTrue(
            DirectBootLegacyClaimStore.retireQualifiedTerminal(
                context, key1, hasCeDurableProof = false, isAbsentFromV2QueueAndOwner = true
            ) is LegacyClaimRetirementResult.Rejected
        )
        assertTrue(
            DirectBootLegacyClaimStore.retireQualifiedTerminal(
                context, key1, hasCeDurableProof = true, isAbsentFromV2QueueAndOwner = false
            ) is LegacyClaimRetirementResult.Rejected
        )

        // Qualified retirement frees slot 32 -> 31, allowing 33rd claim
        val retired = DirectBootLegacyClaimStore.retireQualifiedTerminal(
            context, key1, hasCeDurableProof = true, isAbsentFromV2QueueAndOwner = true
        )
        assertTrue(retired is LegacyClaimRetirementResult.Retired)
        assertEquals(31, (retired as LegacyClaimRetirementResult.Retired).remainingCount)

        val r33 = DirectBootLegacyClaimStore.writeInitialClaim(
            context,
            sampleClaim(alarmId = 33L, scheduledAt = 1_700_000_000_033L, nonce = "n-33")
        )
        assertTrue(r33 is LegacyClaimWriteResult.Committed)
    }

    // =========================================================================
    // 6. Fail-Closed on Corrupt / Unsupported Schema
    // =========================================================================

    @Test
    fun testCorruptAndUnsupportedSchema_failClosedWithoutClearingRawPayload() {
        val prefs = DirectBootLegacyClaimStore.prefs(context)
        val corruptRaw = "{corrupt-json!!"
        prefs.edit().putString(DirectBootLegacyClaimStore.KEY_LEGACY_CLAIM_STORE_PAYLOAD, corruptRaw).commit()

        assertTrue(DirectBootLegacyClaimStore.inspectStorageState(context) is LegacyClaimStorageState.Corrupt)
        val writeResult = DirectBootLegacyClaimStore.writeInitialClaim(context, sampleClaim())
        assertTrue(writeResult is LegacyClaimWriteResult.RejectedStorageCorrupt)
        assertEquals(corruptRaw, prefs.getString(DirectBootLegacyClaimStore.KEY_LEGACY_CLAIM_STORE_PAYLOAD, null))

        val unsupportedRaw = """{"schemaVersion":99,"records":[]}"""
        prefs.edit().putString(DirectBootLegacyClaimStore.KEY_LEGACY_CLAIM_STORE_PAYLOAD, unsupportedRaw).commit()

        assertTrue(DirectBootLegacyClaimStore.inspectStorageState(context) is LegacyClaimStorageState.UnsupportedSchema)
        val writeResult2 = DirectBootLegacyClaimStore.writeInitialClaim(context, sampleClaim())
        assertTrue(writeResult2 is LegacyClaimWriteResult.RejectedUnsupportedSchema)
        assertEquals(unsupportedRaw, prefs.getString(DirectBootLegacyClaimStore.KEY_LEGACY_CLAIM_STORE_PAYLOAD, null))
    }

    // =========================================================================
    // 7. Commit False / Exception / Readback Mismatch Distinction
    // =========================================================================

    @Test
    fun testCommitSeamFailure_returnsPersistenceFailedAndRestoresPreviousPayload() {
        DirectBootLegacyClaimStore.commitSeam = { _, _ -> false }
        val res = DirectBootLegacyClaimStore.writeInitialClaim(context, sampleClaim())
        assertTrue(res is LegacyClaimWriteResult.RejectedPersistenceFailed)
        assertFalse((res as LegacyClaimWriteResult.RejectedPersistenceFailed).isReadbackMismatch)
    }

    @Test
    fun testCommitSeamException_returnsPersistenceFailed() {
        DirectBootLegacyClaimStore.commitSeam = { _, _ -> throw RuntimeException("Disk full") }
        val res = DirectBootLegacyClaimStore.writeInitialClaim(context, sampleClaim())
        assertTrue(res is LegacyClaimWriteResult.RejectedPersistenceFailed)
        assertFalse((res as LegacyClaimWriteResult.RejectedPersistenceFailed).isReadbackMismatch)
    }

    @Test
    fun testReadbackMismatch_returnsPersistenceFailedWithReadbackMismatchTrue() {
        var reads = 0
        DirectBootLegacyClaimStore.readbackSeam = { prefs ->
            reads++
            if (reads == 1) {
                prefs.getString(DirectBootLegacyClaimStore.KEY_LEGACY_CLAIM_STORE_PAYLOAD, null)
            } else {
                "tampered-payload"
            }
        }
        val res = DirectBootLegacyClaimStore.writeInitialClaim(context, sampleClaim())
        assertTrue(res is LegacyClaimWriteResult.RejectedPersistenceFailed)
        assertTrue((res as LegacyClaimWriteResult.RejectedPersistenceFailed).isReadbackMismatch)
    }

    // =========================================================================
    // 8. Idempotency & Duplicate Rejection
    // =========================================================================

    @Test
    fun testWriteInitialClaim_whenDuplicateKey_returnsDuplicateRejected() {
        val claim = sampleClaim()
        val r1 = DirectBootLegacyClaimStore.writeInitialClaim(context, claim)
        assertTrue(r1 is LegacyClaimWriteResult.Committed)

        val r2 = DirectBootLegacyClaimStore.writeInitialClaim(context, claim.copy(claimNonce = "different-nonce"))
        assertTrue(r2 is LegacyClaimWriteResult.RejectedDuplicate)
    }

    // =========================================================================
    // 9. Old Ledger Migration Contract (Isolated)
    // =========================================================================

    @Test
    fun testOldLedgerMigration_migratesViaCopyReadbackAndScopedRetire() {
        val oldLegacy = LegacyLedgerMigrationRecord(
            recordId = "delivery_NORMAL_77_gen1_legacy_claim_77_trig1700000000777",
            kind = "NORMAL",
            alarmId = 77L,
            triggerTime = 1_700_000_000_777L,
            deliveredAt = 1_700_000_000_780L,
            generation = 1,
            instanceToken = "legacy_claim_77",
            provenance = "LEGACY_DELIVERY",
            restoreEpoch = 1L
        )
        var retiredId: String? = null
        val res = DirectBootLegacyClaimStore.migrateOldLedgerLegacyClaim(
            context = context,
            ledgerRecord = oldLegacy,
            hasCeTerminalProof = false,
            isAbsentFromV2QueueAndOwner = true,
            retireFromOldLedger = { id ->
                retiredId = id
                true
            }
        )
        assertTrue(res is LegacyMigrationResult.MigratedAndRetired)
        assertEquals(oldLegacy.recordId, retiredId)
        val state = DirectBootLegacyClaimStore.inspectStorageState(context)
        val stored = (state as LegacyClaimStorageState.ValidWithRecords).records.single()
        assertEquals(LegacyClaimState.UNKNOWN, stored.state)
        assertEquals(oldLegacy.recordId, stored.migratedFromLedgerRecordId)

        // Non-legacy record is untouched
        val v2Record = oldLegacy.copy(
            recordId = "delivery_NORMAL_88_gen2_tok_abc_trig1700000000888",
            alarmId = 88L,
            generation = 2,
            instanceToken = "tok_abc",
            provenance = "BOOT"
        )
        val resNonLegacy = DirectBootLegacyClaimStore.migrateOldLedgerLegacyClaim(
            context = context,
            ledgerRecord = v2Record,
            hasCeTerminalProof = true,
            isAbsentFromV2QueueAndOwner = true,
            retireFromOldLedger = { true }
        )
        assertTrue(resNonLegacy is LegacyMigrationResult.IgnoredNonLegacy)
    }

    @Test
    fun testOldLedgerMigration_idempotentOnCrashMidMigration() {
        val oldLegacy = LegacyLedgerMigrationRecord(
            recordId = "delivery_NORMAL_77_gen1_legacy_claim_77_trig1700000000777",
            kind = "NORMAL",
            alarmId = 77L,
            triggerTime = 1_700_000_000_777L,
            deliveredAt = 1_700_000_000_780L,
            generation = 1,
            instanceToken = "legacy_claim_77",
            provenance = "LEGACY_DELIVERY",
            restoreEpoch = 1L
        )
        // First run: old ledger retirement fails (crash/power cut)
        val res1 = DirectBootLegacyClaimStore.migrateOldLedgerLegacyClaim(
            context = context,
            ledgerRecord = oldLegacy,
            hasCeTerminalProof = false,
            isAbsentFromV2QueueAndOwner = true,
            retireFromOldLedger = { false }
        )
        assertTrue(res1 is LegacyMigrationResult.CopiedPendingRetire)

        // Second run: retry succeeds in retiring old ledger without creating duplicate in LegacyClaimStore
        var retired = false
        val res2 = DirectBootLegacyClaimStore.migrateOldLedgerLegacyClaim(
            context = context,
            ledgerRecord = oldLegacy,
            hasCeTerminalProof = false,
            isAbsentFromV2QueueAndOwner = true,
            retireFromOldLedger = {
                retired = true
                true
            }
        )
        assertTrue(res2 is LegacyMigrationResult.MigratedAndRetired)
        assertTrue(retired)
        val state = DirectBootLegacyClaimStore.inspectStorageState(context)
        assertEquals(1, (state as LegacyClaimStorageState.ValidWithRecords).records.size)
    }
}
