package com.sysadmindoc.alarmclock.service

import android.Manifest
import android.app.Application
import android.app.KeyguardManager
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.AlarmClockApp
import com.sysadmindoc.alarmclock.MainActivity
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.entity.AlarmIncidentEvent
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.data.repository.AlarmEventRepository
import com.sysadmindoc.alarmclock.data.repository.AlarmIncidentRepository
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.data.repository.WeatherRepository
import com.sysadmindoc.alarmclock.directboot.outputauthority.SharedOutputAuthorityGate
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import com.sysadmindoc.alarmclock.ui.alarmfiring.AlarmFiringActivity
import com.sysadmindoc.alarmclock.ui.alarmfiring.AlarmFiringViewModel
import com.sysadmindoc.alarmclock.ui.alarmfiring.challenges.DigitalInkChallengeRecognizer
import dagger.hilt.android.EntryPointAccessors
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
class AlarmRealOsSafetyCorrectionTest {

    private lateinit var context: Context
    private lateinit var app: Application

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        app = context.applicationContext as Application
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        AlarmService.activeAlarm.set(null)
        AlarmServiceCallbackPolicy.defaultTracker.resetState()
        AlarmFullScreenFallback.clearForTesting()
        clearStartedIntents()
    }

    @After
    fun tearDown() {
        AlarmService.activeAlarm.set(null)
        AlarmServiceCallbackPolicy.defaultTracker.resetState()
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
            processSessionToken = "test-realos-safety"
        ).apply { isEnabled = false }
        svc.eventRepository = mockk(relaxed = true)
        svc.webhookService = mockk(relaxed = true)
        svc.wearNextAlarmBridge = mockk(relaxed = true)
        svc.dismissActionExecutor = mockk(relaxed = true)
        svc.weatherRepository = mockk(relaxed = true)
        svc.calendarRepository = mockk(relaxed = true)
        return controller to svc
    }

    // -------------------------------------------------------------------------
    // CORRECTION A: BAL_BLOCK / Full-Screen Intent
    // -------------------------------------------------------------------------

    @Test
    fun a1_initialForegroundPlaceholderNotificationHasValidOccurrenceFsiAndSkipsBackgroundDirectStartActivity() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries().build()
        val repo = AlarmRepository(db, db.alarmDao(), db.alarmGroupDao())
        val incidentRepo = AlarmIncidentRepository(db.alarmIncidentEventDao(), context)
        val pm = mockk<PreferencesManager>(relaxed = true) {
            every { getCachedSettings() } returns AppSettings()
            coEvery { getCurrentSettings() } returns AppSettings()
            every { isSettingsLoaded } returns true
        }
        val alarm = Alarm(id = 701L, hour = 7, minute = 0, label = "AOD Alarm", isEnabled = true, vibrationEnabled = false)
        repo.save(alarm)

        val powerManager = context.getSystemService(PowerManager::class.java)
        val keyguardManager = context.getSystemService(KeyguardManager::class.java)
        shadowOf(powerManager).setIsInteractive(false)
        shadowOf(keyguardManager).setKeyguardLocked(true)

        val (controller, service) = buildIsolatedService(repo, incidentRepo, pm)
        try {
            val startLatch = CompletableDeferred<Unit>()
            service.testStartAlarmCompletionLatch = startLatch
            val fireId = AlarmFireDismissContract.fireId(701L, 1_000_000L)
            val startIntent = AlarmFireDismissContract.startServiceIntent(
                context = context,
                alarmId = 701L,
                scheduledAt = 1_000_000L,
                fireId = fireId
            )

            service.onStartCommand(startIntent, 0, 1)

            // Synchronous placeholder notification MUST already carry valid occurrence FSI!
            val placeholderNotif: Notification? = shadowOf(service).lastForegroundNotification
            assertNotNull("Initial startForeground notification must exist", placeholderNotif)
            assertNotNull(
                "A1: Initial startForeground placeholder notification MUST set fullScreenIntent",
                placeholderNotif?.fullScreenIntent
            )
            assertNotNull(
                "A1: Initial startForeground placeholder notification MUST set contentIntent",
                placeholderNotif?.contentIntent
            )
            val fsiSavedIntent = shadowOf(placeholderNotif!!.fullScreenIntent).savedIntent
            assertEquals(AlarmFiringActivity::class.java.name, fsiSavedIntent.component?.className)
            assertEquals(701L, fsiSavedIntent.getLongExtra(AlarmScheduler.EXTRA_ALARM_ID, -1L))
            assertEquals(1_000_000L, fsiSavedIntent.getLongExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, -1L))
            assertEquals(fireId, fsiSavedIntent.getStringExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID))

            withTimeout(5_000L) {
                while (!startLatch.isCompleted) {
                    ShadowLooper.idleMainLooper()
                    kotlinx.coroutines.yield()
                }
            }

            // Locked / non-interactive background service MUST NOT call raw startActivity() (causes BAL_BLOCK)
            val directActivity = shadowOf(app).nextStartedActivity
            assertNull(
                "A1: Locked/background AlarmService must NOT invoke raw startActivity() (BAL_BLOCK)",
                directActivity
            )
        } finally {
            controller.destroy()
            db.close()
        }
    }

    @Test
    fun a2_twoOccurrencesOfSameAlarmDoNotCollideFullScreenPendingIntents() = runBlocking {
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
            val buildNotifMethod = AlarmService::class.java.getDeclaredMethod("buildAlarmNotification", Alarm::class.java).apply {
                isAccessible = true
            }
            val currentScheduledAtField = AlarmService::class.java.getDeclaredField("currentScheduledAt").apply { isAccessible = true }
            val currentFireIdField = AlarmService::class.java.getDeclaredField("currentFireId").apply { isAccessible = true }

            val alarm = Alarm(id = 702L, hour = 7, minute = 0, label = "Collision Check", isEnabled = true)

            currentScheduledAtField.setLong(service, 1_000_000L)
            currentFireIdField.set(service, "fire-702-occ1")
            val n1 = buildNotifMethod.invoke(service, alarm) as Notification

            currentScheduledAtField.setLong(service, 2_000_000L)
            currentFireIdField.set(service, "fire-702-occ2")
            val n2 = buildNotifMethod.invoke(service, alarm) as Notification

            val pi1 = n1.fullScreenIntent!!
            val pi2 = n2.fullScreenIntent!!
            assertNotEquals(
                "A2: Distinct occurrences of the same alarmId must produce distinct FSI PendingIntents",
                pi1,
                pi2
            )
            val intent1 = shadowOf(pi1).savedIntent
            val intent2 = shadowOf(pi2).savedIntent
            assertEquals(1_000_000L, intent1.getLongExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, -1L))
            assertEquals("fire-702-occ1", intent1.getStringExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID))
            assertEquals(2_000_000L, intent2.getLongExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, -1L))
            assertEquals("fire-702-occ2", intent2.getStringExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID))
        } finally {
            controller.destroy()
            db.close()
        }
    }

    @Test
    fun a3_staleOrSnoozedOccurrenceFsiLaunchIsRejectedBeforeLockscreenOcclusion() {
        AlarmService.activeAlarm.set(
            AlarmService.Companion.ActiveAlarmSnapshot(
                alarmId = 703L,
                scheduledAt = 2_000_000L,
                fireId = "fire-703-new",
                firedAt = 2_000_000L,
                state = "FIRING"
            )
        )
        val staleIntent = AlarmFireDismissContract.firingActivityIntent(
            context = context,
            alarmId = 703L,
            scheduledAt = 1_000_000L,
            fireId = "fire-703-old"
        )

        val controller = Robolectric.buildActivity(AlarmFiringActivity::class.java, staleIntent)
        val activity = controller.create().get()
        try {
            assertTrue(
                "A3/A6: Stale occurrence FSI intent must be rejected synchronously in AlarmFiringActivity.onCreate",
                activity.isFinishing
            )
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun a4_a5_fsiPermissionDeniedRecordsUnreachableIncidentAndKeepsNotification() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries().build()
        val repo = AlarmRepository(db, db.alarmDao(), db.alarmGroupDao())
        val incidentRepo = AlarmIncidentRepository(db.alarmIncidentEventDao(), context)
        val pm = mockk<PreferencesManager>(relaxed = true) {
            every { getCachedSettings() } returns AppSettings()
            coEvery { getCurrentSettings() } returns AppSettings()
            every { isSettingsLoaded } returns true
        }
        val alarm = Alarm(id = 704L, hour = 7, minute = 15, label = "Denied FSI", isEnabled = true, vibrationEnabled = false)
        repo.save(alarm)

        // Deny POST_NOTIFICATIONS so FSI / notification readiness reports unreachable
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        val (controller, service) = buildIsolatedService(repo, incidentRepo, pm)
        try {
            val startLatch = CompletableDeferred<Unit>()
            service.testStartAlarmCompletionLatch = startLatch
            val fireId = AlarmFireDismissContract.fireId(704L, 1_000_000L)
            service.onStartCommand(
                AlarmFireDismissContract.startServiceIntent(context, 704L, 1_000_000L, fireId),
                0,
                1
            )

            withTimeout(5_000L) {
                while (!startLatch.isCompleted) {
                    ShadowLooper.idleMainLooper()
                    kotlinx.coroutines.yield()
                }
            }

            // Notification + active session must still remain active
            assertNotNull(shadowOf(service).lastForegroundNotification)
            assertEquals(704L, AlarmService.activeAlarm.get()?.alarmId)

            var unreachableIncident: AlarmIncidentEvent? = null
            withTimeout(5_000L) {
                while (unreachableIncident == null) {
                    ShadowLooper.idleMainLooper()
                    unreachableIncident = incidentRepo.getRecent().firstOrNull {
                        it.alarmId == 704L &&
                            it.type == AlarmIncidentEvent.TYPE_ACTIVITY_LAUNCH &&
                            it.status == AlarmIncidentEvent.STATUS_FAILED &&
                            it.reasonCode.startsWith("FIRING_UI_UNREACHABLE_")
                    }
                    if (unreachableIncident == null) kotlinx.coroutines.delay(10)
                }
            }
            assertNotNull("A5: Permission-denied state must explicitly record FIRING_UI_UNREACHABLE_*", unreachableIncident)
        } finally {
            controller.destroy()
            db.close()
        }
    }

    // -------------------------------------------------------------------------
    // CORRECTION B: DISMISS_UI_RACE / Snooze Mis-transition
    // -------------------------------------------------------------------------

    @Test
    fun b1_dismissThenMainActivityResumeDoesNotRelaunchFiringActivity() {
        val entryPoint = EntryPointAccessors.fromApplication(app, AlarmClockApp.AppEntryPoint::class.java)
        val fireId = AlarmFireDismissContract.fireId(801L, 1_000_000L)
        val genA = AlarmGeneration(801L, 1_000_000L, fireId)
        val snapshot = AlarmService.Companion.ActiveAlarmSnapshot(
            alarmId = 801L,
            scheduledAt = 1_000_000L,
            fireId = fireId,
            firedAt = 1_000_000L,
            state = "FIRING"
        )
        runBlocking {
            entryPoint.alarmRepository().save(
                Alarm(id = 801L, hour = 7, minute = 0, label = "Dismiss Race", isEnabled = true, nextTriggerTime = 1_000_000L)
            )
            val ack = CompletableDeferred<PreferencesManager.ActiveAlarmUpdateResult>()
            entryPoint.preferencesManager().enqueueActiveAlarmUpdate(snapshot, genA, ack)
            ack.await()
        }
        AlarmService.activeAlarm.set(snapshot)

        val firingIntent = AlarmFireDismissContract.firingActivityIntent(context, 801L, 1_000_000L, fireId)
        val firingController = Robolectric.buildActivity(AlarmFiringActivity::class.java, firingIntent).create()
        val firingActivity = firingController.get()
        try {
            clearStartedIntents()
            val dismissMethod = AlarmFiringActivity::class.java.getDeclaredMethod("dismiss").apply { isAccessible = true }
            dismissMethod.invoke(firingActivity)
            clearStartedIntents()

            // Even BEFORE AlarmService completes async DataStore clear (activeAlarm still non-null in race window),
            // MainActivity.onResume() MUST NOT relaunch AlarmFiringActivity!
            val mainController = Robolectric.buildActivity(MainActivity::class.java).setup().resume()
            try {
                ShadowLooper.idleMainLooper()
                val launched = shadowOf(app).nextStartedActivity
                assertNotEquals(
                    "B1/B3: MainActivity.onResume must not relaunch AlarmFiringActivity after user dismiss",
                    AlarmFiringActivity::class.java.name,
                    launched?.component?.className
                )
            } finally {
                mainController.destroy()
            }
        } finally {
            firingController.destroy()
            runBlocking {
                val ack = CompletableDeferred<PreferencesManager.ActiveAlarmUpdateResult>()
                entryPoint.preferencesManager().enqueueActiveAlarmUpdate(null, genA, ack)
                ack.await()
            }
        }
    }

    @Test
    fun b2_snoozeThenMainActivityResumeDoesNotRelaunchZombieUiAndRejectsStaleDismiss() = runBlocking {
        val entryPoint = EntryPointAccessors.fromApplication(app, AlarmClockApp.AppEntryPoint::class.java)
        val scheduledAt = 1_000_000L
        val refireAt = System.currentTimeMillis() + 600_000L
        val fireId = AlarmFireDismissContract.fireId(802L, scheduledAt)
        val genA = AlarmGeneration(802L, scheduledAt, fireId)
        val firingSnapshot = AlarmService.Companion.ActiveAlarmSnapshot(
            alarmId = 802L,
            scheduledAt = scheduledAt,
            fireId = fireId,
            firedAt = scheduledAt,
            state = "FIRING"
        )
        val snoozeSnapshot = firingSnapshot.copy(state = "SNOOZED", refireAt = refireAt)

        entryPoint.alarmRepository().save(
            Alarm(id = 802L, hour = 7, minute = 30, label = "Snooze Race", isEnabled = true, nextTriggerTime = refireAt)
        )
        val ack1 = CompletableDeferred<PreferencesManager.ActiveAlarmUpdateResult>()
        entryPoint.preferencesManager().enqueueActiveAlarmUpdate(firingSnapshot, genA, ack1)
        ack1.await()
        val ack2 = CompletableDeferred<PreferencesManager.ActiveAlarmUpdateResult>()
        entryPoint.preferencesManager().enqueueActiveAlarmUpdate(snoozeSnapshot, genA, ack2)
        assertEquals(PreferencesManager.ActiveAlarmUpdateResult.COMMITTED, ack2.await())
        AlarmService.activeAlarm.set(snoozeSnapshot)

        val mainController = Robolectric.buildActivity(MainActivity::class.java).setup().resume()
        try {
            ShadowLooper.idleMainLooper()
            val launched = shadowOf(app).nextStartedActivity
            assertNotEquals(
                "B2/B3: MainActivity.onResume must not launch AlarmFiringActivity when state == SNOOZED",
                AlarmFiringActivity::class.java.name,
                launched?.component?.className
            )
            assertEquals("SNOOZED", entryPoint.preferencesManager().getCurrentSettings().activeAlarmState)

            // Also verify stale ACTION_DISMISS for the already-snoozed generation does NOT cancel snooze!
            val serviceController = Robolectric.buildService(AlarmService::class.java)
            val service = serviceController.create().get()
            try {
                service.preferencesManager = entryPoint.preferencesManager()
                service.repository = entryPoint.alarmRepository()
                service.onStartCommand(
                    AlarmFireDismissContract.dismissServiceIntent(context, 802L, scheduledAt, fireId),
                    0,
                    1
                )
                ShadowLooper.idleMainLooper()
                kotlinx.coroutines.delay(100)
                ShadowLooper.idleMainLooper()
                val settingsAfter = entryPoint.preferencesManager().getCurrentSettings()
                assertEquals(
                    "B2/B6: Stale ACTION_DISMISS must not cancel an already-SNOOZED generation",
                    "SNOOZED",
                    settingsAfter.activeAlarmState
                )
                assertEquals(802L, settingsAfter.activeAlarmId)
            } finally {
                serviceController.destroy()
            }
        } finally {
            mainController.destroy()
            val ackClear = CompletableDeferred<PreferencesManager.ActiveAlarmUpdateResult>()
            entryPoint.preferencesManager().enqueueActiveAlarmUpdate(null, genA, ackClear)
            ackClear.await()
        }
    }

    @Test
    fun b3_terminalOrNonFiringStateRejectsZombieFiringActivityAndViewModel() = runTest(StandardTestDispatcher()) {
        val dispatcher = coroutineContext[kotlin.coroutines.ContinuationInterceptor] as kotlinx.coroutines.CoroutineDispatcher
        Dispatchers.setMain(dispatcher)
        try {
            // Part 1: AlarmFiringActivity launched with SNOOZED state must finish immediately
            val snoozedActivityIntent = AlarmFireDismissContract.firingActivityIntent(
                context = context,
                alarmId = 803L,
                scheduledAt = 1_000_000L,
                fireId = "fire-803"
            ).putExtra(AlarmService.EXTRA_ALARM_STATE, "SNOOZED")
            val actController = Robolectric.buildActivity(AlarmFiringActivity::class.java, snoozedActivityIntent)
            val activity = actController.create().get()
            assertTrue("B3: SNOOZED state must finish AlarmFiringActivity immediately", activity.isFinishing)
            actController.destroy()

            // Part 2: AlarmFiringViewModel must NOT treat existing DB row alone as active firing!
            val repo = mockk<AlarmRepository>(relaxed = true)
            coEvery { repo.getById(803L) } returns Alarm(
                id = 803L,
                hour = 8,
                minute = 0,
                label = "Repeating Alarm",
                isEnabled = true,
                nextTriggerTime = 9_999_999L
            )
            val pm = mockk<PreferencesManager>(relaxed = true) {
                every { settings } returns flowOf(AppSettings())
                coEvery { getCurrentSettings() } returns AppSettings()
                every { getCachedSettings() } returns AppSettings()
            }
            val vm = AlarmFiringViewModel(
                appContext = context,
                savedStateHandle = SavedStateHandle(
                    mapOf(
                        AlarmScheduler.EXTRA_ALARM_ID to 803L,
                        AlarmScheduler.EXTRA_SCHEDULED_AT to 1_000_000L,
                        AlarmScheduler.EXTRA_ALARM_FIRE_ID to "fire-803"
                    )
                ),
                repository = repo,
                eventRepository = mockk<AlarmEventRepository>(relaxed = true),
                preferencesManager = pm,
                weatherRepository = mockk<WeatherRepository>(relaxed = true),
                digitalInkChallengeRecognizer = mockk<DigitalInkChallengeRecognizer>(relaxed = true)
            )
            var finishEmitted = false
            val collectJob = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                vm.finishEvents.first()
                finishEmitted = true
            }
            advanceUntilIdle()
            ShadowLooper.idleMainLooper()
            collectJob.cancel()
            assertTrue(
                "B4: AlarmFiringViewModel must emit finish when no active firing session exists even if DB row exists",
                finishEmitted
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun b4_asyncClearOfOldOccurrencePreservesNewOccurrence() = runBlocking {
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
            val genOld = AlarmGeneration(804L, 1_000_000L, "fire-804-old")
            val genNew = AlarmGeneration(805L, 2_000_000L, "fire-805-new")
            val snapNew = AlarmService.Companion.ActiveAlarmSnapshot(
                alarmId = 805L,
                scheduledAt = 2_000_000L,
                fireId = "fire-805-new",
                firedAt = 2_000_000L,
                state = "FIRING"
            )
            AlarmService.activeAlarm.set(snapNew)

            val updateMethod = AlarmService::class.java.getDeclaredMethod(
                "updateActiveAlarm",
                AlarmService.Companion.ActiveAlarmSnapshot::class.java,
                AlarmGeneration::class.java,
                CompletableDeferred::class.java
            ).apply { isAccessible = true }
            updateMethod.invoke(service, null, genOld, null)

            val after = AlarmService.activeAlarm.get()
            assertNotNull("B5: Async clear of A(old) must NOT clear C(new) in memory", after)
            assertEquals(805L, after?.alarmId)
            assertEquals("fire-805-new", after?.fireId)
        } finally {
            controller.destroy()
            db.close()
        }
    }

    @Test
    fun b5_duplicateUserActionOnFiringActivityDispatchesOnlyOnce() {
        val fireId = AlarmFireDismissContract.fireId(806L, 1_000_000L)
        AlarmService.activeAlarm.set(
            AlarmService.Companion.ActiveAlarmSnapshot(
                alarmId = 806L,
                scheduledAt = 1_000_000L,
                fireId = fireId,
                firedAt = 1_000_000L,
                state = "FIRING"
            )
        )
        val firingIntent = AlarmFireDismissContract.firingActivityIntent(context, 806L, 1_000_000L, fireId)
        val controller = Robolectric.buildActivity(AlarmFiringActivity::class.java, firingIntent).create()
        val activity = controller.get()
        try {
            clearStartedIntents()
            val dismissMethod = AlarmFiringActivity::class.java.getDeclaredMethod("dismiss").apply { isAccessible = true }
            dismissMethod.invoke(activity)
            dismissMethod.invoke(activity)

            var serviceStartCount = 0
            while (shadowOf(app).nextStartedService != null) {
                serviceStartCount++
            }
            assertEquals(
                "B7: Duplicate dismiss taps must dispatch only 1 startForegroundService(ACTION_DISMISS)",
                1,
                serviceStartCount
            )
        } finally {
            controller.destroy()
        }
    }
}
