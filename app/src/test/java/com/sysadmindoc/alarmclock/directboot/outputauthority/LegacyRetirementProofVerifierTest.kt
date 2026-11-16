package com.sysadmindoc.alarmclock.directboot.outputauthority

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.entity.DirectBootOccurrenceReceipt
import com.sysadmindoc.alarmclock.directboot.BootProvenance
import com.sysadmindoc.alarmclock.directboot.DirectBootAlarmCache
import com.sysadmindoc.alarmclock.directboot.DirectBootDurableOwner
import com.sysadmindoc.alarmclock.directboot.DirectBootDurableOwnerStore
import com.sysadmindoc.alarmclock.directboot.DirectBootLegacyClaimStore
import com.sysadmindoc.alarmclock.directboot.HandoverClaim
import com.sysadmindoc.alarmclock.directboot.LegacyClaimRecord
import com.sysadmindoc.alarmclock.directboot.LegacyClaimSource
import com.sysadmindoc.alarmclock.directboot.LegacyClaimState
import com.sysadmindoc.alarmclock.directboot.LegacyTerminalOutcome
import com.sysadmindoc.alarmclock.directboot.OutputOccurrenceKey
import com.sysadmindoc.alarmclock.directboot.OwnerState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class LegacyRetirementProofVerifierTest {

    private lateinit var context: Context
    private lateinit var db: AlarmDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        clearStorage()
    }

    @After
    fun tearDown() {
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

    private fun seedTerminalClaim(key: OutputOccurrenceKey) {
        val claim = LegacyClaimRecord(
            alarmId = key.alarmId,
            scheduledAt = key.scheduledAt,
            refireCount = key.refireCount,
            source = LegacyClaimSource.ALARM_RECEIVER,
            claimNonce = "nonce-1",
            processSessionToken = "proc-1",
            bootCount = 10L,
            state = LegacyClaimState.CLAIMED_RECEIVER
        )
        DirectBootLegacyClaimStore.writeInitialClaim(context, claim)
        DirectBootLegacyClaimStore.transition(
            context = context,
            key = key,
            expectedFrom = LegacyClaimState.CLAIMED_RECEIVER,
            toState = LegacyClaimState.START_REQUESTED,
            expectedNonce = "nonce-1",
            newNonce = "nonce-2",
            processSessionToken = "proc-1",
            bootCount = 10L
        )
        DirectBootLegacyClaimStore.transition(
            context = context,
            key = key,
            expectedFrom = LegacyClaimState.START_REQUESTED,
            toState = LegacyClaimState.OUTPUT_OBSERVED,
            expectedNonce = "nonce-2",
            newNonce = "nonce-3",
            processSessionToken = "proc-1",
            bootCount = 10L
        )
        DirectBootLegacyClaimStore.transition(
            context = context,
            key = key,
            expectedFrom = LegacyClaimState.OUTPUT_OBSERVED,
            toState = LegacyClaimState.TERMINAL,
            expectedNonce = "nonce-3",
            newNonce = "nonce-4",
            processSessionToken = "proc-1",
            bootCount = 10L,
            terminalOutcome = LegacyTerminalOutcome.CONSUMED_DISMISSED
        )
    }

    private fun insertValidReceipt(
        alarmId: Long,
        triggerTime: Long,
        restoreEpoch: Long = 10L,
        outcome: String = "CONSUMED_DELIVERED",
        recordId: String = "rec-1",
        gen: Int = 1,
        token: String = "tok-1",
        hashOverride: String? = null
    ) {
        val payloadHash = hashOverride ?: DirectBootOccurrenceReceipt.calculateCanonicalPayloadHash(
            sourceRecordId = recordId,
            recordType = "NORMAL",
            alarmId = alarmId,
            triggerTime = triggerTime,
            generation = gen,
            instanceToken = token,
            restoreEpoch = restoreEpoch
        )
        val receipt = DirectBootOccurrenceReceipt(
            occurrenceKey = "occ_${alarmId}_$triggerTime",
            sourceRecordId = recordId,
            recordType = "NORMAL",
            alarmId = alarmId,
            triggerTime = triggerTime,
            generation = gen,
            instanceToken = token,
            restoreEpoch = restoreEpoch,
            sourcePayloadHash = payloadHash,
            consumerOutcome = outcome,
            completedAt = System.currentTimeMillis()
        )
        db.directBootOccurrenceReceiptDao().insert(receipt)
    }

    @Test
    fun testVerify_rejectsWhenNoClaimRecord() {
        val key = OutputOccurrenceKey(100L, 1_000L, 0)
        val result = LegacyRetirementProofVerifier.verify(context, db, key)
        assertTrue(result is RetirementVerificationResult.Rejected)
        assertTrue((result as RetirementVerificationResult.Rejected).reason.contains("not found"))
    }

    @Test
    fun testVerify_rejectsWhenClaimNotTerminal() {
        val key = OutputOccurrenceKey(100L, 1_000L, 0)
        val claim = LegacyClaimRecord(
            alarmId = key.alarmId,
            scheduledAt = key.scheduledAt,
            refireCount = key.refireCount,
            source = LegacyClaimSource.ALARM_RECEIVER,
            claimNonce = "nonce-1",
            processSessionToken = "proc-1",
            bootCount = 10L,
            state = LegacyClaimState.START_REQUESTED
        )
        DirectBootLegacyClaimStore.writeInitialClaim(context, claim)

        val result = LegacyRetirementProofVerifier.verify(context, db, key)
        assertTrue(result is RetirementVerificationResult.Rejected)
        assertTrue((result as RetirementVerificationResult.Rejected).reason.contains("must be in TERMINAL state"))
    }

    @Test
    fun testVerify_rejectsWhenReceiptMissing() {
        val key = OutputOccurrenceKey(100L, 1_000L, 0)
        seedTerminalClaim(key)

        val result = LegacyRetirementProofVerifier.verify(context, db, key)
        assertTrue(result is RetirementVerificationResult.Rejected)
        assertTrue((result as RetirementVerificationResult.Rejected).reason.contains("No Room 38 receipt found"))
    }

    @Test
    fun testVerify_rejectsWhenRestoreEpochZeroOrNegative() {
        val key = OutputOccurrenceKey(100L, 1_000L, 0)
        seedTerminalClaim(key)
        insertValidReceipt(key.alarmId, key.scheduledAt, restoreEpoch = 0L)

        val result = LegacyRetirementProofVerifier.verify(context, db, key)
        assertTrue(result is RetirementVerificationResult.Rejected)
        assertTrue((result as RetirementVerificationResult.Rejected).reason.contains("Invalid restoreEpoch"))
    }

    @Test
    fun testVerify_rejectsIneligibleOutcome() {
        val key = OutputOccurrenceKey(100L, 1_000L, 0)
        seedTerminalClaim(key)
        insertValidReceipt(key.alarmId, key.scheduledAt, outcome = "CONSUMED_DISMISSED")

        val result = LegacyRetirementProofVerifier.verify(context, db, key)
        assertTrue(result is RetirementVerificationResult.Rejected)
        assertTrue((result as RetirementVerificationResult.Rejected).reason.contains("Ineligible consumer outcome"))
    }

    @Test
    fun testVerify_rejectsPayloadHashMismatch() {
        val key = OutputOccurrenceKey(100L, 1_000L, 0)
        seedTerminalClaim(key)
        insertValidReceipt(key.alarmId, key.scheduledAt, hashOverride = "forged-hash-12345")

        val result = LegacyRetirementProofVerifier.verify(context, db, key)
        assertTrue(result is RetirementVerificationResult.Rejected)
        assertTrue((result as RetirementVerificationResult.Rejected).reason.contains("Payload hash mismatch"))
    }

    @Test
    fun testVerify_rejectsWhenPresentInV2DurableOwner() {
        val key = OutputOccurrenceKey(100L, 1_000L, 0)
        seedTerminalClaim(key)
        insertValidReceipt(key.alarmId, key.scheduledAt)

        // Seed V2 durable owner claiming this occurrence
        val owner = DirectBootDurableOwner(
            state = OwnerState.HANDOVER_PENDING,
            ownerSeq = 1L,
            bootProvenance = BootProvenance(bootCount = 10),
            handoverClaim = HandoverClaim(
                handoverId = "h-1",
                alarmId = key.alarmId,
                triggerTime = key.scheduledAt,
                generation = 1,
                instanceToken = "tok-1",
                restoreEpoch = 10L
            ),
            updatedAtEpochMs = System.currentTimeMillis()
        )
        DirectBootDurableOwnerStore.write(context, owner, expectedOwnerSeq = 0L)

        val result = LegacyRetirementProofVerifier.verify(context, db, key)
        assertTrue(result is RetirementVerificationResult.Rejected)
        assertTrue((result as RetirementVerificationResult.Rejected).reason.contains("active in V2 Durable Owner"))
    }

    @Test
    fun testVerify_succeedsWithVerifiedEvidenceWhenAllProofsValid() {
        val key = OutputOccurrenceKey(100L, 1_000L, 0)
        seedTerminalClaim(key)
        insertValidReceipt(key.alarmId, key.scheduledAt)

        val result = LegacyRetirementProofVerifier.verify(context, db, key)
        assertTrue(result is RetirementVerificationResult.Verified)
        val evidence = (result as RetirementVerificationResult.Verified).evidence
        assertEquals(key, evidence.key)
        assertEquals("CONSUMED_DELIVERED", evidence.consumerOutcome)
        assertEquals(10L, evidence.restoreEpoch)
        assertNotNull(evidence.payloadHash)
    }
}
