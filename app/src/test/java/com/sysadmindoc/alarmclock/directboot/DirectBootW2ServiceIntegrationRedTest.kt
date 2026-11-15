package com.sysadmindoc.alarmclock.directboot

import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController

/**
 * Production-Coupled Test Suite for W2 Service Delivery Integration (KNO-75 Phase C7-V2-WITNESS-W2-ACCEPTANCE).
 *
 * Adapted for B1b-R1 default-deny without weakening any W2 witness invariants:
 * - Unauthenticated V2 ACTION_START on an idle Service is default-denied (zero witness/audio/foreground).
 * - T1-T3: W2 witness reservation + duplicate V2 START intent handling preserves active output and does NOT
 *   duplicate witness records.
 * - T4-T8: Outcome transition state machine & commit failure verification.
 * - T9-T10: Legacy V1 isolation & capacity 16 fencing.
 */
@RunWith(RobolectricTestRunner::class)
class DirectBootW2ServiceIntegrationRedTest {

    private lateinit var context: Context
    private lateinit var deContext: Context
    private lateinit var controller: ServiceController<DirectBootAlarmService>
    private lateinit var service: DirectBootAlarmService
    private val baseTime = 1_700_000_000_000L

    private fun clearWitnessStorage() {
        DirectBootServiceTestFixture.clearAllDirectBootPrefs(deContext)
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        deContext = context.createDeviceProtectedStorageContext()
        DirectBootFiredWitnessLedger.resetSeamsForTesting()
        clearWitnessStorage()
        DirectBootServiceTestFixture.configureAudioAndVibrationEnvironment(context)

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
    // T1 - T3: Default-Deny + W2 Witness & Duplicate Delivery Verification
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

        // B1b default-deny: unauthenticated V2 ACTION_START on an idle Service must NOT append a witness or start output
        val idleProbeController = Robolectric.buildService(DirectBootAlarmService::class.java)
        val idleProbeService = idleProbeController.create().get()
        idleProbeService.onStartCommand(startIntent, 0, 1)
        assertFalse(
            "Unauthenticated V2 ACTION_START without Durable Owner must NOT append witness record",
            DirectBootFiredWitnessLedger.hasWitness(
                context = deContext,
                alarmId = alarmId,
                triggerTime = triggerTime,
                generation = gen,
                instanceToken = token,
                kind = DirectBootFiredWitnessRecord.KIND_NORMAL
            )
        )
        assertNull(DirectBootServiceTestFixture.mediaPlayer(idleProbeService))
        assertNull(shadowOf(idleProbeService).lastForegroundNotification)
        idleProbeController.destroy()

        // T1: Seed synthetic active V2 Service identity/effects + W2 witness reservation directly via Witness Ledger
        DirectBootServiceTestFixture.seedActiveState(
            service = service,
            context = context,
            alarmId = alarmId,
            triggerTime = triggerTime,
            generation = gen,
            instanceToken = token,
            restoreEpoch = epoch
        )
        val w2Record = DirectBootFiredWitnessRecord.createNormal(
            alarmId = alarmId,
            triggerTime = triggerTime,
            generation = gen,
            instanceToken = token,
            restoreEpoch = epoch,
            deliveryMode = DirectBootFiredWitnessRecord.MODE_SOUND_AND_VIBRATION,
            deliveryOutcome = DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_STATUS_UNKNOWN
        )
        val reserveRes = DirectBootFiredWitnessLedger.reserveWitnessSlot(deContext, w2Record)
        assertTrue("T1: W2 witness slot reservation MUST append", reserveRes is WitnessAppendResult.Appended)
        val dupReserve = DirectBootFiredWitnessLedger.reserveWitnessSlot(deContext, w2Record)
        assertTrue("T2: Direct W2 duplicate reservation MUST return DuplicateIgnored", dupReserve is WitnessAppendResult.DuplicateIgnored)

        val outcomeRes = DirectBootFiredWitnessLedger.updateWitnessOutcome(
            deContext,
            w2Record.recordId,
            w2Record.deliveryMode,
            DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_ACCEPTED
        )
        assertTrue("T1: W2 outcome update MUST succeed", outcomeRes is WitnessUpdateResult.Updated)

        val witnessPresent = DirectBootFiredWitnessLedger.hasWitness(
            context = deContext,
            alarmId = alarmId,
            triggerTime = triggerTime,
            generation = gen,
            instanceToken = token,
            kind = DirectBootFiredWitnessRecord.KIND_NORMAL
        )
        assertTrue("T1: Witness record MUST exist in DirectBootFiredWitnessLedger", witnessPresent)

        val before = DirectBootServiceTestFixture.captureEffects(service)
        assertTrue(before.mediaPlayerPlaying)
        assertTrue(before.vibrating)
        assertTrue(before.autoStopScheduled)

        // T2 & T3: Duplicate V2 START intent arrives for same active occurrence
        val res2 = service.onStartCommand(startIntent, 0, 2)
        assertEquals("T2: Duplicate start intent MUST return START_NOT_STICKY", Service.START_NOT_STICKY, res2)

        val after = DirectBootServiceTestFixture.captureEffects(service)
        assertFalse("T3: Duplicate start MUST NOT call stopSelf on active service", after.stoppedBySelf)
        assertSame("T2: Active MediaPlayer instance MUST be preserved without restart", before.mediaPlayer, after.mediaPlayer)
        assertTrue("T2: Active MediaPlayer MUST remain playing", after.mediaPlayerPlaying)
        assertSame("T2: Active Vibrator instance MUST be preserved", before.vibrator, after.vibrator)
        assertFalse("T2: Active Vibrator MUST NOT be cancelled", after.vibratorCancelled)
        assertSame("T2: Active AutoStop callback MUST be preserved", before.autoStopRunnable, after.autoStopRunnable)
        assertTrue("T2: Active AutoStop MUST remain scheduled", after.autoStopScheduled)

        val finalizedRecord = (outcomeRes as WitnessUpdateResult.Updated).record
        val dupFinalized = DirectBootFiredWitnessLedger.appendWitness(deContext, finalizedRecord)
        assertTrue("T2: Duplicate finalized witness append MUST return DuplicateIgnored", dupFinalized is WitnessAppendResult.DuplicateIgnored)

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
