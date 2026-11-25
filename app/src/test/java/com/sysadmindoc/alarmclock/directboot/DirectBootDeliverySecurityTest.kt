package com.sysadmindoc.alarmclock.directboot

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.work.Operation
import androidx.work.WorkManager
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.data.repository.AlarmIncidentRepository
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.data.repository.HolidayRepository
import com.sysadmindoc.alarmclock.data.repository.WeatherRepository
import com.sysadmindoc.alarmclock.directboot.outputauthority.OutputSinkType
import com.sysadmindoc.alarmclock.directboot.outputauthority.SharedOutputAuthorityGate
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import com.sysadmindoc.alarmclock.domain.NextAlarmCalculator
import com.sysadmindoc.alarmclock.domain.ScheduleAuthorityOwner
import com.sysadmindoc.alarmclock.receiver.AlarmReceiver
import com.sysadmindoc.alarmclock.receiver.BootReceiver
import com.sysadmindoc.alarmclock.service.BedtimeZenRuleManager
import com.sysadmindoc.alarmclock.widget.WidgetUpdater
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkAll
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * KNO-75 Phase G4-STEP9-S-V2-DELIVERY-SECURITY-BOUNDARY
 *
 * Verifies that under Flag OFF (`SharedOutputAuthorityGate.isEnabled == false`),
 * `LEGACY_ONLY`, and `DEFAULT_DENY`:
 * - S1: Registration via BootReceiver never invokes reconciler or arms V2 PendingIntent.
 * - S2: Receiver drops V2 intent fail-closed (0 tickets, 0 queue mutation, 0 ledger, 0 FGS).
 * - S3: Service rejects V2 ACTION_START fail-closed (0 sound, 0 vibration, stopSelf, release HANDOVER_PENDING).
 * - S4: OutputResourceArbiter is mandatory for V2 and cannot be bypassed by Flag OFF.
 * - S6: Legacy V1 remains 100% operational under Flag OFF.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [33], application = android.app.Application::class)
class DirectBootDeliverySecurityTest {

    private lateinit var ceContext: Context
    private lateinit var deContext: Context
    private val baseTime = 1_700_000_000_000L

    private class CapturingContext(base: Context) : android.content.ContextWrapper(base) {
        val startedServices = mutableListOf<Intent>()
        override fun startForegroundService(service: Intent?): android.content.ComponentName? {
            if (service != null) startedServices.add(service)
            return android.content.ComponentName(packageName, DirectBootAlarmService::class.java.name)
        }
    }

    private fun seedHandoverAndLedger(
        alarmId: Long,
        triggerTime: Long,
        token: String,
        epoch: Long = 1L,
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

    @Before
    fun setUp() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        ceContext = appContext
        deContext = appContext.createDeviceProtectedStorageContext()
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(ceContext)
        DirectBootServiceTestFixture.clearAllDirectBootPrefs(deContext)
        DirectBootServiceTestFixture.configureAudioAndVibrationEnvironment(ceContext)
        DirectBootAlarmReceiver.resetSeamsForTesting()
        DirectBootAlarmCache.resetD4TestingSeams()
        SharedOutputAuthorityGate.getInstance(ceContext).apply {
            isEnabled = false
            arbiter.clearForTesting()
        }
    }

    @After
    fun tearDown() {
        cancelKnownTestPendingIntents()
        DirectBootServiceTestFixture.clearAllDirectBootPrefs(deContext)
        DirectBootAlarmReceiver.resetSeamsForTesting()
        DirectBootAlarmCache.resetD4TestingSeams()
        SharedOutputAuthorityGate.getInstance(ceContext).apply {
            isEnabled = false
            arbiter.clearForTesting()
        }
    }

    private fun cancelKnownTestPendingIntents() {
        val am = ceContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (id in listOf(1L, 2L, 3L, 9996L, 9999L)) {
            lookupCanonicalV1AlarmReceiverPiForTest(id)?.let { pi ->
                am.cancel(pi)
                pi.cancel()
            }
        }
        PendingIntent.getBroadcast(
            ceContext,
            89996,
            Intent(ceContext, AlarmReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )?.let { pi ->
            am.cancel(pi)
            pi.cancel()
        }
        PendingIntent.getBroadcast(
            ceContext,
            9996,
            Intent("com.sysadmindoc.alarmclock.DIRECT_BOOT_ALARM_FIRE").setPackage(ceContext.packageName),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )?.let { pi ->
            am.cancel(pi)
            pi.cancel()
        }
    }

    // =========================================================================
    // S1. Registration Gate: BootReceiver under DEFAULT_DENY
    // =========================================================================

    @Test
    fun s1_bootReceiver_defaultDeny_neverInvokesReconciler_preservesV1Fallback() {
        var reconcilerCalls = 0
        var fallbackCalls = 0

        val queueItem = DirectBootQueueItem(
            kind = DirectBootQueueItem.KIND_NORMAL,
            alarmId = 101L,
            triggerTime = baseTime + 10_000L,
            generation = 1,
            instanceToken = "tok_s1_101",
            restoreEpoch = 1L
        )
        DirectBootAlarmCache.writeQueue(deContext, listOf(queueItem), now = baseTime)

        val receiver = BootReceiver().apply {
            testScope = TestScope(UnconfinedTestDispatcher())
            reconcilerProvider = {
                reconcilerCalls++
                DirectBootRegistrationReconciler.getInstance(it)
            }
            legacyFallbackScheduler = {
                fallbackCalls++
                DirectBootFallbackScheduleResult.NoSchedulableTarget(
                    reason = NoTargetReason.SNAPSHOT_ABSENT
                )
            }
        }

        assertFalse("BootReceiver default MUST be DEFAULT_DENY", receiver.v2DeliveryCapableProvider())

        receiver.onReceive(ceContext, Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED))

        assertEquals("S1: BootReceiver under DEFAULT_DENY MUST NOT call reconciler", 0, reconcilerCalls)
        assertEquals("S1: BootReceiver under DEFAULT_DENY MUST execute legacy V1 fallback", 1, fallbackCalls)
        assertEquals("S1: DE Queue MUST remain untouched and intact", 1, DirectBootAlarmCache.readQueue(deContext, now = 0L).size)
    }

    // =========================================================================
    // S2. Receiver Gate: DirectBootAlarmReceiver under Flag OFF / DEFAULT_DENY
    // =========================================================================

    @Test
    fun s2_receiver_flagOff_dropsV2Intent_noTicket_noQueueConsume_noLedger_noFgs() {
        val queueItem = DirectBootQueueItem(
            kind = DirectBootQueueItem.KIND_NORMAL,
            alarmId = 201L,
            triggerTime = baseTime + 20_000L,
            generation = 1,
            instanceToken = "tok_s2_201",
            restoreEpoch = 1L
        )
        assertTrue(DirectBootAlarmCache.writeQueue(deContext, listOf(queueItem), now = baseTime))

        val identity = DirectBootV2NormalIntentIdentity.fromQueueItem(queueItem)
        val v2Intent = identity.createIntent(ceContext)

        val capturingContext = CapturingContext(ceContext)
        val receiver = DirectBootAlarmReceiver()

        receiver.onReceive(capturingContext, v2Intent)

        assertEquals("S2: Unapproved V2 Intent MUST NOT start FGS", 0, capturingContext.startedServices.size)
        assertEquals("S2: Unapproved V2 Intent MUST NOT consume DE Queue", 1, DirectBootAlarmCache.readQueue(deContext, now = 0L).size)
        assertTrue("S2: Unapproved V2 Intent MUST NOT commit DeliveryLedger", DirectBootDeliveryLedger.readUnacknowledgedRecords(deContext) is LedgerStorageState.Absent)
        assertTrue("S2: Unapproved V2 Intent MUST NOT mutate DurableOwner", DirectBootDurableOwnerStore.read(deContext) is OwnerReadResult.Absent)
    }

    // =========================================================================
    // S3. Service Gate: Direct ACTION_START on DirectBootAlarmService under Flag OFF
    // =========================================================================

