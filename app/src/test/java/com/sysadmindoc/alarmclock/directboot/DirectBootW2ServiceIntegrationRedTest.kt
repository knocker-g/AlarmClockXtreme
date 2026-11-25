package com.sysadmindoc.alarmclock.directboot

import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

        val gate = com.sysadmindoc.alarmclock.directboot.outputauthority.SharedOutputAuthorityGate.getInstance(context).apply {
            isEnabled = true
            bootCount = 1L
            arbiter.clearForTesting()
        }
        controller = Robolectric.buildService(DirectBootAlarmService::class.java)
        service = controller.create().get()
        service.outputAuthorityGate = gate
        service.v2DeliveryCapableProvider = { true }
    }

    @After
    fun tearDown() {
        controller.destroy()
        DirectBootFiredWitnessLedger.resetSeamsForTesting()
        org.robolectric.shadows.ShadowMediaPlayer.resetStaticState()
        clearWitnessStorage()
        com.sysadmindoc.alarmclock.directboot.outputauthority.SharedOutputAuthorityGate.getInstance(context).apply {
            isEnabled = false
            arbiter.clearForTesting()
        }
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

    // =========================================================================
    // KNO-75 G4-STEP6-P23 RED: P3 Durable Owner, Witness, Arbiter & Cutpoints M2..M10
    // =========================================================================

    private fun seedHandoverAndLedger(
        alarmId: Long,
        triggerTime: Long,
        token: String,
        epoch: Long = 2L,
        expectedSeq: Long = 0L,
        bootProvenance: BootProvenance = BootProvenance(
            bootCount = null,
            lastElapsedRealtime = android.os.SystemClock.elapsedRealtime(),
            bootEpochEstimateMs = (System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        )
    ): DirectBootV2NormalIntentIdentity {
        val delRecord = DirectBootDeliveryRecord.createNormal(
            alarmId = alarmId,
            triggerTime = triggerTime,
            deliveredAt = triggerTime + 10L,
            generation = 1,
            instanceToken = token,
            restoreEpoch = epoch
        )
        assertTrue(DirectBootDeliveryLedger.appendRecord(deContext, delRecord) is DeliveryAppendResult.Appended)
        val handoverOwner = DirectBootDurableOwner(
            schemaVersion = DirectBootDurableOwnerStore.CURRENT_SCHEMA_VERSION,
            state = OwnerState.HANDOVER_PENDING,
            ownerSeq = expectedSeq,
            bootProvenance = bootProvenance,
            handoverClaim = HandoverClaim(
                handoverId = delRecord.recordId,
                alarmId = alarmId,
                triggerTime = triggerTime,
                generation = 1,
                instanceToken = token,
                restoreEpoch = epoch
            ),
            activeSession = null,
            updatedAtEpochMs = System.currentTimeMillis()
        )
        assertTrue(DirectBootDurableOwnerStore.write(deContext, handoverOwner, expectedOwnerSeq = expectedSeq) is OwnerWriteResult.Success)
        return DirectBootV2NormalIntentIdentity(
            alarmId = alarmId,
            generation = 1,
            instanceToken = token,
            triggerTime = triggerTime,
            restoreEpoch = epoch
        )
    }

    private fun buildServiceStartIntent(identity: DirectBootV2NormalIntentIdentity): Intent {
        return Intent(context, DirectBootAlarmService::class.java).apply {
            action = DirectBootAlarmService.ACTION_START
            data = identity.toUri()
            putExtra(DirectBootAlarmReceiver.EXTRA_ALARM_ID, identity.alarmId)
            putExtra(DirectBootAlarmReceiver.EXTRA_TRIGGER_TIME, identity.triggerTime)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_KIND, identity.kind)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_GENERATION, identity.generation)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_INSTANCE_TOKEN, identity.instanceToken)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_RESTORE_EPOCH, identity.restoreEpoch)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_IDENTITY_DIGEST, identity.computeDigest())
            putExtra(DirectBootAlarmReceiver.EXTRA_TIME_LABEL, "7:30 AM")
        }
    }

    @Test
    fun p3_01_handoverPendingAdmitsV2_casV2Active_reservesAndAcceptsWitness_stopReleasesIdle() {
        val trig = baseTime + 50_000L
        val identity = seedHandoverAndLedger(alarmId = 901L, triggerTime = trig, token = "tok_p3_901")

        service.onStartCommand(buildServiceStartIntent(identity), 0, 1)

        val effects = DirectBootServiceTestFixture.captureEffects(service)
        assertFalse("Verified HANDOVER_PENDING MUST admit V2 without stopSelf", effects.stoppedBySelf)
        assertEquals(901L, effects.alarmId)
        assertEquals("tok_p3_901", effects.instanceToken)
        assertEquals(2L, effects.restoreEpoch)
        assertTrue(effects.mediaPlayerPlaying)
        assertTrue(effects.vibrating)
        assertTrue(effects.autoStopScheduled)

        val ownerActive = DirectBootDurableOwnerStore.read(deContext) as OwnerReadResult.Success
        assertEquals(OwnerState.V2_ACTIVE, ownerActive.owner.state)
        assertEquals(2L, ownerActive.owner.ownerSeq)
        assertEquals(901L, ownerActive.owner.activeSession?.alarmId)

        val witnessState = DirectBootFiredWitnessLedger.readWitnesses(deContext) as WitnessLedgerState.ValidWithRecords
        assertEquals(1, witnessState.records.size)
        assertEquals(DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_ACCEPTED, witnessState.records[0].deliveryOutcome)

        val stopIntent = Intent(context, DirectBootAlarmService::class.java).apply {
            action = DirectBootAlarmService.ACTION_STOP
            putExtra(DirectBootAlarmReceiver.EXTRA_ALARM_ID, 901L)
            putExtra(DirectBootAlarmReceiver.EXTRA_TRIGGER_TIME, trig)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_GENERATION, 1)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_INSTANCE_TOKEN, "tok_p3_901")
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_RESTORE_EPOCH, 2L)
        }
        service.onStartCommand(stopIntent, 0, 2)
        val ownerIdle = DirectBootDurableOwnerStore.read(deContext) as OwnerReadResult.Success
        assertEquals(OwnerState.IDLE, ownerIdle.owner.state)
        assertEquals(3L, ownerIdle.owner.ownerSeq)
    }

    @Test
    fun p3_m2_arbiterPreemptionStopUnconfirmed_blocksV2StartAndReleasesHandover() {
        val gate = com.sysadmindoc.alarmclock.directboot.outputauthority.SharedOutputAuthorityGate().apply {
            isEnabled = true
            bootCount = 1L
        }
        val externalKey = OutputOccurrenceKey(alarmId = 999L, scheduledAt = baseTime + 5_000L, refireCount = 0)
        val acq = gate.arbiter.acquirePending(
            key = externalKey,
            sink = com.sysadmindoc.alarmclock.directboot.outputauthority.OutputSinkType.ALARM_SERVICE_1001,
            activeNonce = "ext-nonce-999"
        )
        assertTrue(acq.first)

        service.outputAuthorityGate = gate
        val trig = baseTime + 60_000L
        val identity = seedHandoverAndLedger(alarmId = 902L, triggerTime = trig, token = "tok_m2_902")
        service.onStartCommand(buildServiceStartIntent(identity), 0, 1)

        val effects = DirectBootServiceTestFixture.captureEffects(service)
        assertTrue("M2: Unconfirmed external owner stop MUST stopSelf idle service", effects.stoppedBySelf)
        assertFalse(effects.mediaPlayerPlaying)
        assertEquals(-1L, effects.alarmId)
        assertTrue(DirectBootFiredWitnessLedger.readWitnesses(deContext) is WitnessLedgerState.ValidEmpty)
        assertEquals(999L, gate.arbiter.activeHandle?.key?.alarmId)
        gate.arbiter.clearForTesting()
    }

    @Test
    fun p3_m4_rebootBootProvenanceMismatch_failsClosedUnknownKeepOld() {
        val trig = baseTime + 70_000L
        val oldBoot = BootProvenance(bootCount = 1, lastElapsedRealtime = 5_000L, bootEpochEstimateMs = baseTime - 5_000L)
        val identity = seedHandoverAndLedger(
            alarmId = 903L,
            triggerTime = trig,
            token = "tok_m4_903",
            bootProvenance = oldBoot
        )

        service.onStartCommand(buildServiceStartIntent(identity), 0, 1)

        val effects = DirectBootServiceTestFixture.captureEffects(service)
        assertTrue("M4: Cross-reboot HANDOVER_PENDING MUST fail closed", effects.stoppedBySelf)
        assertFalse(effects.mediaPlayerPlaying)
        assertTrue(DirectBootFiredWitnessLedger.readWitnesses(deContext) is WitnessLedgerState.ValidEmpty)
        val ownerStillPending = DirectBootDurableOwnerStore.read(deContext) as OwnerReadResult.Success
        assertEquals(OwnerState.HANDOVER_PENDING, ownerStillPending.owner.state)
    }

    @Test
    fun p3_m7_witnessCapacityExhausted_rollsBackV2ActiveToIdleAndRecordsMissed() {
        val mode = DirectBootFiredWitnessRecord.MODE_SOUND_AND_VIBRATION
        val outcome = DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_ACCEPTED
        for (i in 1..16) {
            val rec = DirectBootFiredWitnessRecord.createNormal(
                alarmId = i.toLong(),
                triggerTime = baseTime + (i * 1000L),
                generation = 1,
                instanceToken = "tok_cap_$i",
                restoreEpoch = 1L,
                deliveryMode = mode,
                deliveryOutcome = outcome
            )
            assertTrue(DirectBootFiredWitnessLedger.appendWitness(deContext, rec) is WitnessAppendResult.Appended)
        }

        val trig = baseTime + 80_000L
        val identity = seedHandoverAndLedger(alarmId = 904L, triggerTime = trig, token = "tok_m7_904")
        service.onStartCommand(buildServiceStartIntent(identity), 0, 1)

        val effects = DirectBootServiceTestFixture.captureEffects(service)
        assertTrue("M7: Witness reservation failure MUST stopSelf", effects.stoppedBySelf)
        assertFalse(effects.mediaPlayerPlaying)
        assertNull(effects.foregroundNotification)
        val ownerAfter = DirectBootDurableOwnerStore.read(deContext) as OwnerReadResult.Success
        assertEquals(OwnerState.IDLE, ownerAfter.owner.state)
        val missed = DirectBootMissedStore.readUnacknowledgedRecords(deContext) as MissedStoreStorageState.ValidWithRecords
        assertEquals(1, missed.records.size)
        assertEquals(DirectBootMissedRecord.CLASSIFICATION_SERVICE_START_FAILED, missed.records[0].classification)
    }

    @Test
    fun p3_m8_allOutputsFail_recordsOutputStartFailedWitnessAndReleasesOwner() {
        // Clear Robolectric MediaPlayer & Vibrator capabilities so both sound & vibration fail
        listOf(
            android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM),
            android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
        ).filterNotNull().forEach { uri ->
            org.robolectric.shadows.ShadowMediaPlayer.addException(
                org.robolectric.shadows.util.DataSource.toDataSource(context.applicationContext, uri),
                java.io.IOException("Test forced media failure")
            )
        }
        val vib = DirectBootServiceTestFixture.obtainDefaultVibrator(context)
        if (vib != null) shadowOf(vib).setHasVibrator(false)

        val trig = baseTime + 90_000L
        val identity = seedHandoverAndLedger(alarmId = 905L, triggerTime = trig, token = "tok_m8_905")
        service.onStartCommand(buildServiceStartIntent(identity), 0, 1)

        val effects = DirectBootServiceTestFixture.captureEffects(service)
        assertTrue("M8: Total output failure MUST stopSelf", effects.stoppedBySelf)
        val witnessState = DirectBootFiredWitnessLedger.readWitnesses(deContext) as WitnessLedgerState.ValidWithRecords
        assertEquals(DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_FAILED, witnessState.records.single().deliveryOutcome)
        val ownerAfter = DirectBootDurableOwnerStore.read(deContext) as OwnerReadResult.Success
        assertEquals(OwnerState.IDLE, ownerAfter.owner.state)
    }

    @Test
    fun p3_m9_witnessOutcomeUpdateCommitFailure_retainsUnknownReservation() {
        val trig = baseTime + 95_000L
        val identity = seedHandoverAndLedger(alarmId = 906L, triggerTime = trig, token = "tok_m9_906")
        var commitCount = 0
        DirectBootFiredWitnessLedger.commitSeam = { editor ->
            commitCount++
            if (commitCount == 1) editor.commit() else false
        }

        service.onStartCommand(buildServiceStartIntent(identity), 0, 1)

        val witnessState = DirectBootFiredWitnessLedger.readWitnesses(deContext) as WitnessLedgerState.ValidWithRecords
        assertEquals(DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_STATUS_UNKNOWN, witnessState.records.single().deliveryOutcome)
    }

    @Test
    fun p3_m10_lateStopAfterSuccessorHandover_doesNotClobberAlarm2HandoverPending() {
        val t1 = baseTime + 100_000L
        val id1 = seedHandoverAndLedger(alarmId = 907L, triggerTime = t1, token = "tok_m10_907", expectedSeq = 0L)
        service.onStartCommand(buildServiceStartIntent(id1), 0, 1)
        assertEquals(OwnerState.V2_ACTIVE, (DirectBootDurableOwnerStore.read(deContext) as OwnerReadResult.Success).owner.state)

        // Simulate Alarm 2 committing HANDOVER_PENDING (seq 2 -> 3) in DE storage before Alarm 1's late teardown
        val t2 = baseTime + 110_000L
        val handover2 = DirectBootDurableOwner(
            schemaVersion = DirectBootDurableOwnerStore.CURRENT_SCHEMA_VERSION,
            state = OwnerState.HANDOVER_PENDING,
            ownerSeq = 2L,
            bootProvenance = (DirectBootDurableOwnerStore.read(deContext) as OwnerReadResult.Success).owner.bootProvenance,
            handoverClaim = HandoverClaim("ho_908", 908L, t2, 1, "tok_m10_908", 2L),
            activeSession = null,
            updatedAtEpochMs = System.currentTimeMillis()
        )
        assertTrue(DirectBootDurableOwnerStore.write(deContext, handover2, expectedOwnerSeq = 2L) is OwnerWriteResult.Success)

        val stopA1 = Intent(context, DirectBootAlarmService::class.java).apply {
            action = DirectBootAlarmService.ACTION_STOP
            putExtra(DirectBootAlarmReceiver.EXTRA_ALARM_ID, 907L)
            putExtra(DirectBootAlarmReceiver.EXTRA_TRIGGER_TIME, t1)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_GENERATION, 1)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_INSTANCE_TOKEN, "tok_m10_907")
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_RESTORE_EPOCH, 2L)
        }
        service.onStartCommand(stopA1, 0, 2)

        val ownerAfter = DirectBootDurableOwnerStore.read(deContext) as OwnerReadResult.Success
        assertEquals("M10: Alarm 1 teardown MUST NOT wipe Alarm 2 HANDOVER_PENDING", OwnerState.HANDOVER_PENDING, ownerAfter.owner.state)
        assertEquals(3L, ownerAfter.owner.ownerSeq)
        assertEquals(908L, ownerAfter.owner.handoverClaim?.alarmId)
    }

    @Test
    fun p3_m6_casPromotionFailure_blocksAudioAndWitnessWithoutClobberingSuccessor() {
        val t1 = baseTime + 120_000L
        val t2 = baseTime + 130_000L
        val id1 = seedHandoverAndLedger(alarmId = 909L, triggerTime = t1, token = "tok_m6_909", expectedSeq = 0L)

        // Simulate concurrent Alarm 2 preemption advancing ownerSeq (1 -> 2) right before Alarm 1 CAS promote
        service.beforeV2OwnerPromoteHook = {
            val current = (DirectBootDurableOwnerStore.read(deContext) as OwnerReadResult.Success).owner
            val handover2 = DirectBootDurableOwner(
                schemaVersion = DirectBootDurableOwnerStore.CURRENT_SCHEMA_VERSION,
                state = OwnerState.HANDOVER_PENDING,
                ownerSeq = current.ownerSeq,
                bootProvenance = current.bootProvenance,
                handoverClaim = HandoverClaim("ho_910", 910L, t2, 1, "tok_m6_910", 2L),
                activeSession = null,
                updatedAtEpochMs = System.currentTimeMillis()
            )
            assertTrue(DirectBootDurableOwnerStore.write(deContext, handover2, expectedOwnerSeq = current.ownerSeq) is OwnerWriteResult.Success)
        }
        try {
            service.onStartCommand(buildServiceStartIntent(id1), 0, 1)
        } finally {
            service.beforeV2OwnerPromoteHook = null
        }

        val effects = DirectBootServiceTestFixture.captureEffects(service)
        assertTrue("M6: CAS failure on idle service MUST stopSelf", effects.stoppedBySelf)
        assertFalse("M6: CAS failure MUST NOT start audio", effects.mediaPlayerPlaying)
        assertFalse("M6: CAS failure MUST NOT vibrate", effects.vibrating)
        assertNull("M6: CAS failure MUST NOT post foreground notification", effects.foregroundNotification)
        assertTrue("M6: CAS failure MUST NOT write Witness", DirectBootFiredWitnessLedger.readWitnesses(deContext) is WitnessLedgerState.ValidEmpty)

        val ownerAfter = DirectBootDurableOwnerStore.read(deContext) as OwnerReadResult.Success
        assertEquals(OwnerState.HANDOVER_PENDING, ownerAfter.owner.state)
        assertEquals(2L, ownerAfter.owner.ownerSeq)
        assertEquals(910L, ownerAfter.owner.handoverClaim?.alarmId)
    }

    @Test
    fun p3_v2Isolation_doesNotMutateV1FiredMarkerOrSnapshot_andRecordsOnlyV2Witness() {
        val v1Trigger = baseTime + 140_000L
        val v2Trigger = baseTime + 150_000L
        val v1SnapshotAlarm = com.sysadmindoc.alarmclock.data.model.Alarm(
            id = 911L,
            hour = 7,
            minute = 15,
            label = "V1 Snapshot 911",
            isEnabled = true,
            nextTriggerTime = v1Trigger
        )
        DirectBootAlarmCache.saveIfEarlier(deContext, v1SnapshotAlarm, v1Trigger, now = baseTime)
        val snapshotBefore = DirectBootAlarmCache.read(deContext)
        assertNotNull("Precondition: V1 snapshot must be seeded", snapshotBefore)

        val prevV1AlarmId = 505L
        val prevV1Trigger = baseTime + 5_000L
        val prevV1FiredAt = baseTime + 5_005L
        DirectBootAlarmCache.recordFired(deContext, prevV1AlarmId, prevV1Trigger, prevV1FiredAt)

        val v2Identity = seedHandoverAndLedger(alarmId = 911L, triggerTime = v2Trigger, token = "tok_iso_911", expectedSeq = 0L)
        service.onStartCommand(buildServiceStartIntent(v2Identity), 0, 1)

        // V2 witness must be reserved and updated to ACCEPTED
        val witnessState = DirectBootFiredWitnessLedger.readWitnesses(deContext) as WitnessLedgerState.ValidWithRecords
        val witness = witnessState.records.single()
        assertEquals(911L, witness.alarmId)
        assertEquals(v2Trigger, witness.triggerTime)
        assertEquals("tok_iso_911", witness.instanceToken)
        assertEquals(DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_ACCEPTED, witness.deliveryOutcome)

        // V1 fired marker and V1 snapshot must remain completely untouched by V2 firing
        assertFalse(
            "V2 firing MUST NOT write V1 DirectBootAlarmCache fired witness",
            DirectBootAlarmCache.hasFiredWitness(deContext, 911L, v2Trigger)
        )
        val v1Prefs = deContext.getSharedPreferences("direct_boot_next_alarm", Context.MODE_PRIVATE)
        assertEquals("V2 firing MUST NOT overwrite V1 fired_alarm_id", prevV1AlarmId, v1Prefs.getLong("fired_alarm_id", -1L))
        assertEquals("V2 firing MUST NOT overwrite V1 fired_trigger_time", prevV1Trigger, v1Prefs.getLong("fired_trigger_time", -1L))
        assertEquals("V2 firing MUST NOT overwrite V1 fired_at", prevV1FiredAt, v1Prefs.getLong("fired_at", -1L))
        assertEquals("V2 firing MUST NOT delete or mutate V1 scalar snapshot", snapshotBefore, DirectBootAlarmCache.read(deContext))
    }

    @Test
    fun p3_v1LegacyIsolation_preservesV1RecordFiredAndLeavesV2WitnessEmpty() {
        val flagOffGate = com.sysadmindoc.alarmclock.directboot.outputauthority.SharedOutputAuthorityGate.getInstance(context).apply { isEnabled = false }
        service.outputAuthorityGate = flagOffGate
        val v1Trigger = baseTime + 160_000L
        val v1Alarm = com.sysadmindoc.alarmclock.data.model.Alarm(
            id = 912L,
            hour = 8,
            minute = 0,
            label = "Legacy V1 912",
            isEnabled = true,
            nextTriggerTime = v1Trigger
        )
        DirectBootAlarmCache.saveIfEarlier(deContext, v1Alarm, v1Trigger, now = baseTime)
        assertNotNull(DirectBootAlarmCache.read(deContext))

        val legacyIntent = Intent(context, DirectBootAlarmService::class.java).apply {
            action = DirectBootAlarmService.ACTION_START
            putExtra(DirectBootAlarmReceiver.EXTRA_ALARM_ID, 912L)
            putExtra(DirectBootAlarmReceiver.EXTRA_TRIGGER_TIME, v1Trigger)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_GENERATION, 0)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_INSTANCE_TOKEN, "")
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_RESTORE_EPOCH, 0L)
        }
        service.onStartCommand(legacyIntent, 0, 1)

        assertTrue(
            "LEGACY_V1 MUST still call DirectBootAlarmCache.recordFired",
            DirectBootAlarmCache.hasFiredWitness(deContext, 912L, v1Trigger)
        )
        assertNull("LEGACY_V1 recordFired clears matching V1 snapshot as designed", DirectBootAlarmCache.read(deContext))
        val witnessState = DirectBootFiredWitnessLedger.readWitnesses(deContext)
        assertTrue(
            "LEGACY_V1 MUST NOT write V2 WitnessLedger",
            witnessState is WitnessLedgerState.Absent || witnessState is WitnessLedgerState.ValidEmpty
        )
    }
}

