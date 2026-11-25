package com.sysadmindoc.alarmclock.widget

import android.app.Application
import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Operation
import androidx.work.WorkManager
import com.sysadmindoc.alarmclock.AlarmClockApp
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthorityMeta
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.data.repository.AlarmIncidentRepository
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.data.repository.HolidayRepository
import com.sysadmindoc.alarmclock.data.repository.WeatherRepository
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import com.sysadmindoc.alarmclock.domain.NextAlarmCalculator
import com.sysadmindoc.alarmclock.domain.ProductionSchedulingRouteAuthority
import com.sysadmindoc.alarmclock.service.BedtimeZenRuleManager
import dagger.hilt.android.EntryPointAccessors
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.DayOfWeek

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class NextAlarmWidgetActionTest {

    private lateinit var context: Context
    private lateinit var db: AlarmDatabase
    private lateinit var repository: AlarmRepository
    private lateinit var scheduler: AlarmScheduler

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)

        db = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        repository = AlarmRepository(
            dao = db.alarmDao(),
            groupDao = db.alarmGroupDao(),
            database = db
        )

        val workManager = mockk<WorkManager>(relaxed = true)
        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(any<Context>()) } returns workManager
        every { workManager.cancelUniqueWork(any()) } returns mockk<Operation>(relaxed = true)

        mockkObject(WidgetUpdater)
        every { WidgetUpdater.requestUpdate(any()) } just Runs

        mockkObject(BedtimeZenRuleManager)
        every { BedtimeZenRuleManager.syncRule(any(), any(), any()) } returns mockk(relaxed = true)

        val preferencesManager = mockk<PreferencesManager>(relaxed = true)
        coEvery { preferencesManager.getCurrentSettings() } returns AppSettings()
        every { preferencesManager.getCachedSettings() } returns AppSettings()

        val holidayRepository = mockk<HolidayRepository>(relaxed = true)
        coEvery { holidayRepository.isHoliday(any()) } returns false

        val incidentRepository = mockk<AlarmIncidentRepository>(relaxed = true)
        val weatherRepository = mockk<WeatherRepository>(relaxed = true)
        every { weatherRepository.getCachedWeather() } returns null

        val legacyRoute = mockk<ProductionSchedulingRouteAuthority>()
        every { legacyRoute.isOwnerRouteActive() } returns false

        scheduler = AlarmScheduler(
            context = context,
            repository = repository,
            calculator = mockk<NextAlarmCalculator>(relaxed = true),
            preferencesManager = preferencesManager,
            holidayRepository = holidayRepository,
            alarmIncidentRepository = incidentRepository,
            weatherRepository = weatherRepository,
            routeAuthority = legacyRoute
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
        db.close()
    }

    @Test
    fun `RED-C41-06 - widget adjust rejects post-read restore or edit race and leaves OS unarmed`() = runBlocking {
        val realAm = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val shadowAm = shadowOf(realAm)
        shadowAm.scheduledAlarms.clear()

        val t1 = System.currentTimeMillis() + 7_200_000L
        val tRestored = System.currentTimeMillis() + 14_400_000L

        db.scheduleAuthorityDao().insertMeta(
            ScheduleAuthorityMeta(id = 1, restoreEpoch = 100L, bootstrapState = "INITIALIZED")
        )
        val alarmId = repository.save(
            Alarm(
                hour = 7,
                minute = 0,
                isEnabled = true,
                scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
                repeatDays = setOf(DayOfWeek.MONDAY),
                nextTriggerTime = t1
            )
        )
        val seededAuth = db.scheduleAuthorityDao().getAuthorityByAlarmId(alarmId)!!

        val spiedScheduler = spyk(scheduler)
        coEvery {
            spiedScheduler.scheduleAt(any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            // Simulate concurrent backup restore after widget read getNextAlarm() and before scheduleAt() locks
            db.scheduleAuthorityDao().insertMeta(
                ScheduleAuthorityMeta(id = 1, restoreEpoch = 101L, bootstrapState = "INITIALIZED")
            )
            db.scheduleAuthorityDao().insertAuthority(
                seededAuth.copy(
                    instanceToken = "tok-widget-restored",
                    restoreEpoch = 101L,
                    scheduleRevision = 1L,
                    desiredTriggerMillis = tRestored
                )
            )
            db.alarmDao().updateNextTrigger(alarmId, tRestored)
            callOriginal()
        }

        val ep = mockk<AlarmClockApp.AppEntryPoint>(relaxed = true)
        every { ep.alarmRepository() } returns repository
        every { ep.alarmScheduler() } returns spiedScheduler

        mockkStatic(EntryPointAccessors::class)
        every { EntryPointAccessors.fromApplication(any(), AlarmClockApp.AppEntryPoint::class.java) } returns ep

        mockkConstructor(NextAlarmWidget::class)
        coEvery { anyConstructed<NextAlarmWidget>().update(any(), any()) } just Runs

        val glanceId = mockk<GlanceId>(relaxed = true)
        val params = actionParametersOf(ActionParameters.Key<Int>("adjust_minutes") to 10)
        AdjustAlarmAction().onAction(context, glanceId, params)

        assertEquals(
            "RED-C41-06: stale widget adjust after restore MUST NOT arm OS AlarmManager",
            0,
            shadowAm.scheduledAlarms.size
        )
        assertEquals(
            "RED-C41-06: restored nextTriggerTime MUST remain intact",
            tRestored,
            repository.getById(alarmId)?.nextTriggerTime
        )
    }

    @Test
    fun `CTRL-C41-01 - widget adjust succeeds for Quick alarm with null ScheduleAuthority`() = runBlocking {
        val realAm = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val shadowAm = shadowOf(realAm)
        shadowAm.scheduledAlarms.clear()

        val t1 = System.currentTimeMillis() + 7_200_000L
        db.scheduleAuthorityDao().insertMeta(
            ScheduleAuthorityMeta(id = 1, restoreEpoch = 100L, bootstrapState = "INITIALIZED")
        )
        val quickId = repository.save(
            Alarm(
                hour = 7,
                minute = 15,
                isEnabled = true,
                kind = Alarm.KIND_QUICK,
                repeatDays = emptySet(),
                nextTriggerTime = t1
            )
        )
        // Quick alarms do not admit ScheduleAuthority
        assertEquals(null, db.scheduleAuthorityDao().getAuthorityByAlarmId(quickId))

        val ep = mockk<AlarmClockApp.AppEntryPoint>(relaxed = true)
        every { ep.alarmRepository() } returns repository
        every { ep.alarmScheduler() } returns scheduler

        mockkStatic(EntryPointAccessors::class)
        every { EntryPointAccessors.fromApplication(any(), AlarmClockApp.AppEntryPoint::class.java) } returns ep

        mockkConstructor(NextAlarmWidget::class)
        coEvery { anyConstructed<NextAlarmWidget>().update(any(), any()) } just Runs

        val glanceId = mockk<GlanceId>(relaxed = true)
        val params = actionParametersOf(ActionParameters.Key<Int>("adjust_minutes") to 10)
        AdjustAlarmAction().onAction(context, glanceId, params)

        assertTrue(
            "Quick alarm adjust MUST arm OS AlarmManager without ScheduleAuthority",
            shadowAm.scheduledAlarms.isNotEmpty()
        )
        assertEquals(t1 + 600_000L, repository.getById(quickId)?.nextTriggerTime)
    }
}
