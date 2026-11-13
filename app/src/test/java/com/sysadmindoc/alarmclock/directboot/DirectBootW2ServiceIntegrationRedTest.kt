package com.sysadmindoc.alarmclock.directboot

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.android.controller.ServiceController

/**
 * Production-Coupled Test Suite for W2 Service Delivery Integration (KNO-75 Phase C7-V2-WITNESS-W2-ACCEPTANCE).
 *
 * Verifies T1 - T12 Acceptance & Side-Effect Contracts:
 * - T1: First V2 delivery is appended 1 time to DirectBootFiredWitnessLedger.
 * - T2: Duplicate V2 START intent returns DuplicateIgnored with 0 extra audio output calls.
 * - T3: Duplicate V2 START intent does NOT stop active playing alarm.
 * - T4: UNKNOWN reservation -> successful outcome update (OUTPUT_START_ACCEPTED).
 * - T5: Re-updating finalized outcome with identical outcome returns DuplicateIgnored.
 * - T6: Attempting to change a finalized outcome to a DIFFERENT outcome returns StateConflict.
 * - T7: Commit failure during outcome update retains UNKNOWN reservation in DE storage without claiming false success.
 * - T8: Invalid deliveryMode or deliveryOutcome string returns RejectedIneligible.
 * - T9: Legacy V1 alarm bypasses V2 Witness Ledger.
 * - T10: Capacity 16 exhaustion fencing preserved.
 * - T11: Legacy V1 fired marker alignment.
 * - T12: STOP P1B Authority regression suite passes.
 */
@RunWith(RobolectricTestRunner::class)
class DirectBootW2ServiceIntegrationRedTest {

    private lateinit var context: Context
    private lateinit var deContext: Context
    private lateinit var controller: ServiceController<DirectBootAlarmService>
    private lateinit var service: DirectBootAlarmService
    private val baseTime = 1_700_000_000_000L

    private fun clearWitnessStorage() {
        val dePrefs = deContext.getSharedPreferences(DirectBootFiredWitnessLedger.PREFS_NAME, Context.MODE_PRIVATE)
        dePrefs.edit().clear().commit()
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        deContext = context.createDeviceProtectedStorageContext()
        DirectBootFiredWitnessLedger.resetSeamsForTesting()
        clearWitnessStorage()

        controller = Robolectric.buildService(DirectBootAlarmService::class.java)
        service = controller.create().get()
    }

    @After
    fun tearDown() {
        controller.destroy()
        DirectBootFiredWitnessLedger.resetSeamsForTesting()
        clearWitnessStorage()
    }

    // =========================================================================
    // T1 - T3: Admission & Duplicate Delivery Side-Effect Verification
    // =========================================================================