    @Test
    fun s3_service_flagOff_directActionStartWithV2Extras_failsClosed() {
        val trig = baseTime + 30_000L
        val identity = seedHandoverAndLedger(alarmId = 301L, triggerTime = trig, token = "tok_s3_301")

        val startIntent = Intent(ceContext, DirectBootAlarmService::class.java).apply {
            action = DirectBootAlarmService.ACTION_START
            data = identity.toUri()
            putExtra(DirectBootAlarmReceiver.EXTRA_ALARM_ID, identity.alarmId)
            putExtra(DirectBootAlarmReceiver.EXTRA_TRIGGER_TIME, identity.triggerTime)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_KIND, identity.kind)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_GENERATION, identity.generation)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_INSTANCE_TOKEN, identity.instanceToken)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_RESTORE_EPOCH, identity.restoreEpoch)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_IDENTITY_DIGEST, identity.computeDigest())
            putExtra(DirectBootAlarmReceiver.EXTRA_TIME_LABEL, "07:30 AM")
        }

        val controller = Robolectric.buildService(DirectBootAlarmService::class.java)
        val service = controller.create().get()

        service.onStartCommand(startIntent, 0, 1)

        val effects = DirectBootServiceTestFixture.captureEffects(service)
        assertTrue("S3: Direct V2 Service start under Flag OFF MUST stopSelf()", effects.stoppedBySelf)
        assertFalse("S3: Direct V2 Service start under Flag OFF MUST NOT play audio", effects.mediaPlayerPlaying)
        assertFalse("S3: Direct V2 Service start under Flag OFF MUST NOT vibrate", effects.vibrating)
        assertNull("S3: Direct V2 Service start under Flag OFF MUST NOT post notification", shadowOf(service).lastForegroundNotification)

        val ownerAfter = DirectBootDurableOwnerStore.read(deContext) as OwnerReadResult.Success
        assertEquals("S3: Unadmitted HANDOVER_PENDING claim MUST be released to IDLE", OwnerState.IDLE, ownerAfter.owner.state)
        controller.destroy()
    }

    // =========================================================================
    // S4. Arbiter Mandatory: V2 Output cannot skip OutputResourceArbiter
    // =========================================================================

    @Test
    fun s4_service_v2StartRequiresArbiter_failsClosedIfCompetitorHoldsArbiter() {
        val gate = SharedOutputAuthorityGate.getInstance(ceContext).apply {
            isEnabled = true
            bootCount = 1L
        }

        // Competitor owns arbiter
        val competitorKey = OutputOccurrenceKey(alarmId = 999L, scheduledAt = baseTime + 5_000L, refireCount = 0)
        val (acquired, _) = gate.arbiter.acquirePending(
            key = competitorKey,
            sink = OutputSinkType.ALARM_SERVICE_1001,
            activeNonce = "competitor-nonce"
        )
        assertTrue("Competitor must acquire arbiter", acquired)

        val trig = baseTime + 40_000L
        val identity = seedHandoverAndLedger(alarmId = 401L, triggerTime = trig, token = "tok_s4_401")

        val controller = Robolectric.buildService(DirectBootAlarmService::class.java)
        val service = controller.create().get()
        service.outputAuthorityGate = gate
        service.v2DeliveryCapableProvider = { true }

        val startIntent = Intent(ceContext, DirectBootAlarmService::class.java).apply {
            action = DirectBootAlarmService.ACTION_START
            data = identity.toUri()
            putExtra(DirectBootAlarmReceiver.EXTRA_ALARM_ID, identity.alarmId)
            putExtra(DirectBootAlarmReceiver.EXTRA_TRIGGER_TIME, identity.triggerTime)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_KIND, identity.kind)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_GENERATION, identity.generation)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_INSTANCE_TOKEN, identity.instanceToken)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_RESTORE_EPOCH, identity.restoreEpoch)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_IDENTITY_DIGEST, identity.computeDigest())
            putExtra(DirectBootAlarmReceiver.EXTRA_TIME_LABEL, "07:45 AM")
        }

        service.onStartCommand(startIntent, 0, 1)

        val effects = DirectBootServiceTestFixture.captureEffects(service)
        assertTrue("S4: V2 start MUST stopSelf when Arbiter acquisition fails", effects.stoppedBySelf)
        assertFalse(effects.mediaPlayerPlaying)
        assertFalse(effects.vibrating)

        val ownerAfter = DirectBootDurableOwnerStore.read(deContext) as OwnerReadResult.Success
        assertEquals("S4: Failed arbiter acquisition MUST release HANDOVER_PENDING -> IDLE", OwnerState.IDLE, ownerAfter.owner.state)
        controller.destroy()
    }

    // =========================================================================
    // S6. Legacy V1 Compatibility under Flag OFF / DEFAULT_DENY
    // =========================================================================

    @Test
    fun s6_legacyV1_underFlagOff_operatesNormally() {
        val v1Intent = Intent(ceContext, DirectBootAlarmReceiver::class.java).apply {
            action = DirectBootAlarmReceiver.ACTION_FIRE_DIRECT_BOOT_ALARM
            putExtra(DirectBootAlarmReceiver.EXTRA_ALARM_ID, 501L)
            putExtra(DirectBootAlarmReceiver.EXTRA_TRIGGER_TIME, baseTime + 50_000L)
            putExtra(DirectBootAlarmReceiver.EXTRA_LABEL, "Legacy Morning")
            putExtra(DirectBootAlarmReceiver.EXTRA_TIME_LABEL, "08:00 AM")
            putExtra(DirectBootAlarmReceiver.EXTRA_PLAY_DEFAULT_SOUND, true)
            putExtra(DirectBootAlarmReceiver.EXTRA_VIBRATION_ENABLED, true)
        }

        val capturingContext = CapturingContext(ceContext)
        DirectBootAlarmReceiver().onReceive(capturingContext, v1Intent)

        assertEquals("S6: Legacy V1 intent MUST start FGS under Flag OFF", 1, capturingContext.startedServices.size)
        val startedIntent = capturingContext.startedServices.single()
        assertEquals(DirectBootAlarmService.ACTION_START, startedIntent.action)
        assertEquals(501L, startedIntent.getLongExtra(DirectBootAlarmReceiver.EXTRA_ALARM_ID, -1L))

        val controller = Robolectric.buildService(DirectBootAlarmService::class.java)
        val service = controller.create().get()

        service.onStartCommand(startedIntent, 0, 1)

        val effects = DirectBootServiceTestFixture.captureEffects(service)
        assertFalse("S6: Legacy V1 service MUST NOT stopSelf on start", effects.stoppedBySelf)
        assertTrue("S6: Legacy V1 service MUST play sound under Flag OFF", effects.mediaPlayerPlaying)
        assertTrue("S6: Legacy V1 service MUST vibrate under Flag OFF", effects.vibrating)

        // Stop legacy alarm
        val stopIntent = Intent(ceContext, DirectBootAlarmService::class.java).apply {
            action = DirectBootAlarmService.ACTION_STOP
            putExtra(DirectBootAlarmReceiver.EXTRA_ALARM_ID, 501L)
            putExtra(DirectBootAlarmReceiver.EXTRA_TRIGGER_TIME, baseTime + 50_000L)
        }
        service.onStartCommand(stopIntent, 0, 2)

        val effectsAfterStop = DirectBootServiceTestFixture.captureEffects(service)
        assertTrue("S6: Legacy V1 STOP MUST call stopSelf", effectsAfterStop.stoppedBySelf)
        assertFalse(effectsAfterStop.mediaPlayerPlaying)
        controller.destroy()
    }

    // =========================================================================
    // D4 Isolated Capability Contract Tests (KNO-75 G4-STEP9-D4)
    // =========================================================================

    private fun writeD4GrantJson(
        sessionToken: String,
        issuedAtEpochMs: Long,
        expiryEpochMs: Long,
        targetBootCount: Int,
        occurrences: List<Pair<DirectBootQueueItem, Boolean>>,
        revoked: Boolean = false,
        schemaVersion: Int = 1
    ) {
        val array = JSONArray()
        occurrences.forEach { (item, admitted) ->
            array.put(
                JSONObject().apply {
                    put("alarmId", item.alarmId)
                    put("generation", item.generation)
                    put("instanceToken", item.instanceToken)
                    put("triggerTime", item.triggerTime)
                    put("restoreEpoch", item.restoreEpoch)
                    put("admitted", admitted)
                }
            )
        }
        val root = JSONObject().apply {
            put("schemaVersion", schemaVersion)
            put("testSuiteTag", "D4_REAL_OS_FBE")
            put("sessionToken", sessionToken)
            put("issuedAtEpochMs", issuedAtEpochMs)
            put("expiryEpochMs", expiryEpochMs)
            put("targetBootCount", targetBootCount)
            put("allowedOccurrences", array)
            put("revoked", revoked)
        }
        val prefs = deContext.getSharedPreferences("direct_boot_next_alarm", Context.MODE_PRIVATE)
        assertTrue(prefs.edit().putString("d4_isolated_capability_grant", root.toString()).commit())
    }

    private fun readD4GrantJson(): JSONObject? {
        val raw = deContext.getSharedPreferences("direct_boot_next_alarm", Context.MODE_PRIVATE)
            .getString("d4_isolated_capability_grant", null) ?: return null
        return JSONObject(raw)
    }

    @Test
    fun d4_bootReceiver_armsQueueHeadOnlyWhenGrantAndBootCountAndQueueHeadMatch() {
        val now = System.currentTimeMillis()
        val sessionToken = "d4-sess-boot1"
        val epoch = now - 5_000L
        val item1 = DirectBootQueueItem(
            kind = DirectBootQueueItem.KIND_NORMAL,
            alarmId = 9996L,
            triggerTime = now + 180_000L,
            generation = 1,
            instanceToken = "d4-v2-token-9996-$sessionToken",
            restoreEpoch = epoch
        )
        val item2 = DirectBootQueueItem(
            kind = DirectBootQueueItem.KIND_NORMAL,
            alarmId = 9999L,
            triggerTime = now + 300_000L,
            generation = 1,
            instanceToken = "d4-v2-token-9999-$sessionToken",
            restoreEpoch = epoch
        )
        assertTrue(DirectBootAlarmCache.writeQueue(deContext, listOf(item1, item2), now = now))
        Settings.Global.putInt(ceContext.contentResolver, Settings.Global.BOOT_COUNT, 42)
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        writeD4GrantJson(
            sessionToken = sessionToken,
            issuedAtEpochMs = now - 5_000L,
            expiryEpochMs = now + 25 * 60_000L,
            targetBootCount = 42,
            occurrences = listOf(item1 to false, item2 to false)
        )

        var reconcilerCalls = 0
        var fallbackCalls = 0
        val receiver = BootReceiver().apply {
            testScope = TestScope(UnconfinedTestDispatcher())
            reconcilerProvider = { ctx ->
                reconcilerCalls++
                DirectBootRegistrationReconciler.getInstance(ctx)
            }
            legacyFallbackScheduler = {
                fallbackCalls++
                DirectBootFallbackScheduleResult.NoSchedulableTarget(NoTargetReason.SNAPSHOT_ABSENT)
            }
        }

        receiver.onReceive(ceContext, Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED))

        assertEquals("D4 BootReceiver MUST run V2 reconciler when D4 grant + bootCount + queue head match", 1, reconcilerCalls)
        assertEquals("D4 BootReceiver MUST skip legacy V1 fallback once D4 V2 arms", 0, fallbackCalls)
    }

    @Test
    fun d4_bootReceiver_nonD4QueueWithValidD4Grant_staysDefaultDeny() {
        val now = System.currentTimeMillis()
        val sessionToken = "d4-sess-fence"
        val epoch = now - 5_000L
        val d4Item1 = DirectBootQueueItem(
            kind = DirectBootQueueItem.KIND_NORMAL,
            alarmId = 9996L,
            triggerTime = now + 180_000L,
            generation = 1,
            instanceToken = "d4-v2-token-9996-$sessionToken",
            restoreEpoch = epoch
        )
        val nonD4Head = DirectBootQueueItem(
            kind = DirectBootQueueItem.KIND_NORMAL,
            alarmId = 777L,
            triggerTime = now + 60_000L,
            generation = 1,
            instanceToken = "prod-token-777",
            restoreEpoch = epoch
        )
        assertTrue(DirectBootAlarmCache.writeQueue(deContext, listOf(nonD4Head, d4Item1), now = now))
        Settings.Global.putInt(ceContext.contentResolver, Settings.Global.BOOT_COUNT, 42)
        writeD4GrantJson(
            sessionToken = sessionToken,
            issuedAtEpochMs = now - 5_000L,
            expiryEpochMs = now + 25 * 60_000L,
            targetBootCount = 42,
            occurrences = listOf(d4Item1 to false)
        )

        var reconcilerCalls = 0
        var fallbackCalls = 0
        val receiver = BootReceiver().apply {
            testScope = TestScope(UnconfinedTestDispatcher())
            reconcilerProvider = { ctx ->
                reconcilerCalls++
                DirectBootRegistrationReconciler.getInstance(ctx)
            }
            legacyFallbackScheduler = {
                fallbackCalls++
                DirectBootFallbackScheduleResult.NoSchedulableTarget(NoTargetReason.SNAPSHOT_ABSENT)
            }
        }

        receiver.onReceive(ceContext, Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED))

        assertEquals("Non-D4 queue head MUST NOT invoke V2 reconciler even with valid D4 grant", 0, reconcilerCalls)
        assertEquals("Non-D4 queue head MUST retain default fallback path", 1, fallbackCalls)
    }

    @Test
    fun d4_receiverToService_atomicAdmission_serviceProof_and_arbiterReleaseUnderFlagOff() {
        val now = System.currentTimeMillis()
        val sessionToken = "d4-sess-e2e"
        val epoch = now - 5_000L
        val item1 = DirectBootQueueItem(
            kind = DirectBootQueueItem.KIND_NORMAL,
            alarmId = 9996L,
            triggerTime = now - 2_000L,
            generation = 1,
            instanceToken = "d4-v2-token-9996-$sessionToken",
            restoreEpoch = epoch
        )
        val item2 = DirectBootQueueItem(
            kind = DirectBootQueueItem.KIND_NORMAL,
            alarmId = 9999L,
            triggerTime = now - 1_000L,
            generation = 1,
            instanceToken = "d4-v2-token-9999-$sessionToken",
            restoreEpoch = epoch
        )
        assertTrue(DirectBootAlarmCache.writeQueue(deContext, listOf(item1, item2), now = now - 10_000L))
        Settings.Global.putInt(ceContext.contentResolver, Settings.Global.BOOT_COUNT, 42)
        writeD4GrantJson(
            sessionToken = sessionToken,
            issuedAtEpochMs = now - 5_000L,
            expiryEpochMs = now + 25 * 60_000L,
            targetBootCount = 42,
            occurrences = listOf(item1 to false, item2 to false)
        )

        val gate = SharedOutputAuthorityGate.getInstance(ceContext).apply {
            isEnabled = false
            arbiter.clearForTesting()
        }

        val identity1 = DirectBootV2NormalIntentIdentity.fromQueueItem(item1)
        val identity2 = DirectBootV2NormalIntentIdentity.fromQueueItem(item2)
        val receiver = DirectBootAlarmReceiver()
        val capturing1 = CapturingContext(ceContext)

        // 1. Alarm 1 intake at Receiver under global gate.isEnabled == false
        receiver.onReceive(capturing1, identity1.createIntent(ceContext))
        assertEquals("D4 Alarm 1 MUST start DirectBootAlarmService", 1, capturing1.startedServices.size)

        // Verify atomic 4-key update committed admitted=true for 9996L, false for 9999L
        val grantAfter1 = readD4GrantJson()!!
        val occsAfter1 = grantAfter1.getJSONArray("allowedOccurrences")
        assertTrue("9996L must be admitted=true atomically", occsAfter1.getJSONObject(0).getBoolean("admitted"))
        assertFalse("9999L must remain admitted=false", occsAfter1.getJSONObject(1).getBoolean("admitted"))
        val remainingHead = DirectBootAlarmCache.readQueueHead(deContext, now = 0L)
        assertEquals("9999L must now be DE Queue head", 9999L, remainingHead?.alarmId)

        // 2. Duplicate delivery of Alarm 1 rejected fail-closed pre-ticket
        val dupCap = CapturingContext(ceContext)
        receiver.onReceive(dupCap, identity1.createIntent(ceContext))
        assertEquals("Duplicate Alarm 1 intent MUST be rejected", 0, dupCap.startedServices.size)

        // 3. Alarm 1 Service execution under global gate.isEnabled == false (no ticketId in Ledger)
        val controller1 = Robolectric.buildService(DirectBootAlarmService::class.java)
        val service1 = controller1.create().get()
        service1.outputAuthorityGate = gate
        service1.onStartCommand(capturing1.startedServices.single(), 0, 1)

        val effects1 = DirectBootServiceTestFixture.captureEffects(service1)
        assertFalse("D4 Alarm 1 service MUST NOT stopSelf", effects1.stoppedBySelf)
        assertTrue("D4 Alarm 1 service MUST play sound", effects1.mediaPlayerPlaying)
        assertTrue("D4 Alarm 1 service MUST vibrate", effects1.vibrating)
        assertNotNull("D4 Alarm 1 MUST acquire OutputResourceArbiter", gate.arbiter.activeHandle)

        // 4. Stop Alarm 1 -> MUST release OutputResourceArbiter even when gate.isEnabled == false!
        val stopIntent1 = Intent(ceContext, DirectBootAlarmService::class.java).apply {
            action = DirectBootAlarmService.ACTION_STOP
            putExtra(DirectBootAlarmReceiver.EXTRA_ALARM_ID, identity1.alarmId)
            putExtra(DirectBootAlarmReceiver.EXTRA_TRIGGER_TIME, identity1.triggerTime)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_GENERATION, identity1.generation)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_INSTANCE_TOKEN, identity1.instanceToken)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_RESTORE_EPOCH, identity1.restoreEpoch)
        }
        service1.onStartCommand(stopIntent1, 0, 2)
        assertNull("Arbiter MUST be released on V2 stop even with gate.isEnabled == false", gate.arbiter.activeHandle)
        val ownerAfterStop1 = DirectBootDurableOwnerStore.read(deContext) as OwnerReadResult.Success
        assertEquals(OwnerState.IDLE, ownerAfterStop1.owner.state)
        controller1.destroy()

        // 5. Alarm 2 intake & Service execution after Alarm 1 release
        val capturing2 = CapturingContext(ceContext)
        receiver.onReceive(capturing2, identity2.createIntent(ceContext))
        assertEquals("D4 Alarm 2 MUST start DirectBootAlarmService", 1, capturing2.startedServices.size)

        val controller2 = Robolectric.buildService(DirectBootAlarmService::class.java)
        val service2 = controller2.create().get()
        service2.outputAuthorityGate = gate
        service2.onStartCommand(capturing2.startedServices.single(), 0, 3)

        val effects2 = DirectBootServiceTestFixture.captureEffects(service2)
        assertFalse("D4 Alarm 2 service MUST NOT stopSelf", effects2.stoppedBySelf)
        assertTrue("D4 Alarm 2 service MUST play sound", effects2.mediaPlayerPlaying)
        assertEquals(9999L, gate.arbiter.activeHandle?.key?.alarmId)
        controller2.destroy()
    }

    @Test
    fun d4_atomicCommit_transitionNormalUpdatesGrantAtomicallyAndCommitFailurePreservesAllFourKeys() {
        val now = System.currentTimeMillis()
        val sessionToken = "d4-sess-atom"
        val epoch = now - 5_000L
        val item1 = DirectBootQueueItem(
            kind = DirectBootQueueItem.KIND_NORMAL,
            alarmId = 9996L,
            triggerTime = now - 2_000L,
            generation = 1,
            instanceToken = "d4-v2-token-9996-$sessionToken",
            restoreEpoch = epoch
        )
        assertTrue(DirectBootAlarmCache.writeQueue(deContext, listOf(item1), now = now - 10_000L))
        Settings.Global.putInt(ceContext.contentResolver, Settings.Global.BOOT_COUNT, 42)
        writeD4GrantJson(
            sessionToken = sessionToken,
            issuedAtEpochMs = now - 5_000L,
            expiryEpochMs = now + 25 * 60_000L,
            targetBootCount = 42,
            occurrences = listOf(item1 to false)
        )

        val firingIdentity = FiredOccurrenceIdentity(
            kind = DirectBootDeliveryRecord.KIND_NORMAL,
            alarmId = item1.alarmId,
            triggerTime = item1.triggerTime,
            generation = item1.generation,
            instanceToken = item1.instanceToken,
            restoreEpoch = item1.restoreEpoch,
            provenance = "D4_TEST"
        )

        // Commit failure MUST preserve all 4 keys (Queue, Ledger, Owner, Grant)
        val failedResult = DirectBootDeliveryTransitionEngine.transitionNormalForTesting(
            context = deContext,
            firingIdentity = firingIdentity,
            deliveredAt = now,
            capabilityAtomicEditorCommitter = { _, _, _, _, _ -> false }
        )
        assertTrue("Failed commit must return Uncertain.CommitFailed", failedResult is DeliveryTransitionResult.Uncertain.CommitFailed)
        assertEquals(1, DirectBootAlarmCache.readQueue(deContext, now = 0L).size)
        assertTrue(DirectBootDeliveryLedger.readUnacknowledgedRecords(deContext) is LedgerStorageState.Absent)
        assertTrue(DirectBootDurableOwnerStore.read(deContext) is OwnerReadResult.Absent)
        assertFalse(readD4GrantJson()!!.getJSONArray("allowedOccurrences").getJSONObject(0).getBoolean("admitted"))

        // Production commit MUST atomically update all 4 keys together
        val okResult = DirectBootDeliveryTransitionEngine.transitionNormal(
            context = deContext,
            firingIdentity = firingIdentity,
            deliveredAt = now
        )
        assertTrue("Production transitionNormal must confirm", okResult is DeliveryTransitionResult.Confirmed)
        assertEquals(0, DirectBootAlarmCache.readQueue(deContext, now = 0L).size)
        assertTrue(DirectBootDeliveryLedger.readUnacknowledgedRecords(deContext) is LedgerStorageState.ValidWithRecords)
        val owner = (DirectBootDurableOwnerStore.read(deContext) as OwnerReadResult.Success).owner
        assertEquals(OwnerState.HANDOVER_PENDING, owner.state)
        assertTrue("Grant admitted must flip atomically in same transitionNormal commit", readD4GrantJson()!!.getJSONArray("allowedOccurrences").getJSONObject(0).getBoolean("admitted"))
    }

    @Test
    fun d4_security_rejectsBootCountMismatch_expiredTtl_clockRollback_corruptGrant_and_activeLegacyClaim() {
        val now = System.currentTimeMillis()
        val sessionToken = "d4-sess-neg"
        val epoch = now - 5_000L
        val item1 = DirectBootQueueItem(
            kind = DirectBootQueueItem.KIND_NORMAL,
            alarmId = 9996L,
            triggerTime = now - 2_000L,
            generation = 1,
            instanceToken = "d4-v2-token-9996-$sessionToken",
            restoreEpoch = epoch
        )
        val identity1 = DirectBootV2NormalIntentIdentity.fromQueueItem(item1)
        val receiver = DirectBootAlarmReceiver()

        // 1. Pre-reboot bootCount (41 != 42)
        DirectBootAlarmCache.writeQueue(deContext, listOf(item1), now = now - 10_000L)
        Settings.Global.putInt(ceContext.contentResolver, Settings.Global.BOOT_COUNT, 41)
        writeD4GrantJson(sessionToken, now - 5_000L, now + 25 * 60_000L, 42, listOf(item1 to false))
        val capPre = CapturingContext(ceContext)
        receiver.onReceive(capPre, identity1.createIntent(ceContext))
        assertEquals("Pre-reboot bootCount MUST reject", 0, capPre.startedServices.size)

        // 2. Re-reboot bootCount (43 != 42)
        Settings.Global.putInt(ceContext.contentResolver, Settings.Global.BOOT_COUNT, 43)
        val capRe = CapturingContext(ceContext)
        receiver.onReceive(capRe, identity1.createIntent(ceContext))
        assertEquals("Re-reboot bootCount MUST reject", 0, capRe.startedServices.size)

        // 3. Expired TTL
        Settings.Global.putInt(ceContext.contentResolver, Settings.Global.BOOT_COUNT, 42)
        writeD4GrantJson(sessionToken, now - 20 * 60_000L, now - 1_000L, 42, listOf(item1 to false))
        val capExp = CapturingContext(ceContext)
        receiver.onReceive(capExp, identity1.createIntent(ceContext))
        assertEquals("Expired grant MUST reject", 0, capExp.startedServices.size)

        // 3b. Excessive TTL (> 30 min) MUST reject fail-closed
        writeD4GrantJson(sessionToken, now - 5_000L, now + 35 * 60_000L, 42, listOf(item1 to false))
        val capTooLong = CapturingContext(ceContext)
        receiver.onReceive(capTooLong, identity1.createIntent(ceContext))
        assertEquals("Grant exceeding 30m max TTL MUST reject", 0, capTooLong.startedServices.size)

        // 4. Clock rollback (now < issuedAtEpochMs)
        writeD4GrantJson(sessionToken, now + 60_000L, now + 25 * 60_000L, 42, listOf(item1 to false))
        val capRollback = CapturingContext(ceContext)
        receiver.onReceive(capRollback, identity1.createIntent(ceContext))
        assertEquals("Clock rollback MUST reject", 0, capRollback.startedServices.size)

        // 5. Corrupt JSON
        deContext.getSharedPreferences("direct_boot_next_alarm", Context.MODE_PRIVATE)
            .edit().putString("d4_isolated_capability_grant", "{corrupt_json").commit()
        val capCorrupt = CapturingContext(ceContext)
        receiver.onReceive(capCorrupt, identity1.createIntent(ceContext))
        assertEquals("Corrupt grant MUST reject fail-closed", 0, capCorrupt.startedServices.size)

        // 6. Active LegacyClaim conflict blocks D4
        writeD4GrantJson(sessionToken, now - 5_000L, now + 25 * 60_000L, 42, listOf(item1 to false))
        val legacyClaim = LegacyClaimRecord(
            alarmId = item1.alarmId,
            scheduledAt = item1.triggerTime,
            refireCount = 0,
            source = LegacyClaimSource.DIRECT_BOOT_RECEIVER,
            claimNonce = "legacy-nonce-1",
            processSessionToken = "proc-sess-1",
            bootCount = 42L,
            state = LegacyClaimState.CLAIMED_RECEIVER
        )
        assertTrue(DirectBootLegacyClaimStore.writeInitialClaim(deContext, legacyClaim) is LegacyClaimWriteResult.Committed)
        val capLegacyConflict = CapturingContext(ceContext)
        receiver.onReceive(capLegacyConflict, identity1.createIntent(ceContext))
        assertEquals("Active LegacyClaim for same occurrence MUST block D4", 0, capLegacyConflict.startedServices.size)
    }

    @Test
    fun d4_crashSafety_unknownWitnessSafeDeferAndRetain_and_serviceStartNotSticky() {
        val now = System.currentTimeMillis()
        val token = "d4-v2-token-9996-d4-sess-c4"
        val epoch = now - 5_000L
        val delRecord = DirectBootDeliveryRecord.createNormal(
            alarmId = 9996L,
            triggerTime = now - 2_000L,
            deliveredAt = now - 1_900L,
            generation = 1,
            instanceToken = token,
            restoreEpoch = epoch
        )
        assertTrue(DirectBootDeliveryLedger.appendRecord(deContext, delRecord) is DeliveryAppendResult.Appended)
        val unknownWitness = DirectBootFiredWitnessRecord.createNormal(
            alarmId = 9996L,
            triggerTime = now - 2_000L,
            generation = 1,
            instanceToken = token,
            restoreEpoch = epoch,
            deliveryMode = DirectBootFiredWitnessRecord.MODE_SOUND_AND_VIBRATION,
            deliveryOutcome = DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_STATUS_UNKNOWN,
            observedAtEpochMs = now - 1_850L
        )
        assertTrue(DirectBootFiredWitnessLedger.reserveWitnessSlot(deContext, unknownWitness) is WitnessAppendResult.Appended)

        DirectBootCeTakeoverStore.unlockGateSeam = { true }
        val handoff = DirectBootCeHandoffCoordinator.handoffDelivered(deContext, ceContext, delRecord)
        assertTrue(handoff is CeHandoffResult.Confirmed)

        val db = Room.inMemoryDatabaseBuilder(ceContext, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val res = runBlocking {
                DirectBootEventConsumerEngine.consumePendingTakeoverRecords(deContext, ceContext, db)
            }
            val successRes = res as DirectBootEventConsumerEngine.ConsumerExecutionResult.Success
            assertEquals(
                "UNKNOWN witness MUST commit zero Room 38 receipts (SAFE_DEFER_AND_RETAIN)",
                0,
                successRes.receiptsCommitted
            )
            assertEquals("Zero Room 38 receipts may be inserted for UNKNOWN witness", 0L, db.directBootOccurrenceReceiptDao().count())
            assertTrue("CE takeover slot MUST remain retained", DirectBootCeTakeoverStore.inspectStorageState(ceContext) is CeTakeoverStorageState.ValidWithRecords)
            assertTrue("DE DeliveryLedger MUST remain retained", DirectBootDeliveryLedger.readUnacknowledgedRecords(deContext) is LedgerStorageState.ValidWithRecords)
            assertTrue("DE WitnessLedger MUST remain retained", DirectBootFiredWitnessLedger.readWitnesses(deContext) is WitnessLedgerState.ValidWithRecords)
        } finally {
            db.close()
            DirectBootCeTakeoverStore.clear(ceContext)
            DirectBootCeTakeoverStore.resetSeamsForTesting()
        }

        // Verify DirectBootAlarmService restart policy is START_NOT_STICKY and stale cross-boot V2_ACTIVE is rejected
        val boot1 = BootProvenance(bootCount = 41, lastElapsedRealtime = 1_000L, bootEpochEstimateMs = now - 60_000L)
        val boot2 = BootProvenance(bootCount = 42, lastElapsedRealtime = 2_000L, bootEpochEstimateMs = now - 5_000L)
        val staleActiveOwner = DirectBootDurableOwner(
            schemaVersion = DirectBootDurableOwnerStore.CURRENT_SCHEMA_VERSION,
            state = OwnerState.V2_ACTIVE,
            ownerSeq = 1L,
            bootProvenance = boot1,
            handoverClaim = null,
            activeSession = ActiveOwnerSession(
                alarmId = 9996L,
                triggerTime = now - 2_000L,
                generation = 1,
                instanceToken = token,
                restoreEpoch = epoch
            ),
            updatedAtEpochMs = now - 30_000L
        )
        assertTrue(DirectBootDurableOwnerStore.write(deContext, staleActiveOwner, expectedOwnerSeq = 0L) is OwnerWriteResult.Success)
        val identity = DirectBootV2NormalIntentIdentity(
            alarmId = 9996L,
            generation = 1,
            instanceToken = token,
            triggerTime = now - 2_000L,
            restoreEpoch = epoch
        )
        val controller = Robolectric.buildService(DirectBootAlarmService::class.java)
        val service = controller.create().get()
        service.bootProvenanceProvider = { boot2 }
        val startResult = service.onStartCommand(
            identity.createIntent(ceContext).apply { action = DirectBootAlarmService.ACTION_START },
            0,
            1
        )
        assertEquals("DirectBootAlarmService MUST return START_NOT_STICKY", android.app.Service.START_NOT_STICKY, startResult)
        assertTrue("Cross-boot stale V2_ACTIVE MUST stopSelf fail-closed", DirectBootServiceTestFixture.captureEffects(service).stoppedBySelf)
        controller.destroy()
    }

    @Test
    fun d4_nonDebugBuildGate_and_revokePreservesNonD4Storage() {
        val now = System.currentTimeMillis()
        val sessionToken = "d4-sess-rel"
        val epoch = now - 5_000L
        val item1 = DirectBootQueueItem(
            kind = DirectBootQueueItem.KIND_NORMAL,
            alarmId = 9996L,
            triggerTime = now + 180_000L,
            generation = 1,
            instanceToken = "d4-v2-token-9996-$sessionToken",
            restoreEpoch = epoch
        )
        assertTrue(DirectBootAlarmCache.writeQueue(deContext, listOf(item1), now = now))
        Settings.Global.putInt(ceContext.contentResolver, Settings.Global.BOOT_COUNT, 42)
        writeD4GrantJson(sessionToken, now - 5_000L, now + 25 * 60_000L, 42, listOf(item1 to false))
        assertTrue("Grant should be valid in Debug before toggle", DirectBootAlarmCache.hasValidD4CapabilityForBoot(deContext, now))

        // Non-Debug (Release simulation) MUST disable every D4 capability gate
        DirectBootAlarmCache.d4DebugBuildProvider = { false }
        assertFalse("Non-Debug MUST deny hasValidD4CapabilityForBoot", DirectBootAlarmCache.hasValidD4CapabilityForBoot(deContext, now))
        assertFalse("Non-Debug MUST deny isD4OccurrenceAdmissible", DirectBootAlarmCache.isD4OccurrenceAdmissible(deContext, DirectBootV2NormalIntentIdentity.fromQueueItem(item1), now))
        assertNull("Non-Debug MUST deny readD4IsolatedCapability", DirectBootAlarmCache.readD4IsolatedCapability(deContext))

        // Restore Debug and verify revokeD4IsolatedCapability preserves DE Queue while revoking grant
        DirectBootAlarmCache.d4DebugBuildProvider = { true }
        assertTrue(DirectBootAlarmCache.revokeD4IsolatedCapability(deContext))
        assertFalse("Revoked grant MUST fail-closed", DirectBootAlarmCache.hasValidD4CapabilityForBoot(deContext, now))
        assertEquals("Revoke MUST NOT wipe DE Queue", 1, DirectBootAlarmCache.readQueue(deContext, now = 0L).size)
    }

    @Test
    fun d4_atomicCommit_rejectsExpiredRevokedBootMismatchAndAbsentGrantWithout3KeyFallback() {
        val now = System.currentTimeMillis()
        val sessionToken = "d4-sess-no3key"
        val epoch = now - 5_000L
        val item1 = DirectBootQueueItem(
            kind = DirectBootQueueItem.KIND_NORMAL,
            alarmId = 9996L,
            triggerTime = now - 2_000L,
            generation = 1,
            instanceToken = "d4-v2-token-9996-$sessionToken",
            restoreEpoch = epoch
        )
        val firingIdentity = FiredOccurrenceIdentity(
            kind = DirectBootDeliveryRecord.KIND_NORMAL,
            alarmId = item1.alarmId,
            triggerTime = item1.triggerTime,
            generation = item1.generation,
            instanceToken = item1.instanceToken,
            restoreEpoch = item1.restoreEpoch,
            provenance = "V2_INTENT_DELIVERY"
        )

        fun assertRejectedWithoutMutation(label: String, res: DeliveryTransitionResult) {
            assertTrue("$label must reject with PreCommitFailure (no 3-key fallback), got $res", res is DeliveryTransitionResult.Rejected.PreCommitFailure)
            assertTrue("$label must guarantee zero write", res.isGuaranteedNoWrite)
            assertEquals("$label must preserve Queue", 1, DirectBootAlarmCache.readQueue(deContext, now = 0L).size)
            assertTrue("$label must preserve Ledger Absent", DirectBootDeliveryLedger.readUnacknowledgedRecords(deContext) is LedgerStorageState.Absent)
            assertTrue("$label must preserve Owner Absent", DirectBootDurableOwnerStore.read(deContext) is OwnerReadResult.Absent)
        }

        // 1. Expired TTL
        assertTrue(DirectBootAlarmCache.writeQueue(deContext, listOf(item1), now = now - 10_000L))
        Settings.Global.putInt(ceContext.contentResolver, Settings.Global.BOOT_COUNT, 42)
        writeD4GrantJson(sessionToken, now - 20 * 60_000L, now - 1_000L, 42, listOf(item1 to false))
        assertRejectedWithoutMutation(
            "Expired Grant",
            DirectBootDeliveryTransitionEngine.transitionNormal(deContext, firingIdentity, deliveredAt = now)
        )

        // 2. Revoked Grant
        writeD4GrantJson(sessionToken, now - 5_000L, now + 25 * 60_000L, 42, listOf(item1 to false), revoked = true)
        assertRejectedWithoutMutation(
            "Revoked Grant",
            DirectBootDeliveryTransitionEngine.transitionNormal(deContext, firingIdentity, deliveredAt = now)
        )

        // 3. BootCount mismatch
        writeD4GrantJson(sessionToken, now - 5_000L, now + 25 * 60_000L, 42, listOf(item1 to false), revoked = false)
        Settings.Global.putInt(ceContext.contentResolver, Settings.Global.BOOT_COUNT, 43)
        assertRejectedWithoutMutation(
            "BootCount mismatch",
            DirectBootDeliveryTransitionEngine.transitionNormal(deContext, firingIdentity, deliveredAt = now)
        )

        // 4. Corrupt Grant JSON
        Settings.Global.putInt(ceContext.contentResolver, Settings.Global.BOOT_COUNT, 42)
        deContext.getSharedPreferences("direct_boot_next_alarm", Context.MODE_PRIVATE)
            .edit().putString("d4_isolated_capability_grant", "{corrupt").commit()
        assertRejectedWithoutMutation(
            "Corrupt Grant",
            DirectBootDeliveryTransitionEngine.transitionNormal(deContext, firingIdentity, deliveredAt = now)
        )

        // 5. Absent Grant
        deContext.getSharedPreferences("direct_boot_next_alarm", Context.MODE_PRIVATE)
            .edit().remove("d4_isolated_capability_grant").commit()
        assertRejectedWithoutMutation(
            "Absent Grant",
            DirectBootDeliveryTransitionEngine.transitionNormal(deContext, firingIdentity, deliveredAt = now)
        )
    }

    @Test
    fun d4_atomicCommit_rejectsConcurrentGrantOverwriteOrRevocationInPreCommitHook() {
        val now = System.currentTimeMillis()
        val sessionToken = "d4-sess-race"
        val epoch = now - 5_000L
        val item1 = DirectBootQueueItem(
            kind = DirectBootQueueItem.KIND_NORMAL,
            alarmId = 9996L,
            triggerTime = now - 2_000L,
            generation = 1,
            instanceToken = "d4-v2-token-9996-$sessionToken",
            restoreEpoch = epoch
        )
        val firingIdentity = FiredOccurrenceIdentity(
            kind = DirectBootDeliveryRecord.KIND_NORMAL,
            alarmId = item1.alarmId,
            triggerTime = item1.triggerTime,
            generation = item1.generation,
            instanceToken = item1.instanceToken,
            restoreEpoch = item1.restoreEpoch,
            provenance = "V2_INTENT_DELIVERY"
        )
        assertTrue(DirectBootAlarmCache.writeQueue(deContext, listOf(item1), now = now - 10_000L))
        Settings.Global.putInt(ceContext.contentResolver, Settings.Global.BOOT_COUNT, 42)

        // Case A: Grant revoked right before Editor.commit()
        writeD4GrantJson(sessionToken, now - 5_000L, now + 25 * 60_000L, 42, listOf(item1 to false))
        val revokedRaceRes = DirectBootDeliveryTransitionEngine.transitionNormalForTesting(
            context = deContext,
            firingIdentity = firingIdentity,
            deliveredAt = now,
            preCommitHook = {
                DirectBootAlarmCache.revokeD4IsolatedCapability(deContext)
            }
        )
        assertTrue("Pre-commit Grant revocation MUST reject under lock", revokedRaceRes is DeliveryTransitionResult.Rejected.PreCommitFailure)
        assertEquals(1, DirectBootAlarmCache.readQueue(deContext, now = 0L).size)
        assertTrue(DirectBootDeliveryLedger.readUnacknowledgedRecords(deContext) is LedgerStorageState.Absent)
        assertTrue(DirectBootDurableOwnerStore.read(deContext) is OwnerReadResult.Absent)

        // Case B: Grant overwritten with another sessionToken right before Editor.commit()
        writeD4GrantJson(sessionToken, now - 5_000L, now + 25 * 60_000L, 42, listOf(item1 to false))
        val otherSessionItem = item1.copy(instanceToken = "d4-v2-token-9996-d4-sess-other")
        val overwriteRaceRes = DirectBootDeliveryTransitionEngine.transitionNormalForTesting(
            context = deContext,
            firingIdentity = firingIdentity,
            deliveredAt = now,
            preCommitHook = {
                writeD4GrantJson("d4-sess-other", now - 4_000L, now + 25 * 60_000L, 42, listOf(otherSessionItem to false))
            }
        )
        assertTrue("Pre-commit Grant overwrite MUST reject under lock", overwriteRaceRes is DeliveryTransitionResult.Rejected.PreCommitFailure)
        assertEquals(1, DirectBootAlarmCache.readQueue(deContext, now = 0L).size)
        assertTrue(DirectBootDeliveryLedger.readUnacknowledgedRecords(deContext) is LedgerStorageState.Absent)
        assertTrue(DirectBootDurableOwnerStore.read(deContext) is OwnerReadResult.Absent)
        assertEquals("d4-sess-other", DirectBootAlarmCache.readD4IsolatedCapability(deContext)?.sessionToken)
    }

    @Test
    fun d4_atomicCommit_rejects3KeyFallback_resendAlreadyAdmitted_and_invalidQueueHead() {
        val now = System.currentTimeMillis()
        val sessionToken = "d4-sess-replay"
        val epoch = now - 5_000L
        val item1 = DirectBootQueueItem(
            kind = DirectBootQueueItem.KIND_NORMAL,
            alarmId = 9996L,
            triggerTime = now - 2_000L,
            generation = 1,
            instanceToken = "d4-v2-token-9996-$sessionToken",
            restoreEpoch = epoch
        )
        val item2 = DirectBootQueueItem(
            kind = DirectBootQueueItem.KIND_NORMAL,
            alarmId = 9999L,
            triggerTime = now - 1_000L,
            generation = 1,
            instanceToken = "d4-v2-token-9999-$sessionToken",
            restoreEpoch = epoch
        )
        val firing1 = FiredOccurrenceIdentity(
            kind = DirectBootDeliveryRecord.KIND_NORMAL,
            alarmId = item1.alarmId,
            triggerTime = item1.triggerTime,
            generation = item1.generation,
            instanceToken = item1.instanceToken,
            restoreEpoch = item1.restoreEpoch,
            provenance = "V2_INTENT_DELIVERY"
        )
        Settings.Global.putInt(ceContext.contentResolver, Settings.Global.BOOT_COUNT, 42)
        assertTrue(DirectBootAlarmCache.writeQueue(deContext, listOf(item1, item2), now = now - 10_000L))

        // 1. 3-key atomicEditorCommitter MUST be forbidden on D4 route
        writeD4GrantJson(sessionToken, now - 5_000L, now + 25 * 60_000L, 42, listOf(item1 to false, item2 to false))
        val threeKeyRes = DirectBootDeliveryTransitionEngine.transitionNormalForTesting(
            context = deContext,
            firingIdentity = firing1,
            deliveredAt = now,
            atomicEditorCommitter = { _, _, _, _ -> true }
        )
        assertTrue("D4 route MUST forbid 3-key atomicEditorCommitter", threeKeyRes is DeliveryTransitionResult.Rejected.PreCommitFailure)
        assertEquals(2, DirectBootAlarmCache.readQueue(deContext, now = 0L).size)
        assertFalse(readD4GrantJson()!!.getJSONArray("allowedOccurrences").getJSONObject(0).getBoolean("admitted"))

        // 2. Resend / replay when Grant already has admitted=true MUST be rejected even if Queue was re-seeded
        writeD4GrantJson(sessionToken, now - 5_000L, now + 25 * 60_000L, 42, listOf(item1 to true, item2 to false))
        val replayRes = DirectBootDeliveryTransitionEngine.transitionNormal(
            context = deContext,
            firingIdentity = firing1,
            deliveredAt = now
        )
        assertTrue("Resend when admitted=true MUST reject", replayRes is DeliveryTransitionResult.Rejected.PreCommitFailure)
        assertEquals(2, DirectBootAlarmCache.readQueue(deContext, now = 0L).size)
        assertTrue(DirectBootDeliveryLedger.readUnacknowledgedRecords(deContext) is LedgerStorageState.Absent)

        // 3. Invalid Queue Head (non-head item2 fired while item1 is head, or wrong generation)
        writeD4GrantJson(sessionToken, now - 5_000L, now + 25 * 60_000L, 42, listOf(item1 to false, item2 to false))
        val nonHeadRes = DirectBootDeliveryTransitionEngine.transitionNormal(
            context = deContext,
            firingIdentity = firing1.copy(
                alarmId = item2.alarmId,
                triggerTime = item2.triggerTime,
                instanceToken = item2.instanceToken
            ),
            deliveredAt = now
        )
        assertTrue("Non-head queue item MUST reject", nonHeadRes is DeliveryTransitionResult.Rejected.NotEligibleQueueHead)
        val staleGenRes = DirectBootDeliveryTransitionEngine.transitionNormal(
            context = deContext,
            firingIdentity = firing1.copy(generation = 2),
            deliveredAt = now
        )
        assertTrue("Stale generation MUST reject", staleGenRes is DeliveryTransitionResult.Rejected.StaleGenerationMismatch)
    }

    @Test
    fun d4_atomicCommit_preservesNonD4StorageAndProductionV2ThreeKeyContract() {
        val now = System.currentTimeMillis()
        val sessionToken = "d4-sess-nond4"
        val epoch = now - 5_000L
        val prodItem = DirectBootQueueItem(
            kind = DirectBootQueueItem.KIND_NORMAL,
            alarmId = 555L,
            triggerTime = now - 2_000L,
            generation = 1,
            instanceToken = "prod-v2-token-555",
            restoreEpoch = epoch
        )
        val d4Item = DirectBootQueueItem(
            kind = DirectBootQueueItem.KIND_NORMAL,
            alarmId = 9996L,
            triggerTime = now + 180_000L,
            generation = 1,
            instanceToken = "d4-v2-token-9996-$sessionToken",
            restoreEpoch = epoch
        )
        assertTrue(DirectBootAlarmCache.writeQueue(deContext, listOf(prodItem, d4Item), now = now - 10_000L))
        Settings.Global.putInt(ceContext.contentResolver, Settings.Global.BOOT_COUNT, 42)
        writeD4GrantJson(sessionToken, now - 5_000L, now + 25 * 60_000L, 42, listOf(d4Item to false))

        var capturedFourKeysD4: String? = "UNSET"
        val prodRes = DirectBootDeliveryTransitionEngine.transitionNormalForTesting(
            context = deContext,
            firingIdentity = FiredOccurrenceIdentity(
                kind = DirectBootDeliveryRecord.KIND_NORMAL,
                alarmId = prodItem.alarmId,
                triggerTime = prodItem.triggerTime,
                generation = prodItem.generation,
                instanceToken = prodItem.instanceToken,
                restoreEpoch = prodItem.restoreEpoch,
                provenance = "V2_INTENT_DELIVERY"
            ),
            deliveredAt = now,
            capabilityAtomicEditorCommitter = { prefs, q, l, o, d4 ->
                capturedFourKeysD4 = d4
                prefs.edit()
                    .putString(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD, q)
                    .putString(DirectBootDeliveryLedger.KEY_DELIVERY_LEDGER_PAYLOAD, l)
                    .putString(DirectBootDurableOwnerStore.KEY_DIRECT_BOOT_DURABLE_OWNER, o)
                    .commit()
            }
        )
        assertTrue("Production V2 non-D4 transition MUST confirm", prodRes is DeliveryTransitionResult.Confirmed)
        assertNull("Production V2 non-D4 route MUST keep d4GrantPayload null (3-key contract)", capturedFourKeysD4)
        val remaining = DirectBootAlarmCache.readQueue(deContext, now = 0L)
        assertEquals(1, remaining.size)
        assertEquals(9996L, remaining.single().alarmId)
        assertFalse("Existing D4 grant MUST remain untouched", readD4GrantJson()!!.getJSONArray("allowedOccurrences").getJSONObject(0).getBoolean("admitted"))
    }

    @Test
    fun d4_exactCleanup_requiresAllSevenFields_blocksMixedOrCorrupt_andPreservesNonD4Storage() {
        val now = System.currentTimeMillis()
        val sessionToken = "d4-sess-clean"
        val epoch = now - 5_000L
        val d4Item1 = DirectBootQueueItem(
            kind = DirectBootQueueItem.KIND_NORMAL,
            alarmId = 9996L,
            triggerTime = now + 180_000L,
            generation = 1,
            instanceToken = "d4-v2-token-9996-$sessionToken",
            restoreEpoch = epoch
        )
        val d4Item2 = DirectBootQueueItem(
            kind = DirectBootQueueItem.KIND_NORMAL,
            alarmId = 9999L,
            triggerTime = now + 300_000L,
            generation = 1,
            instanceToken = "d4-v2-token-9999-$sessionToken",
            restoreEpoch = epoch
        )
        val nonD4Item = DirectBootQueueItem(
            kind = DirectBootQueueItem.KIND_NORMAL,
            alarmId = 701L,
            triggerTime = now + 60_000L,
            generation = 1,
            instanceToken = "prod-token-701",
            restoreEpoch = epoch,
            timeLabel = "07:01"
        )

        // Seed non-D4 DeliveryLedger, FiredWitnessLedger, and DurableOwner to verify strict preservation
        val nonD4LedgerRecord = DirectBootDeliveryRecord.createNormal(
            alarmId = 701L,
            triggerTime = now - 60_000L,
            deliveredAt = now - 59_000L,
            generation = 1,
            instanceToken = "prod-ledger-701",
            restoreEpoch = epoch
        )
        assertTrue(DirectBootDeliveryLedger.appendRecord(deContext, nonD4LedgerRecord) is DeliveryAppendResult.Appended)
        val nonD4Witness = DirectBootFiredWitnessRecord.createNormal(
            alarmId = 701L,
            triggerTime = now - 60_000L,
            generation = 1,
            instanceToken = "prod-ledger-701",
            restoreEpoch = epoch,
            deliveryMode = DirectBootFiredWitnessRecord.MODE_SOUND_AND_VIBRATION,
            deliveryOutcome = DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_ACCEPTED,
            observedAtEpochMs = now - 58_000L
        )
        assertTrue(DirectBootFiredWitnessLedger.reserveWitnessSlot(deContext, nonD4Witness) is WitnessAppendResult.Appended)
        val nonD4Owner = DirectBootDurableOwner(
            schemaVersion = DirectBootDurableOwnerStore.CURRENT_SCHEMA_VERSION,
            state = OwnerState.HANDOVER_PENDING,
            ownerSeq = 0L,
            bootProvenance = BootProvenance(42, 1000L, now - 10_000L),
            handoverClaim = HandoverClaim(
                handoverId = nonD4LedgerRecord.recordId,
                alarmId = 701L,
                triggerTime = now - 60_000L,
                generation = 1,
                instanceToken = "prod-ledger-701",
                restoreEpoch = epoch
            ),
            activeSession = null,
            updatedAtEpochMs = now - 58_000L
        )
        assertTrue(DirectBootDurableOwnerStore.write(deContext, nonD4Owner, expectedOwnerSeq = 0L) is OwnerWriteResult.Success)

        Settings.Global.putInt(ceContext.contentResolver, Settings.Global.BOOT_COUNT, 42)
        writeD4GrantJson(
            sessionToken = sessionToken,
            issuedAtEpochMs = now - 5_000L,
            expiryEpochMs = now + 25 * 60_000L,
            targetBootCount = 42,
            occurrences = listOf(d4Item1 to false, d4Item2 to false)
        )

        // 1. Mismatched generation / restoreEpoch / triggerTime / sessionToken MUST block with zero mutation
        val wrongGenItem = d4Item1.copy(generation = 2)
        assertTrue(DirectBootAlarmCache.writeQueue(deContext, listOf(nonD4Item, wrongGenItem), now = now))
        assertTrue(DirectBootAlarmCache.removeD4QueueItemsExact(deContext) is D4ExactCleanupResult.Blocked)
        assertEquals(2, DirectBootAlarmCache.readQueue(deContext, now = 0L).size)

        val wrongEpochItem = d4Item1.copy(restoreEpoch = epoch + 1L)
        assertTrue(DirectBootAlarmCache.writeQueue(deContext, listOf(nonD4Item, wrongEpochItem), now = now))
        assertTrue(DirectBootAlarmCache.removeD4QueueItemsExact(deContext) is D4ExactCleanupResult.Blocked)
        assertEquals(2, DirectBootAlarmCache.readQueue(deContext, now = 0L).size)

        val wrongTriggerItem = d4Item1.copy(triggerTime = d4Item1.triggerTime + 1_000L)
        assertTrue(DirectBootAlarmCache.writeQueue(deContext, listOf(nonD4Item, wrongTriggerItem), now = now))
        assertTrue(DirectBootAlarmCache.removeD4QueueItemsExact(deContext) is D4ExactCleanupResult.Blocked)
        assertEquals(2, DirectBootAlarmCache.readQueue(deContext, now = 0L).size)

        val wrongSessionItem = d4Item1.copy(instanceToken = "d4-v2-token-9996-d4-sess-other")
        assertTrue(DirectBootAlarmCache.writeQueue(deContext, listOf(nonD4Item, wrongSessionItem), now = now))
        assertTrue(DirectBootAlarmCache.removeD4QueueItemsExact(deContext) is D4ExactCleanupResult.Blocked)
        assertEquals(2, DirectBootAlarmCache.readQueue(deContext, now = 0L).size)

        // 2. Mixed Queue (one exact matching D4 item + one unmatchable 9999L item) MUST block & mutate NOTHING
        assertTrue(DirectBootAlarmCache.writeQueue(deContext, listOf(nonD4Item, d4Item1, wrongSessionItem.copy(alarmId = 9999L, instanceToken = "d4-v2-token-9999-d4-sess-other")), now = now))
        assertTrue(DirectBootAlarmCache.removeD4QueueItemsExact(deContext) is D4ExactCleanupResult.Blocked)
        assertEquals(3, DirectBootAlarmCache.readQueue(deContext, now = 0L).size)

        // 3. Exact 7-field match removes ONLY matching D4 items and preserves non-D4 queue/ledger/witness/owner
        assertTrue(DirectBootAlarmCache.writeQueue(deContext, listOf(nonD4Item, d4Item1, d4Item2), now = now))
        val removed = DirectBootAlarmCache.removeD4QueueItemsExact(deContext)
        assertTrue("Exact 7-field cleanup must succeed: $removed", removed is D4ExactCleanupResult.Removed)
        val rem = removed as D4ExactCleanupResult.Removed
        assertEquals(2, rem.removedCount)
        assertEquals(1, rem.preservedCount)

        val afterQueue = DirectBootAlarmCache.readQueue(deContext, now = 0L)
        assertEquals(1, afterQueue.size)
        assertEquals(nonD4Item.copy(timeLabel = afterQueue.single().timeLabel), afterQueue.single())
        assertEquals(1, (DirectBootDeliveryLedger.readUnacknowledgedRecords(deContext) as LedgerStorageState.ValidWithRecords).records.size)
        assertEquals(1, (DirectBootFiredWitnessLedger.readWitnesses(deContext) as WitnessLedgerState.ValidWithRecords).records.size)
        assertEquals(OwnerState.HANDOVER_PENDING, (DirectBootDurableOwnerStore.read(deContext) as OwnerReadResult.Success).owner.state)

        // 4. Corrupt Queue payload MUST return Blocked and leave raw bytes intact
        val corruptRaw = "{corrupt_queue"
        deContext.getSharedPreferences("direct_boot_next_alarm", Context.MODE_PRIVATE)
            .edit().putString(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD, corruptRaw).commit()
        assertTrue(DirectBootAlarmCache.removeD4QueueItemsExact(deContext) is D4ExactCleanupResult.Blocked)
        assertEquals(corruptRaw, DirectBootAlarmCache.getRawQueuePayload(deContext))
    }

    @Test
    fun d4_exactCeCleanup_requiresSessionOwnershipProof_notLabelAlone() {
        val now = System.currentTimeMillis()
        val sessionToken = "d4-sess-ce"
        val epoch = now - 5_000L
        val trigger1 = now + 180_000L
        val d4Item1 = DirectBootQueueItem(
            kind = DirectBootQueueItem.KIND_NORMAL,
            alarmId = 9996L,
            triggerTime = trigger1,
            generation = 1,
            instanceToken = "d4-v2-token-9996-$sessionToken",
            restoreEpoch = epoch
        )
        val matchingAlarm = com.sysadmindoc.alarmclock.data.model.Alarm(
            id = 9996L,
            hour = 8,
            minute = 0,
            label = "D4 FBE Probe 1",
            isEnabled = true,
            nextTriggerTime = trigger1
        )

        // Label matches, but D4 grant is absent -> MUST refuse ownership
        assertFalse(DirectBootAlarmCache.isCeAlarmOwnedByD4Grant(deContext, matchingAlarm, "D4 FBE Probe 1"))

        // Grant present, but label differs -> MUST refuse ownership
        Settings.Global.putInt(ceContext.contentResolver, Settings.Global.BOOT_COUNT, 42)
        writeD4GrantJson(sessionToken, now - 5_000L, now + 25 * 60_000L, 42, listOf(d4Item1 to false))
        assertFalse(DirectBootAlarmCache.isCeAlarmOwnedByD4Grant(deContext, matchingAlarm.copy(label = "User Alarm"), "D4 FBE Probe 1"))

        // Grant present + label matches, but triggerTime differs -> MUST refuse ownership
        assertFalse(DirectBootAlarmCache.isCeAlarmOwnedByD4Grant(deContext, matchingAlarm.copy(nextTriggerTime = trigger1 + 60_000L), "D4 FBE Probe 1"))

        // Grant revoked -> MUST refuse ownership
        writeD4GrantJson(sessionToken, now - 5_000L, now + 25 * 60_000L, 42, listOf(d4Item1 to false), revoked = true)
        assertFalse(DirectBootAlarmCache.isCeAlarmOwnedByD4Grant(deContext, matchingAlarm, "D4 FBE Probe 1"))

        // Active grant + exact alarmId + label + triggerTime + sessionToken -> proven D4 session ownership
        writeD4GrantJson(sessionToken, now - 5_000L, now + 25 * 60_000L, 42, listOf(d4Item1 to false), revoked = false)
        assertTrue(DirectBootAlarmCache.isCeAlarmOwnedByD4Grant(deContext, matchingAlarm, "D4 FBE Probe 1"))
    }

    // =========================================================================
    // KNO-75 G4-STEP9-D4-CLEANUP-FINAL-CLOSURE (TASK C)
    // =========================================================================

    private fun wrapContextWithCommitFailure(base: Context, failCommit: () -> Boolean): Context {
        return object : android.content.ContextWrapper(base) {
            override fun createDeviceProtectedStorageContext(): Context = this

            override fun getSharedPreferences(name: String?, mode: Int): android.content.SharedPreferences {
                val delegate = super.getSharedPreferences(name, mode)
                if (name != "direct_boot_next_alarm") return delegate
                return object : android.content.SharedPreferences by delegate {
                    override fun edit(): android.content.SharedPreferences.Editor {
                        val ed = delegate.edit()
                        return object : android.content.SharedPreferences.Editor by ed {
                            override fun putString(key: String?, value: String?): android.content.SharedPreferences.Editor {
                                ed.putString(key, value)
                                return this
                            }

                            override fun putStringSet(key: String?, values: MutableSet<String>?): android.content.SharedPreferences.Editor {
                                ed.putStringSet(key, values)
                                return this
                            }

                            override fun putInt(key: String?, value: Int): android.content.SharedPreferences.Editor {
                                ed.putInt(key, value)
                                return this
                            }

                            override fun putLong(key: String?, value: Long): android.content.SharedPreferences.Editor {
                                ed.putLong(key, value)
                                return this
                            }

                            override fun putFloat(key: String?, value: Float): android.content.SharedPreferences.Editor {
                                ed.putFloat(key, value)
                                return this
                            }

                            override fun putBoolean(key: String?, value: Boolean): android.content.SharedPreferences.Editor {
                                ed.putBoolean(key, value)
                                return this
                            }

                            override fun remove(key: String?): android.content.SharedPreferences.Editor {
                                ed.remove(key)
                                return this
                            }

                            override fun clear(): android.content.SharedPreferences.Editor {
                                ed.clear()
                                return this
                            }

                            override fun commit(): Boolean {
                                return if (failCommit()) {
                                    false
                                } else {
                                    ed.commit()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private data class StagedCleanupRunResult(
        val cleanupFailures: Int,
        val receipts: List<String>,
        val stageTrace: List<String>
    )

    private data class D4TestTargetSpec(
        val alarmId: Long,
        val expectedHour: Int,
        val expectedMinute: Int,
        val expectedLabel: String
    )

    private data class D4TestVerifiedPlan(
        val spec: D4TestTargetSpec,
        val occurrence: D4IsolatedOccurrenceGrant,
        val receipt: com.sysadmindoc.alarmclock.data.local.entity.DirectBootOccurrenceReceipt,
        val expectedCeAlarm: com.sysadmindoc.alarmclock.data.model.Alarm?
    )

    private fun isValidD4RolloverTriggerForTest(
        alarm: com.sysadmindoc.alarmclock.data.model.Alarm,
        occ: D4IsolatedOccurrenceGrant,
        expectedHour: Int,
        expectedMinute: Int,
        calculator: NextAlarmCalculator,
        nowMs: Long
    ): Boolean {
        if (!alarm.isEnabled) return false
        if (alarm.nextTriggerTime <= occ.triggerTime) return false
        if ((alarm.nextTriggerTime - occ.triggerTime) > 7L * 24L * 60L * 60L * 1000L) return false
        val scheduleZone = alarm.schedulingZone(ZoneId.systemDefault())
        val triggerZdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(alarm.nextTriggerTime), scheduleZone)
        if (triggerZdt.hour != expectedHour ||
            triggerZdt.minute != expectedMinute ||
            triggerZdt.second != 0 ||
            triggerZdt.nano != 0
        ) {
            return false
        }
        val firstRollover = calculator.calculate(
            alarm,
            ZonedDateTime.ofInstant(Instant.ofEpochMilli(occ.triggerTime), scheduleZone)
        )
        val latestRollover = calculator.calculate(
            alarm,
            ZonedDateTime.ofInstant(Instant.ofEpochMilli(maxOf(nowMs, occ.triggerTime)), scheduleZone)
        )
        return firstRollover > occ.triggerTime &&
            latestRollover >= firstRollover &&
            alarm.nextTriggerTime in firstRollover..latestRollover
    }

    /**
     * Mirrors the exact fail-closed 4-stage cleanup contract enforced in
     * MainV2RealOsSafetyTest.testD4_phase4_cleanup():
     *   Stage 1: V2 PI Cancellation -> gate
     *   Stage 2: DE Queue Exact Cleanup -> gate
     *   Stage 3 (inside ScheduleAuthorityOwner.withAuthenticatedRecoveryGate):
     *     - Stage 3a: All-target Ownership + Room 38 Receipt + Rollover proof before mutation
     *     - Stage 3b: Pre-3b TOCTOU check + AlarmScheduler.applyRawOsCancel(id) + V1 FLAG_NO_CREATE check
     *     - Stage 3c: Pre-3c Grant check + SQLite db.withTransaction re-verify + delete + null check
     *   Stage 4: Grant Revocation (STRICTLY LAST, ONLY after Stages 1, 2, and 3 all succeed)
     */
    private suspend fun executeStagedD4CleanupContract(
        ceCtx: Context,
        deCtx: Context,
        alarmDao: com.sysadmindoc.alarmclock.data.local.AlarmDao,
        receiptDao: com.sysadmindoc.alarmclock.data.local.DirectBootOccurrenceReceiptDao,
        db: AlarmDatabase? = null,
        alarmScheduler: AlarmScheduler? = null,
        clockProvider: () -> Long = { System.currentTimeMillis() },
        piCancelAction: (DirectBootV2NormalIntentIdentity) -> Boolean = { identity ->
            val am = ceCtx.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val piBefore = identity.lookupPendingIntentForCancel(ceCtx)
            if (piBefore != null) {
                am.cancel(piBefore)
                piBefore.cancel()
            }
            identity.lookupPendingIntentForCancel(ceCtx) == null
        },
        beforeV1CancelHook: (suspend (Long) -> Unit)? = null,
        beforeCeDeleteHook: (suspend (Long) -> Unit)? = null,
        ceDeleteOverride: (suspend (Long) -> Unit)? = null,
        grantRevokeOverride: ((Context) -> Boolean)? = null
    ): StagedCleanupRunResult {
        var cleanupFailures = 0
        val receipts = mutableListOf<String>()
        val stageTrace = mutableListOf<String>()
        val targetSpecs = listOf(
            D4TestTargetSpec(9996L, 8, 0, "D4 FBE Probe 1"),
            D4TestTargetSpec(9999L, 8, 2, "D4 FBE Probe 2")
        )
        val targetIds = targetSpecs.map { it.alarmId }.toSet()
        val calculator = NextAlarmCalculator(AppSettings())
        val effectiveScheduler = alarmScheduler ?: db?.let { buildRealTestAlarmScheduler(it) }

        val grant = DirectBootAlarmCache.readD4IsolatedCapability(deCtx)

        // Stage 1: Exact V2 PI Cancellation
        var stage1Ok = true
        if (grant != null && !grant.revoked) {
            stageTrace += "STAGE_1_PI"
            for (occ in grant.allowedOccurrences) {
                try {
                    val identity = DirectBootV2NormalIntentIdentity(
                        alarmId = occ.alarmId,
                        generation = occ.generation,
                        instanceToken = occ.instanceToken,
                        triggerTime = occ.triggerTime,
                        restoreEpoch = occ.restoreEpoch
                    )
                    if (!piCancelAction(identity)) {
                        cleanupFailures++
                        receipts += "PI_CANCEL_RESIDUAL:${occ.alarmId}"
                        stage1Ok = false
                        break
                    }
                } catch (t: Throwable) {
                    cleanupFailures++
                    receipts += "PI_CANCEL_EXCEPTION:${occ.alarmId}:${t.message}"
                    stage1Ok = false
                    break
                }
            }
        }

        // Stage 2: DE Queue Exact Cleanup (gated on Stage 1)
        var stage2Ok = false
        if (stage1Ok && cleanupFailures == 0) {
            stageTrace += "STAGE_2_DE"
            try {
                when (val res = DirectBootAlarmCache.removeD4QueueItemsExact(deCtx)) {
                    is D4ExactCleanupResult.Removed,
                    is D4ExactCleanupResult.NothingToRemove -> {
                        stage2Ok = true
                    }
                    is D4ExactCleanupResult.Blocked -> {
                        cleanupFailures++
                        receipts += "DE_BLOCKED:${res.reason}"
                    }
                    is D4ExactCleanupResult.PersistenceFailed -> {
                        cleanupFailures++
                        receipts += "DE_PERSISTENCE_FAILED:${res.reason}"
                    }
                }
            } catch (t: Throwable) {
                cleanupFailures++
                receipts += "DE_EXCEPTION:${t.message}"
            }
        }

        // Stage 3: Stage 3a Ownership Proof -> Stage 3b V1 PI Cancel -> Stage 3c CE Delete
        var stage3Ok = false
        if (stage1Ok && stage2Ok && cleanupFailures == 0) {
            stageTrace += "STAGE_3_CE"
            ScheduleAuthorityOwner.withAuthenticatedRecoveryGate(
                context = ceCtx,
                onBlocked = {
                    cleanupFailures++
                    receipts += "STAGE_3_GATE_BLOCKED"
                }
            ) {
                val nowMs = clockProvider()
                val liveGrant = DirectBootAlarmCache.readD4IsolatedCapability(deCtx)
                if (liveGrant == null || liveGrant.revoked) {
                    val hasLingering = targetSpecs.any { spec ->
                        alarmDao.getById(spec.alarmId) != null ||
                            lookupCanonicalV1AlarmReceiverPiForTest(spec.alarmId) != null
                    }
                    if (hasLingering) {
                        cleanupFailures++
                        receipts += "CE_GRANT_MISSING_OR_REVOKED_WITH_LINGERING_ARTIFACTS"
                    } else {
                        stage3Ok = true
                    }
                    return@withAuthenticatedRecoveryGate
                }

                // Stage 3a
                val verifiedPlans = mutableListOf<D4TestVerifiedPlan>()
                var proofFailed = false

                val grantStructuralValid =
                    liveGrant.schemaVersion == 1 &&
                        liveGrant.testSuiteTag == "D4_REAL_OS_FBE" &&
                        !liveGrant.revoked &&
                        liveGrant.sessionToken.isNotBlank() &&
                        liveGrant.targetBootCount >= 0 &&
                        liveGrant.issuedAtEpochMs > 0L &&
                        liveGrant.expiryEpochMs > liveGrant.issuedAtEpochMs &&
                        (liveGrant.expiryEpochMs - liveGrant.issuedAtEpochMs) <= 30L * 60_000L &&
                        liveGrant.allowedOccurrences.all { it.alarmId in targetIds }

                if (!grantStructuralValid) {
                    cleanupFailures++
                    receipts += "GRANT_STRUCTURAL_INVALID"
                    proofFailed = true
                }

                if (!proofFailed) {
                    for (spec in targetSpecs) {
                        try {
                            val matchingOccs = liveGrant.allowedOccurrences.filter { it.alarmId == spec.alarmId }
                            if (matchingOccs.size > 1) {
                                cleanupFailures++
                                receipts += "GRANT_DUPLICATE_OCCURRENCE:${spec.alarmId}"
                                proofFailed = true
                                break
                            }
                            val existingCe = alarmDao.getById(spec.alarmId)
                            val existingV1Pi = lookupCanonicalV1AlarmReceiverPiForTest(spec.alarmId)
                            if (matchingOccs.isEmpty()) {
                                if (existingCe != null || existingV1Pi != null) {
                                    cleanupFailures++
                                    receipts += "UNCOVERED_D4_ARTIFACT:${spec.alarmId}"
                                    proofFailed = true
                                    break
                                }
                                continue
                            }

                            val occ = matchingOccs.single()
                            val expectedToken = "d4-v2-token-${spec.alarmId}-${liveGrant.sessionToken}"
                            val occValid =
                                occ.generation >= 1 &&
                                    occ.triggerTime > 0L &&
                                    occ.restoreEpoch > 0L &&
                                    occ.instanceToken == expectedToken &&
                                    occ.triggerTime in liveGrant.issuedAtEpochMs..liveGrant.expiryEpochMs
                            if (!occValid) {
                                cleanupFailures++
                                receipts += "OCC_IDENTITY_INVALID:${spec.alarmId}"
                                proofFailed = true
                                break
                            }

                            val foundReceipts = receiptDao.findByAlarmId(spec.alarmId)
                            if (foundReceipts.size != 1) {
                                cleanupFailures++
                                receipts += "ROOM38_COUNT_INVALID:${spec.alarmId}:${foundReceipts.size}"
                                proofFailed = true
                                break
                            }
                            val receipt = foundReceipts.single()
                            val receiptMatch =
                                receipt.alarmId == occ.alarmId &&
                                    receipt.generation == occ.generation &&
                                    receipt.instanceToken == occ.instanceToken &&
                                    receipt.triggerTime == occ.triggerTime &&
                                    receipt.restoreEpoch == occ.restoreEpoch &&
                                    receipt.consumerOutcome == "CONSUMED_DELIVERED" &&
                                    receipt.completedAt in liveGrant.issuedAtEpochMs..liveGrant.expiryEpochMs
                            if (!receiptMatch) {
                                cleanupFailures++
                                receipts += "ROOM38_IDENTITY_MISMATCH:${spec.alarmId}"
                                proofFailed = true
                                break
                            }

                            if (nowMs > liveGrant.expiryEpochMs && !occ.admitted) {
                                cleanupFailures++
                                receipts += "EXPIRED_GRANT_WITHOUT_ADMISSION:${spec.alarmId}"
                                proofFailed = true
                                break
                            }

                            if (existingCe != null) {
                                val identityMatch =
                                    !existingCe.isQuickAlarm &&
                                        existingCe.id == spec.alarmId &&
                                        existingCe.label == spec.expectedLabel &&
                                        existingCe.hour == spec.expectedHour &&
                                        existingCe.minute == spec.expectedMinute &&
                                        existingCe.generation == occ.generation
                                val triggerMatch = if (existingCe.nextTriggerTime == occ.triggerTime) {
                                    DirectBootAlarmCache.isCeAlarmOwnedByD4Grant(deCtx, existingCe, spec.expectedLabel)
                                } else {
                                    isValidD4RolloverTriggerForTest(
                                        alarm = existingCe,
                                        occ = occ,
                                        expectedHour = spec.expectedHour,
                                        expectedMinute = spec.expectedMinute,
                                        calculator = calculator,
                                        nowMs = nowMs
                                    )
                                }
                                if (!identityMatch || !triggerMatch) {
                                    cleanupFailures++
                                    receipts += "CE_OWNERSHIP_MISMATCH:${spec.alarmId}"
                                    proofFailed = true
                                    break
                                }
                            }

                            verifiedPlans.add(D4TestVerifiedPlan(spec, occ, receipt, existingCe))
                        } catch (t: Throwable) {
                            cleanupFailures++
                            receipts += "STAGE_3A_EXCEPTION:${spec.alarmId}:${t.message}"
                            proofFailed = true
                            break
                        }
                    }
                }

                // Stage 3b: Pre-3b TOCTOU Check + Exact V1 PI Cancel via AlarmScheduler.applyRawOsCancel
                var stage3bOk = false
                if (!proofFailed && cleanupFailures == 0) {
                    var v1Failed = false
                    for (plan in verifiedPlans) {
                        try {
                            beforeV1CancelHook?.invoke(plan.spec.alarmId)
                            val currentGrant = DirectBootAlarmCache.readD4IsolatedCapability(deCtx)
                            val currentReceipts = receiptDao.findByAlarmId(plan.spec.alarmId)
                            val currentCe = alarmDao.getById(plan.spec.alarmId)
                            if (currentGrant != liveGrant ||
                                currentReceipts.size != 1 ||
                                currentReceipts.single() != plan.receipt ||
                                currentCe != plan.expectedCeAlarm
                            ) {
                                cleanupFailures++
                                receipts += "TOCTOU_PRE_3B_STATE_CHANGED:${plan.spec.alarmId}"
                                v1Failed = true
                                break
                            }

                            val cancelOk = if (effectiveScheduler != null) {
                                effectiveScheduler.applyRawOsCancel(plan.spec.alarmId)
                            } else {
                                val pi = lookupCanonicalV1AlarmReceiverPiForTest(plan.spec.alarmId)
                                if (pi != null) {
                                    val am = ceCtx.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                                    am.cancel(pi)
                                    pi.cancel()
                                }
                                true
                            }
                            if (!cancelOk || lookupCanonicalV1AlarmReceiverPiForTest(plan.spec.alarmId) != null) {
                                cleanupFailures++
                                receipts += "V1_PI_CANCEL_FAILED_OR_RESIDUAL:${plan.spec.alarmId}"
                                v1Failed = true
                                break
                            }
                        } catch (t: Throwable) {
                            cleanupFailures++
                            receipts += "V1_PI_CANCEL_EXCEPTION:${plan.spec.alarmId}:${t.message}"
                            v1Failed = true
                            break
                        }
                    }
                    if (!v1Failed && cleanupFailures == 0) {
                        stage3bOk = true
                    }
                }

                // Stage 3c: Pre-3c Grant Re-Check + Atomic SQLite Transaction CE Re-Verify & Delete
                if (stage3bOk && cleanupFailures == 0) {
                    var ceFailed = false
                    for (plan in verifiedPlans) {
                        try {
                            beforeCeDeleteHook?.invoke(plan.spec.alarmId)
                            val currentGrant = DirectBootAlarmCache.readD4IsolatedCapability(deCtx)
                            if (currentGrant != liveGrant) {
                                cleanupFailures++
                                receipts += "TOCTOU_PRE_3C_GRANT_CHANGED:${plan.spec.alarmId}"
                                ceFailed = true
                                break
                            }

                            val expectedExisting = plan.expectedCeAlarm
                            if (expectedExisting != null) {
                                val execDelete: suspend () -> Unit = {
                                    val txCe = alarmDao.getById(plan.spec.alarmId)
                                    val txReceipts = receiptDao.findByAlarmId(plan.spec.alarmId)
                                    if (txCe != expectedExisting ||
                                        txReceipts.size != 1 ||
                                        txReceipts.single() != plan.receipt
                                    ) {
                                        throw IllegalStateException("TOCTOU_IN_TX_STATE_CHANGED:${plan.spec.alarmId}")
                                    }
                                    if (ceDeleteOverride != null) {
                                        ceDeleteOverride(plan.spec.alarmId)
                                    } else {
                                        if (db != null) {
                                            db.scheduleExceptionDao().deleteForAlarm(plan.spec.alarmId)
                                        }
                                        alarmDao.deleteById(plan.spec.alarmId)
                                    }
                                    if (alarmDao.getById(plan.spec.alarmId) != null) {
                                        throw IllegalStateException("CE_RESIDUAL:${plan.spec.alarmId}")
                                    }
                                }
                                if (db != null) {
                                    db.withTransaction { execDelete() }
                                } else {
                                    execDelete()
                                }
                            }
                        } catch (t: Throwable) {
                            cleanupFailures++
                            receipts += "CE_EXCEPTION:${plan.spec.alarmId}:${t.message}"
                            ceFailed = true
                            break
                        }
                    }
                    if (!ceFailed && cleanupFailures == 0) {
                        stage3Ok = true
                    }
                }
            }
        }

        // Stage 4: Grant Revocation (STRICTLY LAST, ONLY after Stages 1, 2, 3 succeed)
        if (stage1Ok && stage2Ok && stage3Ok && cleanupFailures == 0) {
            stageTrace += "STAGE_4_REVOKE_GRANT"
            try {
                val currentGrant = DirectBootAlarmCache.readD4IsolatedCapability(deCtx)
                if (currentGrant != null && !currentGrant.revoked) {
                    val revokedOk = grantRevokeOverride?.invoke(deCtx)
                        ?: DirectBootAlarmCache.revokeD4IsolatedCapability(deCtx)
                    if (!revokedOk) {
                        cleanupFailures++
                        receipts += "GRANT_REVOKE_FAILED"
                    }
                }
            } catch (t: Throwable) {
                cleanupFailures++
                receipts += "GRANT_REVOKE_EXCEPTION:${t.message}"
            }
        }

        return StagedCleanupRunResult(cleanupFailures, receipts, stageTrace)
    }

    private fun seedNonD4DeAndCeArtifacts(
        now: Long,
        epoch: Long,
        db: AlarmDatabase
    ): Pair<DirectBootQueueItem, com.sysadmindoc.alarmclock.data.model.Alarm> = runBlocking {
        // Non-D4 V1 snapshot
        val v1Alarm = com.sysadmindoc.alarmclock.data.model.Alarm(
            id = 701L,
            hour = 7,
            minute = 1,
            label = "User Morning Alarm",
            isEnabled = true,
            nextTriggerTime = now + 60_000L
        )
        DirectBootAlarmCache.saveIfEarlier(deContext, v1Alarm, v1Alarm.nextTriggerTime, now = now)

        // Non-D4 Queue item
        val nonD4Item = DirectBootQueueItem(
            kind = DirectBootQueueItem.KIND_NORMAL,
            alarmId = 701L,
            triggerTime = now + 60_000L,
            generation = 1,
            instanceToken = "prod-token-701",
            restoreEpoch = epoch,
            timeLabel = "07:01"
        )

        // Non-D4 DeliveryLedger, FiredWitnessLedger, DurableOwner
        val nonD4Ledger = DirectBootDeliveryRecord.createNormal(
            alarmId = 701L,
            triggerTime = now - 60_000L,
            deliveredAt = now - 59_000L,
            generation = 1,
            instanceToken = "prod-ledger-701",
            restoreEpoch = epoch
        )
        assertTrue(DirectBootDeliveryLedger.appendRecord(deContext, nonD4Ledger) is DeliveryAppendResult.Appended)
        val nonD4Witness = DirectBootFiredWitnessRecord.createNormal(
            alarmId = 701L,
            triggerTime = now - 60_000L,
            generation = 1,
            instanceToken = "prod-ledger-701",
            restoreEpoch = epoch,
            deliveryMode = DirectBootFiredWitnessRecord.MODE_SOUND_AND_VIBRATION,
            deliveryOutcome = DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_ACCEPTED,
            observedAtEpochMs = now - 58_000L
        )
        assertTrue(DirectBootFiredWitnessLedger.reserveWitnessSlot(deContext, nonD4Witness) is WitnessAppendResult.Appended)
        val nonD4Owner = DirectBootDurableOwner(
            schemaVersion = DirectBootDurableOwnerStore.CURRENT_SCHEMA_VERSION,
            state = OwnerState.HANDOVER_PENDING,
            ownerSeq = 0L,
            bootProvenance = BootProvenance(42, 1000L, now - 10_000L),
            handoverClaim = HandoverClaim(
                handoverId = nonD4Ledger.recordId,
                alarmId = 701L,
                triggerTime = now - 60_000L,
                generation = 1,
                instanceToken = "prod-ledger-701",
                restoreEpoch = epoch
            ),
            activeSession = null,
            updatedAtEpochMs = now - 58_000L
        )
        assertTrue(DirectBootDurableOwnerStore.write(deContext, nonD4Owner, expectedOwnerSeq = 0L) is OwnerWriteResult.Success)

        // Non-D4 CE Alarm + Room 38 receipt
        db.alarmDao().insert(v1Alarm)
        db.directBootOccurrenceReceiptDao().insert(
            com.sysadmindoc.alarmclock.data.local.entity.DirectBootOccurrenceReceipt(
                occurrenceKey = "prod-key-701",
                sourceRecordId = nonD4Ledger.recordId,
                recordType = "NORMAL",
                alarmId = 701L,
                triggerTime = now - 60_000L,
                generation = 1,
                instanceToken = "prod-ledger-701",
                restoreEpoch = epoch,
                sourcePayloadHash = "hash-701",
                consumerOutcome = "CONSUMED_DELIVERED",
                completionSequence = 1L,
                completedAt = now - 57_000L
            )
        )
        nonD4Item to v1Alarm
    }

    private fun assertNonD4BaselinePreserved(db: AlarmDatabase) = runBlocking {
        assertNotNull("Non-D4 V1 snapshot must stay intact", DirectBootAlarmCache.read(deContext))
        assertEquals(701L, DirectBootAlarmCache.read(deContext)!!.alarmId)
        assertEquals(1, (DirectBootDeliveryLedger.readUnacknowledgedRecords(deContext) as LedgerStorageState.ValidWithRecords).records.size)
        assertEquals(1, (DirectBootFiredWitnessLedger.readWitnesses(deContext) as WitnessLedgerState.ValidWithRecords).records.size)
        assertEquals(OwnerState.HANDOVER_PENDING, (DirectBootDurableOwnerStore.read(deContext) as OwnerReadResult.Success).owner.state)
        assertTrue("Non-D4 queue item 701 must survive", DirectBootAlarmCache.readQueue(deContext, now = 0L).any { it.alarmId == 701L })
        assertNotNull("Non-D4 CE alarm 701 must survive", db.alarmDao().getById(701L))
        assertEquals(1, db.directBootOccurrenceReceiptDao().findByAlarmId(701L).size)
    }

    @Test
    fun d4_cleanup_deBlocked_preservesGrantQueueCeAndNonD4() = runBlocking {
        val now = System.currentTimeMillis()
        val epoch = now - 5_000L
        val sessionToken = "d4-sess-blk"
        val db = Room.inMemoryDatabaseBuilder(ceContext, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val (nonD4Item, _) = seedNonD4DeAndCeArtifacts(now, epoch, db)
            val d4Item1 = DirectBootQueueItem(
                kind = DirectBootQueueItem.KIND_NORMAL,
                alarmId = 9996L,
                triggerTime = now + 180_000L,
                generation = 1,
                instanceToken = "d4-v2-token-9996-$sessionToken",
                restoreEpoch = epoch
            )
            // Seed mixed/unmatchable queue item (generation mismatch) to trigger Blocked
            val badGenItem = d4Item1.copy(generation = 2)
            assertTrue(DirectBootAlarmCache.writeQueue(deContext, listOf(nonD4Item, badGenItem), now = now))
            Settings.Global.putInt(ceContext.contentResolver, Settings.Global.BOOT_COUNT, 42)
            writeD4GrantJson(sessionToken, now - 5_000L, now + 25 * 60_000L, 42, listOf(d4Item1 to false))
            db.alarmDao().insert(
                com.sysadmindoc.alarmclock.data.model.Alarm(
                    id = 9996L,
                    hour = 8,
                    minute = 0,
                    label = "D4 FBE Probe 1",
                    isEnabled = true,
                    nextTriggerTime = d4Item1.triggerTime
                )
            )

            val res = executeStagedD4CleanupContract(ceContext, deContext, db.alarmDao(), db.directBootOccurrenceReceiptDao())
            assertTrue("DE Blocked must increment cleanupFailures", res.cleanupFailures > 0)
            assertEquals(listOf("STAGE_1_PI", "STAGE_2_DE"), res.stageTrace)
            assertFalse("Grant must stay unrevoked when DE Blocked", DirectBootAlarmCache.readD4IsolatedCapability(deContext)!!.revoked)
            assertEquals(2, DirectBootAlarmCache.readQueue(deContext, now = 0L).size)
            assertNotNull("CE D4 alarm must remain intact when DE Blocked stops Stage 3", db.alarmDao().getById(9996L))
            assertNonD4BaselinePreserved(db)
        } finally {
            db.close()
        }
    }

    @Test
    fun d4_cleanup_dePersistenceFailed_preservesGrantQueueCeAndNonD4() = runBlocking {
        val now = System.currentTimeMillis()
        val epoch = now - 5_000L
        val sessionToken = "d4-sess-pf"
        val db = Room.inMemoryDatabaseBuilder(ceContext, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val (nonD4Item, _) = seedNonD4DeAndCeArtifacts(now, epoch, db)
            val d4Item1 = DirectBootQueueItem(
                kind = DirectBootQueueItem.KIND_NORMAL,
                alarmId = 9996L,
                triggerTime = now + 180_000L,
                generation = 1,
                instanceToken = "d4-v2-token-9996-$sessionToken",
                restoreEpoch = epoch
            )
            assertTrue(DirectBootAlarmCache.writeQueue(deContext, listOf(nonD4Item, d4Item1), now = now))
            Settings.Global.putInt(ceContext.contentResolver, Settings.Global.BOOT_COUNT, 42)
            writeD4GrantJson(sessionToken, now - 5_000L, now + 25 * 60_000L, 42, listOf(d4Item1 to false))
            db.alarmDao().insert(
                com.sysadmindoc.alarmclock.data.model.Alarm(
                    id = 9996L,
                    hour = 8,
                    minute = 0,
                    label = "D4 FBE Probe 1",
                    isEnabled = true,
                    nextTriggerTime = d4Item1.triggerTime
                )
            )
            db.directBootOccurrenceReceiptDao().insert(
                com.sysadmindoc.alarmclock.data.local.entity.DirectBootOccurrenceReceipt(
                    occurrenceKey = "d4-pf-9996",
                    sourceRecordId = "rec-pf-9996",
                    recordType = "DELIVERED",
                    alarmId = 9996L,
                    triggerTime = d4Item1.triggerTime,
                    generation = 1,
                    instanceToken = d4Item1.instanceToken,
                    restoreEpoch = epoch,
                    sourcePayloadHash = "hash-pf-9996",
                    consumerOutcome = "CONSUMED_DELIVERED",
                    completionSequence = 2L,
                    completedAt = now - 500L
                )
            )

            var shouldFailCommit = true
            val failingDeCtx = wrapContextWithCommitFailure(deContext) { shouldFailCommit }
            val failRes = executeStagedD4CleanupContract(ceContext, failingDeCtx, db.alarmDao(), db.directBootOccurrenceReceiptDao(), db = db)
            assertTrue("DE PersistenceFailed must increment cleanupFailures", failRes.cleanupFailures > 0)
            assertTrue(failRes.receipts.any { it.startsWith("DE_PERSISTENCE_FAILED:") })
            assertEquals(listOf("STAGE_1_PI", "STAGE_2_DE"), failRes.stageTrace)
            assertFalse("Grant must stay unrevoked on PersistenceFailed", DirectBootAlarmCache.readD4IsolatedCapability(deContext)!!.revoked)
            assertEquals(2, DirectBootAlarmCache.readQueue(deContext, now = 0L).size)
            assertNotNull("CE D4 alarm must remain intact when PersistenceFailed stops Stage 3", db.alarmDao().getById(9996L))
            assertNonD4BaselinePreserved(db)

            // Same-session retry after commit recovers -> full cleanup completes and revokes Grant
            shouldFailCommit = false
            val retryRes = executeStagedD4CleanupContract(ceContext, failingDeCtx, db.alarmDao(), db.directBootOccurrenceReceiptDao(), db = db)
            assertEquals(0, retryRes.cleanupFailures)
            assertEquals(listOf("STAGE_1_PI", "STAGE_2_DE", "STAGE_3_CE", "STAGE_4_REVOKE_GRANT"), retryRes.stageTrace)
            assertTrue("Grant must be revoked after retry succeeds", DirectBootAlarmCache.readD4IsolatedCapability(deContext)!!.revoked)
            assertNull("CE D4 alarm 9996 must be deleted after retry", db.alarmDao().getById(9996L))
            assertNonD4BaselinePreserved(db)
        } finally {
            db.close()
        }
    }

    @Test
    fun d4_cleanup_ceOwnershipOrReceiptMismatch_blocksDeletionAndPreservesGrant() = runBlocking {
        val now = System.currentTimeMillis()
        val epoch = now - 5_000L
        val sessionToken = "d4-sess-cegate"
        val db = Room.inMemoryDatabaseBuilder(ceContext, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val (nonD4Item, _) = seedNonD4DeAndCeArtifacts(now, epoch, db)
            val d4Item1 = DirectBootQueueItem(
                kind = DirectBootQueueItem.KIND_NORMAL,
                alarmId = 9996L,
                triggerTime = now + 180_000L,
                generation = 1,
                instanceToken = "d4-v2-token-9996-$sessionToken",
                restoreEpoch = epoch
            )
            assertTrue(DirectBootAlarmCache.writeQueue(deContext, listOf(nonD4Item), now = now))
            Settings.Global.putInt(ceContext.contentResolver, Settings.Global.BOOT_COUNT, 42)
            writeD4GrantJson(sessionToken, now - 5_000L, now + 25 * 60_000L, 42, listOf(d4Item1 to false))

            // 1. Wrong label on 9996L (even with valid Room 38 receipt) -> blocks CE delete & keeps Grant unrevoked
            db.directBootOccurrenceReceiptDao().insert(
                com.sysadmindoc.alarmclock.data.local.entity.DirectBootOccurrenceReceipt(
                    occurrenceKey = "d4-cegate-valid-9996",
                    sourceRecordId = "rec-cegate-valid",
                    recordType = "DELIVERED",
                    alarmId = 9996L,
                    triggerTime = d4Item1.triggerTime,
                    generation = 1,
                    instanceToken = d4Item1.instanceToken,
                    restoreEpoch = epoch,
                    sourcePayloadHash = "hash-valid",
                    consumerOutcome = "CONSUMED_DELIVERED",
                    completionSequence = 2L,
                    completedAt = now - 1_000L
                )
            )
            db.alarmDao().insert(
                com.sysadmindoc.alarmclock.data.model.Alarm(
                    id = 9996L,
                    hour = 8,
                    minute = 0,
                    label = "Preexisting User Alarm",
                    isEnabled = true,
                    nextTriggerTime = d4Item1.triggerTime
                )
            )
            val labelMismatchRes = executeStagedD4CleanupContract(ceContext, deContext, db.alarmDao(), db.directBootOccurrenceReceiptDao(), db = db)
            assertTrue(labelMismatchRes.cleanupFailures > 0)
            assertEquals(listOf("STAGE_1_PI", "STAGE_2_DE", "STAGE_3_CE"), labelMismatchRes.stageTrace)
            assertNotNull("9996L with mismatched label must NOT be deleted", db.alarmDao().getById(9996L))
            assertFalse("Grant must stay unrevoked on CE ownership mismatch", DirectBootAlarmCache.readD4IsolatedCapability(deContext)!!.revoked)

            // 2. Fresh DB: Correct label & triggerTime, but single Room 38 receipt has wrong session token/epoch -> blocks CE delete & keeps Grant unrevoked
            val db2 = Room.inMemoryDatabaseBuilder(ceContext, AlarmDatabase::class.java)
                .allowMainThreadQueries()
                .build()
            try {
                db2.alarmDao().insert(
                    com.sysadmindoc.alarmclock.data.model.Alarm(
                        id = 9996L,
                        hour = 8,
                        minute = 0,
                        label = "D4 FBE Probe 1",
                        isEnabled = true,
                        nextTriggerTime = d4Item1.triggerTime
                    )
                )
                db2.directBootOccurrenceReceiptDao().insert(
                    com.sysadmindoc.alarmclock.data.local.entity.DirectBootOccurrenceReceipt(
                        occurrenceKey = "d4-wrong-receipt-9996",
                        sourceRecordId = "rec-wrong",
                        recordType = "DELIVERED",
                        alarmId = 9996L,
                        triggerTime = d4Item1.triggerTime,
                        generation = 1,
                        instanceToken = "d4-v2-token-9996-other-session",
                        restoreEpoch = epoch + 999L,
                        sourcePayloadHash = "hash-wrong",
                        consumerOutcome = "CONSUMED_DELIVERED",
                        completionSequence = 2L,
                        completedAt = now - 1_000L
                    )
                )
                val receiptMismatchRes = executeStagedD4CleanupContract(ceContext, deContext, db2.alarmDao(), db2.directBootOccurrenceReceiptDao(), db = db2)
                assertTrue(receiptMismatchRes.cleanupFailures > 0)
                assertEquals(listOf("STAGE_1_PI", "STAGE_2_DE", "STAGE_3_CE"), receiptMismatchRes.stageTrace)
                assertNotNull("9996L with inconsistent Room 38 receipt must NOT be deleted", db2.alarmDao().getById(9996L))
                assertFalse("Grant must stay unrevoked on Room 38 receipt mismatch", DirectBootAlarmCache.readD4IsolatedCapability(deContext)!!.revoked)
            } finally {
                db2.close()
            }
            assertNonD4BaselinePreserved(db)
        } finally {
            db.close()
        }
    }

    @Test
    fun d4_cleanup_piCancelFailure_haltsBeforeDeCeAndPreservesGrant() = runBlocking {
        val now = System.currentTimeMillis()
        val epoch = now - 5_000L
        val sessionToken = "d4-sess-pi"
        val db = Room.inMemoryDatabaseBuilder(ceContext, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val (nonD4Item, _) = seedNonD4DeAndCeArtifacts(now, epoch, db)
            val d4Item1 = DirectBootQueueItem(
                kind = DirectBootQueueItem.KIND_NORMAL,
                alarmId = 9996L,
                triggerTime = now + 180_000L,
                generation = 1,
                instanceToken = "d4-v2-token-9996-$sessionToken",
                restoreEpoch = epoch
            )
            val d4Item2 = DirectBootQueueItem(
                kind = DirectBootQueueItem.KIND_NORMAL,
                alarmId = 9999L,
                triggerTime = now + 300_000L,
                generation = 1,
                instanceToken = "d4-v2-token-9999-$sessionToken",
                restoreEpoch = epoch
            )
            assertTrue(DirectBootAlarmCache.writeQueue(deContext, listOf(nonD4Item, d4Item1, d4Item2), now = now))
            Settings.Global.putInt(ceContext.contentResolver, Settings.Global.BOOT_COUNT, 42)
            writeD4GrantJson(sessionToken, now - 5_000L, now + 25 * 60_000L, 42, listOf(d4Item1 to false, d4Item2 to false))
            db.alarmDao().insertAll(
                listOf(
                    com.sysadmindoc.alarmclock.data.model.Alarm(9996L, 8, 0, label = "D4 FBE Probe 1", isEnabled = true, nextTriggerTime = d4Item1.triggerTime),
                    com.sysadmindoc.alarmclock.data.model.Alarm(9999L, 8, 2, label = "D4 FBE Probe 2", isEnabled = true, nextTriggerTime = d4Item2.triggerTime)
                )
            )
            val id1 = DirectBootV2NormalIntentIdentity.fromQueueItem(d4Item1)
            val id2 = DirectBootV2NormalIntentIdentity.fromQueueItem(d4Item2)
            assertNotNull(id1.createPendingIntent(ceContext))
            assertNotNull(id2.createPendingIntent(ceContext))

            // Simulate Stage 1 PI failure on 9996L -> Stages 2, 3, 4 MUST NOT execute
            val piFailRes = executeStagedD4CleanupContract(
                ceCtx = ceContext,
                deCtx = deContext,
                alarmDao = db.alarmDao(),
                receiptDao = db.directBootOccurrenceReceiptDao(),
                db = db,
                piCancelAction = { false }
            )
            assertEquals(1, piFailRes.cleanupFailures)
            assertEquals(listOf("STAGE_1_PI"), piFailRes.stageTrace)
            assertFalse("Grant must stay unrevoked when PI cancel fails", DirectBootAlarmCache.readD4IsolatedCapability(deContext)!!.revoked)
            assertEquals(3, DirectBootAlarmCache.readQueue(deContext, now = 0L).size)
            assertNotNull(db.alarmDao().getById(9996L))
            assertNotNull(db.alarmDao().getById(9999L))
            assertNonD4BaselinePreserved(db)
        } finally {
            db.close()
        }
    }

    @Test
    fun d4_cleanup_normalSuccess_revokesGrantLast_andIsIdempotent() = runBlocking {
        val now = System.currentTimeMillis()
        val epoch = now - 5_000L
        val sessionToken = "d4-sess-ok"
        val db = Room.inMemoryDatabaseBuilder(ceContext, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val (nonD4Item, _) = seedNonD4DeAndCeArtifacts(now, epoch, db)
            val d4Item1 = DirectBootQueueItem(
                kind = DirectBootQueueItem.KIND_NORMAL,
                alarmId = 9996L,
                triggerTime = now + 180_000L,
                generation = 1,
                instanceToken = "d4-v2-token-9996-$sessionToken",
                restoreEpoch = epoch
            )
            val d4Item2 = DirectBootQueueItem(
                kind = DirectBootQueueItem.KIND_NORMAL,
                alarmId = 9999L,
                triggerTime = now + 300_000L,
                generation = 1,
                instanceToken = "d4-v2-token-9999-$sessionToken",
                restoreEpoch = epoch
            )
            assertTrue(DirectBootAlarmCache.writeQueue(deContext, listOf(nonD4Item, d4Item1, d4Item2), now = now))
            Settings.Global.putInt(ceContext.contentResolver, Settings.Global.BOOT_COUNT, 42)
            writeD4GrantJson(sessionToken, now - 5_000L, now + 25 * 60_000L, 42, listOf(d4Item1 to false, d4Item2 to false))
            db.alarmDao().insertAll(
                listOf(
                    com.sysadmindoc.alarmclock.data.model.Alarm(9996L, 8, 0, label = "D4 FBE Probe 1", isEnabled = true, nextTriggerTime = d4Item1.triggerTime),
                    com.sysadmindoc.alarmclock.data.model.Alarm(9999L, 8, 2, label = "D4 FBE Probe 2", isEnabled = true, nextTriggerTime = d4Item2.triggerTime)
                )
            )
            listOf(d4Item1, d4Item2).forEachIndexed { idx, item ->
                db.directBootOccurrenceReceiptDao().insert(
                    com.sysadmindoc.alarmclock.data.local.entity.DirectBootOccurrenceReceipt(
                        occurrenceKey = "d4-ok-${item.alarmId}",
                        sourceRecordId = "rec-${item.alarmId}",
                        recordType = "NORMAL",
                        alarmId = item.alarmId,
                        triggerTime = item.triggerTime,
                        generation = item.generation,
                        instanceToken = item.instanceToken,
                        restoreEpoch = item.restoreEpoch,
                        sourcePayloadHash = "hash-${item.alarmId}",
                        consumerOutcome = "CONSUMED_DELIVERED",
                        completionSequence = (idx + 2).toLong(),
                        completedAt = now - 500L
                    )
                )
            }
            val id1 = DirectBootV2NormalIntentIdentity.fromQueueItem(d4Item1)
            val id2 = DirectBootV2NormalIntentIdentity.fromQueueItem(d4Item2)
            assertNotNull(id1.createPendingIntent(ceContext))
            assertNotNull(id2.createPendingIntent(ceContext))

            val firstRun = executeStagedD4CleanupContract(ceContext, deContext, db.alarmDao(), db.directBootOccurrenceReceiptDao(), db = db)
            assertEquals(0, firstRun.cleanupFailures)
            assertEquals(listOf("STAGE_1_PI", "STAGE_2_DE", "STAGE_3_CE", "STAGE_4_REVOKE_GRANT"), firstRun.stageTrace)
            assertNull(id1.lookupPendingIntentForCancel(ceContext))
            assertNull(id2.lookupPendingIntentForCancel(ceContext))
            assertEquals(listOf(701L), DirectBootAlarmCache.readQueue(deContext, now = 0L).map { it.alarmId })
            assertNull(db.alarmDao().getById(9996L))
            assertNull(db.alarmDao().getById(9999L))
            assertTrue("Grant must be revoked ONLY after all stages succeed", DirectBootAlarmCache.readD4IsolatedCapability(deContext)!!.revoked)
            assertNonD4BaselinePreserved(db)

            // Idempotent re-run after completion
            val secondRun = executeStagedD4CleanupContract(ceContext, deContext, db.alarmDao(), db.directBootOccurrenceReceiptDao(), db = db)
            assertEquals(0, secondRun.cleanupFailures)
            assertEquals(listOf("STAGE_2_DE", "STAGE_3_CE", "STAGE_4_REVOKE_GRANT"), secondRun.stageTrace)
            assertTrue(DirectBootAlarmCache.readD4IsolatedCapability(deContext)!!.revoked)
            assertNonD4BaselinePreserved(db)
        } finally {
            db.close()
        }
    }

    @Test
    fun d4_cleanup_partialFailure_allowsSameSessionRetry_thenRevokesGrant() = runBlocking {
        val now = System.currentTimeMillis()
        val epoch = now - 5_000L
        val sessionToken = "d4-sess-retry"
        val db = Room.inMemoryDatabaseBuilder(ceContext, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val (nonD4Item, _) = seedNonD4DeAndCeArtifacts(now, epoch, db)
            val d4Item1 = DirectBootQueueItem(
                kind = DirectBootQueueItem.KIND_NORMAL,
                alarmId = 9996L,
                triggerTime = now + 180_000L,
                generation = 1,
                instanceToken = "d4-v2-token-9996-$sessionToken",
                restoreEpoch = epoch
            )
            val d4Item2 = DirectBootQueueItem(
                kind = DirectBootQueueItem.KIND_NORMAL,
                alarmId = 9999L,
                triggerTime = now + 300_000L,
                generation = 1,
                instanceToken = "d4-v2-token-9999-$sessionToken",
                restoreEpoch = epoch
            )
            assertTrue(DirectBootAlarmCache.writeQueue(deContext, listOf(nonD4Item, d4Item1, d4Item2), now = now))
            Settings.Global.putInt(ceContext.contentResolver, Settings.Global.BOOT_COUNT, 42)
            writeD4GrantJson(sessionToken, now - 5_000L, now + 25 * 60_000L, 42, listOf(d4Item1 to false, d4Item2 to false))
            db.alarmDao().insertAll(
                listOf(
                    com.sysadmindoc.alarmclock.data.model.Alarm(9996L, 8, 0, label = "D4 FBE Probe 1", isEnabled = true, nextTriggerTime = d4Item1.triggerTime),
                    com.sysadmindoc.alarmclock.data.model.Alarm(9999L, 8, 2, label = "D4 FBE Probe 2", isEnabled = true, nextTriggerTime = d4Item2.triggerTime)
                )
            )
            listOf(d4Item1, d4Item2).forEachIndexed { idx, item ->
                db.directBootOccurrenceReceiptDao().insert(
                    com.sysadmindoc.alarmclock.data.local.entity.DirectBootOccurrenceReceipt(
                        occurrenceKey = "d4-retry-${item.alarmId}",
                        sourceRecordId = "rec-retry-${item.alarmId}",
                        recordType = "DELIVERED",
                        alarmId = item.alarmId,
                        triggerTime = item.triggerTime,
                        generation = item.generation,
                        instanceToken = item.instanceToken,
                        restoreEpoch = item.restoreEpoch,
                        sourcePayloadHash = "hash-${item.alarmId}",
                        consumerOutcome = "CONSUMED_DELIVERED",
                        completionSequence = (idx + 2).toLong(),
                        completedAt = now - 500L
                    )
                )
            }
            val id1 = DirectBootV2NormalIntentIdentity.fromQueueItem(d4Item1)
            val id2 = DirectBootV2NormalIntentIdentity.fromQueueItem(d4Item2)
            assertNotNull(id1.createPendingIntent(ceContext))
            assertNotNull(id2.createPendingIntent(ceContext))

            // Run 1: Stage 1 (PI) & Stage 2 (DE) succeed, Stage 3 deletes 9996L then fails transiently on 9999L
            var failAlarm2Delete = true
            val partialRun = executeStagedD4CleanupContract(
                ceCtx = ceContext,
                deCtx = deContext,
                alarmDao = db.alarmDao(),
                receiptDao = db.directBootOccurrenceReceiptDao(),
                db = db,
                ceDeleteOverride = { id ->
                    if (id == 9999L && failAlarm2Delete) {
                        throw IllegalStateException("Transient Room delete failure")
                    }
                    db.alarmDao().deleteById(id)
                }
            )
            assertEquals(1, partialRun.cleanupFailures)
            assertEquals(listOf("STAGE_1_PI", "STAGE_2_DE", "STAGE_3_CE"), partialRun.stageTrace)
            assertFalse("Grant MUST remain unrevoked after partial failure so same-session retry keeps ownership proof", DirectBootAlarmCache.readD4IsolatedCapability(deContext)!!.revoked)
            assertNull(db.alarmDao().getById(9996L))
            assertNotNull("9999L remains in CE for same-session retry", db.alarmDao().getById(9999L))
            assertNonD4BaselinePreserved(db)

            // Run 2 (same session retry): Stage 1 & 2 idempotent, Stage 3 completes 9999L via unrevoked Grant, Stage 4 revokes Grant
            failAlarm2Delete = false
            val retryRun = executeStagedD4CleanupContract(
                ceCtx = ceContext,
                deCtx = deContext,
                alarmDao = db.alarmDao(),
                receiptDao = db.directBootOccurrenceReceiptDao(),
                db = db
            )
            assertEquals(0, retryRun.cleanupFailures)
            assertEquals(listOf("STAGE_1_PI", "STAGE_2_DE", "STAGE_3_CE", "STAGE_4_REVOKE_GRANT"), retryRun.stageTrace)
            assertNull(db.alarmDao().getById(9999L))
            assertTrue("Grant revoked only after retry finishes all stages", DirectBootAlarmCache.readD4IsolatedCapability(deContext)!!.revoked)
            assertNonD4BaselinePreserved(db)
        } finally {
            db.close()
        }
    }

    @Test
    fun d4_cleanup_nonD4DataInvariant_acrossFailuresAndSuccess() = runBlocking {
        val now = System.currentTimeMillis()
        val epoch = now - 5_000L
        val sessionToken = "d4-sess-nond4inv"
        val db = Room.inMemoryDatabaseBuilder(ceContext, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val (nonD4Item, nonD4Alarm) = seedNonD4DeAndCeArtifacts(now, epoch, db)
            val d4Item1 = DirectBootQueueItem(
                kind = DirectBootQueueItem.KIND_NORMAL,
                alarmId = 9996L,
                triggerTime = now + 180_000L,
                generation = 1,
                instanceToken = "d4-v2-token-9996-$sessionToken",
                restoreEpoch = epoch
            )
            val d4Item2 = DirectBootQueueItem(
                kind = DirectBootQueueItem.KIND_NORMAL,
                alarmId = 9999L,
                triggerTime = now + 300_000L,
                generation = 1,
                instanceToken = "d4-v2-token-9999-$sessionToken",
                restoreEpoch = epoch
            )
            Settings.Global.putInt(ceContext.contentResolver, Settings.Global.BOOT_COUNT, 42)
            writeD4GrantJson(sessionToken, now - 5_000L, now + 25 * 60_000L, 42, listOf(d4Item1 to false, d4Item2 to false))
            db.alarmDao().insertAll(
                listOf(
                    com.sysadmindoc.alarmclock.data.model.Alarm(9996L, 8, 0, label = "D4 FBE Probe 1", isEnabled = true, nextTriggerTime = d4Item1.triggerTime),
                    com.sysadmindoc.alarmclock.data.model.Alarm(9999L, 8, 2, label = "D4 FBE Probe 2", isEnabled = true, nextTriggerTime = d4Item2.triggerTime)
                )
            )
            listOf(d4Item1, d4Item2).forEachIndexed { idx, item ->
                db.directBootOccurrenceReceiptDao().insert(
                    com.sysadmindoc.alarmclock.data.local.entity.DirectBootOccurrenceReceipt(
                        occurrenceKey = "d4-inv-${item.alarmId}",
                        sourceRecordId = "rec-inv-${item.alarmId}",
                        recordType = "DELIVERED",
                        alarmId = item.alarmId,
                        triggerTime = item.triggerTime,
                        generation = item.generation,
                        instanceToken = item.instanceToken,
                        restoreEpoch = item.restoreEpoch,
                        sourcePayloadHash = "hash-${item.alarmId}",
                        consumerOutcome = "CONSUMED_DELIVERED",
                        completionSequence = (idx + 2).toLong(),
                        completedAt = now - 500L
                    )
                )
            }
            assertTrue(DirectBootAlarmCache.writeQueue(deContext, listOf(nonD4Item, d4Item1, d4Item2), now = now))

            // 1. Stage 1 PI failure preserves non-D4 baseline
            val r1 = executeStagedD4CleanupContract(ceContext, deContext, db.alarmDao(), db.directBootOccurrenceReceiptDao(), db = db, piCancelAction = { false })
            assertTrue(r1.cleanupFailures > 0)
            assertNonD4BaselinePreserved(db)

            // 2. Stage 2 PersistenceFailed preserves non-D4 baseline
            val failingCtx = wrapContextWithCommitFailure(deContext) { true }
            val r2 = executeStagedD4CleanupContract(ceContext, failingCtx, db.alarmDao(), db.directBootOccurrenceReceiptDao(), db = db)
            assertTrue(r2.cleanupFailures > 0)
            assertNonD4BaselinePreserved(db)

            // 3. Stage 2 Blocked (mismatched item) preserves non-D4 baseline
            assertTrue(DirectBootAlarmCache.writeQueue(deContext, listOf(nonD4Item, d4Item1.copy(generation = 2)), now = now))
            val r3 = executeStagedD4CleanupContract(ceContext, deContext, db.alarmDao(), db.directBootOccurrenceReceiptDao(), db = db)
            assertTrue(r3.cleanupFailures > 0)
            assertNonD4BaselinePreserved(db)

            // 4. Full cleanup succeeds & STILL preserves every non-D4 artifact verbatim
            assertTrue(DirectBootAlarmCache.writeQueue(deContext, listOf(nonD4Item, d4Item1, d4Item2), now = now))
            val r4 = executeStagedD4CleanupContract(ceContext, deContext, db.alarmDao(), db.directBootOccurrenceReceiptDao(), db = db)
            assertEquals(0, r4.cleanupFailures)
            assertNonD4BaselinePreserved(db)
            assertEquals(nonD4Alarm.label, db.alarmDao().getById(701L)!!.label)
            assertEquals(nonD4Alarm.nextTriggerTime, db.alarmDao().getById(701L)!!.nextTriggerTime)
            assertTrue(DirectBootAlarmCache.readD4IsolatedCapability(deContext)!!.revoked)
        } finally {
            db.close()
        }
    }

    // =========================================================================
    // KNO-75 G4-STEP9-D4-POSTUNLOCK-CLEANUP-CORRECTION (TASK C)
    // =========================================================================

    private fun buildRealTestAlarmScheduler(
        db: AlarmDatabase,
        calc: NextAlarmCalculator = NextAlarmCalculator(AppSettings())
    ): AlarmScheduler {
        val repo = AlarmRepository(db, db.alarmDao(), db.alarmGroupDao())
        val prefs = mockk<PreferencesManager>(relaxed = true).apply {
            every { getCachedSettings() } returns AppSettings()
            coEvery { getCurrentSettings() } returns AppSettings()
        }
        return AlarmScheduler(
            context = ceContext,
            repository = repo,
            calculator = calc,
            preferencesManager = prefs,
            holidayRepository = mockk<HolidayRepository>(relaxed = true),
            alarmIncidentRepository = mockk<AlarmIncidentRepository>(relaxed = true),
            weatherRepository = mockk<WeatherRepository>(relaxed = true)
        )
    }

    private fun armCanonicalV1AlarmReceiverPendingIntent(alarmId: Long, triggerTime: Long): PendingIntent {
        val intent = Intent(ceContext, AlarmReceiver::class.java).apply {
            action = "com.sysadmindoc.alarmclock.ALARM_FIRE"
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, triggerTime)
        }
        val pi = PendingIntent.getBroadcast(
            ceContext,
            alarmId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val am = ceContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pi)
        return pi
    }

    private fun lookupCanonicalV1AlarmReceiverPiForTest(alarmId: Long): PendingIntent? {
        val intent = Intent(ceContext, AlarmReceiver::class.java).apply {
            action = "com.sysadmindoc.alarmclock.ALARM_FIRE"
        }
        return PendingIntent.getBroadcast(
            ceContext,
            alarmId.toInt(),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun seedThree0600NonD4AlarmsAndTwelveLegacyRecords(
        now: Long,
        db: AlarmDatabase,
        nonD4Hour: Int = 6,
        nonD4Minute: Int = 0,
        nonD4TriggerTimeOverride: Long? = null
    ) = runBlocking {
        val zone = ZoneId.systemDefault()
        val nonD4Trigger = nonD4TriggerTimeOverride ?: ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
            .toLocalDate().plusDays(1).atTime(nonD4Hour, nonD4Minute).atZone(zone).toInstant().toEpochMilli()
        val existing0600 = listOf(1L, 2L, 3L).map { id ->
            armCanonicalV1AlarmReceiverPendingIntent(id, nonD4Trigger)
            com.sysadmindoc.alarmclock.data.model.Alarm(
                id = id,
                hour = nonD4Hour,
                minute = nonD4Minute,
                label = "起床",
                isEnabled = true,
                nextTriggerTime = nonD4Trigger
            )
        }
        db.alarmDao().insertAll(existing0600)
        DirectBootAlarmCache.saveIfEarlier(deContext, existing0600.first(), nonD4Trigger, now = now)

        repeat(12) { idx ->
            val recAlarmId = if (idx < 9) 9995L else (idx - 8).toLong()
            val trig = now - 86_400_000L + idx * 100L
            val rec = DirectBootDeliveryRecord.createNormal(
                alarmId = recAlarmId,
                triggerTime = trig,
                deliveredAt = trig + 50L,
                generation = 1,
                instanceToken = "legacy_claim_$recAlarmId",
                provenance = "LEGACY_DELIVERY",
                restoreEpoch = 1L
            )
            assertTrue(DirectBootDeliveryLedger.appendRecord(deContext, rec) is DeliveryAppendResult.Appended)
        }
    }

    private fun assertThree0600AndTwelveLegacyPreserved(
        db: AlarmDatabase,
        expectedCacheAlarmId: Long? = 1L
    ) = runBlocking {
        for (id in listOf(1L, 2L, 3L)) {
            val row = db.alarmDao().getById(id)
            assertNotNull("Non-D4 06:00 alarm $id must survive CE cleanup", row)
            assertEquals("起床", row!!.label)
            assertNotNull("Non-D4 06:00 V1 PI $id must survive", lookupCanonicalV1AlarmReceiverPiForTest(id))
        }
        val ledgerState = DirectBootDeliveryLedger.readUnacknowledgedRecords(deContext)
        assertTrue(ledgerState is LedgerStorageState.ValidWithRecords)
        assertEquals(12, (ledgerState as LedgerStorageState.ValidWithRecords).records.size)
        assertTrue(ledgerState.records.all { it.provenance == "LEGACY_DELIVERY" })
        if (expectedCacheAlarmId != null) {
            assertEquals(expectedCacheAlarmId, DirectBootAlarmCache.read(deContext)?.alarmId)
        }
    }

    @Test
    fun d4_postUnlock_rollover_cancelsOnlyD4V1Pis_andPreserves0600AndLegacyLedger() = runBlocking {
        val now = System.currentTimeMillis()
        val epoch = now - 30 * 60_000L
        val sessionToken = "d4-sess-rollover"
        val origTrig1 = epoch + 180_000L
        val origTrig2 = epoch + 300_000L
        val zone = ZoneId.systemDefault()
        val calc = NextAlarmCalculator(AppSettings())
        val rolloverTrig1 = calc.calculate(
            com.sysadmindoc.alarmclock.data.model.Alarm(9996L, 8, 0, label = "D4 FBE Probe 1", isEnabled = true),
            ZonedDateTime.ofInstant(Instant.ofEpochMilli(origTrig1), zone)
        )
        val rolloverTrig2 = calc.calculate(
            com.sysadmindoc.alarmclock.data.model.Alarm(9999L, 8, 2, label = "D4 FBE Probe 2", isEnabled = true),
            ZonedDateTime.ofInstant(Instant.ofEpochMilli(origTrig2), zone)
        )
        val db = Room.inMemoryDatabaseBuilder(ceContext, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            seedThree0600NonD4AlarmsAndTwelveLegacyRecords(now, db)
            val d4Item1 = DirectBootQueueItem(kind = DirectBootQueueItem.KIND_NORMAL, alarmId = 9996L, triggerTime = origTrig1, generation = 1, instanceToken = "d4-v2-token-9996-$sessionToken", restoreEpoch = epoch)
            val d4Item2 = DirectBootQueueItem(kind = DirectBootQueueItem.KIND_NORMAL, alarmId = 9999L, triggerTime = origTrig2, generation = 1, instanceToken = "d4-v2-token-9999-$sessionToken", restoreEpoch = epoch)
            Settings.Global.putInt(ceContext.contentResolver, Settings.Global.BOOT_COUNT, 9)
            writeD4GrantJson(sessionToken, epoch, epoch + 30 * 60_000L, 9, listOf(d4Item1 to true, d4Item2 to true))

            db.alarmDao().insertAll(
                listOf(
                    com.sysadmindoc.alarmclock.data.model.Alarm(9996L, 8, 0, label = "D4 FBE Probe 1", isEnabled = true, nextTriggerTime = rolloverTrig1),
                    com.sysadmindoc.alarmclock.data.model.Alarm(9999L, 8, 2, label = "D4 FBE Probe 2", isEnabled = true, nextTriggerTime = rolloverTrig2)
                )
            )
            armCanonicalV1AlarmReceiverPendingIntent(9996L, rolloverTrig1)
            armCanonicalV1AlarmReceiverPendingIntent(9999L, rolloverTrig2)
            listOf(d4Item1, d4Item2).forEachIndexed { idx, item ->
                db.directBootOccurrenceReceiptDao().insert(
                    com.sysadmindoc.alarmclock.data.local.entity.DirectBootOccurrenceReceipt(
                        occurrenceKey = "ce_takeover_normal_${item.alarmId}_gen1_${item.instanceToken}_trig${item.triggerTime}",
                        sourceRecordId = "delivery_NORMAL_${item.alarmId}_gen1_${item.instanceToken}_trig${item.triggerTime}",
                        recordType = "DELIVERED",
                        alarmId = item.alarmId,
                        triggerTime = item.triggerTime,
                        generation = item.generation,
                        instanceToken = item.instanceToken,
                        restoreEpoch = item.restoreEpoch,
                        sourcePayloadHash = "hash-${item.alarmId}",
                        consumerOutcome = "CONSUMED_DELIVERED",
                        completionSequence = idx.toLong(),
                        completedAt = item.triggerTime + 50L
                    )
                )
            }
            val res = executeStagedD4CleanupContract(ceContext, deContext, db.alarmDao(), db.directBootOccurrenceReceiptDao(), db = db)
            assertEquals("Rollover cleanup must succeed: ${res.receipts}", 0, res.cleanupFailures)
            assertNull("9996L V1 PI must be cancelled", lookupCanonicalV1AlarmReceiverPiForTest(9996L))
            assertNull("9999L V1 PI must be cancelled", lookupCanonicalV1AlarmReceiverPiForTest(9999L))
            assertNull("9996L CE row must be deleted", db.alarmDao().getById(9996L))
            assertNull("9999L CE row must be deleted", db.alarmDao().getById(9999L))
            assertEquals("Room 38 receipts must NOT be deleted", 1, db.directBootOccurrenceReceiptDao().findByAlarmId(9996L).size)
            assertEquals("Room 38 receipts must NOT be deleted", 1, db.directBootOccurrenceReceiptDao().findByAlarmId(9999L).size)
            assertTrue("Grant must be revoked last", DirectBootAlarmCache.readD4IsolatedCapability(deContext)!!.revoked)
            assertThree0600AndTwelveLegacyPreserved(db)
        } finally {
            db.close()
        }
    }

    private fun runD4RealBootRescheduleContractAtJstClock(
        jstHour: Int,
        jstMinute: Int,
        nonD4Hour: Int = 6,
        nonD4Minute: Int = 0,
        expectedCacheAlarmId: Long,
        sessionSuffix: String
    ) = runBlocking {
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        cancelKnownTestPendingIntents()
        DirectBootServiceTestFixture.clearAllDirectBootPrefs(deContext)

        val prevTimeZone = java.util.TimeZone.getDefault()
        val jstZone = ZoneId.of("Asia/Tokyo")
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(jstZone))

        val now = System.currentTimeMillis()
        val anchorZdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), jstZone)
            .toLocalDate().plusDays(2).atTime(jstHour, jstMinute).atZone(jstZone)
        val anchorMs = anchorZdt.toInstant().toEpochMilli()

        val epoch = now - 30 * 60_000L
        val sessionToken = "d4-sess-realresched-$sessionSuffix"
        val origTrig1 = epoch + 180_000L
        val origTrig2 = epoch + 300_000L

        val realCalc = NextAlarmCalculator(AppSettings())
        val deterministicCalc = spyk(realCalc)
        every { deterministicCalc.calculate(any(), any()) } answers {
            realCalc.calculate(firstArg(), anchorZdt)
        }

        val expectedNonD4Trigger = realCalc.calculate(
            com.sysadmindoc.alarmclock.data.model.Alarm(1L, nonD4Hour, nonD4Minute, isEnabled = true),
            anchorZdt
        )
        val expectedD4Trig1 = realCalc.calculate(
            com.sysadmindoc.alarmclock.data.model.Alarm(9996L, 8, 0, isEnabled = true),
            anchorZdt
        )
        val expectedD4Trig2 = realCalc.calculate(
            com.sysadmindoc.alarmclock.data.model.Alarm(9999L, 8, 2, isEnabled = true),
            anchorZdt
        )

        val db = Room.inMemoryDatabaseBuilder(ceContext, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val workManager = mockk<WorkManager>(relaxed = true)
        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(any<Context>()) } returns workManager
        every { workManager.cancelUniqueWork(any()) } returns mockk<Operation>(relaxed = true)
        mockkObject(WidgetUpdater)
        every { WidgetUpdater.requestUpdate(any()) } just Runs
        mockkObject(BedtimeZenRuleManager)
        every { BedtimeZenRuleManager.syncRule(any(), any(), any()) } returns mockk(relaxed = true)
        try {
            seedThree0600NonD4AlarmsAndTwelveLegacyRecords(
                now = now,
                db = db,
                nonD4Hour = nonD4Hour,
                nonD4Minute = nonD4Minute,
                nonD4TriggerTimeOverride = expectedNonD4Trigger
            )
            val d4Item1 = DirectBootQueueItem(kind = DirectBootQueueItem.KIND_NORMAL, alarmId = 9996L, triggerTime = origTrig1, generation = 1, instanceToken = "d4-v2-token-9996-$sessionToken", restoreEpoch = epoch)
            val d4Item2 = DirectBootQueueItem(kind = DirectBootQueueItem.KIND_NORMAL, alarmId = 9999L, triggerTime = origTrig2, generation = 1, instanceToken = "d4-v2-token-9999-$sessionToken", restoreEpoch = epoch)
            Settings.Global.putInt(ceContext.contentResolver, Settings.Global.BOOT_COUNT, 9)
            writeD4GrantJson(sessionToken, epoch, epoch + 30 * 60_000L, 9, listOf(d4Item1 to true, d4Item2 to true))
            db.alarmDao().insertAll(
                listOf(
                    com.sysadmindoc.alarmclock.data.model.Alarm(9996L, 8, 0, label = "D4 FBE Probe 1", isEnabled = true, nextTriggerTime = origTrig1),
                    com.sysadmindoc.alarmclock.data.model.Alarm(9999L, 8, 2, label = "D4 FBE Probe 2", isEnabled = true, nextTriggerTime = origTrig2)
                )
            )
            listOf(d4Item1, d4Item2).forEachIndexed { idx, item ->
                db.directBootOccurrenceReceiptDao().insert(
                    com.sysadmindoc.alarmclock.data.local.entity.DirectBootOccurrenceReceipt(
                        occurrenceKey = "r38-real-$sessionSuffix-${item.alarmId}", sourceRecordId = "sr-real-$sessionSuffix-${item.alarmId}", recordType = "DELIVERED",
                        alarmId = item.alarmId, triggerTime = item.triggerTime, generation = 1,
                        instanceToken = item.instanceToken, restoreEpoch = epoch,
                        sourcePayloadHash = "h-${item.alarmId}", consumerOutcome = "CONSUMED_DELIVERED", completionSequence = idx.toLong(), completedAt = origTrig1 + 100L
                    )
                )
            }

            // Execute REAL Production rescheduleAllInBatches (same path invoked by BootRescheduleWorker)
            val scheduler = buildRealTestAlarmScheduler(db, deterministicCalc)
            scheduler.rescheduleAllInBatches(forceRecalculate = false, batchSize = 25)

            val rolled1 = db.alarmDao().getById(9996L)!!
            val rolled2 = db.alarmDao().getById(9999L)!!
            assertEquals("9996L rollover trigger must match deterministic JST calculation", expectedD4Trig1, rolled1.nextTriggerTime)
            assertEquals("9999L rollover trigger must match deterministic JST calculation", expectedD4Trig2, rolled2.nextTriggerTime)
            assertTrue("Real rescheduleAllInBatches must move past D4 trigger forward", rolled1.nextTriggerTime > origTrig1)
            assertTrue("Real rescheduleAllInBatches must move past D4 trigger forward", rolled2.nextTriggerTime > origTrig2)
            assertNotNull("Real rescheduleAllInBatches must arm V1 PI 9996", lookupCanonicalV1AlarmReceiverPiForTest(9996L))
            assertNotNull("Real rescheduleAllInBatches must arm V1 PI 9999", lookupCanonicalV1AlarmReceiverPiForTest(9999L))

            // Verify runtime DirectBootAlarmCache.saveIfEarlier earliest selection contract faithfully
            val expectedEarliestTrigger = minOf(expectedNonD4Trigger, expectedD4Trig1)
            val cacheSnapshot = DirectBootAlarmCache.read(deContext)
            assertNotNull("DirectBootAlarmCache must hold earliest active alarm", cacheSnapshot)
            assertEquals("saveIfEarlier must select true earliest alarm at %02d:%02d JST".format(jstHour, jstMinute), expectedCacheAlarmId, cacheSnapshot!!.alarmId)
            assertEquals(expectedEarliestTrigger, cacheSnapshot.triggerTime)

            val res = executeStagedD4CleanupContract(
                ceContext,
                deContext,
                db.alarmDao(),
                db.directBootOccurrenceReceiptDao(),
                db = db,
                alarmScheduler = scheduler,
                clockProvider = { anchorMs }
            )
            assertEquals("Real rollover path cleanup must succeed: ${res.receipts}", 0, res.cleanupFailures)
            assertNull(lookupCanonicalV1AlarmReceiverPiForTest(9996L))
            assertNull(lookupCanonicalV1AlarmReceiverPiForTest(9999L))
            assertNull(db.alarmDao().getById(9996L))
            assertNull(db.alarmDao().getById(9999L))
            assertTrue(DirectBootAlarmCache.readD4IsolatedCapability(deContext)!!.revoked)
            assertThree0600AndTwelveLegacyPreserved(db, expectedCacheAlarmId = expectedCacheAlarmId)
        } finally {
            java.util.TimeZone.setDefault(prevTimeZone)
            unmockkAll()
            db.close()
        }
    }

    @Test
    fun d4_postUnlock_realBootRescheduleWorkerPath_rollsOverNextTriggerAndCleansUpSafely() {
        runD4RealBootRescheduleContractAtJstClock(
            jstHour = 12,
            jstMinute = 0,
            nonD4Hour = 6,
            nonD4Minute = 0,
            expectedCacheAlarmId = 1L,
            sessionSuffix = "1200jst"
        )
    }

    @Test
    fun d4_postUnlock_realBootRescheduleWorkerPath_deterministicAcrossJstRolloverWindows() {
        // 00:30 JST: 06:00 non-D4 (today 06:00) < 08:00 D4 (today 08:00) -> cache selects 1L
        runD4RealBootRescheduleContractAtJstClock(
            jstHour = 0,
            jstMinute = 30,
            nonD4Hour = 6,
            nonD4Minute = 0,
            expectedCacheAlarmId = 1L,
            sessionSuffix = "0030jst"
        )
        // 06:30 JST (06:00 non-D4 vs 08:00 D4): today 08:00 < tomorrow 06:00 -> runtime saveIfEarlier selects 9996L!
        runD4RealBootRescheduleContractAtJstClock(
            jstHour = 6,
            jstMinute = 30,
            nonD4Hour = 6,
            nonD4Minute = 0,
            expectedCacheAlarmId = 9996L,
            sessionSuffix = "0630jst-d4earliest"
        )
        // 06:30 JST (07:00 non-D4 vs 08:00 D4): today 07:00 < today 08:00 -> cache selects 1L
        runD4RealBootRescheduleContractAtJstClock(
            jstHour = 6,
            jstMinute = 30,
            nonD4Hour = 7,
            nonD4Minute = 0,
            expectedCacheAlarmId = 1L,
            sessionSuffix = "0630jst-nond4earliest"
        )
        // 12:00 JST: tomorrow 06:00 < tomorrow 08:00 -> cache selects 1L
        runD4RealBootRescheduleContractAtJstClock(
            jstHour = 12,
            jstMinute = 0,
            nonD4Hour = 6,
            nonD4Minute = 0,
            expectedCacheAlarmId = 1L,
            sessionSuffix = "1200jst-matrix"
        )
        // 23:30 JST: tomorrow 06:00 < tomorrow 08:00 -> cache selects 1L
        runD4RealBootRescheduleContractAtJstClock(
            jstHour = 23,
            jstMinute = 30,
            nonD4Hour = 6,
            nonD4Minute = 0,
            expectedCacheAlarmId = 1L,
            sessionSuffix = "2330jst"
        )
    }

    @Test
    fun d4_postUnlock_v1PiIdentityIsolation_doesNotTouchNonCanonicalPi_andFailsClosedOnResidualV1Pi() = runBlocking {
        val now = System.currentTimeMillis()
        val epoch = now - 5_000L
        val sessionToken = "d4-sess-v1id"
        val db = Room.inMemoryDatabaseBuilder(ceContext, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val d4Item1 = DirectBootQueueItem(kind = DirectBootQueueItem.KIND_NORMAL, alarmId = 9996L, triggerTime = now + 180_000L, generation = 1, instanceToken = "d4-v2-token-9996-$sessionToken", restoreEpoch = epoch)
            Settings.Global.putInt(ceContext.contentResolver, Settings.Global.BOOT_COUNT, 9)
            writeD4GrantJson(sessionToken, epoch, epoch + 30 * 60_000L, 9, listOf(d4Item1 to true))
            db.alarmDao().insert(com.sysadmindoc.alarmclock.data.model.Alarm(9996L, 8, 0, label = "D4 FBE Probe 1", isEnabled = true, nextTriggerTime = d4Item1.triggerTime))
            db.directBootOccurrenceReceiptDao().insert(
                com.sysadmindoc.alarmclock.data.local.entity.DirectBootOccurrenceReceipt(
                    occurrenceKey = "r38-v1id", sourceRecordId = "sr-v1id", recordType = "DELIVERED",
                    alarmId = 9996L, triggerTime = d4Item1.triggerTime, generation = 1,
                    instanceToken = d4Item1.instanceToken, restoreEpoch = epoch,
                    sourcePayloadHash = "h-v1id", consumerOutcome = "CONSUMED_DELIVERED", completionSequence = 0L, completedAt = epoch + 100L
                )
            )

            // Non-canonical PIs sharing alarmId=9996 (QuickAlarm requestCode=89996, DIRECT_BOOT action requestCode=9996, MAIN_V2 tokenized URI)
            val quickPi = PendingIntent.getBroadcast(
                ceContext, 89996,
                Intent(ceContext, AlarmReceiver::class.java).putExtra(AlarmScheduler.EXTRA_QUICK_ALARM, true),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val directBootV1Pi = PendingIntent.getBroadcast(
                ceContext, 9996,
                Intent("com.sysadmindoc.alarmclock.DIRECT_BOOT_ALARM_FIRE").setPackage(ceContext.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val scheduler = buildRealTestAlarmScheduler(db)
            val mainV2Pi = scheduler.createTokenizedPendingIntent(9996L, d4Item1.triggerTime, "fire-9996", "tok-unrelated")
            val am = ceContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, d4Item1.triggerTime, quickPi)
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, d4Item1.triggerTime, directBootV1Pi)
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, d4Item1.triggerTime, mainV2Pi)

            // Arm canonical V1 PI too -> applyRawOsCancel(9996L) cancels ONLY canonical V1 ALARM_FIRE (data=null, rc=9996)
            armCanonicalV1AlarmReceiverPendingIntent(9996L, d4Item1.triggerTime)
            val res = executeStagedD4CleanupContract(ceContext, deContext, db.alarmDao(), db.directBootOccurrenceReceiptDao(), db = db, alarmScheduler = scheduler)
            assertEquals(0, res.cleanupFailures)
            assertNull("Canonical V1 PI must be cancelled", lookupCanonicalV1AlarmReceiverPiForTest(9996L))
            assertNotNull("QuickAlarm PI must stay untouched", PendingIntent.getBroadcast(ceContext, 89996, Intent(ceContext, AlarmReceiver::class.java), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE))
            assertNotNull("DirectBoot V1 PI (distinct action) must stay untouched", PendingIntent.getBroadcast(ceContext, 9996, Intent("com.sysadmindoc.alarmclock.DIRECT_BOOT_ALARM_FIRE").setPackage(ceContext.packageName), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE))
            assertNotNull("MAIN_V2 tokenized PI (distinct data URI) must stay untouched", scheduler.lookupTokenizedPendingIntentForCancel(9996L, "tok-unrelated"))
        } finally {
            db.close()
        }
    }

    @Test
    fun d4_postUnlock_missingOrMultipleRoom38Receipt_failsClosed() = runBlocking {
        val now = System.currentTimeMillis()
        val epoch = now - 5_000L
        val sessionToken = "d4-sess-r38guard"
        val db = Room.inMemoryDatabaseBuilder(ceContext, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val d4Item1 = DirectBootQueueItem(kind = DirectBootQueueItem.KIND_NORMAL, alarmId = 9996L, triggerTime = now + 180_000L, generation = 1, instanceToken = "d4-v2-token-9996-$sessionToken", restoreEpoch = epoch)
            Settings.Global.putInt(ceContext.contentResolver, Settings.Global.BOOT_COUNT, 9)
            writeD4GrantJson(sessionToken, epoch, epoch + 30 * 60_000L, 9, listOf(d4Item1 to true))
            db.alarmDao().insert(com.sysadmindoc.alarmclock.data.model.Alarm(9996L, 8, 0, label = "D4 FBE Probe 1", isEnabled = true, nextTriggerTime = d4Item1.triggerTime))
            armCanonicalV1AlarmReceiverPendingIntent(9996L, d4Item1.triggerTime)

            // Missing Room 38 receipt -> MUST fail closed
            val missingRes = executeStagedD4CleanupContract(ceContext, deContext, db.alarmDao(), db.directBootOccurrenceReceiptDao(), db = db)
            assertTrue("Missing Room 38 receipt must fail closed", missingRes.cleanupFailures > 0)
            assertNotNull("9996L CE row must survive when Room 38 receipt missing", db.alarmDao().getById(9996L))
            assertNotNull("9996L V1 PI must NOT be cancelled when ownership unproven", lookupCanonicalV1AlarmReceiverPiForTest(9996L))
            assertFalse(DirectBootAlarmCache.readD4IsolatedCapability(deContext)!!.revoked)

            // Multiple (contradictory/duplicate) Room 38 receipts -> MUST fail closed
            db.directBootOccurrenceReceiptDao().insert(
                com.sysadmindoc.alarmclock.data.local.entity.DirectBootOccurrenceReceipt(
                    occurrenceKey = "r38-1", sourceRecordId = "sr1", recordType = "DELIVERED",
                    alarmId = 9996L, triggerTime = d4Item1.triggerTime, generation = 1,
                    instanceToken = d4Item1.instanceToken, restoreEpoch = epoch,
                    sourcePayloadHash = "h1", consumerOutcome = "CONSUMED_DELIVERED", completionSequence = 0L, completedAt = epoch + 100L
                )
            )
            db.directBootOccurrenceReceiptDao().insert(
                com.sysadmindoc.alarmclock.data.local.entity.DirectBootOccurrenceReceipt(
                    occurrenceKey = "r38-2", sourceRecordId = "sr2", recordType = "DELIVERED",
                    alarmId = 9996L, triggerTime = d4Item1.triggerTime, generation = 1,
                    instanceToken = "d4-v2-token-9996-other", restoreEpoch = epoch,
                    sourcePayloadHash = "h2", consumerOutcome = "CONSUMED_DELIVERED", completionSequence = 1L, completedAt = epoch + 200L
                )
            )
            val multiRes = executeStagedD4CleanupContract(ceContext, deContext, db.alarmDao(), db.directBootOccurrenceReceiptDao(), db = db)
            assertTrue("Multiple Room 38 receipts must fail closed", multiRes.cleanupFailures > 0)
            assertNotNull(db.alarmDao().getById(9996L))
            assertNotNull(lookupCanonicalV1AlarmReceiverPiForTest(9996L))
            assertFalse(DirectBootAlarmCache.readD4IsolatedCapability(deContext)!!.revoked)
        } finally {
            db.close()
        }
    }

    @Test
    fun d4_postUnlock_grantSessionMismatch_failsClosed() = runBlocking {
        val now = System.currentTimeMillis()
        val epoch = now - 5_000L
        val db = Room.inMemoryDatabaseBuilder(ceContext, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            db.alarmDao().insert(com.sysadmindoc.alarmclock.data.model.Alarm(9996L, 8, 0, label = "D4 FBE Probe 1", isEnabled = true, nextTriggerTime = now + 180_000L))
            armCanonicalV1AlarmReceiverPendingIntent(9996L, now + 180_000L)

            // 1. Grant missing (null) while CE/V1 artifacts exist -> fail closed
            val noGrantRes = executeStagedD4CleanupContract(ceContext, deContext, db.alarmDao(), db.directBootOccurrenceReceiptDao(), db = db)
            assertTrue("Missing grant with lingering 9996L must fail closed", noGrantRes.cleanupFailures > 0)
            assertNotNull(db.alarmDao().getById(9996L))
            assertNotNull(lookupCanonicalV1AlarmReceiverPiForTest(9996L))

            // 2. Session mismatch: Grant has sessionToken="d4-sess-A", while occurrence & Room 38 use "d4-sess-B"
            Settings.Global.putInt(ceContext.contentResolver, Settings.Global.BOOT_COUNT, 9)
            val rawMismatchJson = JSONObject().apply {
                put("schemaVersion", 1)
                put("testSuiteTag", "D4_REAL_OS_FBE")
                put("sessionToken", "d4-sess-A")
                put("issuedAtEpochMs", epoch)
                put("expiryEpochMs", epoch + 30 * 60_000L)
                put("targetBootCount", 9)
                put("revoked", false)
                put("allowedOccurrences", JSONArray().put(JSONObject().apply {
                    put("alarmId", 9996L)
                    put("generation", 1)
                    put("instanceToken", "d4-v2-token-9996-d4-sess-B")
                    put("triggerTime", now + 180_000L)
                    put("restoreEpoch", epoch)
                    put("admitted", true)
                }))
            }.toString()
            deContext.getSharedPreferences("direct_boot_next_alarm", Context.MODE_PRIVATE)
                .edit().putString(DirectBootAlarmCache.KEY_D4_ISOLATED_CAPABILITY, rawMismatchJson).commit()
            db.directBootOccurrenceReceiptDao().insert(
                com.sysadmindoc.alarmclock.data.local.entity.DirectBootOccurrenceReceipt(
                    occurrenceKey = "r38-sess-b", sourceRecordId = "sr-sess-b", recordType = "DELIVERED",
                    alarmId = 9996L, triggerTime = now + 180_000L, generation = 1,
                    instanceToken = "d4-v2-token-9996-d4-sess-B", restoreEpoch = epoch,
                    sourcePayloadHash = "h-b", consumerOutcome = "CONSUMED_DELIVERED", completionSequence = 0L, completedAt = epoch + 100L
                )
            )
            val sessMismatchRes = executeStagedD4CleanupContract(ceContext, deContext, db.alarmDao(), db.directBootOccurrenceReceiptDao(), db = db)
            assertTrue("Session mismatch must fail closed", sessMismatchRes.cleanupFailures > 0)
            assertNotNull(db.alarmDao().getById(9996L))
            assertNotNull(lookupCanonicalV1AlarmReceiverPiForTest(9996L))
        } finally {
            db.close()
        }
    }

    @Test
    fun d4_postUnlock_expiredGrant_requiresAdmittedAndReceipt_andRevokedGrantFailsClosed() = runBlocking {
        val now = System.currentTimeMillis()
        val expiredIssued = now - 60 * 60_000L
        val expiredExpiry = expiredIssued + 30 * 60_000L
        val sessionToken = "d4-sess-exp"
        val origTrig1 = expiredIssued + 180_000L
        val db = Room.inMemoryDatabaseBuilder(ceContext, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val d4Item1 = DirectBootQueueItem(kind = DirectBootQueueItem.KIND_NORMAL, alarmId = 9996L, triggerTime = origTrig1, generation = 1, instanceToken = "d4-v2-token-9996-$sessionToken", restoreEpoch = expiredIssued)
            Settings.Global.putInt(ceContext.contentResolver, Settings.Global.BOOT_COUNT, 9)
            db.alarmDao().insert(com.sysadmindoc.alarmclock.data.model.Alarm(9996L, 8, 0, label = "D4 FBE Probe 1", isEnabled = true, nextTriggerTime = origTrig1))

            // 1. Revoked grant while CE row still exists -> MUST fail closed
            writeD4GrantJson(sessionToken, expiredIssued, expiredExpiry, 9, listOf(d4Item1 to true), revoked = true)
            db.directBootOccurrenceReceiptDao().insert(
                com.sysadmindoc.alarmclock.data.local.entity.DirectBootOccurrenceReceipt(
                    occurrenceKey = "r38-exp", sourceRecordId = "sr-exp", recordType = "DELIVERED",
                    alarmId = 9996L, triggerTime = origTrig1, generation = 1,
                    instanceToken = d4Item1.instanceToken, restoreEpoch = expiredIssued,
                    sourcePayloadHash = "h-exp", consumerOutcome = "CONSUMED_DELIVERED", completionSequence = 0L, completedAt = origTrig1 + 50L
                )
            )
            val revokedRes = executeStagedD4CleanupContract(ceContext, deContext, db.alarmDao(), db.directBootOccurrenceReceiptDao(), db = db)
            assertTrue("Revoked grant with lingering CE row must fail closed", revokedRes.cleanupFailures > 0)
            assertNotNull(db.alarmDao().getById(9996L))

            // 2. Expired grant WITHOUT admitted=true -> MUST reject improper deletion
            writeD4GrantJson(sessionToken, expiredIssued, expiredExpiry, 9, listOf(d4Item1 to false), revoked = false)
            val unAdmittedExpiredRes = executeStagedD4CleanupContract(ceContext, deContext, db.alarmDao(), db.directBootOccurrenceReceiptDao(), db = db)
            assertTrue("Expired grant without admitted delivery evidence must reject deletion", unAdmittedExpiredRes.cleanupFailures > 0)
            assertNotNull(db.alarmDao().getById(9996L))

            // 3. Expired grant WITH admitted=true + Room 38 receipt inside valid TTL window -> succeeds
            writeD4GrantJson(sessionToken, expiredIssued, expiredExpiry, 9, listOf(d4Item1 to true), revoked = false)
            val validExpiredRes = executeStagedD4CleanupContract(ceContext, deContext, db.alarmDao(), db.directBootOccurrenceReceiptDao(), db = db)
            assertEquals("Valid post-TTL cleanup with admitted+receipt must succeed", 0, validExpiredRes.cleanupFailures)
            assertNull(db.alarmDao().getById(9996L))
            assertTrue(DirectBootAlarmCache.readD4IsolatedCapability(deContext)!!.revoked)
        } finally {
            db.close()
        }
    }

    @Test
    fun d4_postUnlock_ceIdentityAndScheduleMismatch_failsClosed() = runBlocking {
        val now = System.currentTimeMillis()
        val epoch = now - 5_000L
        val sessionToken = "d4-sess-cemis"
        val origTrig1 = now + 180_000L
        val d4Item1 = DirectBootQueueItem(kind = DirectBootQueueItem.KIND_NORMAL, alarmId = 9996L, triggerTime = origTrig1, generation = 1, instanceToken = "d4-v2-token-9996-$sessionToken", restoreEpoch = epoch)
        val zone = ZoneId.systemDefault()
        val badAlarms = listOf(
            "wrong_hour" to com.sysadmindoc.alarmclock.data.model.Alarm(9996L, 7, 0, label = "D4 FBE Probe 1", isEnabled = true, nextTriggerTime = origTrig1),
            "wrong_minute" to com.sysadmindoc.alarmclock.data.model.Alarm(9996L, 8, 15, label = "D4 FBE Probe 1", isEnabled = true, nextTriggerTime = origTrig1),
            "quick_alarm" to com.sysadmindoc.alarmclock.data.model.Alarm(9996L, 8, 0, label = "D4 FBE Probe 1", isEnabled = true, kind = com.sysadmindoc.alarmclock.data.model.Alarm.KIND_QUICK, nextTriggerTime = origTrig1),
            "wrong_generation" to com.sysadmindoc.alarmclock.data.model.Alarm(9996L, 8, 0, label = "D4 FBE Probe 1", isEnabled = true, generation = 2, nextTriggerTime = origTrig1),
            "non_calendar_rollover" to com.sysadmindoc.alarmclock.data.model.Alarm(9996L, 8, 0, label = "D4 FBE Probe 1", isEnabled = true, nextTriggerTime = origTrig1 + 123_456L),
            "disabled_rollover" to com.sysadmindoc.alarmclock.data.model.Alarm(
                9996L, 8, 0, label = "D4 FBE Probe 1", isEnabled = false,
                nextTriggerTime = ZonedDateTime.ofInstant(Instant.ofEpochMilli(origTrig1), zone).toLocalDate().plusDays(1).atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
            ),
            "far_future_jump_10d" to com.sysadmindoc.alarmclock.data.model.Alarm(
                9996L, 8, 0, label = "D4 FBE Probe 1", isEnabled = true,
                nextTriggerTime = ZonedDateTime.ofInstant(Instant.ofEpochMilli(origTrig1), zone).toLocalDate().plusDays(10).atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
            )
        )

        for ((caseName, badAlarm) in badAlarms) {
            val db = Room.inMemoryDatabaseBuilder(ceContext, AlarmDatabase::class.java)
                .allowMainThreadQueries()
                .build()
            try {
                Settings.Global.putInt(ceContext.contentResolver, Settings.Global.BOOT_COUNT, 9)
                writeD4GrantJson(sessionToken, epoch, epoch + 30 * 60_000L, 9, listOf(d4Item1 to true))
                db.directBootOccurrenceReceiptDao().insert(
                    com.sysadmindoc.alarmclock.data.local.entity.DirectBootOccurrenceReceipt(
                        occurrenceKey = "r38-$caseName", sourceRecordId = "sr-$caseName", recordType = "DELIVERED",
                        alarmId = 9996L, triggerTime = origTrig1, generation = 1,
                        instanceToken = d4Item1.instanceToken, restoreEpoch = epoch,
                        sourcePayloadHash = "h-$caseName", consumerOutcome = "CONSUMED_DELIVERED", completionSequence = 0L, completedAt = epoch + 100L
                    )
                )
                db.alarmDao().insert(badAlarm)
                armCanonicalV1AlarmReceiverPendingIntent(9996L, badAlarm.nextTriggerTime)

                val res = executeStagedD4CleanupContract(ceContext, deContext, db.alarmDao(), db.directBootOccurrenceReceiptDao(), db = db)
                assertTrue("Case $caseName MUST fail closed: ${res.receipts}", res.cleanupFailures > 0)
                assertNotNull("Case $caseName must preserve CE alarm", db.alarmDao().getById(9996L))
                assertNotNull("Case $caseName must NOT cancel V1 PI", lookupCanonicalV1AlarmReceiverPiForTest(9996L))
                assertFalse("Case $caseName must preserve Grant", DirectBootAlarmCache.readD4IsolatedCapability(deContext)!!.revoked)
            } finally {
                db.close()
            }
        }
    }

    @Test
    fun d4_postUnlock_v1PiCancelFailure_blocksCeDeleteAndGrantRevoke() = runBlocking {
        val now = System.currentTimeMillis()
        val epoch = now - 5_000L
        val sessionToken = "d4-sess-v1fail"
        val origTrig1 = now + 180_000L
        val origTrig2 = now + 300_000L
        val db = Room.inMemoryDatabaseBuilder(ceContext, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val d4Item1 = DirectBootQueueItem(kind = DirectBootQueueItem.KIND_NORMAL, alarmId = 9996L, triggerTime = origTrig1, generation = 1, instanceToken = "d4-v2-token-9996-$sessionToken", restoreEpoch = epoch)
            val d4Item2 = DirectBootQueueItem(kind = DirectBootQueueItem.KIND_NORMAL, alarmId = 9999L, triggerTime = origTrig2, generation = 1, instanceToken = "d4-v2-token-9999-$sessionToken", restoreEpoch = epoch)
            Settings.Global.putInt(ceContext.contentResolver, Settings.Global.BOOT_COUNT, 9)
            writeD4GrantJson(sessionToken, epoch, epoch + 30 * 60_000L, 9, listOf(d4Item1 to true, d4Item2 to true))
            db.alarmDao().insertAll(
                listOf(
                    com.sysadmindoc.alarmclock.data.model.Alarm(9996L, 8, 0, label = "D4 FBE Probe 1", isEnabled = true, nextTriggerTime = origTrig1),
                    com.sysadmindoc.alarmclock.data.model.Alarm(9999L, 8, 2, label = "D4 FBE Probe 2", isEnabled = true, nextTriggerTime = origTrig2)
                )
            )
            listOf(d4Item1, d4Item2).forEachIndexed { idx, item ->
                armCanonicalV1AlarmReceiverPendingIntent(item.alarmId, item.triggerTime)
                db.directBootOccurrenceReceiptDao().insert(
                    com.sysadmindoc.alarmclock.data.local.entity.DirectBootOccurrenceReceipt(
                        occurrenceKey = "r38-v1f-${item.alarmId}", sourceRecordId = "sr-v1f-${item.alarmId}", recordType = "DELIVERED",
                        alarmId = item.alarmId, triggerTime = item.triggerTime, generation = 1,
                        instanceToken = item.instanceToken, restoreEpoch = epoch,
                        sourcePayloadHash = "h-v1f-${item.alarmId}", consumerOutcome = "CONSUMED_DELIVERED", completionSequence = idx.toLong(), completedAt = epoch + 100L
                    )
                )
            }

            val spyScheduler = spyk(buildRealTestAlarmScheduler(db))
            every { spyScheduler.applyRawOsCancel(9999L) } returns false

            val res = executeStagedD4CleanupContract(ceContext, deContext, db.alarmDao(), db.directBootOccurrenceReceiptDao(), db = db, alarmScheduler = spyScheduler)
            assertTrue("V1 PI cancel failure on 9999L must fail closed", res.cleanupFailures > 0)
            assertNull("9996L V1 PI was cancelled in step 1 of 3b", lookupCanonicalV1AlarmReceiverPiForTest(9996L))
            assertNotNull("9999L V1 PI remains on failed cancel", lookupCanonicalV1AlarmReceiverPiForTest(9999L))
            assertNotNull("9996L CE row MUST NOT be deleted if Stage 3b fails", db.alarmDao().getById(9996L))
            assertNotNull("9999L CE row MUST NOT be deleted if Stage 3b fails", db.alarmDao().getById(9999L))
            assertFalse("Grant must stay unrevoked", DirectBootAlarmCache.readD4IsolatedCapability(deContext)!!.revoked)
        } finally {
            db.close()
        }
    }

    @Test
    fun d4_postUnlock_grantRevokeFailure_incrementsFailuresAndAllowsRetry() = runBlocking {
        val now = System.currentTimeMillis()
        val epoch = now - 5_000L
        val sessionToken = "d4-sess-revfail"
        val origTrig1 = now + 180_000L
        val db = Room.inMemoryDatabaseBuilder(ceContext, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val d4Item1 = DirectBootQueueItem(kind = DirectBootQueueItem.KIND_NORMAL, alarmId = 9996L, triggerTime = origTrig1, generation = 1, instanceToken = "d4-v2-token-9996-$sessionToken", restoreEpoch = epoch)
            Settings.Global.putInt(ceContext.contentResolver, Settings.Global.BOOT_COUNT, 9)
            writeD4GrantJson(sessionToken, epoch, epoch + 30 * 60_000L, 9, listOf(d4Item1 to true))
            db.alarmDao().insert(com.sysadmindoc.alarmclock.data.model.Alarm(9996L, 8, 0, label = "D4 FBE Probe 1", isEnabled = true, nextTriggerTime = origTrig1))
            db.directBootOccurrenceReceiptDao().insert(
                com.sysadmindoc.alarmclock.data.local.entity.DirectBootOccurrenceReceipt(
                    occurrenceKey = "r38-revf", sourceRecordId = "sr-revf", recordType = "DELIVERED",
                    alarmId = 9996L, triggerTime = origTrig1, generation = 1,
                    instanceToken = d4Item1.instanceToken, restoreEpoch = epoch,
                    sourcePayloadHash = "h-revf", consumerOutcome = "CONSUMED_DELIVERED", completionSequence = 0L, completedAt = epoch + 100L
                )
            )
            var failRevoke = true
            val wrappedDe = wrapContextWithCommitFailure(deContext) { failRevoke }
            val failRes = executeStagedD4CleanupContract(
                ceContext, wrappedDe, db.alarmDao(), db.directBootOccurrenceReceiptDao(), db = db,
                grantRevokeOverride = { ctx -> DirectBootAlarmCache.revokeD4IsolatedCapability(ctx) }
            )
            assertTrue("Stage 4 grant revoke failure must increment cleanupFailures", failRes.cleanupFailures > 0)
            assertFalse("Grant must remain unrevoked after commit failure", DirectBootAlarmCache.readD4IsolatedCapability(deContext)!!.revoked)

            failRevoke = false
            val retryRes = executeStagedD4CleanupContract(ceContext, wrappedDe, db.alarmDao(), db.directBootOccurrenceReceiptDao(), db = db)
            assertEquals("Same-session retry must finish Stage 4 cleanly", 0, retryRes.cleanupFailures)
            assertTrue(DirectBootAlarmCache.readD4IsolatedCapability(deContext)!!.revoked)
        } finally {
            db.close()
        }
    }

    @Test
    fun d4_postUnlock_sameSessionAndPartialCeDeletedRetry_succeedsAndMixedSessionRejected() = runBlocking {
        val now = System.currentTimeMillis()
        val epoch = now - 5_000L
        val sessionToken = "d4-sess-partialce"
        val origTrig1 = now + 180_000L
        val origTrig2 = now + 300_000L
        val db = Room.inMemoryDatabaseBuilder(ceContext, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            seedThree0600NonD4AlarmsAndTwelveLegacyRecords(now, db)
            val d4Item1 = DirectBootQueueItem(kind = DirectBootQueueItem.KIND_NORMAL, alarmId = 9996L, triggerTime = origTrig1, generation = 1, instanceToken = "d4-v2-token-9996-$sessionToken", restoreEpoch = epoch)
            val d4Item2 = DirectBootQueueItem(kind = DirectBootQueueItem.KIND_NORMAL, alarmId = 9999L, triggerTime = origTrig2, generation = 1, instanceToken = "d4-v2-token-9999-$sessionToken", restoreEpoch = epoch)
            Settings.Global.putInt(ceContext.contentResolver, Settings.Global.BOOT_COUNT, 9)
            writeD4GrantJson(sessionToken, epoch, epoch + 30 * 60_000L, 9, listOf(d4Item1 to true, d4Item2 to true))

            // 9996L CE row & V1 PI already absent; V2 PI absent; DE Queue empty; only 9999L CE + V1 PI remain
            db.alarmDao().insert(com.sysadmindoc.alarmclock.data.model.Alarm(9999L, 8, 2, label = "D4 FBE Probe 2", isEnabled = true, nextTriggerTime = origTrig2))
            armCanonicalV1AlarmReceiverPendingIntent(9999L, origTrig2)

            // 1. Mixed-session tampering on 9996L Room 38 receipt -> MUST reject EVEN THOUGH 9996L CE row is null
            db.directBootOccurrenceReceiptDao().insert(
                com.sysadmindoc.alarmclock.data.local.entity.DirectBootOccurrenceReceipt(
                    occurrenceKey = "r38-mix-9996", sourceRecordId = "sr-mix-9996", recordType = "DELIVERED",
                    alarmId = 9996L, triggerTime = origTrig1, generation = 1,
                    instanceToken = "d4-v2-token-9996-d4-sess-other", restoreEpoch = epoch,
                    sourcePayloadHash = "h-mix", consumerOutcome = "CONSUMED_DELIVERED", completionSequence = 0L, completedAt = epoch + 100L
                )
            )
            db.directBootOccurrenceReceiptDao().insert(
                com.sysadmindoc.alarmclock.data.local.entity.DirectBootOccurrenceReceipt(
                    occurrenceKey = "r38-mix-9999", sourceRecordId = "sr-mix-9999", recordType = "DELIVERED",
                    alarmId = 9999L, triggerTime = origTrig2, generation = 1,
                    instanceToken = d4Item2.instanceToken, restoreEpoch = epoch,
                    sourcePayloadHash = "h-mix2", consumerOutcome = "CONSUMED_DELIVERED", completionSequence = 1L, completedAt = epoch + 200L
                )
            )
            val mixedRes = executeStagedD4CleanupContract(ceContext, deContext, db.alarmDao(), db.directBootOccurrenceReceiptDao(), db = db)
            assertTrue("Mixed-session Room 38 receipt must fail closed even when 9996L CE row already deleted", mixedRes.cleanupFailures > 0)
            assertNotNull("9999L must remain untouched when 9996L session fails verification", db.alarmDao().getById(9999L))
            assertNotNull("9999L V1 PI must remain untouched", lookupCanonicalV1AlarmReceiverPiForTest(9999L))
            assertFalse(DirectBootAlarmCache.readD4IsolatedCapability(deContext)!!.revoked)

            // 2. Replace with matching same-session receipt for 9996L -> retry safely completes 9999L & revokes Grant
            DirectBootServiceTestFixture.clearAllDirectBootPrefs(deContext)
            Settings.Global.putInt(ceContext.contentResolver, Settings.Global.BOOT_COUNT, 9)
            writeD4GrantJson(sessionToken, epoch, epoch + 30 * 60_000L, 9, listOf(d4Item1 to true, d4Item2 to true))
            val dbRetry = Room.inMemoryDatabaseBuilder(ceContext, AlarmDatabase::class.java)
                .allowMainThreadQueries()
                .build()
            try {
                seedThree0600NonD4AlarmsAndTwelveLegacyRecords(now, dbRetry)
                dbRetry.alarmDao().insert(com.sysadmindoc.alarmclock.data.model.Alarm(9999L, 8, 2, label = "D4 FBE Probe 2", isEnabled = true, nextTriggerTime = origTrig2))
                armCanonicalV1AlarmReceiverPendingIntent(9999L, origTrig2)
                listOf(d4Item1, d4Item2).forEachIndexed { idx, item ->
                    dbRetry.directBootOccurrenceReceiptDao().insert(
                        com.sysadmindoc.alarmclock.data.local.entity.DirectBootOccurrenceReceipt(
                            occurrenceKey = "r38-ok-${item.alarmId}", sourceRecordId = "sr-ok-${item.alarmId}", recordType = "DELIVERED",
                            alarmId = item.alarmId, triggerTime = item.triggerTime, generation = 1,
                            instanceToken = item.instanceToken, restoreEpoch = epoch,
                            sourcePayloadHash = "h-ok-${item.alarmId}", consumerOutcome = "CONSUMED_DELIVERED", completionSequence = idx.toLong(), completedAt = epoch + 100L
                        )
                    )
                }
                val retryOk = executeStagedD4CleanupContract(ceContext, deContext, dbRetry.alarmDao(), dbRetry.directBootOccurrenceReceiptDao(), db = dbRetry)
                assertEquals("Same-session partial-CE retry must succeed: ${retryOk.receipts}", 0, retryOk.cleanupFailures)
                assertNull(dbRetry.alarmDao().getById(9999L))
                assertNull(lookupCanonicalV1AlarmReceiverPiForTest(9999L))
                assertTrue(DirectBootAlarmCache.readD4IsolatedCapability(deContext)!!.revoked)
                assertThree0600AndTwelveLegacyPreserved(dbRetry)
            } finally {
                dbRetry.close()
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun d4_postUnlock_toctouProtection_serializesWithAlarmScheduler_andFailsClosedOnMidStageMutation() = runBlocking {
        val now = System.currentTimeMillis()
        val epoch = now - 5_000L
        val sessionToken = "d4-sess-toctou"
        val origTrig1 = now + 180_000L
        val origTrig2 = now + 300_000L
        val d4Item1 = DirectBootQueueItem(kind = DirectBootQueueItem.KIND_NORMAL, alarmId = 9996L, triggerTime = origTrig1, generation = 1, instanceToken = "d4-v2-token-9996-$sessionToken", restoreEpoch = epoch)
        val d4Item2 = DirectBootQueueItem(kind = DirectBootQueueItem.KIND_NORMAL, alarmId = 9999L, triggerTime = origTrig2, generation = 1, instanceToken = "d4-v2-token-9999-$sessionToken", restoreEpoch = epoch)

        // Part A: ScheduleAuthorityOwner.withAuthenticatedRecoveryGate serializes Stage 3 vs concurrent rescheduleAllInBatches
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val dbLock = Room.inMemoryDatabaseBuilder(ceContext, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val workManager = mockk<WorkManager>(relaxed = true)
        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(any<Context>()) } returns workManager
        mockkObject(WidgetUpdater)
        every { WidgetUpdater.requestUpdate(any()) } just Runs
        mockkObject(BedtimeZenRuleManager)
        every { BedtimeZenRuleManager.syncRule(any(), any(), any()) } returns mockk(relaxed = true)
        try {
            Settings.Global.putInt(ceContext.contentResolver, Settings.Global.BOOT_COUNT, 9)
            writeD4GrantJson(sessionToken, epoch, epoch + 30 * 60_000L, 9, listOf(d4Item1 to true))
            dbLock.alarmDao().insert(com.sysadmindoc.alarmclock.data.model.Alarm(9996L, 8, 0, label = "D4 FBE Probe 1", isEnabled = true, nextTriggerTime = origTrig1))
            dbLock.directBootOccurrenceReceiptDao().insert(
                com.sysadmindoc.alarmclock.data.local.entity.DirectBootOccurrenceReceipt(
                    occurrenceKey = "r38-lock", sourceRecordId = "sr-lock", recordType = "DELIVERED",
                    alarmId = 9996L, triggerTime = origTrig1, generation = 1,
                    instanceToken = d4Item1.instanceToken, restoreEpoch = epoch,
                    sourcePayloadHash = "h-lock", consumerOutcome = "CONSUMED_DELIVERED", completionSequence = 0L, completedAt = epoch + 100L
                )
            )
            val scheduler = buildRealTestAlarmScheduler(dbLock)
            val events = java.util.Collections.synchronizedList(mutableListOf<String>())
            var concurrentResched: kotlinx.coroutines.Deferred<com.sysadmindoc.alarmclock.domain.ScheduleOutcome.BatchResult>? = null

            val cleanupRes = executeStagedD4CleanupContract(
                ceCtx = ceContext, deCtx = deContext, alarmDao = dbLock.alarmDao(), receiptDao = dbLock.directBootOccurrenceReceiptDao(), db = dbLock, alarmScheduler = scheduler,
                beforeV1CancelHook = {
                    events += "ENTER_STAGE_3B"
                    concurrentResched = async(kotlinx.coroutines.Dispatchers.Default) {
                        scheduler.rescheduleAllInBatches().also {
                            events += "CONCURRENT_RESCHEDULE_FINISHED"
                        }
                    }
                    delay(120L)
                    events += "EXIT_STAGE_3B_HOOK"
                },
                beforeCeDeleteHook = {
                    events += "ENTER_STAGE_3C"
                }
            )
            concurrentResched?.await()
            assertEquals(0, cleanupRes.cleanupFailures)
            val finishIdx = events.indexOf("CONCURRENT_RESCHEDULE_FINISHED")
            val stage3cIdx = events.indexOf("ENTER_STAGE_3C")
            assertTrue("Concurrent AlarmScheduler reschedule MUST wait until Stage 3 releases ScheduleAuthorityOwner gate ($events)", finishIdx > stage3cIdx)
        } finally {
            unmockkAll()
            dbLock.close()
        }

        // Part B: Pre-3b TOCTOU mutation aborts BEFORE V1 cancel or CE delete
        cancelKnownTestPendingIntents()
        DirectBootServiceTestFixture.clearAllDirectBootPrefs(deContext)
        val dbPre3b = Room.inMemoryDatabaseBuilder(ceContext, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            Settings.Global.putInt(ceContext.contentResolver, Settings.Global.BOOT_COUNT, 9)
            writeD4GrantJson(sessionToken, epoch, epoch + 30 * 60_000L, 9, listOf(d4Item1 to true))
            dbPre3b.alarmDao().insert(com.sysadmindoc.alarmclock.data.model.Alarm(9996L, 8, 0, label = "D4 FBE Probe 1", isEnabled = true, nextTriggerTime = origTrig1))
            armCanonicalV1AlarmReceiverPendingIntent(9996L, origTrig1)
            dbPre3b.directBootOccurrenceReceiptDao().insert(
                com.sysadmindoc.alarmclock.data.local.entity.DirectBootOccurrenceReceipt(
                    occurrenceKey = "r38-3b", sourceRecordId = "sr-3b", recordType = "DELIVERED",
                    alarmId = 9996L, triggerTime = origTrig1, generation = 1,
                    instanceToken = d4Item1.instanceToken, restoreEpoch = epoch,
                    sourcePayloadHash = "h-3b", consumerOutcome = "CONSUMED_DELIVERED", completionSequence = 0L, completedAt = epoch + 100L
                )
            )
            val pre3bRes = executeStagedD4CleanupContract(
                ceCtx = ceContext, deCtx = deContext, alarmDao = dbPre3b.alarmDao(), receiptDao = dbPre3b.directBootOccurrenceReceiptDao(), db = dbPre3b,
                beforeV1CancelHook = {
                    dbPre3b.alarmDao().insert(com.sysadmindoc.alarmclock.data.model.Alarm(9996L, 8, 0, label = "Mutated Owner", isEnabled = true, nextTriggerTime = origTrig1))
                }
            )
            assertTrue("Pre-3b mutation must abort fail-closed", pre3bRes.receipts.any { it.startsWith("TOCTOU_PRE_3B_STATE_CHANGED:") })
            assertNotNull("V1 PI must NOT be cancelled after pre-3b TOCTOU abort", lookupCanonicalV1AlarmReceiverPiForTest(9996L))
            assertNotNull("CE row must NOT be deleted", dbPre3b.alarmDao().getById(9996L))
            assertFalse(DirectBootAlarmCache.readD4IsolatedCapability(deContext)!!.revoked)
        } finally {
            dbPre3b.close()
        }

        // Part C: Pre-3c / In-Tx TOCTOU mutation aborts inside SQLite transaction BEFORE CE delete
        cancelKnownTestPendingIntents()
        DirectBootServiceTestFixture.clearAllDirectBootPrefs(deContext)
        val dbPre3c = Room.inMemoryDatabaseBuilder(ceContext, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            Settings.Global.putInt(ceContext.contentResolver, Settings.Global.BOOT_COUNT, 9)
            writeD4GrantJson(sessionToken, epoch, epoch + 30 * 60_000L, 9, listOf(d4Item2 to true))
            dbPre3c.alarmDao().insert(com.sysadmindoc.alarmclock.data.model.Alarm(9999L, 8, 2, label = "D4 FBE Probe 2", isEnabled = true, nextTriggerTime = origTrig2))
            dbPre3c.directBootOccurrenceReceiptDao().insert(
                com.sysadmindoc.alarmclock.data.local.entity.DirectBootOccurrenceReceipt(
                    occurrenceKey = "r38-3c", sourceRecordId = "sr-3c", recordType = "DELIVERED",
                    alarmId = 9999L, triggerTime = origTrig2, generation = 1,
                    instanceToken = d4Item2.instanceToken, restoreEpoch = epoch,
                    sourcePayloadHash = "h-3c", consumerOutcome = "CONSUMED_DELIVERED", completionSequence = 0L, completedAt = epoch + 100L
                )
            )
            val pre3cRes = executeStagedD4CleanupContract(
                ceCtx = ceContext, deCtx = deContext, alarmDao = dbPre3c.alarmDao(), receiptDao = dbPre3c.directBootOccurrenceReceiptDao(), db = dbPre3c,
                beforeCeDeleteHook = {
                    dbPre3c.alarmDao().insert(com.sysadmindoc.alarmclock.data.model.Alarm(9999L, 8, 2, label = "Mutated Before Delete", isEnabled = true, nextTriggerTime = origTrig2))
                }
            )
            assertTrue("Pre-3c in-transaction check must abort fail-closed", pre3cRes.receipts.any { it.contains("TOCTOU_IN_TX_STATE_CHANGED:") })
            assertNotNull("9999L must survive pre-3c TOCTOU abort", dbPre3c.alarmDao().getById(9999L))
            assertFalse(DirectBootAlarmCache.readD4IsolatedCapability(deContext)!!.revoked)
        } finally {
            dbPre3c.close()
        }
    }
}

