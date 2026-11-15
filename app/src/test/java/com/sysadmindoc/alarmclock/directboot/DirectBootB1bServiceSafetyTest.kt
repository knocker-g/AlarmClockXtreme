package com.sysadmindoc.alarmclock.directboot

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Looper
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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadows.ShadowLog
import java.time.Duration

/**
 * KNO-75 Phase C7-B1b-R1 Service Lifecycle Safety Test Suite.
 *
 * Covers R01 - R14 across:
 * - Active-state audio, vibration, AutoStop, foreground notification, and identity preservation.
 * - Default-deny for owner-unverified V2 and same-UID direct START.
 * - Preserved Legacy V1 (gen=0 and Receiver-forwarded gen=1).
 * - Idle vs active stopSelf lifetime distinction.
 * - Corrupt storage, malformed extras, and log redaction.
 */
@RunWith(RobolectricTestRunner::class)
class DirectBootB1bServiceSafetyTest {

    private lateinit var context: Context
    private lateinit var deContext: Context
    private lateinit var controller: ServiceController<DirectBootAlarmService>
    private lateinit var service: DirectBootAlarmService
    private val baseTime = 1_700_000_000_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        deContext = context.createDeviceProtectedStorageContext()
        DirectBootFiredWitnessLedger.resetSeamsForTesting()
        DirectBootDeliveryLedger.resetSeamsForTesting()
        DirectBootServiceTestFixture.clearAllDirectBootPrefs(deContext)
        DirectBootServiceTestFixture.configureAudioAndVibrationEnvironment(context)
        ShadowLog.clear()

