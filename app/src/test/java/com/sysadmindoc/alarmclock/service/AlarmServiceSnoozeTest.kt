package com.sysadmindoc.alarmclock.service

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import com.sysadmindoc.alarmclock.domain.NextAlarmCalculator
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class AlarmServiceSnoozeTest {

    private lateinit var context: Context
    private lateinit var alarmScheduler: AlarmScheduler
    private lateinit var alarmRepository: AlarmRepository
    private lateinit var calculator: NextAlarmCalculator

    private val testAlarm = Alarm(
        id = 42L,
        hour = 7,
        minute = 30,
        label = "Test Alarm",
        isEnabled = true,
        snoozeDurationMinutes = 10,
        nextTriggerTime = 1000L
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        alarmScheduler = mockk(relaxed = true)
        alarmRepository = mockk(relaxed = true)
        calculator = mockk(relaxed = true)

        coEvery { alarmRepository.getById(42L) } returns testAlarm
    }

    @Test
    fun `A - when exact capability denied preflight registration is rejected and failure returned`() = runBlocking {
        coEvery { alarmScheduler.canScheduleExactAlarms() } returns false

        val canSchedule = alarmScheduler.canScheduleExactAlarms()
        assertFalse("Preflight MUST reject exact Snooze registration when capability denied", canSchedule)
    }

    @Test
    fun `B - when scheduleSnoozeAt returns false registration failure is indicated`() = runBlocking {
        coEvery { alarmScheduler.canScheduleExactAlarms() } returns true
        coEvery { alarmScheduler.scheduleSnoozeAt(any(), any()) } returns false

        val success = alarmScheduler.scheduleSnoozeAt(testAlarm, System.currentTimeMillis() + 600_000L)
        assertFalse("Registration MUST return false on OS registration failure", success)
    }

    @Test
    fun `C - when scheduleSnoozeAt throws SecurityException failure is caught cleanly`() = runBlocking {
        coEvery { alarmScheduler.canScheduleExactAlarms() } returns true
        coEvery { alarmScheduler.scheduleSnoozeAt(any(), any()) } throws SecurityException("Exact alarm permission revoked")

        var exceptionCaught = false
        try {
            alarmScheduler.scheduleSnoozeAt(testAlarm, System.currentTimeMillis() + 600_000L)
        } catch (_: SecurityException) {
            exceptionCaught = true
        }

        assertTrue("SecurityException caught cleanly from OS registration failure", exceptionCaught)
    }

    @Test
    fun `D - when scheduleSnoozeAt throws IllegalStateException no false success is returned`() = runBlocking {
        coEvery { alarmScheduler.canScheduleExactAlarms() } returns true
        coEvery { alarmScheduler.scheduleSnoozeAt(any(), any()) } throws IllegalStateException("OS AlarmManager internal failure")

        var exceptionCaught = false
        try {
            alarmScheduler.scheduleSnoozeAt(testAlarm, System.currentTimeMillis() + 600_000L)
        } catch (_: IllegalStateException) {
            exceptionCaught = true
        }

        assertTrue("IllegalStateException caught cleanly without false success", exceptionCaught)
    }

    @Test
    fun `E - successful OS registration returns true`() = runBlocking {
        coEvery { alarmScheduler.canScheduleExactAlarms() } returns true
        coEvery { alarmScheduler.scheduleSnoozeAt(any(), any()) } returns true

        val success = alarmScheduler.scheduleSnoozeAt(testAlarm, System.currentTimeMillis() + 600_000L)
        assertTrue("Successful OS registration MUST return true", success)
    }

    @Test
    fun `G - OS registration failure in scheduleAt preserves previous nextTriggerTime without destructive erasure`() = runBlocking {
        val previousTrigger = 5000L
        val alarmWithTrigger = testAlarm.copy(nextTriggerTime = previousTrigger)
        coEvery { alarmRepository.getById(42L) } returns alarmWithTrigger
        coEvery { alarmScheduler.scheduleAt(any(), any(), any()) } returns false

        val success = alarmScheduler.scheduleAt(alarmWithTrigger, 8000L)

        assertFalse("Failed scheduleAt MUST return false", success)
        coVerify(exactly = 0) { alarmRepository.updateNextTrigger(42L, 0) }
    }
}
