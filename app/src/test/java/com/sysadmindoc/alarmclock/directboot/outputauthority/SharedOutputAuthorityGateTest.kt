package com.sysadmindoc.alarmclock.directboot.outputauthority

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.entity.DirectBootOccurrenceReceipt
import com.sysadmindoc.alarmclock.directboot.BootProvenance
import com.sysadmindoc.alarmclock.directboot.DirectBootDurableOwner
import com.sysadmindoc.alarmclock.directboot.DirectBootDurableOwnerStore
import com.sysadmindoc.alarmclock.directboot.DirectBootLegacyClaimStore
import com.sysadmindoc.alarmclock.directboot.HandoverClaim
import com.sysadmindoc.alarmclock.directboot.LegacyClaimRetirementResult
import com.sysadmindoc.alarmclock.directboot.LegacyClaimSource
import com.sysadmindoc.alarmclock.directboot.LegacyClaimState
import com.sysadmindoc.alarmclock.directboot.LegacyClaimStorageState
import com.sysadmindoc.alarmclock.directboot.LegacyTerminalOutcome
import com.sysadmindoc.alarmclock.directboot.OutputOccurrenceKey
import com.sysadmindoc.alarmclock.directboot.OwnerState
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
        val claim1 = gate.claimOutput(context, key, handoverNonce, OutputSinkType.ALARM_SERVICE_1001)
        assertTrue(claim1 is OutputAuthorizationResult.Authorized)
        val activeNonce = (claim1 as OutputAuthorizationResult.Authorized).activeNonce
        assertTrue(activeNonce.startsWith("active-"))

        // Second claim with old handoverNonce (e.g. racing Fallback): rejected!
        val claim2 = gate.claimOutput(context, key, handoverNonce, OutputSinkType.FULLSCREEN_FALLBACK_1001)
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

        val claim = gate.claimOutput(context, key, handoverNonce, OutputSinkType.ALARM_SERVICE_1001)
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
        val claim1 = gate.claimOutput(context, key1, nonce1, OutputSinkType.ALARM_SERVICE_1001)
        assertTrue(claim1 is OutputAuthorizationResult.Authorized)
        assertEquals(key1, gate.arbiter.activeHandle?.key)

        // Candidate 2 arrives
        val res2 = gate.reserveForServiceStart(context, key2, LegacyClaimSource.ALARM_RECEIVER)
        val nonce2 = (res2 as OutputAuthorizationResult.Authorized).activeNonce
        val claim2 = gate.claimOutput(context, key2, nonce2, OutputSinkType.ALARM_SERVICE_1001)
        assertTrue(claim2 is OutputAuthorizationResult.Authorized)

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
        val claim = gate.claimOutput(context, key, nonce, OutputSinkType.ALARM_SERVICE_1001)
        val activeNonce = (claim as OutputAuthorizationResult.Authorized).activeNonce

        val obs = gate.observePhysicalOutput(context, key, activeNonce, OutputSinkType.ALARM_SERVICE_1001)
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
        val claim = gate.claimOutput(context, key, nonce, OutputSinkType.ALARM_SERVICE_1001)
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
        val claim = gate.claimOutput(context, key, nonce, OutputSinkType.ALARM_SERVICE_1001)
        val activeNonce = (claim as OutputAuthorizationResult.Authorized).activeNonce
        gate.observePhysicalOutput(context, key, activeNonce, OutputSinkType.ALARM_SERVICE_1001)
        gate.completeTerminal(context, key, activeNonce, LegacyTerminalOutcome.CONSUMED_DISMISSED)

        // Attempt retirement before Room receipt exists: rejected
        val retireFail = gate.retireClaimWithProof(context, db, key)
        assertTrue(retireFail is LegacyClaimRetirementResult.Rejected)

        // Insert valid Room 38 receipt
        val receipt = DirectBootOccurrenceReceipt(
            occurrenceKey = "occ_${key.alarmId}_${key.scheduledAt}",
            sourceRecordId = "rec-80",
            recordType = "NORMAL",
            alarmId = key.alarmId,
            triggerTime = key.scheduledAt,
            generation = 1,
            instanceToken = "tok-80",
            restoreEpoch = 10L,
            sourcePayloadHash = DirectBootOccurrenceReceipt.calculateCanonicalPayloadHash(
                sourceRecordId = "rec-80",
                recordType = "NORMAL",
                alarmId = key.alarmId,
                triggerTime = key.scheduledAt,
                generation = 1,
                instanceToken = "tok-80",
                restoreEpoch = 10L
            ),
            consumerOutcome = "CONSUMED_DELIVERED",
            completedAt = System.currentTimeMillis()
        )
        db.directBootOccurrenceReceiptDao().insert(receipt)

        // Attempt retirement now: succeeds and removes record from store
        val retireSuccess = gate.retireClaimWithProof(context, db, key)
        assertTrue(retireSuccess is LegacyClaimRetirementResult.Retired)
        assertEquals(0, (retireSuccess as LegacyClaimRetirementResult.Retired).remainingCount)
    }
}
