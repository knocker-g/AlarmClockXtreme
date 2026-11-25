package com.sysadmindoc.alarmclock.worker

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.UserManager
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.sysadmindoc.alarmclock.R
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthorityMeta
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.data.repository.AlarmIncidentRepository
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.data.repository.HolidayRepository
import com.sysadmindoc.alarmclock.data.repository.WeatherRepository
import com.sysadmindoc.alarmclock.directboot.DirectBootAlarmCache
import com.sysadmindoc.alarmclock.directboot.DirectBootAlarmReceiver
import com.sysadmindoc.alarmclock.directboot.DirectBootAlarmSnapshot
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import com.sysadmindoc.alarmclock.domain.NextAlarmCalculator
import com.sysadmindoc.alarmclock.domain.OwnerWorkIdentity
import com.sysadmindoc.alarmclock.domain.ScheduleOutcome
import com.sysadmindoc.alarmclock.domain.SupportingWorkType
import com.sysadmindoc.alarmclock.integration.hue.HueBridgeClient
import com.sysadmindoc.alarmclock.integration.hue.HuePinResult
import com.sysadmindoc.alarmclock.integration.hue.HueTofuClient
import com.sysadmindoc.alarmclock.integration.hue.HueTofuTrustManager
import com.sysadmindoc.alarmclock.integration.hue.HueTrustStore
import com.sysadmindoc.alarmclock.integration.hue.HueV2ProbeResult
import com.sysadmindoc.alarmclock.receiver.AlarmReceiver
import com.sysadmindoc.alarmclock.service.AlarmService
import com.sysadmindoc.alarmclock.service.BedtimeZenRuleManager
import com.sysadmindoc.alarmclock.service.NextAlarmNotifier
import com.sysadmindoc.alarmclock.service.SmartAlarmService
import com.sysadmindoc.alarmclock.ui.alarmedit.AlarmEditUiState
import com.sysadmindoc.alarmclock.ui.alarmedit.AlarmEditViewModel
import com.sysadmindoc.alarmclock.ui.alarmedit.AlarmEditorCategoryOverview
import com.sysadmindoc.alarmclock.ui.alarmedit.AlarmEditorPage
import com.sysadmindoc.alarmclock.ui.alarmedit.alarmEditIntegrationSections
import com.sysadmindoc.alarmclock.widget.WidgetUpdater
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowLog
import java.util.Locale
import java.util.TimeZone

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en", application = Application::class)
class HueSunrisePersonalIsolationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var db: AlarmDatabase
    private lateinit var repository: AlarmRepository
    private lateinit var calculator: NextAlarmCalculator
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var holidayRepository: HolidayRepository
    private lateinit var incidentRepository: AlarmIncidentRepository
    private lateinit var weatherRepository: WeatherRepository
    private lateinit var workManager: WorkManager
    private lateinit var scheduler: AlarmScheduler
    private var originalTimeZone: TimeZone? = null
    private var originalLocale: Locale? = null

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
        } catch (_: Exception) {
        }

        context = ApplicationProvider.getApplicationContext()
        originalTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        val config = android.content.res.Configuration(context.resources.configuration)
        config.setLocale(Locale.US)
        context.resources.updateConfiguration(config, context.resources.displayMetrics)

        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(context.getSystemService(UserManager::class.java)).setUserUnlocked(true)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        ShadowLog.clear()

        db = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        runBlocking {
            db.scheduleAuthorityDao().insertMeta(
                ScheduleAuthorityMeta(id = 1, restoreEpoch = 10L, bootstrapState = "INITIALIZED")
            )
        }

        repository = AlarmRepository(
            dao = db.alarmDao(),
            groupDao = db.alarmGroupDao(),
            database = db
        )
        calculator = mockk()
        preferencesManager = mockk(relaxed = true)
        holidayRepository = mockk()
        incidentRepository = AlarmIncidentRepository(db.alarmIncidentEventDao(), context)
        weatherRepository = mockk(relaxed = true)
        workManager = mockk(relaxed = true)

        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(any<Context>()) } returns workManager
        every { workManager.cancelUniqueWork(any()) } returns mockk<Operation>(relaxed = true)
        every { workManager.cancelAllWorkByTag(any()) } returns mockk<Operation>(relaxed = true)
        every { workManager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>()) } returns mockk<Operation>(relaxed = true)

        mockkObject(WidgetUpdater)
        every { WidgetUpdater.requestUpdate(any()) } just Runs

        mockkObject(BedtimeZenRuleManager)
        every { BedtimeZenRuleManager.syncRule(any(), any(), any()) } returns mockk(relaxed = true)

        val defaultSettings = AppSettings(
            repeatMissedAlarms = true,
            hueBridgeIp = "192.168.1.50",
            hueApiKey = "test-api-key-123456",
            hueLightIds = "1,2",
            hueBridgeCertFingerprint = "ab".repeat(32)
        )
        coEvery { preferencesManager.getCurrentSettings() } returns defaultSettings
        every { preferencesManager.getCachedSettings() } returns defaultSettings
        coEvery { preferencesManager.settings } returns flowOf(defaultSettings)
        coEvery { holidayRepository.isHoliday(any()) } returns false

        DirectBootAlarmCache.clear(context)
        context.getSharedPreferences("alarm_scheduler_armed_slots", Context.MODE_PRIVATE).edit().clear().commit()

        scheduler = AlarmScheduler(
            context = context,
            repository = repository,
            calculator = calculator,
            preferencesManager = preferencesManager,
            holidayRepository = holidayRepository,
            alarmIncidentRepository = incidentRepository,
            weatherRepository = weatherRepository
        )
    }

    @After
    fun tearDown() {
        try {
            composeTestRule.waitForIdle()
        } catch (_: Exception) {
        }
        try {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
        } catch (_: Exception) {
        }
        DirectBootAlarmCache.clear(context)
        context.getSharedPreferences("alarm_scheduler_armed_slots", Context.MODE_PRIVATE).edit().clear().commit()
        unmockkAll()
        db.close()
        originalTimeZone?.let { TimeZone.setDefault(it) }
        originalLocale?.let {
            Locale.setDefault(it)
            val config = android.content.res.Configuration(context.resources.configuration)
            config.setLocale(it)
            context.resources.updateConfiguration(config, context.resources.displayMetrics)
        }
        Dispatchers.resetMain()
    }

    private fun lookupMainPendingIntent(alarmId: Long): PendingIntent? {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = "com.sysadmindoc.alarmclock.ALARM_FIRE"
        }
        return PendingIntent.getBroadcast(
            context,
            alarmId.toInt(),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun lookupSmartPendingIntent(alarmId: Long): PendingIntent? {
        val smartIntent = Intent(context, SmartAlarmService::class.java).apply {
            action = SmartAlarmService.ACTION_START_SMART
        }
        return PendingIntent.getForegroundService(
            context,
            (alarmId + 50000).toInt(),
            smartIntent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildHueBridgeClientMock(): HueBridgeClient {
        val client = mockk<HueBridgeClient>(relaxed = true)
        coEvery { client.probeV2(any(), any(), any(), any()) } returns HueV2ProbeResult.Reachable("ab".repeat(32))
        every { client.buildTofuClient(any()) } returns HueTofuClient(
            client = OkHttpClient(),
            trustManager = HueTofuTrustManager("ab".repeat(32))
        )
        return client
    }

    private fun buildHueTrustStoreMock(): HueTrustStore {
        val trustStore = mockk<HueTrustStore>(relaxed = true)
        coEvery { trustStore.rememberFirstUse(any()) } returns HuePinResult.Accepted("ab".repeat(32), newlyPinned = false)
        return trustStore
    }

    @Test
    fun `H01 Hue Sunrise UI is hidden in Integrations and excluded from Overview integrationCount`() {
        val state = AlarmEditUiState(
            hueEnabled = true,
            huePreWakeMinutes = 30
        )
        val mockViewModel = mockk<AlarmEditViewModel>(relaxed = true)

        composeTestRule.setContent {
            LazyColumn(modifier = Modifier.testTag("hue_isolation_list")) {
                alarmEditIntegrationSections(
                    editorPage = AlarmEditorPage.INTEGRATIONS,
                    state = state,
                    viewModel = mockViewModel,
                    hasSmsPermission = true,
                    onRequestGuardianSmsPermission = {}
                )
                item(key = "overview") {
                    AlarmEditorCategoryOverview(
                        state = state,
                        onSelect = {}
                    )
                }
            }
        }

        composeTestRule.onNodeWithText(context.getString(R.string.alarm_edit_hue_sunrise)).assertDoesNotExist()
        composeTestRule.onNodeWithText(context.getString(R.string.alarm_edit_hue_start)).assertDoesNotExist()
        composeTestRule.onNodeWithText(context.getString(R.string.alarm_edit_hue_hint)).assertDoesNotExist()
        composeTestRule.onNodeWithTag("hue_isolation_list").performScrollToKey("overview")
        composeTestRule.onNodeWithText(context.getString(R.string.alarm_edit_hue_sunrise)).assertDoesNotExist()
        composeTestRule.onNodeWithText(context.getString(R.string.alarm_edit_no_integrations))
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(
            context.resources.getQuantityString(R.plurals.alarm_edit_active_integrations, 1, 1)
        ).assertDoesNotExist()
    }

    @Test
    fun `H02 AlarmEditViewModel save preserves persisted hueEnabled and huePreWakeMinutes without scheduling Hue work`() = runBlocking {
        val alarmId = 4201L
        val triggerTime = System.currentTimeMillis() + 3_600_000L
        val initialAlarm = Alarm(
            id = alarmId,
            hour = 7,
            minute = 0,
            label = "Original",
            isEnabled = true,
            hueEnabled = true,
            huePreWakeMinutes = 45
        )
        repository.save(initialAlarm)
        every { calculator.calculate(any<Alarm>()) } returns triggerTime
        every { calculator.calculate(any<Alarm>(), any()) } returns triggerTime

        val viewModel = AlarmEditViewModel(
            savedStateHandle = SavedStateHandle(mapOf("alarmId" to alarmId)),
            context = context,
            repository = repository,
            scheduler = scheduler,
            calculator = calculator,
            preferencesManager = preferencesManager
        )

        val loaded = viewModel.uiState.first { it.isEditing }
        assertTrue(loaded.hueEnabled)
        assertEquals(45, loaded.huePreWakeMinutes)

        viewModel.updateLabel("Updated Work")
        viewModel.updateTime(7, 30)

        val savedDeferred = CompletableDeferred<Boolean>()
        viewModel.save(onComplete = { savedDeferred.complete(true) })
        assertTrue("Save callback must complete", savedDeferred.await())

        val persisted = repository.getById(alarmId)
        assertNotNull(persisted)
        assertEquals("Updated Work", persisted!!.label)
        assertTrue("Persisted hueEnabled MUST be preserved non-destructively", persisted.hueEnabled)
        assertEquals("Persisted huePreWakeMinutes MUST be preserved non-destructively", 45, persisted.huePreWakeMinutes)

        verify(exactly = 0) {
            workManager.enqueueUniqueWork(HueSunriseWorker.uniqueName(alarmId), any(), any<OneTimeWorkRequest>())
        }

        viewModel.viewModelScope.cancel()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `H03 scheduling hueEnabled alarm enqueues zero Hue work while preserving Main PI DirectBoot and FireWatchdog`() = runBlocking {
        val alarmId = 4202L
        val triggerTime = System.currentTimeMillis() + 3_600_000L
        val alarm = Alarm(
            id = alarmId,
            hour = 7,
            minute = 0,
            isEnabled = true,
            hueEnabled = true,
            huePreWakeMinutes = 30
        )
        repository.save(alarm)
        every { calculator.calculate(any<Alarm>()) } returns triggerTime
        every { calculator.calculate(any<Alarm>(), any()) } returns triggerTime

        val outcome = scheduler.scheduleAt(alarm, triggerTime, requestWidgetUpdate = false)

        assertTrue("Main alarm MUST arm cleanly", outcome is ScheduleOutcome.Armed)
        verify(exactly = 0) {
            workManager.enqueueUniqueWork(HueSunriseWorker.uniqueName(alarmId), any(), any<OneTimeWorkRequest>())
        }
        verify(atLeast = 1) {
            workManager.cancelUniqueWork(HueSunriseWorker.uniqueName(alarmId))
        }
        assertNotNull("Main PendingIntent MUST exist", lookupMainPendingIntent(alarmId))
        assertEquals(triggerTime, DirectBootAlarmCache.read(context)?.triggerTime)
        verify(exactly = 1) {
            workManager.enqueueUniqueWork(FireWatchdogWorker.uniqueName(alarmId), ExistingWorkPolicy.REPLACE, any<OneTimeWorkRequest>())
        }
    }

    @Test
    fun `H04 reschedule requests cancellation of legacy Hue work without enqueuing new work and preserves Main PI`() = runBlocking {
        val alarmId = 4203L
        val triggerTime = System.currentTimeMillis() + 3_600_000L
        val alarm = Alarm(
            id = alarmId,
            hour = 8,
            minute = 0,
            isEnabled = true,
            nextTriggerTime = triggerTime,
            hueEnabled = true,
            huePreWakeMinutes = 20
        )
        repository.save(alarm)
        every { calculator.calculate(any<Alarm>()) } returns triggerTime
        every { calculator.calculate(any<Alarm>(), any()) } returns triggerTime

        val batch = scheduler.rescheduleAllInBatches(forceRecalculate = false)

        assertEquals(1, batch.armedCount)
        assertEquals(0, batch.failedCount)
        verify(exactly = 0) {
            workManager.enqueueUniqueWork(HueSunriseWorker.uniqueName(alarmId), any(), any<OneTimeWorkRequest>())
        }
        verify(atLeast = 1) {
            workManager.cancelUniqueWork(HueSunriseWorker.uniqueName(alarmId))
        }
        assertNotNull("Main PendingIntent MUST remain intact", lookupMainPendingIntent(alarmId))
        assertEquals(triggerTime, DirectBootAlarmCache.read(context)?.triggerTime)
    }

    @Test
    fun `H05 boot and restore reschedule never re-enqueues Hue Sunrise work when restoreEpoch advances`() = runBlocking {
        val alarmId = 4204L
        val triggerTime = System.currentTimeMillis() + 3_600_000L
        db.scheduleAuthorityDao().insertMeta(
            ScheduleAuthorityMeta(id = 1, restoreEpoch = 25L, bootstrapState = "INITIALIZED")
        )
        repository.save(
            Alarm(
                id = alarmId,
                hour = 6,
                minute = 45,
                isEnabled = true,
                nextTriggerTime = triggerTime,
                hueEnabled = true,
                huePreWakeMinutes = 30
            )
        )
        every { calculator.calculate(any<Alarm>()) } returns triggerTime
        every { calculator.calculate(any<Alarm>(), any()) } returns triggerTime

        val batch = scheduler.rescheduleAllInBatches(forceRecalculate = false)

        assertEquals(1, batch.armedCount)
        assertEquals(0, batch.failedCount)
        verify(exactly = 0) {
            workManager.enqueueUniqueWork(HueSunriseWorker.uniqueName(alarmId), any(), any<OneTimeWorkRequest>())
        }
        verify(atLeast = 1) {
            workManager.cancelUniqueWork(HueSunriseWorker.uniqueName(alarmId))
        }
        verify(exactly = 1) {
            workManager.enqueueUniqueWork(FireWatchdogWorker.uniqueName(alarmId), ExistingWorkPolicy.REPLACE, any<OneTimeWorkRequest>())
        }
        assertNotNull(lookupMainPendingIntent(alarmId))
    }

    @Test
    fun `H06 direct HueSunriseWorker doWork fails closed with zero bridge probe HTTP TOFU or notification post`() = runBlocking {
        val alarmId = 4205L
        val now = System.currentTimeMillis()
        val rampEnd = now + 1_800_000L
        repository.save(
            Alarm(
                id = alarmId,
                hour = 7,
                minute = 0,
                isEnabled = true,
                nextTriggerTime = rampEnd,
                hueEnabled = true,
                huePreWakeMinutes = 30,
                generation = 1
            )
        )

        val bridgeClient = buildHueBridgeClientMock()
        val trustStore = buildHueTrustStoreMock()
        val wp = mockk<WorkerParameters>(relaxed = true)
        every { wp.inputData } returns workDataOf(
            HueSunriseWorker.KEY_ALARM_ID to alarmId,
            HueSunriseWorker.KEY_RAMP_START to (now - 60_000L),
            HueSunriseWorker.KEY_RAMP_END to rampEnd,
            HueSunriseWorker.KEY_EXPECTED_GENERATION to 1,
            HueSunriseWorker.KEY_EXPECTED_RESTORE_EPOCH to 10L
        )

        val result = HueSunriseWorker(
            context = context,
            workerParams = wp,
            repository = repository,
            preferencesManager = preferencesManager,
            hueBridgeClient = bridgeClient,
            hueTrustStore = trustStore
        ).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 0) { bridgeClient.probeV2(any(), any(), any(), any()) }
        verify(exactly = 0) { bridgeClient.buildTofuClient(any()) }
        coVerify(exactly = 0) { trustStore.rememberFirstUse(any()) }
        verify(exactly = 0) {
            workManager.enqueueUniqueWork(HueSunriseWorker.uniqueName(alarmId), ExistingWorkPolicy.APPEND, any<OneTimeWorkRequest>())
        }

        val nm = context.getSystemService(NotificationManager::class.java)
        val postedHue = shadowOf(nm).getNotification(HueSunriseNotifications.notificationId(alarmId))
        assertNull("HueSunriseWorker MUST post 0 notifications", postedHue)
    }

    @Test
    fun `H07 pre-queued APPEND continuation segment fails closed with zero HTTPS and zero further APPEND`() = runBlocking {
        val alarmId = 4206L
        val now = System.currentTimeMillis()
        val rampStart = now - 6 * 60_000L
        val rampEnd = now + 24 * 60_000L
        repository.save(
            Alarm(
                id = alarmId,
                hour = 7,
                minute = 0,
                isEnabled = true,
                nextTriggerTime = rampEnd,
                hueEnabled = true,
                huePreWakeMinutes = 30,
                generation = 1
            )
        )

        val bridgeClient = buildHueBridgeClientMock()
        val trustStore = buildHueTrustStoreMock()
        val wp = mockk<WorkerParameters>(relaxed = true)
        every { wp.inputData } returns workDataOf(
            HueSunriseWorker.KEY_ALARM_ID to alarmId,
            HueSunriseWorker.KEY_RAMP_START to rampStart,
            HueSunriseWorker.KEY_RAMP_END to rampEnd,
            HueSunriseWorker.KEY_EXPECTED_GENERATION to 1,
            HueSunriseWorker.KEY_EXPECTED_RESTORE_EPOCH to 10L
        )

        val result = HueSunriseWorker(
            context = context,
            workerParams = wp,
            repository = repository,
            preferencesManager = preferencesManager,
            hueBridgeClient = bridgeClient,
            hueTrustStore = trustStore
        ).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 0) { bridgeClient.probeV2(any(), any(), any(), any()) }
        verify(exactly = 0) { bridgeClient.buildTofuClient(any()) }
        coVerify(exactly = 0) { trustStore.rememberFirstUse(any()) }
        verify(exactly = 0) {
            workManager.enqueueUniqueWork(HueSunriseWorker.uniqueName(alarmId), ExistingWorkPolicy.APPEND, any<OneTimeWorkRequest>())
        }
        val nm = context.getSystemService(NotificationManager::class.java)
        assertNull(shadowOf(nm).getNotification(HueSunriseNotifications.notificationId(alarmId)))
    }

    @Test
    fun `H08 notification ID collision 42 vs 100042 never cancels another alarm Hue Main DirectBoot or WakeConfirm notification`() = runBlocking {
        val nm = context.getSystemService(NotificationManager::class.java)
        val dummyNotif = Notification.Builder(context, "test")
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle("Test")
            .build()

        val mainId = AlarmService.NOTIFICATION_ID // 1001
        val missedId = AlarmService.MISSED_NOTIFICATION_ID // 1003
        val directBootId = 1011
        val wakeConfirmId = WakeConfirmWorker.notificationId(42L, 0L)
        val nextAlarmId = NextAlarmNotifier.NOTIFICATION_ID_PERSISTENT // 2004
        val collidingHueId = HueSunriseNotifications.notificationId(42L) // 800_042 == notificationId(100_042L)

        nm.notify(mainId, dummyNotif)
        nm.notify(missedId, dummyNotif)
        nm.notify(directBootId, dummyNotif)
        nm.notify(wakeConfirmId, dummyNotif)
        nm.notify(nextAlarmId, dummyNotif)
        nm.notify(collidingHueId, dummyNotif)

        val bridgeClient = buildHueBridgeClientMock()
        val trustStore = buildHueTrustStoreMock()

        // 1. Worker runs for colliding alarmId 100_042L (same modulo 100_000 bucket as 42L)
        val wpColliding = mockk<WorkerParameters>(relaxed = true)
        every { wpColliding.inputData } returns workDataOf(HueSunriseWorker.KEY_ALARM_ID to 100_042L)
        val resColliding = HueSunriseWorker(
            context = context,
            workerParams = wpColliding,
            repository = repository,
            preferencesManager = preferencesManager,
            hueBridgeClient = bridgeClient,
            hueTrustStore = trustStore
        ).doWork()
        assertEquals(ListenableWorker.Result.success(), resColliding)

        // 2. Worker runs with invalid negative alarmId = -1L
        val wpNegative = mockk<WorkerParameters>(relaxed = true)
        every { wpNegative.inputData } returns workDataOf(HueSunriseWorker.KEY_ALARM_ID to -1L)
        val resNegative = HueSunriseWorker(
            context = context,
            workerParams = wpNegative,
            repository = repository,
            preferencesManager = preferencesManager,
            hueBridgeClient = bridgeClient,
            hueTrustStore = trustStore
        ).doWork()
        assertEquals(ListenableWorker.Result.success(), resNegative)

        // 3. Scheduling colliding alarm 100_042L also must not clear alarm 42L's notification
        val triggerTime = System.currentTimeMillis() + 3_600_000L
        val alarm100042 = Alarm(id = 100_042L, hour = 7, minute = 0, isEnabled = true, hueEnabled = true)
        repository.save(alarm100042)
        every { calculator.calculate(any<Alarm>()) } returns triggerTime
        every { calculator.calculate(any<Alarm>(), any()) } returns triggerTime
        scheduler.scheduleAt(alarm100042, triggerTime, requestWidgetUpdate = false)

        val shadowNm = shadowOf(nm)
        assertNotNull("Main notification 1001 MUST remain intact", shadowNm.getNotification(mainId))
        assertNotNull("Missed notification 1003 MUST remain intact", shadowNm.getNotification(missedId))
        assertNotNull("DirectBoot notification 1011 MUST remain intact", shadowNm.getNotification(directBootId))
        assertNotNull("WakeConfirm notification MUST remain intact", shadowNm.getNotification(wakeConfirmId))
        assertNotNull("NextAlarm notification 2004 MUST remain intact", shadowNm.getNotification(nextAlarmId))
        assertNotNull(
            "Colliding Hue notification for alarm 42L MUST NOT be falsely cancelled by 100_042L",
            shadowNm.getNotification(collidingHueId)
        )
    }

    @Test
    fun `H09 WorkManager cancel exception preserves Main PI and FireWatchdog reports cancel failure truthfully and Worker fails closed`() = runBlocking {
        val alarmId = 4207L
        val triggerTime = System.currentTimeMillis() + 3_600_000L
        val alarm = Alarm(
            id = alarmId,
            hour = 7,
            minute = 0,
            isEnabled = true,
            nextTriggerTime = triggerTime,
            hueEnabled = true,
            huePreWakeMinutes = 30
        )
        repository.save(alarm)
        every { calculator.calculate(any<Alarm>()) } returns triggerTime
        every { calculator.calculate(any<Alarm>(), any()) } returns triggerTime
        every {
            workManager.cancelUniqueWork(HueSunriseWorker.uniqueName(alarmId))
        } throws IllegalStateException("Simulated WorkManager cancelUniqueWork failure")

        // (a) scheduleAt must continue to FireWatchdog & return Armed for Main alarm, logging Log.e
        val outcome = scheduler.scheduleAt(alarm, triggerTime, requestWidgetUpdate = false)
        assertTrue("Main alarm MUST arm even when Hue cancel throws", outcome is ScheduleOutcome.Armed)
        assertNotNull("Main PendingIntent MUST exist", lookupMainPendingIntent(alarmId))
        verify(exactly = 1) {
            workManager.enqueueUniqueWork(FireWatchdogWorker.uniqueName(alarmId), ExistingWorkPolicy.REPLACE, any<OneTimeWorkRequest>())
        }
        assertTrue(
            "Hue cancel failure MUST be logged with Log.e",
            ShadowLog.getLogsForTag("AlarmScheduler").any {
                it.type == android.util.Log.ERROR && it.msg.contains(alarmId.toString())
            }
        )

        // (b) Explicit cancel must NOT fake true and must NOT abort sibling FireWatchdog/WakeConfirm cancels
        ShadowLog.clear()
        val cancelResult = scheduler.cancelScheduledEntriesWithResult(alarmId, includeFollowUpWorkers = true)
        assertFalse("cancelScheduledEntriesWithResult MUST NOT mask Hue cancel exception as true", cancelResult)
        verify(exactly = 1) { workManager.cancelUniqueWork(FireWatchdogWorker.uniqueName(alarmId)) }
        verify(exactly = 1) {
            workManager.cancelUniqueWork(OwnerWorkIdentity.legacyUniqueName(alarmId, SupportingWorkType.GUARDIAN))
        }
        verify(exactly = 1) { workManager.cancelUniqueWork("wake_confirm_$alarmId") }
        assertTrue(
            "Explicit cancel Hue failure MUST be logged with Log.e",
            ShadowLog.getLogsForTag("AlarmScheduler").any {
                it.type == android.util.Log.ERROR && it.msg.contains(alarmId.toString())
            }
        )

        // (c) Independent fallback defense: uncancelled HueSunriseWorker still fails closed with 0 HTTPS
        val bridgeClient = buildHueBridgeClientMock()
        val trustStore = buildHueTrustStoreMock()
        val wp = mockk<WorkerParameters>(relaxed = true)
        every { wp.inputData } returns workDataOf(HueSunriseWorker.KEY_ALARM_ID to alarmId)
        val workerRes = HueSunriseWorker(
            context = context,
            workerParams = wp,
            repository = repository,
            preferencesManager = preferencesManager,
            hueBridgeClient = bridgeClient,
            hueTrustStore = trustStore
        ).doWork()
        assertEquals(ListenableWorker.Result.success(), workerRes)
        coVerify(exactly = 0) { bridgeClient.probeV2(any(), any(), any(), any()) }
        verify(exactly = 0) { bridgeClient.buildTofuClient(any()) }
    }

    @Test
    fun `H10 Smart isolation and Guardian scheduling containment remain preserved alongside Hue retirement`() = runBlocking {
        val alarmId = 4208L
        val triggerTime = System.currentTimeMillis() + 3_600_000L
        val alarm = Alarm(
            id = alarmId,
            hour = 7,
            minute = 0,
            isEnabled = true,
            hueEnabled = true,
            huePreWakeMinutes = 30,
            smartAlarmEnabled = true,
            smartAlarmWindowMinutes = 20,
            guardianEnabled = true,
            guardianPhone = "+15551234567"
        )
        repository.save(alarm)
        every { calculator.calculate(any<Alarm>()) } returns triggerTime
        every { calculator.calculate(any<Alarm>(), any()) } returns triggerTime

        val outcome = scheduler.scheduleAt(alarm, triggerTime, requestWidgetUpdate = false)

        assertTrue(outcome is ScheduleOutcome.Armed)
        assertNull("Smart PI MUST remain retired in Personal", lookupSmartPendingIntent(alarmId))
        verify(exactly = 0) {
            workManager.enqueueUniqueWork(HueSunriseWorker.uniqueName(alarmId), any(), any<OneTimeWorkRequest>())
        }
        verify(exactly = 0) {
            workManager.cancelUniqueWork(OwnerWorkIdentity.legacyUniqueName(alarmId, SupportingWorkType.GUARDIAN))
        }
        verify(exactly = 0) {
            workManager.cancelAllWorkByTag(GuardianWorker.alarmTag(alarmId))
        }
    }

    @Test
    fun `H11 DirectBoot normal path remains intact with zero HueBridgeClient invocations across lifecycle`() = runBlocking {
        val alarmId = 4209L
        val triggerTime = System.currentTimeMillis() + 3_600_000L
        val alarm = Alarm(
            id = alarmId,
            hour = 7,
            minute = 15,
            isEnabled = true,
            nextTriggerTime = triggerTime,
            hueEnabled = true,
            huePreWakeMinutes = 30
        )
        repository.save(alarm)
        every { calculator.calculate(any<Alarm>()) } returns triggerTime
        every { calculator.calculate(any<Alarm>(), any()) } returns triggerTime

        val outcome = scheduler.scheduleAt(alarm, triggerTime, requestWidgetUpdate = false)
        assertTrue(outcome is ScheduleOutcome.Armed)

        val snapshot = DirectBootAlarmCache.read(context)
        assertNotNull("DirectBoot snapshot MUST be populated", snapshot)
        assertEquals(alarmId, snapshot!!.alarmId)
        assertEquals(triggerTime, snapshot.triggerTime)

        assertTrue(DirectBootAlarmCache.scheduleCachedAlarm(context, triggerTime - 1_800_000L))
        val directBootPi = DirectBootAlarmReceiver.pendingIntent(
            context = context,
            snapshot = DirectBootAlarmSnapshot(
                alarmId = alarmId,
                triggerTime = triggerTime,
                label = "",
                timeLabel = "",
                playDefaultSound = true,
                vibrationEnabled = true,
                updatedAt = 0L
            ),
            flags = PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        assertNotNull("DirectBoot PendingIntent MUST be armed for normal firing", directBootPi)

        val bridgeClient = buildHueBridgeClientMock()
        val trustStore = buildHueTrustStoreMock()
        val wp = mockk<WorkerParameters>(relaxed = true)
        every { wp.inputData } returns workDataOf(HueSunriseWorker.KEY_ALARM_ID to alarmId)
        val workerRes = HueSunriseWorker(
            context = context,
            workerParams = wp,
            repository = repository,
            preferencesManager = preferencesManager,
            hueBridgeClient = bridgeClient,
            hueTrustStore = trustStore
        ).doWork()

        assertEquals(ListenableWorker.Result.success(), workerRes)
        coVerify(exactly = 0) { bridgeClient.probeV2(any(), any(), any(), any()) }
        verify(exactly = 0) { bridgeClient.buildTofuClient(any()) }
    }
}
