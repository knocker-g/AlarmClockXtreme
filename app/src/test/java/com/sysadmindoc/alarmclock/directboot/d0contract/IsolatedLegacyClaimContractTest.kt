package com.sysadmindoc.alarmclock.directboot.d0contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Isolated D0-P0 Contract Tests for Legacy Claim State Machine, Bounded Capacity,
 * Corrupt/Unsupported Fail-Closed, and Old Ledger Migration.
 */
class IsolatedLegacyClaimContractTest {

    private lateinit var store: InMemoryLegacyClaimStore

    @Before
    fun setUp() {
        store = InMemoryLegacyClaimStore()
    }

    private fun sampleClaim(
        alarmId: Long = 100L,
        scheduledAt: Long = 1_700_000_000_000L,
        refireCount: Int = 0,
        source: ClaimSource = ClaimSource.ALARM_RECEIVER,
        nonce: String = "n1",
        proc: String = "proc-1",
        boot: Long = 10L,
        state: LegacyClaimState = LegacyClaimState.CLAIMED_RECEIVER
    ) = LegacyClaimRecord(
        alarmId = alarmId,
        scheduledAt = scheduledAt,
        refireCount = refireCount,
        source = source,
        claimNonce = nonce,
        processSessionToken = proc,
        bootCount = boot,
        state = state
    )

    @Test
    fun `monotonic lifecycle transitions succeed in exact canonical order`() {
        val claim = sampleClaim()
        val res1 = store.writeInitialClaim(claim)
        assertTrue(res1 is ClaimStoreWriteResult.Committed)

        val res2 = store.transition(claim.key, LegacyClaimState.CLAIMED_RECEIVER, LegacyClaimState.START_REQUESTED, "n1", "n2", "proc-1", 10L)
        assertTrue(res2 is ClaimStoreWriteResult.Committed)

        val res3 = store.transition(claim.key, LegacyClaimState.START_REQUESTED, LegacyClaimState.SERVICE_ACCEPTED, "n2", "n3", "proc-1", 10L)
        assertTrue(res3 is ClaimStoreWriteResult.Committed)

        val res4 = store.transition(claim.key, LegacyClaimState.SERVICE_ACCEPTED, LegacyClaimState.OUTPUT_CLAIMING, "n3", "n4", "proc-1", 10L)
        assertTrue(res4 is ClaimStoreWriteResult.Committed)

        val res5 = store.transition(claim.key, LegacyClaimState.OUTPUT_CLAIMING, LegacyClaimState.OUTPUT_OBSERVED, "n4", "n5", "proc-1", 10L)
        assertTrue(res5 is ClaimStoreWriteResult.Committed)

        val res6 = store.transition(
            claim.key,
            LegacyClaimState.OUTPUT_OBSERVED,
            LegacyClaimState.TERMINAL,
            "n5",
            "n6",
            "proc-1",
            10L,
            terminalOutcome = TerminalOutcome.CONSUMED_DISMISSED
        )
        assertTrue(res6 is ClaimStoreWriteResult.Committed)
    }

    @Test
    fun `non-monotonic regression and terminal overwrite are strictly rejected`() {
        val claim = sampleClaim()
        store.writeInitialClaim(claim)
        store.transition(claim.key, LegacyClaimState.CLAIMED_RECEIVER, LegacyClaimState.START_REQUESTED, "n1", "n2", "proc-1", 10L)
        store.transition(claim.key, LegacyClaimState.START_REQUESTED, LegacyClaimState.OUTPUT_CLAIMING, "n2", "n3", "proc-1", 10L)
        store.transition(claim.key, LegacyClaimState.OUTPUT_CLAIMING, LegacyClaimState.OUTPUT_OBSERVED, "n3", "n4", "proc-1", 10L)

        // Attempt regression OUTPUT_OBSERVED -> CLAIMED_RECEIVER
        val regress = store.transition(claim.key, LegacyClaimState.OUTPUT_OBSERVED, LegacyClaimState.CLAIMED_RECEIVER, "n4", "n5", "proc-1", 10L)
        assertTrue(regress is ClaimStoreWriteResult.RejectedInvalidTransition)

        store.transition(
            claim.key,
            LegacyClaimState.OUTPUT_OBSERVED,
            LegacyClaimState.TERMINAL,
            "n4",
            "n5",
            "proc-1",
            10L,
            terminalOutcome = TerminalOutcome.CONSUMED_DISMISSED
        )

        // Attempt reopen TERMINAL -> START_REQUESTED
        val reopen = store.transition(claim.key, LegacyClaimState.TERMINAL, LegacyClaimState.START_REQUESTED, "n5", "n6", "proc-1", 10L)
        assertTrue(reopen is ClaimStoreWriteResult.RejectedInvalidTransition)
    }

    @Test
    fun `unknown state never silently recovers to active or terminal without CE proof`() {
        val claim = sampleClaim(state = LegacyClaimState.START_REQUESTED)
        store.writeInitialClaim(claim)
        store.transition(claim.key, LegacyClaimState.START_REQUESTED, LegacyClaimState.UNKNOWN, "n1", "n2", "proc-1", 10L)

        val silentRecover = store.transition(claim.key, LegacyClaimState.UNKNOWN, LegacyClaimState.OUTPUT_OBSERVED, "n2", "n3", "proc-1", 10L)
        assertTrue(silentRecover is ClaimStoreWriteResult.RejectedInvalidTransition)

        // Even retirement fails for UNKNOWN
        val retireUnknown = store.retireQualifiedTerminal(claim.key, hasCeDurableProof = true, isAbsentFromV2QueueAndOwner = true)
        assertTrue(retireUnknown is ClaimRetirementResult.Rejected)
    }