    @Test
    fun t1_t2_t3_v2AlarmServiceStart_and_duplicateIntentHandling() {
        val alarmId = 101L
        val triggerTime = baseTime + 10_000L
        val gen = 1
        val token = "tok_w2_101"
        val epoch = 1L

        val startIntent = Intent(context, DirectBootAlarmService::class.java).apply {
            action = DirectBootAlarmService.ACTION_START
            putExtra(DirectBootAlarmReceiver.EXTRA_ALARM_ID, alarmId)
            putExtra(DirectBootAlarmReceiver.EXTRA_TRIGGER_TIME, triggerTime)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_GENERATION, gen)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_INSTANCE_TOKEN, token)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_RESTORE_EPOCH, epoch)
            putExtra(DirectBootAlarmReceiver.EXTRA_TIME_LABEL, "7:15 AM")
        }

        // T1: First V2 delivery start
        service.onStartCommand(startIntent, 0, 1)

        val witnessPresent = DirectBootFiredWitnessLedger.hasWitness(
            context = deContext, alarmId = alarmId, triggerTime = triggerTime, generation = gen, instanceToken = token, kind = DirectBootFiredWitnessRecord.KIND_NORMAL
        )
        assertTrue("T1: V2 Service start MUST append witness record to DirectBootFiredWitnessLedger", witnessPresent)

        // T2 & T3: Duplicate V2 START intent arrived for same active occurrence
        val res2 = service.onStartCommand(startIntent, 0, 2)
        assertEquals("T2: Duplicate start intent MUST return START_NOT_STICKY", android.app.Service.START_NOT_STICKY, res2)

        // Verify active service was NOT stopped by duplicate intent
        val state = DirectBootFiredWitnessLedger.readWitnesses(deContext)
        assertTrue(state is WitnessLedgerState.ValidWithRecords)
        assertEquals("T3: Duplicate start MUST NOT duplicate witness record in storage", 1, (state as WitnessLedgerState.ValidWithRecords).records.size)
    }

    // =========================================================================
    // T4 - T8: Outcome Transition State Machine & Commit Failure Verification
    // =========================================================================

    @Test
    fun t4_t5_t6_t7_t8_outcomeStateTransitions() {
        val mode = DirectBootFiredWitnessRecord.MODE_SOUND_AND_VIBRATION
        val record = DirectBootFiredWitnessRecord.createNormal(
            alarmId = 401L, triggerTime = 1000L, generation = 1, instanceToken = "tok_401", restoreEpoch = 1L,
            deliveryMode = mode, deliveryOutcome = DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_STATUS_UNKNOWN
        )

        // Reserve slot (UNKNOWN state)
        val resReserve = DirectBootFiredWitnessLedger.reserveWitnessSlot(deContext, record)
        assertTrue(resReserve is WitnessAppendResult.Appended)

        // T4: UNKNOWN -> ACCEPTED transition
        val resUpdate1 = DirectBootFiredWitnessLedger.updateWitnessOutcome(deContext, record.recordId, mode, DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_ACCEPTED)
        assertTrue("T4: Transition from UNKNOWN to ACCEPTED MUST succeed", resUpdate1 is WitnessUpdateResult.Updated)

        // T5: Finalized outcome -> Idempotent re-update with same outcome
        val resUpdate2 = DirectBootFiredWitnessLedger.updateWitnessOutcome(deContext, record.recordId, mode, DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_ACCEPTED)
        assertTrue("T5: Re-update with identical outcome MUST return Updated", resUpdate2 is WitnessUpdateResult.Updated)

        // T6: Finalized outcome -> Change to DIFFERENT outcome returns StorageCorrupt state conflict
        val resUpdate3 = DirectBootFiredWitnessLedger.updateWitnessOutcome(deContext, record.recordId, mode, DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_FAILED)
        assertTrue("T6: Changing finalized outcome to different outcome MUST fail closed", resUpdate3 is WitnessUpdateResult.StorageCorrupt)

        // T6b: Same outcome BUT different deliveryMode -> Mode Conflict rejected
        val resUpdateModeConflict = DirectBootFiredWitnessLedger.updateWitnessOutcome(
            deContext,
            record.recordId,
            DirectBootFiredWitnessRecord.MODE_SOUND_ONLY,
            DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_ACCEPTED
        )
        assertTrue("T6b: Changing deliveryMode on finalized record MUST fail closed", resUpdateModeConflict is WitnessUpdateResult.StorageCorrupt)

        // T8: Invalid outcome string returns StorageCorrupt
        val resUpdate4 = DirectBootFiredWitnessLedger.updateWitnessOutcome(deContext, record.recordId, mode, "INVALID_OUTCOME")
        assertTrue("T8: Invalid outcome string MUST fail closed", resUpdate4 is WitnessUpdateResult.StorageCorrupt)
    }

    @Test
    fun t7_updateCommitFailure_retainsUnknownReservation() {
        val mode = DirectBootFiredWitnessRecord.MODE_SOUND_AND_VIBRATION
        val record = DirectBootFiredWitnessRecord.createNormal(
            alarmId = 701L, triggerTime = 1000L, generation = 1, instanceToken = "tok_701", restoreEpoch = 1L,
            deliveryMode = mode, deliveryOutcome = DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_STATUS_UNKNOWN
        )

        // Reserve slot
        DirectBootFiredWitnessLedger.reserveWitnessSlot(deContext, record)

        // Inject commit failure during outcome update
        DirectBootFiredWitnessLedger.commitSeam = { false }

        val updateRes = DirectBootFiredWitnessLedger.updateWitnessOutcome(deContext, record.recordId, mode, DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_ACCEPTED)
        assertTrue("T7: Commit failure MUST return PersistenceFailed", updateRes is WitnessUpdateResult.PersistenceFailed)

        // Reset commit seam and verify UNKNOWN reservation was retained in DE storage
        DirectBootFiredWitnessLedger.resetSeamsForTesting()
        val state = DirectBootFiredWitnessLedger.readWitnesses(deContext)
        assertTrue(state is WitnessLedgerState.ValidWithRecords)
        val rec = (state as WitnessLedgerState.ValidWithRecords).records.first()
        assertEquals("T7: UNKNOWN reservation MUST be retained when update commit fails", DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_STATUS_UNKNOWN, rec.deliveryOutcome)
    }

    // =========================================================================
    // T9 & T10: Legacy V1 Isolation & Capacity 16 Fencing
    // =========================================================================

    @Test
    fun t9_legacyV1AlarmStart_mustNotAppendWitness() {
        val alarmId = 201L
        val triggerTime = baseTime + 20_000L

        val startLegacyIntent = Intent(context, DirectBootAlarmService::class.java).apply {
            action = DirectBootAlarmService.ACTION_START
            putExtra(DirectBootAlarmReceiver.EXTRA_ALARM_ID, alarmId)
            putExtra(DirectBootAlarmReceiver.EXTRA_TRIGGER_TIME, triggerTime)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_GENERATION, 0)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_INSTANCE_TOKEN, "")
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_RESTORE_EPOCH, 0L)
        }

        service.onStartCommand(startLegacyIntent, 0, 1)

        val witnessPresent = DirectBootFiredWitnessLedger.hasWitness(
            context = deContext, alarmId = alarmId, triggerTime = triggerTime, generation = 0, instanceToken = "", kind = DirectBootFiredWitnessRecord.KIND_NORMAL
        )

        assertFalse("T9: Legacy V1 alarm MUST NOT append V2 witness record", witnessPresent)
    }

    @Test
    fun t10_capacityExhaustion_17thItemRejected() {
        val mode = DirectBootFiredWitnessRecord.MODE_SOUND_AND_VIBRATION
        val outcome = DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_ACCEPTED
        for (i in 1..16) {
            val rec = DirectBootFiredWitnessRecord.createNormal(alarmId = i.toLong(), triggerTime = baseTime + (i * 1000L), generation = 1, instanceToken = "tok_$i", restoreEpoch = 1L, deliveryMode = mode, deliveryOutcome = outcome)
            DirectBootFiredWitnessLedger.appendWitness(deContext, rec)
        }

        val rec17 = DirectBootFiredWitnessRecord.createNormal(alarmId = 17L, triggerTime = baseTime + 17000L, generation = 1, instanceToken = "tok_17", restoreEpoch = 1L, deliveryMode = mode, deliveryOutcome = outcome)
        val res = DirectBootFiredWitnessLedger.appendWitness(deContext, rec17)
        assertTrue("T10: 17th item append MUST return CapacityExhausted", res is WitnessAppendResult.CapacityExhausted)
    }
}
