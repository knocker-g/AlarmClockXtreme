package com.sysadmindoc.alarmclock.domain

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Operation
import androidx.work.WorkManager
import com.sysadmindoc.alarmclock.directboot.DirectBootAlarmCache
import com.sysadmindoc.alarmclock.data.local.entity.AlarmIncidentEvent
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.data.repository.AlarmIncidentRepository
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.data.repository.HolidayRepository
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
import java.time.DayOfWeek
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class AlarmSchedulerTest {
    private lateinit var context: Context
    private lateinit var repository: AlarmRepository
    private lateinit var calculator: NextAlarmCalculator
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var holidayRepository: HolidayRepository
    private lateinit var incidentRepository: AlarmIncidentRepository
    private lateinit var weatherRepository: com.sysadmindoc.alarmclock.data.repository.WeatherRepository
    private lateinit var workManager: WorkManager
    private lateinit var scheduler: AlarmScheduler

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repository = mockk(relaxed = true)
        calculator = mockk()
        preferencesManager = mockk()
        holidayRepository = mockk()
        incidentRepository = mockk(relaxed = true)
        workManager = mockk(relaxed = true)

        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(any<Context>()) } returns workManager
        every { workManager.cancelUniqueWork(any()) } returns mockk<Operation>(relaxed = true)

        mockkObject(WidgetUpdater)
        every { WidgetUpdater.requestUpdate(any()) } just Runs

        coEvery { preferencesManager.getCurrentSettings() } returns AppSettings()
        every { preferencesManager.getCachedSettings() } returns AppSettings()
        coEvery { holidayRepository.isHoliday(any()) } returns false

        weatherRepository = mockk(relaxed = true)
        every { weatherRepository.getCachedWeather() } returns null

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
        unmockkAll()
    }

    @Test
    fun isSnowOrIceCodeMatchesExpectedWmoCodes() {
        val snowCodes = listOf(56, 57, 66, 67, 71, 73, 75, 77, 85, 86)
        val clearCodes = listOf(0, 1, 2, 3, 45, 48, 51, 53, 55, 61, 63, 65, 80, 81, 82, 95, 96, 99)
        snowCodes.forEach { assert(AlarmScheduler.isSnowOrIceCode(it)) { "Expected $it to be snow/ice" } }
        clearCodes.forEach { assert(!AlarmScheduler.isSnowOrIceCode(it)) { "Expected $it to NOT be snow/ice" } }
    }

    @Test
    fun `successful fresh OS SET cancels DirectBoot fallback`() = runTest {
        val alarm = enabledAlarm(id = 200L)
        val triggerTime = System.currentTimeMillis() + 600_000L
        every { calculator.calculate(any<Alarm>(), any()) } returns triggerTime

        val mockOwner = mockk<ScheduleAuthorityOwner>()
        coEvery { mockOwner.executeOperation(200L, "SET", triggerTime, any()) } returns OwnerResult.OsAccepted(1L, "act-sched-200")

        val schedulerWithOwner = AlarmScheduler(
            context = context,
            repository = repository,
            calculator = calculator,
            preferencesManager = preferencesManager,
            holidayRepository = holidayRepository,
            alarmIncidentRepository = incidentRepository,
            weatherRepository = weatherRepository,
            scheduleAuthorityOwnerProvider = { mockOwner }
        )

        mockkObject(DirectBootAlarmCache)
        every { DirectBootAlarmCache.cancelScheduledFallback(any(), 200L) } just Runs

        schedulerWithOwner.schedule(alarm, requestWidgetUpdate = false)

        verify(exactly = 1) { DirectBootAlarmCache.cancelScheduledFallback(any(), 200L) }
    }

    @Test
    fun `DuplicateIgnored outcome does NOT cancel DirectBoot fallback`() = runTest {
        val alarm = enabledAlarm(id = 201L)
        val triggerTime = System.currentTimeMillis() + 600_000L
        every { calculator.calculate(any<Alarm>(), any()) } returns triggerTime

        val mockOwner = mockk<ScheduleAuthorityOwner>()
        coEvery { mockOwner.executeOperation(201L, "SET", triggerTime, any()) } returns OwnerResult.DuplicateIgnored

        val schedulerWithOwner = AlarmScheduler(
            context = context,
            repository = repository,
            calculator = calculator,
            preferencesManager = preferencesManager,
            holidayRepository = holidayRepository,
            alarmIncidentRepository = incidentRepository,
            weatherRepository = weatherRepository,
            scheduleAuthorityOwnerProvider = { mockOwner }
        )

        mockkObject(DirectBootAlarmCache)
        every { DirectBootAlarmCache.cancelScheduledFallback(any(), 201L) } just Runs

        schedulerWithOwner.schedule(alarm, requestWidgetUpdate = false)

        verify(exactly = 0) { DirectBootAlarmCache.cancelScheduledFallback(any(), 201L) }
    }

    @Test
    fun `failed OS SET preserves DirectBoot fallback`() = runTest {
        val alarm = enabledAlarm(id = 202L)
        val triggerTime = System.currentTimeMillis() + 600_000L
        every { calculator.calculate(any<Alarm>(), any()) } returns triggerTime

        val mockOwner = mockk<ScheduleAuthorityOwner>()
        coEvery { mockOwner.executeOperation(202L, "SET", triggerTime, any()) } returns OwnerResult.OutcomeUnknown(1L, IllegalStateException("OS Error"))
        coEvery { mockOwner.executeOperation(202L, "CANCEL", null, any()) } returns OwnerResult.OsAccepted(1L, "act-cancel-202")

        val schedulerWithOwner = AlarmScheduler(
            context = context,
            repository = repository,
            calculator = calculator,
            preferencesManager = preferencesManager,
            holidayRepository = holidayRepository,
            alarmIncidentRepository = incidentRepository,
            weatherRepository = weatherRepository,
            scheduleAuthorityOwnerProvider = { mockOwner }
        )

        mockkObject(DirectBootAlarmCache)
        every { DirectBootAlarmCache.cancelScheduledFallback(any(), 202L) } just Runs
        every { DirectBootAlarmCache.removeIfMatches(any(), any()) } just Runs

        schedulerWithOwner.schedule(alarm, requestWidgetUpdate = false)

        verify(exactly = 0) { DirectBootAlarmCache.cancelScheduledFallback(any(), 202L) }
    }

    @Test
    fun `valid SmartWake early fire cancels main alarm when target trigger matches`() = runTest {
        val targetTime = System.currentTimeMillis() + 600_000L
        val alarm = enabledAlarm(id = 300L).copy(nextTriggerTime = targetTime)
        coEvery { repository.getById(300L) } returns alarm
        coEvery { repository.getAuthorityByAlarmId(300L) } returns null

        val canceled = scheduler.cancelMainScheduledAlarm(300L, expectedTriggerTime = targetTime)

        coVerify { repository.getById(300L) }
    }

    @Test
    fun `stale SmartWake callback ignores cancellation when current trigger is newer`() = runTest {
        val originalTargetTime = System.currentTimeMillis() + 600_000L
        val newerTargetTime = System.currentTimeMillis() + 86400_000L
        val alarmWithNewerTrigger = enabledAlarm(id = 301L).copy(nextTriggerTime = newerTargetTime)
        coEvery { repository.getById(301L) } returns alarmWithNewerTrigger
        coEvery { repository.getAuthorityByAlarmId(301L) } returns null

        val canceled = scheduler.cancelMainScheduledAlarm(301L, expectedTriggerTime = originalTargetTime)

        assertFalse("Stale cancellation MUST be rejected when current trigger is newer", canceled)
    }

    @Test
    fun `missing alarm row rejects SmartWake cancellation`() = runTest {
        coEvery { repository.getById(302L) } returns null

        val canceled = scheduler.cancelMainScheduledAlarm(302L, expectedTriggerTime = 1000L)

        assertFalse("Missing alarm row MUST reject cancellation", canceled)
    }

    @Test
    fun `new incarnation instanceToken mismatch rejects SmartWake cancellation`() = runTest {
        val alarm = enabledAlarm(id = 303L).copy(nextTriggerTime = 1000L)
        val authority = com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority(
            alarmId = 303L,
            instanceToken = "token-NEW-INC",
            definitionRevision = 1L,
            scheduleRevision = 1L,
            lifecycle = "LIVE",
            desiredOperation = "SET",
            desiredTriggerMillis = 1000L,
            reconcileState = "SETTLED",
            restoreEpoch = 100L,
            actionKey = "act-303"
        )
        coEvery { repository.getById(303L) } returns alarm
        coEvery { repository.getAuthorityByAlarmId(303L) } returns authority

        val canceled = scheduler.cancelMainScheduledAlarm(
            alarmId = 303L,
            expectedTriggerTime = 1000L,
            expectedInstanceToken = "token-OLD-INC" // Mismatched instance token!
        )

        assertFalse("InstanceToken mismatch MUST reject cancellation", canceled)
    }

    @Test
    fun `restoreEpoch mismatch rejects SmartWake cancellation`() = runTest {
        val alarm = enabledAlarm(id = 304L).copy(nextTriggerTime = 1000L)
        val authority = com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority(
            alarmId = 304L,
            instanceToken = "token-304",
            definitionRevision = 1L,
            scheduleRevision = 1L,
            lifecycle = "LIVE",
            desiredOperation = "SET",
            desiredTriggerMillis = 1000L,
            reconcileState = "SETTLED",
            restoreEpoch = 200L, // Current restore epoch is 200L
            actionKey = "act-304"
        )
        coEvery { repository.getById(304L) } returns alarm
        coEvery { repository.getAuthorityByAlarmId(304L) } returns authority

        val canceled = scheduler.cancelMainScheduledAlarm(
            alarmId = 304L,
            expectedTriggerTime = 1000L,
            expectedRestoreEpoch = 100L // Mismatched restore epoch!
        )

        assertFalse("RestoreEpoch mismatch MUST reject cancellation", canceled)
    }

    private fun enabledAlarm(id: Long): Alarm = Alarm(
        id = id,
        hour = 7,
        minute = 30,
        label = "Morning",
        isEnabled = true,
        repeatDays = setOf(DayOfWeek.MONDAY)
    )
}