        controller = Robolectric.buildService(DirectBootAlarmService::class.java)
        service = controller.create().get()
    }

    @After
    fun tearDown() {
        controller.destroy()
        DirectBootFiredWitnessLedger.resetSeamsForTesting()
        DirectBootDeliveryLedger.resetSeamsForTesting()
        DirectBootServiceTestFixture.clearAllDirectBootPrefs(deContext)
        ShadowLog.clear()
    }

    private fun buildLegacyV1StartIntent(
        alarmId: Long,
        triggerTime: Long,
        generation: Int = 0
    ): Intent = Intent(context, DirectBootAlarmService::class.java).apply {
        action = DirectBootAlarmService.ACTION_START
        putExtra(DirectBootAlarmReceiver.EXTRA_ALARM_ID, alarmId)
        putExtra(DirectBootAlarmReceiver.EXTRA_TRIGGER_TIME, triggerTime)
        putExtra(DirectBootV2NormalIntentIdentity.EXTRA_GENERATION, generation)
        putExtra(DirectBootV2NormalIntentIdentity.EXTRA_INSTANCE_TOKEN, "")
        putExtra(DirectBootV2NormalIntentIdentity.EXTRA_RESTORE_EPOCH, 0L)
        putExtra(DirectBootAlarmReceiver.EXTRA_TIME_LABEL, "7:00 AM")
    }

    private fun buildQualifiedV2StartIntent(
        alarmId: Long,
        triggerTime: Long,
        generation: Int = 1,
        instanceToken: String = "tok_v2_$alarmId",
        restoreEpoch: Long = 1L,
        ticketId: String? = null
    ): Intent {
        val identity = DirectBootV2NormalIntentIdentity(
            alarmId = alarmId,
            generation = generation,
            instanceToken = instanceToken,
            triggerTime = triggerTime,
            restoreEpoch = restoreEpoch
        )
        return Intent(context, DirectBootAlarmService::class.java).apply {
            action = DirectBootAlarmService.ACTION_START
            data = identity.toUri()
            putExtra(DirectBootAlarmReceiver.EXTRA_ALARM_ID, alarmId)
            putExtra(DirectBootAlarmReceiver.EXTRA_TRIGGER_TIME, triggerTime)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_KIND, DirectBootV2NormalIntentIdentity.KIND_NORMAL)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_GENERATION, generation)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_INSTANCE_TOKEN, instanceToken)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_RESTORE_EPOCH, restoreEpoch)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_IDENTITY_DIGEST, identity.computeDigest())
            putExtra(DirectBootAlarmReceiver.EXTRA_TIME_LABEL, "7:15 AM")
            if (ticketId != null) {
                putExtra(DirectBootDeliveryAuthorizer.EXTRA_TICKET_ID, ticketId)
            }
        }
    }

    private fun enqueueQualifiedV2Head(
        alarmId: Long,
        triggerTime: Long,
        generation: Int = 1,
        instanceToken: String = "tok_v2_$alarmId",
        restoreEpoch: Long = 1L
    ) {
        val item = DirectBootQueueItem(
            kind = DirectBootQueueItem.KIND_NORMAL,
            alarmId = alarmId,
            triggerTime = triggerTime,
            generation = generation,
            instanceToken = instanceToken,
            restoreEpoch = restoreEpoch,
            updatedAt = baseTime
        )
        val res = DirectBootAlarmCache.enqueueCandidate(deContext, item, now = baseTime)
        assertTrue("Expected queue enqueue success, got $res", res is QueueEnqueueResult.Enqueued)
    }

    private fun assertEffectsPreserved(
        before: DirectBootServiceTestFixture.EffectSnapshot,
        label: String
    ) {
        val after = DirectBootServiceTestFixture.captureEffects(service)
        assertFalse("$label: Service MUST NOT call stopSelf on active alarm", after.stoppedBySelf)
        assertEquals("$label: currentAlarmId MUST remain unchanged", before.alarmId, after.alarmId)
        assertEquals("$label: currentTriggerTime MUST remain unchanged", before.triggerTime, after.triggerTime)
        assertEquals("$label: currentGeneration MUST remain unchanged", before.generation, after.generation)
        assertEquals("$label: currentInstanceToken MUST remain unchanged", before.instanceToken, after.instanceToken)
        assertEquals("$label: currentRestoreEpoch MUST remain unchanged", before.restoreEpoch, after.restoreEpoch)
        assertSame("$label: Active MediaPlayer reference MUST be preserved", before.mediaPlayer, after.mediaPlayer)
        assertTrue("$label: Active MediaPlayer MUST remain playing", after.mediaPlayerPlaying)
        assertSame("$label: Active Vibrator reference MUST be preserved", before.vibrator, after.vibrator)
        assertTrue("$label: Active Vibrator MUST remain vibrating", after.vibrating)
        assertFalse("$label: Active Vibrator MUST NOT be cancelled", after.vibratorCancelled)
        assertSame("$label: Active AutoStop runnable reference MUST be preserved", before.autoStopRunnable, after.autoStopRunnable)
        assertTrue("$label: Active AutoStop runnable MUST remain scheduled", after.autoStopScheduled)
        assertSame("$label: Foreground notification MUST be preserved", before.foregroundNotification, after.foregroundNotification)
    }

    // R01: Duplicate V2 preserves active output
    @Test
    fun r01_duplicateV2_preservesActiveOutput() {
        val alarmId = 101L
        val trig = baseTime + 10_000L
        val token = "tok_r01_101"
        DirectBootServiceTestFixture.seedActiveState(
            service = service,
            context = context,
            alarmId = alarmId,
            triggerTime = trig,
            generation = 1,
            instanceToken = token,
            restoreEpoch = 1L
        )
        val rec = DirectBootFiredWitnessRecord.createNormal(
            alarmId = alarmId,
            triggerTime = trig,
            generation = 1,
            instanceToken = token,
            restoreEpoch = 1L,
            deliveryMode = DirectBootFiredWitnessRecord.MODE_SOUND_AND_VIBRATION,
            deliveryOutcome = DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_ACCEPTED
        )
        DirectBootFiredWitnessLedger.reserveWitnessSlot(deContext, rec)
        DirectBootFiredWitnessLedger.updateWitnessOutcome(
            deContext,
            rec.recordId,
            rec.deliveryMode,
            DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_ACCEPTED
        )

        val before = DirectBootServiceTestFixture.captureEffects(service)
        val duplicateIntent = buildQualifiedV2StartIntent(alarmId, trig, generation = 1, instanceToken = token, restoreEpoch = 1L)

        val res = service.onStartCommand(duplicateIntent, 0, 2)
        assertEquals(Service.START_NOT_STICKY, res)
        assertEffectsPreserved(before, "R01")

        val witnessState = DirectBootFiredWitnessLedger.readWitnesses(deContext)
        assertTrue(witnessState is WitnessLedgerState.ValidWithRecords)
        assertEquals(1, (witnessState as WitnessLedgerState.ValidWithRecords).records.size)
    }

    // R02: Partial V2 preserves active V1
    @Test
    fun r02_partialV2_preservesActiveV1() {
        val v1Trig = baseTime + 20_000L
        service.onStartCommand(buildLegacyV1StartIntent(201L, v1Trig, generation = 0), 0, 1)
        val before = DirectBootServiceTestFixture.captureEffects(service)
        assertTrue(before.mediaPlayerPlaying)
        assertTrue(before.vibrating)
        assertTrue(before.autoStopScheduled)

        val partialV2WithZeroEpoch = Intent(context, DirectBootAlarmService::class.java).apply {
            action = DirectBootAlarmService.ACTION_START
            putExtra(DirectBootAlarmReceiver.EXTRA_ALARM_ID, 202L)
            putExtra(DirectBootAlarmReceiver.EXTRA_TRIGGER_TIME, baseTime + 30_000L)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_GENERATION, 1)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_INSTANCE_TOKEN, "tok_partial_r02")
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_RESTORE_EPOCH, 0L)
        }
        val partialV2MissingToken = Intent(context, DirectBootAlarmService::class.java).apply {
            action = DirectBootAlarmService.ACTION_START
            putExtra(DirectBootAlarmReceiver.EXTRA_ALARM_ID, 203L)
            putExtra(DirectBootAlarmReceiver.EXTRA_TRIGGER_TIME, baseTime + 40_000L)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_GENERATION, 1)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_INSTANCE_TOKEN, "")
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_RESTORE_EPOCH, 5L)
        }

        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(partialV2WithZeroEpoch, 0, 2))
        assertEffectsPreserved(before, "R02-zeroEpoch")

        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(partialV2MissingToken, 0, 3))
        assertEffectsPreserved(before, "R02-missingToken")

        assertTrue(DirectBootFiredWitnessLedger.readWitnesses(deContext) is WitnessLedgerState.ValidEmpty)
        assertFalse(DirectBootAlarmCache.hasFiredWitness(context, 202L, baseTime + 30_000L))
        assertFalse(DirectBootAlarmCache.hasFiredWitness(context, 203L, baseTime + 40_000L))
    }

    // R03: Stale V2 preserves current identity
    @Test
    fun r03_staleV2_preservesCurrentIdentity() {
        val activeTrig = baseTime + 50_000L
        DirectBootServiceTestFixture.seedActiveState(
            service = service,
            context = context,
            alarmId = 301L,
            triggerTime = activeTrig,
            generation = 3,
            instanceToken = "tok_301_gen3",
            restoreEpoch = 4L
        )
        val before = DirectBootServiceTestFixture.captureEffects(service)

        val staleGenIntent = buildQualifiedV2StartIntent(
            alarmId = 301L,
            triggerTime = baseTime + 40_000L,
            generation = 2,
            instanceToken = "tok_301_gen2",
            restoreEpoch = 4L
        )

        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(staleGenIntent, 0, 2))
        assertEffectsPreserved(before, "R03")
        assertTrue(DirectBootFiredWitnessLedger.readWitnesses(deContext) is WitnessLedgerState.ValidEmpty)
    }

    // R04: Older epoch preserves newer active
    @Test
    fun r04_olderEpoch_preservesNewerActive() {
        val activeTrig = baseTime + 60_000L
        DirectBootServiceTestFixture.seedActiveState(
            service = service,
            context = context,
            alarmId = 401L,
            triggerTime = activeTrig,
            generation = 1,
            instanceToken = "tok_401_epoch5",
            restoreEpoch = 5L
        )
        val before = DirectBootServiceTestFixture.captureEffects(service)

        val olderEpochIntent = buildQualifiedV2StartIntent(
            alarmId = 402L,
            triggerTime = baseTime + 70_000L,
            generation = 2,
            instanceToken = "tok_402_epoch3",
            restoreEpoch = 3L
        )

        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(olderEpochIntent, 0, 2))
        assertEffectsPreserved(before, "R04")
        assertTrue(DirectBootFiredWitnessLedger.readWitnesses(deContext) is WitnessLedgerState.ValidEmpty)
    }

    // R05: Malformed V2 preserves effects
    @Test
    fun r05_malformedV2_preservesEffects() {
        DirectBootServiceTestFixture.seedActiveState(
            service = service,
            context = context,
            alarmId = 500L,
            triggerTime = baseTime + 50_000L,
            generation = 1,
            instanceToken = "tok_500",
            restoreEpoch = 2L
        )
        val before = DirectBootServiceTestFixture.captureEffects(service)

        val invalidKindIntent = buildQualifiedV2StartIntent(501L, baseTime + 51_000L).apply {
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_KIND, DirectBootQueueItem.KIND_QUICK)
        }
        val invalidDigestIntent = buildQualifiedV2StartIntent(502L, baseTime + 52_000L).apply {
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_IDENTITY_DIGEST, "bad_digest")
        }
        val tamperedUriIntent = buildQualifiedV2StartIntent(503L, baseTime + 53_000L).apply {
            data = android.net.Uri.parse("acx-directboot://normal/alarm/503/v2/tampered")
        }

        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(invalidKindIntent, 0, 2))
        assertEffectsPreserved(before, "R05-invalidKind")

        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(invalidDigestIntent, 0, 3))
        assertEffectsPreserved(before, "R05-invalidDigest")

        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(tamperedUriIntent, 0, 4))
        assertEffectsPreserved(before, "R05-tamperedUri")

        assertTrue(DirectBootFiredWitnessLedger.readWitnesses(deContext) is WitnessLedgerState.ValidEmpty)
    }

    // R06: Owner-unverified V2 cannot output
    @Test
    fun r06_ownerUnverifiedV2_cannotOutput() {
        val alarmId = 601L
        val trig = baseTime + 60_000L
        val token = "tok_r06_601"
        enqueueQualifiedV2Head(alarmId, trig, generation = 1, instanceToken = token, restoreEpoch = 1L)

        val qualifiedV2Intent = buildQualifiedV2StartIntent(
            alarmId = alarmId,
            triggerTime = trig,
            generation = 1,
            instanceToken = token,
            restoreEpoch = 1L,
            ticketId = "tkt_r06_unverified"
        )

        val res = service.onStartCommand(qualifiedV2Intent, 0, 1)
        assertEquals(Service.START_NOT_STICKY, res)
        assertEquals(-1L, DirectBootServiceTestFixture.currentAlarmId(service))
        assertNull(DirectBootServiceTestFixture.mediaPlayer(service))
        assertNull(DirectBootServiceTestFixture.vibrator(service))
        assertNull(DirectBootServiceTestFixture.autoStopRunnable(service))
        assertNull(shadowOf(service).lastForegroundNotification)
        assertTrue("Idle Service MUST stopSelf cleanly when V2 is unadmitted", shadowOf(service).isStoppedBySelf)
        assertTrue(DirectBootFiredWitnessLedger.readWitnesses(deContext) is WitnessLedgerState.ValidEmpty)
        assertFalse(DirectBootAlarmCache.hasFiredWitness(context, alarmId, trig))
    }

    // R07: Same-UID direct START cannot bypass
    @Test
    fun r07_sameUidDirectStart_cannotBypass() {
        val v1Trig = baseTime + 70_000L
        service.onStartCommand(buildLegacyV1StartIntent(700L, v1Trig, generation = 0), 0, 1)
        val before = DirectBootServiceTestFixture.captureEffects(service)

        val v2Trig = baseTime + 75_000L
        enqueueQualifiedV2Head(701L, v2Trig, generation = 1, instanceToken = "tok_r07_701", restoreEpoch = 1L)
        val directBypassIntent = buildQualifiedV2StartIntent(
            alarmId = 701L,
            triggerTime = v2Trig,
            generation = 1,
            instanceToken = "tok_r07_701",
            restoreEpoch = 1L,
            ticketId = "direct_same_uid_ticket"
        )

        val res = service.onStartCommand(directBypassIntent, 0, 2)
        assertEquals(Service.START_NOT_STICKY, res)
        assertEffectsPreserved(before, "R07")
        assertTrue(DirectBootFiredWitnessLedger.readWitnesses(deContext) is WitnessLedgerState.ValidEmpty)
        assertTrue(DirectBootAlarmCache.hasFiredWitness(context, 700L, v1Trig))
        assertFalse(DirectBootAlarmCache.hasFiredWitness(context, 701L, v2Trig))
    }

    // R08: Legitimate V1 gen0 still works
    @Test
    fun r08_legitimateV1Gen0_stillWorks() {
        val trig = baseTime + 80_000L
        val v1Gen0Intent = buildLegacyV1StartIntent(801L, trig, generation = 0)

        val res = service.onStartCommand(v1Gen0Intent, 0, 1)
        assertEquals(Service.START_NOT_STICKY, res)

        val effects = DirectBootServiceTestFixture.captureEffects(service)
        assertFalse(effects.stoppedBySelf)
        assertEquals(801L, effects.alarmId)
        assertEquals(trig, effects.triggerTime)
        assertEquals(0, effects.generation)
        assertEquals("", effects.instanceToken)
        assertEquals(0L, effects.restoreEpoch)
        assertNotNull(effects.foregroundNotification)
        assertTrue("Legacy V1 gen0 MUST play alarm sound", effects.mediaPlayerPlaying)
        assertTrue("Legacy V1 gen0 MUST vibrate", effects.vibrating)
        assertTrue("Legacy V1 gen0 MUST schedule AutoStop", effects.autoStopScheduled)
        assertTrue(DirectBootAlarmCache.hasFiredWitness(context, 801L, trig))
        assertTrue(DirectBootFiredWitnessLedger.readWitnesses(deContext) is WitnessLedgerState.ValidEmpty)
    }

    // R09: Receiver-forwarded V1 gen1 works
    @Test
    fun r09_receiverForwardedV1Gen1_works() {
        val trig = baseTime + 90_000L
        val receiverForwardedV1 = buildLegacyV1StartIntent(901L, trig, generation = 1)

        val res = service.onStartCommand(receiverForwardedV1, 0, 1)
        assertEquals(Service.START_NOT_STICKY, res)

        val effects = DirectBootServiceTestFixture.captureEffects(service)
        assertFalse(effects.stoppedBySelf)
        assertEquals(901L, effects.alarmId)
        assertEquals(trig, effects.triggerTime)
        assertEquals(1, effects.generation)
        assertEquals("", effects.instanceToken)
        assertEquals(0L, effects.restoreEpoch)
        assertNotNull(effects.foregroundNotification)
        assertTrue("Receiver-forwarded V1 gen1 MUST play alarm sound", effects.mediaPlayerPlaying)
        assertTrue("Receiver-forwarded V1 gen1 MUST vibrate", effects.vibrating)
        assertTrue("Receiver-forwarded V1 gen1 MUST schedule AutoStop", effects.autoStopScheduled)
        assertTrue(DirectBootAlarmCache.hasFiredWitness(context, 901L, trig))
        assertTrue(DirectBootFiredWitnessLedger.readWitnesses(deContext) is WitnessLedgerState.ValidEmpty)
    }

    // R10: Duplicate V1 preserves AutoStop
    @Test
    fun r10_duplicateV1_preservesAutoStop() {
        val trig = baseTime + 100_000L
        val v1Intent = buildLegacyV1StartIntent(1001L, trig, generation = 0)
        service.onStartCommand(v1Intent, 0, 1)
        val before = DirectBootServiceTestFixture.captureEffects(service)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(5))
        assertFalse(shadowOf(service).isStoppedBySelf)

        // Duplicate V1 delivery arrives 5 minutes in
        val res2 = service.onStartCommand(v1Intent, 0, 2)
        assertEquals(Service.START_NOT_STICKY, res2)
        assertEffectsPreserved(before, "R10")

        // Remaining 5 minutes from ORIGINAL start fires original AutoStop (not reset to +10 min)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(5))
        assertTrue("Original AutoStop deadline MUST remain armed and fire at 10m", shadowOf(service).isStoppedBySelf)
    }

    // R11: Rejected START does not stopSelf (active) and stops idle Service cleanly
    @Test
    fun r11_rejectedStart_doesNotStopSelfWhenActive_andStopsIdleCleanly() {
        DirectBootServiceTestFixture.seedActiveState(
            service = service,
            context = context,
            alarmId = 1101L,
            triggerTime = baseTime + 110_000L,
            generation = 1,
            instanceToken = "tok_1101",
            restoreEpoch = 1L
        )
        val before = DirectBootServiceTestFixture.captureEffects(service)

        val invalidAlarmIdIntent = Intent(context, DirectBootAlarmService::class.java).apply {
            action = DirectBootAlarmService.ACTION_START
            putExtra(DirectBootAlarmReceiver.EXTRA_ALARM_ID, -1L)
        }
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(invalidAlarmIdIntent, 0, 2))
        assertEffectsPreserved(before, "R11-active")

        // Idle Service receiving invalid START stops cleanly so it is not orphaned
        val idleController = Robolectric.buildService(DirectBootAlarmService::class.java)
        val idleService = idleController.create().get()
        assertEquals(Service.START_NOT_STICKY, idleService.onStartCommand(invalidAlarmIdIntent, 0, 1))
        assertTrue("Idle Service MUST stopSelf on rejected START", shadowOf(idleService).isStoppedBySelf)
        assertNull(DirectBootServiceTestFixture.mediaPlayer(idleService))
        assertNull(shadowOf(idleService).lastForegroundNotification)
        idleController.destroy()
    }

    // R12: Malformed extras and corrupt storage fail closed without leaking secrets
    @Test
    fun r12_malformedExtrasAndCorruptStorage_failClosedWithoutSecretLeak() {
        DirectBootServiceTestFixture.seedActiveState(
            service = service,
            context = context,
            alarmId = 1201L,
            triggerTime = baseTime + 120_000L,
            generation = 1,
            instanceToken = "tok_1201",
            restoreEpoch = 1L
        )
        val before = DirectBootServiceTestFixture.captureEffects(service)

        // Part A: Corrupt queue, ledger & witness DE storage fail closed for V2 without touching raw payload
        val queuePrefs = deContext.getSharedPreferences("direct_boot_next_alarm", Context.MODE_PRIVATE)
        val witnessPrefs = deContext.getSharedPreferences(DirectBootFiredWitnessLedger.PREFS_NAME, Context.MODE_PRIVATE)
        queuePrefs.edit()
            .putString(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD, "{corrupt_queue")
            .putString(DirectBootDeliveryLedger.KEY_DELIVERY_LEDGER_PAYLOAD, "{corrupt_ledger")
            .commit()
        witnessPrefs.edit().putString(DirectBootFiredWitnessLedger.KEY_WITNESS_PAYLOAD, "{corrupt_witness").commit()

        val qualifiedV2Candidate = buildQualifiedV2StartIntent(1202L, baseTime + 125_000L, generation = 1, instanceToken = "tok_1202", restoreEpoch = 2L)
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(qualifiedV2Candidate, 0, 2))
        assertEffectsPreserved(before, "R12-corruptStorage-V2")
        assertEquals("{corrupt_queue", queuePrefs.getString(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD, null))
        assertEquals("{corrupt_ledger", queuePrefs.getString(DirectBootDeliveryLedger.KEY_DELIVERY_LEDGER_PAYLOAD, null))
        assertEquals("{corrupt_witness", witnessPrefs.getString(DirectBootFiredWitnessLedger.KEY_WITNESS_PAYLOAD, null))

        // Clear corrupt storage for Part B & C
        DirectBootServiceTestFixture.clearAllDirectBootPrefs(deContext)

        // Part B: Wrong-type extra + sentinel secrets never leaked in Service logs
        val sentinelToken = "SECRET_SENTINEL_TOKEN_R12_ABC"
        val sentinelTicket = "SECRET_SENTINEL_TICKET_R12_DEF"
        val sentinelKind = "ATTACKER_SENTINEL_KIND_R12_GHI"
        val sentinelDigest = "SECRET_SENTINEL_DIGEST_R12_JKL"
        ShadowLog.clear()
        val malformedTypesIntent = Intent(context, DirectBootAlarmService::class.java).apply {
            action = DirectBootAlarmService.ACTION_START
            putExtra(DirectBootAlarmReceiver.EXTRA_ALARM_ID, "not_a_long")
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_KIND, sentinelKind)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_INSTANCE_TOKEN, sentinelToken)
            putExtra(DirectBootDeliveryAuthorizer.EXTRA_TICKET_ID, sentinelTicket)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_IDENTITY_DIGEST, sentinelDigest)
        }
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(malformedTypesIntent, 0, 3))
        assertEffectsPreserved(before, "R12-wrongTypes")

        val logs = ShadowLog.getLogsForTag("DirectBootAlarmService").joinToString("\n") { it.msg }
        assertFalse("Service logs MUST NOT expose raw token", logs.contains(sentinelToken))
        assertFalse("Service logs MUST NOT expose raw ticketId", logs.contains(sentinelTicket))
        assertFalse("Service logs MUST NOT expose raw kind", logs.contains(sentinelKind))
        assertFalse("Service logs MUST NOT expose raw digest", logs.contains(sentinelDigest))

        // Part C: Throwing extra read (EXTRAS_READ_FAILED) fails closed via UNKNOWN_KEEP_OLD
        val throwingIntent = object : Intent(context, DirectBootAlarmService::class.java) {
            override fun hasExtra(name: String?): Boolean {
                throw RuntimeException("Simulated Parcelable read failure")
            }
        }.apply {
            action = DirectBootAlarmService.ACTION_START
        }
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(throwingIntent, 0, 4))
        assertEffectsPreserved(before, "R12-extrasReadFailed")
    }

    // R13: Active AutoStop remains scheduled
    @Test
    fun r13_activeAutoStop_remainsScheduled() {
        DirectBootServiceTestFixture.seedActiveState(
            service = service,
            context = context,
            alarmId = 1301L,
            triggerTime = baseTime + 130_000L,
            generation = 1,
            instanceToken = "tok_1301",
            restoreEpoch = 1L
        )
        val before = DirectBootServiceTestFixture.captureEffects(service)
        val originalCb = before.autoStopRunnable
        assertNotNull(originalCb)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(4))
        assertFalse(shadowOf(service).isStoppedBySelf)

        val unadmittedV2 = buildQualifiedV2StartIntent(1302L, baseTime + 135_000L, generation = 1, instanceToken = "tok_1302", restoreEpoch = 1L)
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(unadmittedV2, 0, 2))
        assertEffectsPreserved(before, "R13")
        assertSame(originalCb, DirectBootServiceTestFixture.autoStopRunnable(service))

        // Remaining 6 minutes trigger the preserved AutoStop runnable
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(6))
        assertTrue("Preserved AutoStop callback MUST still fire at full 10m window", shadowOf(service).isStoppedBySelf)
    }

    // R14: No rejected V2 witness/fire marker
    @Test
    fun r14_noRejectedV2WitnessOrFireMarker() {
        val v1Trig = baseTime + 140_000L
        service.onStartCommand(buildLegacyV1StartIntent(1400L, v1Trig, generation = 0), 0, 1)

        // Send malformed, stale-epoch, and owner-unverified V2 candidates
        val malformed = Intent(context, DirectBootAlarmService::class.java).apply {
            action = DirectBootAlarmService.ACTION_START
            putExtra(DirectBootAlarmReceiver.EXTRA_ALARM_ID, 1401L)
            putExtra(DirectBootAlarmReceiver.EXTRA_TRIGGER_TIME, baseTime + 141_000L)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_GENERATION, 1)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_INSTANCE_TOKEN, "tok_1401")
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_RESTORE_EPOCH, 0L)
        }
        val unverified = buildQualifiedV2StartIntent(1402L, baseTime + 142_000L, generation = 1, instanceToken = "tok_1402", restoreEpoch = 1L)

        service.onStartCommand(malformed, 0, 2)
        service.onStartCommand(unverified, 0, 3)

        assertTrue(
            "Rejected/unverified V2 MUST NOT write to Witness Ledger",
            DirectBootFiredWitnessLedger.readWitnesses(deContext) is WitnessLedgerState.ValidEmpty
        )
        assertFalse(DirectBootAlarmCache.hasFiredWitness(context, 1401L, baseTime + 141_000L))
        assertFalse(DirectBootAlarmCache.hasFiredWitness(context, 1402L, baseTime + 142_000L))
        assertTrue(
            "Existing V1 fired marker MUST remain intact",
            DirectBootAlarmCache.hasFiredWitness(context, 1400L, v1Trig)
        )
    }

    // =========================================================================
    // KNO-75 C7-B1b-R2: Legacy V1 / V2 DE Storage Failure Isolation (R2-01..R2-10)
    // =========================================================================

    // R2-01: Valid Legacy V1 gen0 starts with corrupt V2 Witness storage
    @Test
    fun r2_01_validLegacyV1Gen0_startsWithCorruptWitnessStorage() {
        val witnessPrefs = deContext.getSharedPreferences(DirectBootFiredWitnessLedger.PREFS_NAME, Context.MODE_PRIVATE)
        witnessPrefs.edit().putString(DirectBootFiredWitnessLedger.KEY_WITNESS_PAYLOAD, "{corrupt_witness").commit()

        val trig = baseTime + 201_000L
        val res = service.onStartCommand(buildLegacyV1StartIntent(2001L, trig, generation = 0), 0, 1)
        assertEquals(Service.START_NOT_STICKY, res)

        val effects = DirectBootServiceTestFixture.captureEffects(service)
        assertFalse("R2-01: Legacy V1 gen0 MUST NOT call stopSelf", effects.stoppedBySelf)
        assertEquals(2001L, effects.alarmId)
        assertEquals(trig, effects.triggerTime)
        assertEquals(0, effects.generation)
        assertNotNull(effects.foregroundNotification)
        assertTrue("R2-01: Legacy V1 gen0 MUST play sound despite corrupt witness", effects.mediaPlayerPlaying)
        assertTrue("R2-01: Legacy V1 gen0 MUST vibrate despite corrupt witness", effects.vibrating)
        assertTrue("R2-01: Legacy V1 gen0 MUST schedule AutoStop despite corrupt witness", effects.autoStopScheduled)
        assertTrue(DirectBootAlarmCache.hasFiredWitness(context, 2001L, trig))
        assertEquals("{corrupt_witness", witnessPrefs.getString(DirectBootFiredWitnessLedger.KEY_WITNESS_PAYLOAD, null))
    }

    // R2-02: Valid Legacy V1 gen1 starts with corrupt V2 Delivery Ledger
    @Test
    fun r2_02_validLegacyV1Gen1_startsWithCorruptDeliveryLedger() {
        val ledgerPrefs = deContext.getSharedPreferences(DirectBootDeliveryLedger.PREFS_NAME, Context.MODE_PRIVATE)
        ledgerPrefs.edit().putString(DirectBootDeliveryLedger.KEY_DELIVERY_LEDGER_PAYLOAD, "{corrupt_ledger").commit()

        val trig = baseTime + 202_000L
        val res = service.onStartCommand(buildLegacyV1StartIntent(2002L, trig, generation = 1), 0, 1)
        assertEquals(Service.START_NOT_STICKY, res)

        val effects = DirectBootServiceTestFixture.captureEffects(service)
        assertFalse("R2-02: Legacy V1 gen1 MUST NOT call stopSelf", effects.stoppedBySelf)
        assertEquals(2002L, effects.alarmId)
        assertEquals(trig, effects.triggerTime)
        assertEquals(1, effects.generation)
        assertNotNull(effects.foregroundNotification)
        assertTrue("R2-02: Legacy V1 gen1 MUST play sound despite corrupt ledger", effects.mediaPlayerPlaying)
        assertTrue("R2-02: Legacy V1 gen1 MUST vibrate despite corrupt ledger", effects.vibrating)
        assertTrue("R2-02: Legacy V1 gen1 MUST schedule AutoStop despite corrupt ledger", effects.autoStopScheduled)
        assertTrue(DirectBootAlarmCache.hasFiredWitness(context, 2002L, trig))
        assertEquals("{corrupt_ledger", ledgerPrefs.getString(DirectBootDeliveryLedger.KEY_DELIVERY_LEDGER_PAYLOAD, null))
    }

    // R2-03: Valid Legacy V1 starts with corrupt V2 Queue
    @Test
    fun r2_03_validLegacyV1_startsWithCorruptQueueStorage() {
        val queuePrefs = deContext.getSharedPreferences("direct_boot_next_alarm", Context.MODE_PRIVATE)
        queuePrefs.edit().putString(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD, "{corrupt_queue").commit()

        val trig = baseTime + 203_000L
        val res = service.onStartCommand(buildLegacyV1StartIntent(2003L, trig, generation = 0), 0, 1)
        assertEquals(Service.START_NOT_STICKY, res)

        val effects = DirectBootServiceTestFixture.captureEffects(service)
        assertFalse("R2-03: Legacy V1 MUST NOT call stopSelf", effects.stoppedBySelf)
        assertEquals(2003L, effects.alarmId)
        assertEquals(trig, effects.triggerTime)
        assertNotNull(effects.foregroundNotification)
        assertTrue("R2-03: Legacy V1 MUST play sound despite corrupt queue", effects.mediaPlayerPlaying)
        assertTrue("R2-03: Legacy V1 MUST vibrate despite corrupt queue", effects.vibrating)
        assertTrue("R2-03: Legacy V1 MUST schedule AutoStop despite corrupt queue", effects.autoStopScheduled)
        assertTrue(DirectBootAlarmCache.hasFiredWitness(context, 2003L, trig))
        assertEquals("{corrupt_queue", queuePrefs.getString(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD, null))
    }

    // R2-04: Valid Legacy V1 starts with UNSUPPORTED_SCHEMA V2 storage
    @Test
    fun r2_04_validLegacyV1_startsWithUnsupportedSchemaV2Storage() {
        val queuePrefs = deContext.getSharedPreferences(DirectBootDeliveryLedger.PREFS_NAME, Context.MODE_PRIVATE)
        val witnessPrefs = deContext.getSharedPreferences(DirectBootFiredWitnessLedger.PREFS_NAME, Context.MODE_PRIVATE)
        val unsupportedQueue = """{"schemaVersion":99,"items":[]}"""
        val unsupportedLedger = """{"schemaVersion":99,"records":[]}"""
        val unsupportedWitness = """[{"schemaVersion":99}]"""

        queuePrefs.edit()
            .putString(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD, unsupportedQueue)
            .putString(DirectBootDeliveryLedger.KEY_DELIVERY_LEDGER_PAYLOAD, unsupportedLedger)
            .commit()
        witnessPrefs.edit().putString(DirectBootFiredWitnessLedger.KEY_WITNESS_PAYLOAD, unsupportedWitness).commit()

        assertTrue(DirectBootAlarmCache.inspectQueueStorageState(deContext) is QueueStorageState.UnsupportedSchema)
        assertTrue(DirectBootDeliveryLedger.readUnacknowledgedRecords(deContext) is LedgerStorageState.UnsupportedSchema)
        assertTrue(DirectBootFiredWitnessLedger.readWitnesses(deContext) is WitnessLedgerState.UnsupportedSchema)

        val trig = baseTime + 204_000L
        val res = service.onStartCommand(buildLegacyV1StartIntent(2004L, trig, generation = 0), 0, 1)
        assertEquals(Service.START_NOT_STICKY, res)

        val effects = DirectBootServiceTestFixture.captureEffects(service)
        assertFalse("R2-04: Legacy V1 MUST NOT call stopSelf on unsupported V2 schema", effects.stoppedBySelf)
        assertEquals(2004L, effects.alarmId)
        assertEquals(trig, effects.triggerTime)
        assertNotNull(effects.foregroundNotification)
        assertTrue(effects.mediaPlayerPlaying)
        assertTrue(effects.vibrating)
        assertTrue(effects.autoStopScheduled)
        assertTrue(DirectBootAlarmCache.hasFiredWitness(context, 2004L, trig))
        assertEquals(unsupportedQueue, queuePrefs.getString(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD, null))
        assertEquals(unsupportedLedger, queuePrefs.getString(DirectBootDeliveryLedger.KEY_DELIVERY_LEDGER_PAYLOAD, null))
        assertEquals(unsupportedWitness, witnessPrefs.getString(DirectBootFiredWitnessLedger.KEY_WITNESS_PAYLOAD, null))
    }

    // R2-05: Active Legacy V1 is not interrupted by V2 storage corruption
    @Test
    fun r2_05_activeLegacyV1_notInterruptedByV2StorageCorruption() {
        val v1Trig = baseTime + 205_000L
        service.onStartCommand(buildLegacyV1StartIntent(2005L, v1Trig, generation = 0), 0, 1)
        val before = DirectBootServiceTestFixture.captureEffects(service)
        assertTrue(before.mediaPlayerPlaying)
        assertTrue(before.vibrating)
        assertTrue(before.autoStopScheduled)

        // Corrupt all three V2 stores after V1 is already active
        val queuePrefs = deContext.getSharedPreferences(DirectBootDeliveryLedger.PREFS_NAME, Context.MODE_PRIVATE)
        val witnessPrefs = deContext.getSharedPreferences(DirectBootFiredWitnessLedger.PREFS_NAME, Context.MODE_PRIVATE)
        queuePrefs.edit()
            .putString(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD, "{corrupt_queue")
            .putString(DirectBootDeliveryLedger.KEY_DELIVERY_LEDGER_PAYLOAD, "{corrupt_ledger")
            .commit()
        witnessPrefs.edit().putString(DirectBootFiredWitnessLedger.KEY_WITNESS_PAYLOAD, "{corrupt_witness").commit()

        val v2Intent = buildQualifiedV2StartIntent(2099L, baseTime + 206_000L, generation = 1, instanceToken = "tok_r2_05", restoreEpoch = 1L)
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(v2Intent, 0, 2))
        assertEffectsPreserved(before, "R2-05-afterCorruptV2Attempt")

        // Duplicate V1 delivery during V2 storage corruption also preserves active V1 & fires AutoStop on time
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(5))
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(buildLegacyV1StartIntent(2005L, v1Trig, generation = 0), 0, 3))
        assertEffectsPreserved(before, "R2-05-duplicateV1DuringCorrupt")

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(5))
        assertTrue("R2-05: Active V1 AutoStop MUST complete normally at 10m", shadowOf(service).isStoppedBySelf)
        assertEquals("{corrupt_queue", queuePrefs.getString(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD, null))
        assertEquals("{corrupt_ledger", queuePrefs.getString(DirectBootDeliveryLedger.KEY_DELIVERY_LEDGER_PAYLOAD, null))
        assertEquals("{corrupt_witness", witnessPrefs.getString(DirectBootFiredWitnessLedger.KEY_WITNESS_PAYLOAD, null))
    }

    // R2-06: Malformed/partial V2 with corrupt storage remains rejected
    @Test
    fun r2_06_malformedOrPartialV2_withCorruptStorage_remainsRejected() {
        val v1Trig = baseTime + 206_000L
        service.onStartCommand(buildLegacyV1StartIntent(2006L, v1Trig, generation = 0), 0, 1)
        val before = DirectBootServiceTestFixture.captureEffects(service)

        val queuePrefs = deContext.getSharedPreferences(DirectBootDeliveryLedger.PREFS_NAME, Context.MODE_PRIVATE)
        val witnessPrefs = deContext.getSharedPreferences(DirectBootFiredWitnessLedger.PREFS_NAME, Context.MODE_PRIVATE)
        queuePrefs.edit()
            .putString(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD, "{corrupt_queue")
            .putString(DirectBootDeliveryLedger.KEY_DELIVERY_LEDGER_PAYLOAD, "{corrupt_ledger")
            .commit()
        witnessPrefs.edit().putString(DirectBootFiredWitnessLedger.KEY_WITNESS_PAYLOAD, "{corrupt_witness").commit()

        val partialV2 = Intent(context, DirectBootAlarmService::class.java).apply {
            action = DirectBootAlarmService.ACTION_START
            putExtra(DirectBootAlarmReceiver.EXTRA_ALARM_ID, 2061L)
            putExtra(DirectBootAlarmReceiver.EXTRA_TRIGGER_TIME, baseTime + 207_000L)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_GENERATION, 1)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_INSTANCE_TOKEN, "tok_r2_06")
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_RESTORE_EPOCH, 0L)
        }
        val badDigestV2 = buildQualifiedV2StartIntent(2062L, baseTime + 208_000L).apply {
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_IDENTITY_DIGEST, "bad_digest")
        }

        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(partialV2, 0, 2))
        assertEffectsPreserved(before, "R2-06-partial")

        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(badDigestV2, 0, 3))
        assertEffectsPreserved(before, "R2-06-badDigest")

        assertFalse(DirectBootAlarmCache.hasFiredWitness(context, 2061L, baseTime + 207_000L))
        assertFalse(DirectBootAlarmCache.hasFiredWitness(context, 2062L, baseTime + 208_000L))
        assertEquals("{corrupt_queue", queuePrefs.getString(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD, null))
        assertEquals("{corrupt_ledger", queuePrefs.getString(DirectBootDeliveryLedger.KEY_DELIVERY_LEDGER_PAYLOAD, null))
        assertEquals("{corrupt_witness", witnessPrefs.getString(DirectBootFiredWitnessLedger.KEY_WITNESS_PAYLOAD, null))
    }

    // R2-07: Active V2 cannot be preempted by syntactically valid Legacy V1
    @Test
    fun r2_07_activeV2_cannotBePreemptedByValidLegacyV1() {
        DirectBootServiceTestFixture.seedActiveState(
            service = service,
            context = context,
            alarmId = 2070L,
            triggerTime = baseTime + 207_000L,
            generation = 2,
            instanceToken = "tok_r2_07_active",
            restoreEpoch = 3L
        )
        val before = DirectBootServiceTestFixture.captureEffects(service)

        val v1Gen0 = buildLegacyV1StartIntent(2071L, baseTime + 208_000L, generation = 0)
        val v1Gen1 = buildLegacyV1StartIntent(2072L, baseTime + 209_000L, generation = 1)

        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(v1Gen0, 0, 2))
        assertEffectsPreserved(before, "R2-07-v1Gen0")

        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(v1Gen1, 0, 3))
        assertEffectsPreserved(before, "R2-07-v1Gen1")

        assertFalse(DirectBootAlarmCache.hasFiredWitness(context, 2071L, baseTime + 208_000L))
        assertFalse(DirectBootAlarmCache.hasFiredWitness(context, 2072L, baseTime + 209_000L))
    }

    // R2-08: Qualified V2 with no Durable Owner remains default-denied
    @Test
    fun r2_08_qualifiedV2_withoutDurableOwner_remainsDefaultDenied() {
        val alarmId = 2080L
        val trig = baseTime + 208_000L
        val token = "tok_r2_08"
        enqueueQualifiedV2Head(alarmId, trig, generation = 1, instanceToken = token, restoreEpoch = 1L)

        val v2Intent = buildQualifiedV2StartIntent(alarmId, trig, generation = 1, instanceToken = token, restoreEpoch = 1L, ticketId = "tkt_r2_08")
        val res = service.onStartCommand(v2Intent, 0, 1)
        assertEquals(Service.START_NOT_STICKY, res)
        assertEquals(-1L, DirectBootServiceTestFixture.currentAlarmId(service))
        assertNull(DirectBootServiceTestFixture.mediaPlayer(service))
        assertNull(DirectBootServiceTestFixture.vibrator(service))
        assertNull(DirectBootServiceTestFixture.autoStopRunnable(service))
        assertNull(shadowOf(service).lastForegroundNotification)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertTrue(DirectBootFiredWitnessLedger.readWitnesses(deContext) is WitnessLedgerState.ValidEmpty)
        assertFalse(DirectBootAlarmCache.hasFiredWitness(context, alarmId, trig))
    }

    // R2-09: Duplicate active V1 preserves original AutoStop deadline
    @Test
    fun r2_09_duplicateActiveV1_withCorruptV2Storage_preservesOriginalAutoStopDeadline() {
        val trig = baseTime + 209_000L
        val v1Intent = buildLegacyV1StartIntent(2090L, trig, generation = 0)
        service.onStartCommand(v1Intent, 0, 1)
        val before = DirectBootServiceTestFixture.captureEffects(service)
        val originalCb = before.autoStopRunnable
        assertNotNull(originalCb)

        // Corrupt V2 storage after active V1 start
        val queuePrefs = deContext.getSharedPreferences(DirectBootDeliveryLedger.PREFS_NAME, Context.MODE_PRIVATE)
        queuePrefs.edit().putString(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD, "{corrupt_queue").commit()

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(5))
        assertFalse(shadowOf(service).isStoppedBySelf)

        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(v1Intent, 0, 2))
        assertEffectsPreserved(before, "R2-09")
        assertSame(originalCb, DirectBootServiceTestFixture.autoStopRunnable(service))

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(5))
        assertTrue("R2-09: Original 10m AutoStop deadline MUST remain preserved", shadowOf(service).isStoppedBySelf)
    }

    // R2-10: No additional witness or fired marker for rejected V2
    @Test
    fun r2_10_rejectedV2_writesNoWitnessOrFiredMarker() {
        val v1Trig = baseTime + 210_000L
        service.onStartCommand(buildLegacyV1StartIntent(2100L, v1Trig, generation = 0), 0, 1)

        val partial = Intent(context, DirectBootAlarmService::class.java).apply {
            action = DirectBootAlarmService.ACTION_START
            putExtra(DirectBootAlarmReceiver.EXTRA_ALARM_ID, 2101L)
            putExtra(DirectBootAlarmReceiver.EXTRA_TRIGGER_TIME, baseTime + 211_000L)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_GENERATION, 1)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_INSTANCE_TOKEN, "tok_2101")
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_RESTORE_EPOCH, 0L)
        }
        val unverified = buildQualifiedV2StartIntent(2102L, baseTime + 212_000L, generation = 1, instanceToken = "tok_2102", restoreEpoch = 1L)

        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(partial, 0, 2))
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(unverified, 0, 3))

        assertTrue(DirectBootFiredWitnessLedger.readWitnesses(deContext) is WitnessLedgerState.ValidEmpty)
        assertFalse(DirectBootAlarmCache.hasFiredWitness(context, 2101L, baseTime + 211_000L))
        assertFalse(DirectBootAlarmCache.hasFiredWitness(context, 2102L, baseTime + 212_000L))
        assertTrue(DirectBootAlarmCache.hasFiredWitness(context, 2100L, v1Trig))
    }
}
