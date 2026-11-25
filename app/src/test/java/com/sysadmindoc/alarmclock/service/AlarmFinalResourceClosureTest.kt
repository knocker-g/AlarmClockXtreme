package com.sysadmindoc.alarmclock.service

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.PowerManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.entity.AlarmIncidentEvent
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.data.repository.AlarmIncidentRepository
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.directboot.outputauthority.SharedOutputAuthorityGate
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import com.sysadmindoc.alarmclock.ui.alarmfiring.AlarmFiringActivity
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AlarmFinalResourceClosureTest {

    private lateinit var context: Context
    private lateinit var app: Application

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        app = context.applicationContext as Application
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        AlarmService.activeAlarm.set(null)
        AlarmService.clearUiGuardsForTesting()
        AlarmServiceCallbackPolicy.resetState()
        AlarmFullScreenFallback.clearForTesting()
        clearStartedIntents()
    }

    @After
    fun tearDown() {
        AlarmService.activeAlarm.set(null)
        AlarmService.clearUiGuardsForTesting()
        AlarmServiceCallbackPolicy.resetState()
        AlarmFullScreenFallback.clearForTesting()
        clearStartedIntents()
    }

    private fun clearStartedIntents() {
        val shadowApp = shadowOf(app)
        while (shadowApp.nextStartedActivity != null) {
            // drain
        }
        while (shadowApp.nextStartedService != null) {
            // drain
        }
    }

    private fun buildIsolatedService(
        repo: AlarmRepository,
        incidentRepo: AlarmIncidentRepository,
        pm: PreferencesManager
    ): Pair<org.robolectric.android.controller.ServiceController<AlarmService>, AlarmService> {
        val controller = Robolectric.buildService(AlarmService::class.java)
        val svc = controller.create().get()
        svc.repository = repo
        svc.alarmIncidentRepository = incidentRepo
        svc.preferencesManager = pm
        svc.outputAuthorityGate = SharedOutputAuthorityGate(
            bootCount = 10L,
            processSessionToken = "test-final-resource-closure"
        ).apply { isEnabled = false }
        svc.eventRepository = mockk(relaxed = true)
        svc.webhookService = mockk(relaxed = true)
        svc.wearNextAlarmBridge = mockk(relaxed = true)
        svc.dismissActionExecutor = mockk(relaxed = true)
        svc.weatherRepository = mockk(relaxed = true)
        svc.calendarRepository = mockk(relaxed = true)
        svc.alarmScheduler = mockk(relaxed = true) {
            every { canScheduleExactAlarms() } returns true
        }
        return controller to svc
    }

    private fun getWakeLock(service: AlarmService): PowerManager.WakeLock? {
        val field = AlarmService::class.java.getDeclaredField("wakeLock").apply { isAccessible = true }
        return field.get(service) as? PowerManager.WakeLock
    }

    // =========================================================================
    // PART A: Post-Snooze / Post-Dismiss WakeLock Resource Leak
    // =========================================================================

    @Test
    fun a1_delayedChallengeDuckingAfterSnoozeStopsIdleServiceAndReleasesWakeLock() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries().build()
        val repo = AlarmRepository(db, db.alarmDao(), db.alarmGroupDao())
        val incidentRepo = AlarmIncidentRepository(db.alarmIncidentEventDao(), context)
        val pm = mockk<PreferencesManager>(relaxed = true) {
            every { getCachedSettings() } returns AppSettings()
            coEvery { getCurrentSettings() } returns AppSettings()
            every { isSettingsLoaded } returns true
        }

        val scheduledAt = 1_000_000L
        val fireId = AlarmFireDismissContract.fireId(910L, scheduledAt)
        AlarmService.activeAlarm.set(
            AlarmService.Companion.ActiveAlarmSnapshot(
                alarmId = 910L,
                scheduledAt = scheduledAt,
                fireId = fireId,
                firedAt = scheduledAt,
                state = "SNOOZED",
                refireAt = scheduledAt + 600_000L
            )
        )
        AlarmService.markActionUiClosing(AlarmGeneration(910L, scheduledAt, fireId), "SNOOZED")

        val (controller, service) = buildIsolatedService(repo, incidentRepo, pm)
        try {
            val wakeLockBefore = getWakeLock(service)
            assertNotNull("onCreate should acquire AlarmWakeLock", wakeLockBefore)
            assertTrue("WakeLock should be held after onCreate", wakeLockBefore!!.isHeld)

            val duckingIntent = Intent(context, AlarmService::class.java)
                .setAction(AlarmService.ACTION_SET_CHALLENGE_DUCKING)
                .putExtra(AlarmScheduler.EXTRA_ALARM_ID, 910L)
                .putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, scheduledAt)
                .putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, fireId)
                .putExtra(AlarmService.EXTRA_CHALLENGE_DUCKING_ACTIVE, false)
                .putExtra(AlarmService.EXTRA_CHALLENGE_DUCK_PERCENT, 100)

            service.onStartCommand(duckingIntent, 0, 1)
            ShadowLooper.idleMainLooper()

            assertTrue(
                "Delayed ACTION_SET_CHALLENGE_DUCKING on stopped/idle service MUST stopSelf",
                shadowOf(service).isStoppedBySelf
            )
            assertFalse(
                "AlarmWakeLock MUST be released when idle service rejects delayed ducking after Snooze",
                wakeLockBefore.isHeld
            )
        } finally {
            controller.destroy()
            db.close()
        }
    }

    @Test
    fun a2_delayedChallengeDuckingAfterDismissStopsIdleServiceAndReleasesWakeLock() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries().build()
        val repo = AlarmRepository(db, db.alarmDao(), db.alarmGroupDao())
        val incidentRepo = AlarmIncidentRepository(db.alarmIncidentEventDao(), context)
        val pm = mockk<PreferencesManager>(relaxed = true) {
            every { getCachedSettings() } returns AppSettings()
            coEvery { getCurrentSettings() } returns AppSettings()
            every { isSettingsLoaded } returns true
        }

        val scheduledAt = 1_000_000L
        val fireId = AlarmFireDismissContract.fireId(911L, scheduledAt)
        AlarmService.activeAlarm.set(null)
        AlarmService.markActionUiClosing(AlarmGeneration(911L, scheduledAt, fireId), "DISMISSED")

        val (controller, service) = buildIsolatedService(repo, incidentRepo, pm)
        try {
            val wakeLockBefore = getWakeLock(service)!!
            assertTrue(wakeLockBefore.isHeld)

            val duckingIntent = Intent(context, AlarmService::class.java)
                .setAction(AlarmService.ACTION_SET_CHALLENGE_DUCKING)
                .putExtra(AlarmScheduler.EXTRA_ALARM_ID, 911L)
                .putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, scheduledAt)
                .putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, fireId)
                .putExtra(AlarmService.EXTRA_CHALLENGE_DUCKING_ACTIVE, false)
                .putExtra(AlarmService.EXTRA_CHALLENGE_DUCK_PERCENT, 100)

            service.onStartCommand(duckingIntent, 0, 1)
            ShadowLooper.idleMainLooper()

            assertTrue(
                "Delayed ACTION_SET_CHALLENGE_DUCKING after Dismiss MUST stopSelf",
                shadowOf(service).isStoppedBySelf
            )
            assertFalse(
                "AlarmWakeLock MUST be released when idle service rejects delayed ducking after Dismiss",
                wakeLockBefore.isHeld
            )
        } finally {
            controller.destroy()
            db.close()
        }
    }

    @Test
    fun a3_alarmFiringActivityOnDestroyAfterSnoozeOrDismissDoesNotSendDuckingIntent() {
        val scheduledAt = 1_000_000L
        val fireId = AlarmFireDismissContract.fireId(912L, scheduledAt)
        AlarmService.activeAlarm.set(
            AlarmService.Companion.ActiveAlarmSnapshot(
                alarmId = 912L,
                scheduledAt = scheduledAt,
                fireId = fireId,
                firedAt = scheduledAt,
                state = "FIRING"
            )
        )
        val firingIntent = AlarmFireDismissContract.firingActivityIntent(context, 912L, scheduledAt, fireId)
        val controller = Robolectric.buildActivity(AlarmFiringActivity::class.java, firingIntent).create()
        val activity = controller.get()
        clearStartedIntents()

        // Invoke snooze -> clears/marks closing and finishes
        val snoozeMethod = AlarmFiringActivity::class.java.getDeclaredMethod(
            "snooze",
            Integer::class.java,
            java.lang.Long::class.java
        ).apply { isAccessible = true }
        snoozeMethod.invoke(activity, null, null)
        clearStartedIntents()

        // Destroy activity -> must NOT dispatch ACTION_SET_CHALLENGE_DUCKING
        controller.destroy()
        ShadowLooper.idleMainLooper()

        val startedServices = mutableListOf<Intent>()
        while (true) {
            val next = shadowOf(app).nextStartedService ?: break
            startedServices.add(next)
        }
        assertTrue(
            "AlarmFiringActivity.onDestroy() after Snooze MUST NOT start AlarmService with ACTION_SET_CHALLENGE_DUCKING",
            startedServices.none { it.action == AlarmService.ACTION_SET_CHALLENGE_DUCKING }
        )

        // Also test notification-initiated external dismiss (terminalActionDispatched == false, activeAlarm == null)
        AlarmService.clearUiGuardsForTesting()
        val fireId2 = AlarmFireDismissContract.fireId(913L, scheduledAt)
        AlarmService.activeAlarm.set(
            AlarmService.Companion.ActiveAlarmSnapshot(
                alarmId = 913L,
                scheduledAt = scheduledAt,
                fireId = fireId2,
                firedAt = scheduledAt,
                state = "FIRING"
            )
        )
        val controller2 = Robolectric.buildActivity(
            AlarmFiringActivity::class.java,
            AlarmFireDismissContract.firingActivityIntent(context, 913L, scheduledAt, fireId2)
        ).create()
        clearStartedIntents()

        // External notification dismiss stops AlarmService -> activeAlarm cleared + UI suppressed
        AlarmService.markActionUiClosing(AlarmGeneration(913L, scheduledAt, fireId2), "DISMISSED")
        AlarmService.activeAlarm.set(null)
        controller2.destroy()
        ShadowLooper.idleMainLooper()

        val startedServices2 = mutableListOf<Intent>()
        while (true) {
            val next = shadowOf(app).nextStartedService ?: break
            startedServices2.add(next)
        }
        assertTrue(
            "AlarmFiringActivity.onDestroy() after external Dismiss MUST NOT start AlarmService with ACTION_SET_CHALLENGE_DUCKING",
            startedServices2.none { it.action == AlarmService.ACTION_SET_CHALLENGE_DUCKING }
        )
    }

    @Test
    fun a4_a5_rejectedDuckingForOldOrTerminalGenerationDoesNotInterfereWithNewActiveGeneration() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries().build()
        val repo = AlarmRepository(db, db.alarmDao(), db.alarmGroupDao())
        val incidentRepo = AlarmIncidentRepository(db.alarmIncidentEventDao(), context)
        val pm = mockk<PreferencesManager>(relaxed = true) {
            every { getCachedSettings() } returns AppSettings()
            coEvery { getCurrentSettings() } returns AppSettings()
            every { isSettingsLoaded } returns true
        }
        val alarmB = Alarm(id = 914L, hour = 7, minute = 0, label = "New Gen", isEnabled = true, vibrationEnabled = false)
        repo.save(alarmB)

        val (controller, service) = buildIsolatedService(repo, incidentRepo, pm)
        try {
            val startLatch = CompletableDeferred<Unit>()
            service.testStartAlarmCompletionLatch = startLatch
            val fireIdB = AlarmFireDismissContract.fireId(914L, 2_000_000L)
            service.onStartCommand(
                AlarmFireDismissContract.startServiceIntent(context, 914L, 2_000_000L, fireIdB),
                0,
                1
            )
            withTimeout(5_000L) {
                while (!startLatch.isCompleted) {
                    ShadowLooper.idleMainLooper()
                    kotlinx.coroutines.yield()
                }
            }

            val wakeLock = getWakeLock(service)!!
            assertTrue(wakeLock.isHeld)

            // Send ducking for old genA -> MUST be rejected AND MUST NOT stop service or release genB's WakeLock!
            val oldDuckingIntent = Intent(context, AlarmService::class.java)
                .setAction(AlarmService.ACTION_SET_CHALLENGE_DUCKING)
                .putExtra(AlarmScheduler.EXTRA_ALARM_ID, 900L)
                .putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, 1_000_000L)
                .putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, "fire-900-old")
                .putExtra(AlarmService.EXTRA_CHALLENGE_DUCKING_ACTIVE, true)
                .putExtra(AlarmService.EXTRA_CHALLENGE_DUCK_PERCENT, 25)

            service.onStartCommand(oldDuckingIntent, 0, 2)
            ShadowLooper.idleMainLooper()

            assertFalse(
                "Rejected ducking for old generation MUST NOT stop active genB service",
                shadowOf(service).isStoppedBySelf
            )
            assertTrue(
                "WakeLock MUST remain held for active genB",
                wakeLock.isHeld
            )
            assertEquals(914L, service.currentGeneration()?.alarmId)

            // Also verify terminal/finalized genB rejects ducking even if currentAlarmId still populated
            val genB = service.currentGeneration()!!
            AlarmServiceCallbackPolicy.defaultTracker.markFinalized(genB)
            val duckingField = AlarmService::class.java.getDeclaredField("challengeAudioDuckingActive").apply { isAccessible = true }
            val terminalDuckingIntent = Intent(context, AlarmService::class.java)
                .setAction(AlarmService.ACTION_SET_CHALLENGE_DUCKING)
                .putExtra(AlarmScheduler.EXTRA_ALARM_ID, 914L)
                .putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, 2_000_000L)
                .putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, fireIdB)
                .putExtra(AlarmService.EXTRA_CHALLENGE_DUCKING_ACTIVE, true)
                .putExtra(AlarmService.EXTRA_CHALLENGE_DUCK_PERCENT, 20)

            service.onStartCommand(terminalDuckingIntent, 0, 3)
            assertFalse(
                "Ducking for already-finalized generation MUST be rejected",
                duckingField.getBoolean(service)
            )
        } finally {
            controller.destroy()
            db.close()
        }
    }

    @Test
    fun a6_duplicateStopAndStaleDuckingStartIdAreSafe() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries().build()
        val repo = AlarmRepository(db, db.alarmDao(), db.alarmGroupDao())
        val incidentRepo = AlarmIncidentRepository(db.alarmIncidentEventDao(), context)
        val pm = mockk<PreferencesManager>(relaxed = true) {
            every { getCachedSettings() } returns AppSettings()
            coEvery { getCurrentSettings() } returns AppSettings()
            every { isSettingsLoaded } returns true
        }
        val alarm = Alarm(id = 915L, hour = 7, minute = 0, label = "StartId Test", isEnabled = true, vibrationEnabled = false)
        repo.save(alarm)

        val (controller, service) = buildIsolatedService(repo, incidentRepo, pm)
        try {
            val startLatch = CompletableDeferred<Unit>()
            service.testStartAlarmCompletionLatch = startLatch
            val fireId = AlarmFireDismissContract.fireId(915L, 1_000_000L)
            service.onStartCommand(
                AlarmFireDismissContract.startServiceIntent(context, 915L, 1_000_000L, fireId),
                0,
                2
            )
            withTimeout(5_000L) {
                while (!startLatch.isCompleted) {
                    ShadowLooper.idleMainLooper()
                    kotlinx.coroutines.yield()
                }
            }

            // Duplicate rejected ACTION_SET_CHALLENGE_DUCKING with earlier/stale startIds while gen is active MUST NOT stop or release WakeLock
            val staleDucking = Intent(context, AlarmService::class.java)
                .setAction(AlarmService.ACTION_SET_CHALLENGE_DUCKING)
                .putExtra(AlarmScheduler.EXTRA_ALARM_ID, 915L)
                .putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, 500_000L)
                .putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, "stale-fire")
            service.onStartCommand(staleDucking, 0, 1)
            service.onStartCommand(staleDucking, 0, 3)

            val wakeLock = getWakeLock(service)!!
            assertFalse(shadowOf(service).isStoppedBySelf)
            assertTrue(wakeLock.isHeld)

            // Duplicate destroy / stopSelfResult calls must be idempotent and never throw
            controller.destroy()
            assertFalse(wakeLock.isHeld)
            service.onDestroy()
            assertFalse(wakeLock.isHeld)
        } finally {
            db.close()
        }
    }

    @Test
    fun a7_wakeLockIsReleasedEvenWhenStopPlaybackThrowsExceptionDuringDestroy() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries().build()
        val repo = AlarmRepository(db, db.alarmDao(), db.alarmGroupDao())
        val incidentRepo = AlarmIncidentRepository(db.alarmIncidentEventDao(), context)
        val pm = mockk<PreferencesManager>(relaxed = true) {
            every { getCachedSettings() } returns AppSettings()
            coEvery { getCurrentSettings() } returns AppSettings()
            every { isSettingsLoaded } returns true
        }

        val (controller, service) = buildIsolatedService(repo, incidentRepo, pm)
        try {
            val wakeLock = getWakeLock(service)!!
            assertTrue(wakeLock.isHeld)

            // Inject a vibrator whose cancel() throws RuntimeException during stopAlarmPlayback()
            val badVibrator = mockk<android.os.Vibrator> {
                every { cancel() } throws RuntimeException("Simulated OEM vibrator failure on destroy")
            }
            val vibField = AlarmService::class.java.getDeclaredField("vibrator").apply { isAccessible = true }
            vibField.set(service, badVibrator)

            var caught: Throwable? = null
            try {
                controller.destroy()
            } catch (t: Throwable) {
                caught = t
            }
            // Regardless of whether the inner exception propagated or was handled, WakeLock MUST be released!
            assertFalse(
                "AlarmWakeLock MUST be released even if teardown throws an exception ($caught)",
                wakeLock.isHeld
            )
        } finally {
            db.close()
        }
    }

    // =========================================================================
    // PART B: Audio Safety Audit & Minimum Fix
    // =========================================================================

    @Test
    fun b1_media3FallbackWithOverrideFalseAndBackupFalseKeepsLowStreamAlarmVolumeUntouched() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries().build()
        val repo = AlarmRepository(db, db.alarmDao(), db.alarmGroupDao())
        val incidentRepo = AlarmIncidentRepository(db.alarmIncidentEventDao(), context)
        val pm = mockk<PreferencesManager>(relaxed = true) {
            every { getCachedSettings() } returns AppSettings()
            coEvery { getCurrentSettings() } returns AppSettings()
            every { isSettingsLoaded } returns true
        }

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM).coerceAtLeast(7)
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, 1, 0)
        assertEquals(1, audioManager.getStreamVolume(AudioManager.STREAM_ALARM))

        val alarm = Alarm(
            id = 920L,
            hour = 7,
            minute = 0,
            label = "Low Vol Test",
            isEnabled = true,
            overrideSystemVolume = false,
            backupSoundEnabled = false,
            volume = 100,
            vibrationEnabled = false
        )
        repo.save(alarm)

        val (controller, service) = buildIsolatedService(repo, incidentRepo, pm)
        try {
            val gen = AlarmGeneration(920L, 1_000_000L, "fire-920")
            AlarmService::class.java.getDeclaredField("currentAlarmId").apply { isAccessible = true }.setLong(service, 920L)
            AlarmService::class.java.getDeclaredField("currentScheduledAt").apply { isAccessible = true }.setLong(service, 1_000_000L)
            AlarmService::class.java.getDeclaredField("currentFireId").apply { isAccessible = true }.set(service, "fire-920")

            val authorityField = AlarmService::class.java.getDeclaredField("playbackAuthority").apply { isAccessible = true }
            val authority = authorityField.get(service) as AlarmPlaybackAttemptAuthority
            val token1 = authority.mintNextToken(gen)
            val handoff = authority.tryClaimFallbackHandoff(gen, token1)!!

            val escalateMethod = AlarmService::class.java.getDeclaredMethod(
                "escalateMedia3PlaybackFailure",
                Alarm::class.java,
                PlaybackAttemptToken::class.java
            ).apply { isAccessible = true }
            escalateMethod.invoke(service, alarm, handoff)
            ShadowLooper.idleMainLooper()

            val volAfterFallback = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
            assertEquals(
                "Media3 fallback with overrideSystemVolume=false & backupSoundEnabled=false MUST NOT raise low STREAM_ALARM=$volAfterFallback to max=$maxVol",
                1,
                volAfterFallback
            )
        } finally {
            controller.destroy()
            db.close()
        }
    }

    @Test
    fun b2_media3FallbackWithOverrideTrueAndLowVolumeSetsScaledLowVolumeAndRestoresOriginalOnStop() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries().build()
        val repo = AlarmRepository(db, db.alarmDao(), db.alarmGroupDao())
        val incidentRepo = AlarmIncidentRepository(db.alarmIncidentEventDao(), context)
        val pm = mockk<PreferencesManager>(relaxed = true) {
            every { getCachedSettings() } returns AppSettings()
            coEvery { getCurrentSettings() } returns AppSettings()
            every { isSettingsLoaded } returns true
        }

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM).coerceAtLeast(7)
        val originalVol = (maxVol / 2).coerceAtLeast(2)
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, originalVol, 0)

        val alarm = Alarm(
            id = 921L,
            hour = 7,
            minute = 0,
            label = "Low Override Vol",
            isEnabled = true,
            overrideSystemVolume = true,
            backupSoundEnabled = false,
            volume = 15,
            vibrationEnabled = false
        )
        repo.save(alarm)

        val (controller, service) = buildIsolatedService(repo, incidentRepo, pm)
        try {
            val gen = AlarmGeneration(921L, 1_000_000L, "fire-921")
            AlarmService::class.java.getDeclaredField("currentAlarmId").apply { isAccessible = true }.setLong(service, 921L)
            AlarmService::class.java.getDeclaredField("currentScheduledAt").apply { isAccessible = true }.setLong(service, 1_000_000L)
            AlarmService::class.java.getDeclaredField("currentFireId").apply { isAccessible = true }.set(service, "fire-921")

            val authorityField = AlarmService::class.java.getDeclaredField("playbackAuthority").apply { isAccessible = true }
            val authority = authorityField.get(service) as AlarmPlaybackAttemptAuthority
            val token1 = authority.mintNextToken(gen)
            val handoff = authority.tryClaimFallbackHandoff(gen, token1)!!

            val escalateMethod = AlarmService::class.java.getDeclaredMethod(
                "escalateMedia3PlaybackFailure",
                Alarm::class.java,
                PlaybackAttemptToken::class.java
            ).apply { isAccessible = true }
            escalateMethod.invoke(service, alarm, handoff)
            ShadowLooper.idleMainLooper()

            val expectedLowVol = (maxVol * 15 / 100f).toInt().coerceIn(1, maxVol)
            val volAfterFallback = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
            assertEquals(
                "Media3 fallback with overrideSystemVolume=true, volume=15 MUST set scaled low volume (never maxVol=$maxVol)",
                expectedLowVol,
                volAfterFallback
            )

            // Teardown for same generation must restore original device STREAM_ALARM volume
            val stopPlaybackMethod = AlarmService::class.java.getDeclaredMethod(
                "stopAlarmPlayback",
                AlarmGeneration::class.java
            ).apply { isAccessible = true }
            stopPlaybackMethod.invoke(service, gen)

            assertEquals(
                "Teardown MUST restore pre-alarm STREAM_ALARM volume",
                originalVol,
                audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
            )
        } finally {
            controller.destroy()
            db.close()
        }
    }

    @Test
    fun b3_overrideSystemVolumeRestoresOriginalVolumeOnTeardownEvenWithoutFallbackOrBackupSound() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries().build()
        val repo = AlarmRepository(db, db.alarmDao(), db.alarmGroupDao())
        val incidentRepo = AlarmIncidentRepository(db.alarmIncidentEventDao(), context)
        val pm = mockk<PreferencesManager>(relaxed = true) {
            every { getCachedSettings() } returns AppSettings()
            coEvery { getCurrentSettings() } returns AppSettings()
            every { isSettingsLoaded } returns true
        }

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM).coerceAtLeast(7)
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, 1, 0)

        val alarm = Alarm(
            id = 922L,
            hour = 7,
            minute = 0,
            label = "Restore Original Vol",
            isEnabled = true,
            overrideSystemVolume = true,
            backupSoundEnabled = false,
            volume = 80,
            vibrationEnabled = false
        )

        val (controller, service) = buildIsolatedService(repo, incidentRepo, pm)
        try {
            val gen = AlarmGeneration(922L, 1_000_000L, "fire-922")
            AlarmService::class.java.getDeclaredField("currentAlarmId").apply { isAccessible = true }.setLong(service, 922L)
            AlarmService::class.java.getDeclaredField("currentScheduledAt").apply { isAccessible = true }.setLong(service, 1_000_000L)
            AlarmService::class.java.getDeclaredField("currentFireId").apply { isAccessible = true }.set(service, "fire-922")

            val setConfiguredMethod = AlarmService::class.java.getDeclaredMethod(
                "setConfiguredAlarmStreamVolume",
                Alarm::class.java
            ).apply { isAccessible = true }
            setConfiguredMethod.invoke(service, alarm)

            val expectedVol = (maxVol * 80 / 100f).toInt().coerceIn(1, maxVol)
            assertEquals(expectedVol, audioManager.getStreamVolume(AudioManager.STREAM_ALARM))

            val stopPlaybackMethod = AlarmService::class.java.getDeclaredMethod(
                "stopAlarmPlayback",
                AlarmGeneration::class.java
            ).apply { isAccessible = true }
            stopPlaybackMethod.invoke(service, gen)

            assertEquals(
                "Pre-alarm STREAM_ALARM volume=1 MUST be restored after overrideSystemVolume=true teardown",
                1,
                audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
            )
        } finally {
            controller.destroy()
            db.close()
        }
    }

    @Test
    fun b4_backupSoundDisabledSkipsBackupSoundJobCompletely() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries().build()
        val repo = AlarmRepository(db, db.alarmDao(), db.alarmGroupDao())
        val incidentRepo = AlarmIncidentRepository(db.alarmIncidentEventDao(), context)
        val pm = mockk<PreferencesManager>(relaxed = true) {
            every { getCachedSettings() } returns AppSettings()
            coEvery { getCurrentSettings() } returns AppSettings()
            every { isSettingsLoaded } returns true
        }

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, 1, 0)

        val alarm = Alarm(
            id = 923L,
            hour = 7,
            minute = 0,
            label = "No Backup Escalation",
            isEnabled = true,
            overrideSystemVolume = false,
            backupSoundEnabled = false,
            backupSoundDelaySec = 0,
            ringtoneUri = "silent",
            vibrationEnabled = false
        )
        repo.save(alarm)

        val (controller, service) = buildIsolatedService(repo, incidentRepo, pm)
        try {
            val startLatch = CompletableDeferred<Unit>()
            service.testStartAlarmCompletionLatch = startLatch
            val fireId = AlarmFireDismissContract.fireId(923L, 1_000_000L)
            service.onStartCommand(
                AlarmFireDismissContract.startServiceIntent(context, 923L, 1_000_000L, fireId),
                0,
                1
            )
            withTimeout(5_000L) {
                while (!startLatch.isCompleted) {
                    ShadowLooper.idleMainLooper()
                    kotlinx.coroutines.yield()
                }
            }

            val backupJobField = AlarmService::class.java.getDeclaredField("backupSoundJob").apply { isAccessible = true }
            assertNull("backupSoundJob MUST be null when backupSoundEnabled=false", backupJobField.get(service))
            assertEquals(1, audioManager.getStreamVolume(AudioManager.STREAM_ALARM))
            val incidents = incidentRepo.getRecent()
            assertTrue(
                "BACKUP_SOUND_ESCALATED must never be recorded when backupSoundEnabled=false",
                incidents.none { it.reasonCode == "BACKUP_SOUND_ESCALATED" }
            )
        } finally {
            controller.destroy()
            db.close()
        }
    }

    @Test
    fun a8_unknownOrNullActionOnIdleServiceStopsSelfAndReleasesWakeLock() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries().build()
        val repo = AlarmRepository(db, db.alarmDao(), db.alarmGroupDao())
        val incidentRepo = AlarmIncidentRepository(db.alarmIncidentEventDao(), context)
        val pm = mockk<PreferencesManager>(relaxed = true) {
            every { getCachedSettings() } returns AppSettings()
            coEvery { getCurrentSettings() } returns AppSettings()
            every { isSettingsLoaded } returns true
        }

        val (controller, service) = buildIsolatedService(repo, incidentRepo, pm)
        try {
            val wakeLock = getWakeLock(service)!!
            assertTrue(wakeLock.isHeld)
            service.onStartCommand(null, 0, 1)
            assertTrue(shadowOf(service).isStoppedBySelf)
            assertFalse(wakeLock.isHeld)
        } finally {
            controller.destroy()
            db.close()
        }
    }

    @Test
    fun b5_lowAlarmVolumePlayerGainAndDefaultFallbackKeepVolumeLow() {
        assertEquals(
            0.15f,
            alarmPlaybackGain(
                callMuted = false,
                challengeDuckingActive = false,
                challengeDuckPercent = 35,
                rampGain = 1f,
                alarmVolume = 15
            ),
            0.0001f
        )
        assertEquals(
            0.0525f,
            alarmPlaybackGain(
                callMuted = false,
                challengeDuckingActive = true,
                challengeDuckPercent = 35,
                rampGain = 1f,
                alarmVolume = 15
            ),
            0.0001f
        )
    }
}