    @Test
    fun `capacity 31 to 32 succeeds and 32 to 33 fails closed when unresolved`() {
        for (i in 1L..31L) {
            val r = store.writeInitialClaim(sampleClaim(alarmId = i, scheduledAt = 1_700_000_000_000L + i, nonce = "n-$i"))
            assertTrue(r is ClaimStoreWriteResult.Committed)
        }
        val r32 = store.writeInitialClaim(sampleClaim(alarmId = 32L, scheduledAt = 1_700_000_000_032L, nonce = "n-32"))
        assertTrue(r32 is ClaimStoreWriteResult.Committed)
        assertEquals(32, (r32 as ClaimStoreWriteResult.Committed).activeCount)

        val r33 = store.writeInitialClaim(sampleClaim(alarmId = 33L, scheduledAt = 1_700_000_000_033L, nonce = "n-33"))
        assertTrue(r33 is ClaimStoreWriteResult.RejectedCapacityExhausted)
    }

    @Test
    fun `retirement rejects age-only and allows 33rd only after qualified terminal retirement`() {
        for (i in 1L..32L) {
            val claim = sampleClaim(alarmId = i, scheduledAt = 1_700_000_000_000L + i, nonce = "n-$i")
            store.writeInitialClaim(claim)
            if (i == 1L) {
                store.transition(claim.key, LegacyClaimState.CLAIMED_RECEIVER, LegacyClaimState.START_REQUESTED, "n-1", "n-1b", "proc-1", 10L)
                store.transition(claim.key, LegacyClaimState.START_REQUESTED, LegacyClaimState.OUTPUT_OBSERVED, "n-1b", "n-1c", "proc-1", 10L)
                store.transition(
                    claim.key,
                    LegacyClaimState.OUTPUT_OBSERVED,
                    LegacyClaimState.TERMINAL,
                    "n-1c",
                    "n-1d",
                    "proc-1",
                    10L,
                    terminalOutcome = TerminalOutcome.CONSUMED_DISMISSED
                )
            }
        }

        // Age-only retirement must be rejected
        val key1 = OutputOccurrenceKey(1L, 1_700_000_000_001L, 0)
        assertTrue(store.retireQualifiedTerminal(key1, hasCeDurableProof = false, isAbsentFromV2QueueAndOwner = true, ageMsOnly = true) is ClaimRetirementResult.Rejected)

        // Missing CE proof or present in V2 queue must be rejected
        assertTrue(store.retireQualifiedTerminal(key1, hasCeDurableProof = false, isAbsentFromV2QueueAndOwner = true) is ClaimRetirementResult.Rejected)
        assertTrue(store.retireQualifiedTerminal(key1, hasCeDurableProof = true, isAbsentFromV2QueueAndOwner = false) is ClaimRetirementResult.Rejected)

        // Qualified retirement frees slot 32 -> 31, allowing 33rd claim
        val retired = store.retireQualifiedTerminal(key1, hasCeDurableProof = true, isAbsentFromV2QueueAndOwner = true)
        assertTrue(retired is ClaimRetirementResult.Retired)
        assertEquals(31, (retired as ClaimRetirementResult.Retired).remainingCount)

        val r33 = store.writeInitialClaim(sampleClaim(alarmId = 33L, scheduledAt = 1_700_000_000_033L, nonce = "n-33"))
        assertTrue(r33 is ClaimStoreWriteResult.Committed)
    }

    @Test
    fun `corrupt and unsupported schema fail closed without clearing raw payload`() {
        val corruptRaw = "{corrupt-json!!"
        store.rawPayload = corruptRaw
        assertTrue(store.inspect() is LegacyClaimStorageState.Corrupt)
        val writeResult = store.writeInitialClaim(sampleClaim())
        assertTrue(writeResult is ClaimStoreWriteResult.RejectedStorageCorrupt)
        assertEquals(corruptRaw, store.rawPayload)

        val unsupportedRaw = """{"schemaVersion":99,"records":[]}"""
        store.rawPayload = unsupportedRaw
        assertTrue(store.inspect() is LegacyClaimStorageState.UnsupportedSchema)
        val writeResult2 = store.writeInitialClaim(sampleClaim())
        assertTrue(writeResult2 is ClaimStoreWriteResult.RejectedUnsupportedSchema)
        assertEquals(unsupportedRaw, store.rawPayload)
    }

    @Test
    fun `old ledger legacy delivery record migrates via copy readback and scoped retire`() {
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
        val res = store.migrateOldLedgerLegacyClaim(
            ledgerRecord = oldLegacy,
            hasCeTerminalProof = false,
            isAbsentFromV2QueueAndOwner = true,
            retireFromOldLedger = { id ->
                retiredId = id
                true
            }
        )
        assertTrue(res is MigrationResult.MigratedAndRetired)
        assertEquals(oldLegacy.recordId, retiredId)
        val stored = (store.inspect() as LegacyClaimStorageState.ValidWithRecords).records.single()
        // Without CE terminal proof, migrated legacy claim stays pinned UNKNOWN (never silently dropped)
        assertEquals(LegacyClaimState.UNKNOWN, stored.state)
        assertEquals(oldLegacy.recordId, stored.migratedFromLedgerRecordId)

        // Non-legacy V2 record is untouched
        val v2Record = oldLegacy.copy(
            recordId = "delivery_NORMAL_88_gen2_tok_abc_trig1700000000888",
            alarmId = 88L,
            generation = 2,
            instanceToken = "tok_abc",
            provenance = "BOOT"
        )
        assertTrue(
            store.migrateOldLedgerLegacyClaim(v2Record, hasCeTerminalProof = true, isAbsentFromV2QueueAndOwner = true) { true }
                is MigrationResult.IgnoredNonLegacy
        )
    }
}
