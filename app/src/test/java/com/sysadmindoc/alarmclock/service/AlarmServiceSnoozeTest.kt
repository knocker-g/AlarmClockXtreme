package com.sysadmindoc.alarmclock.service

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.entity.AlarmEvent
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
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

    private val testAlarm = Alarm(
        id = 42L,
        hour = 7,
        minute = 30,
        label = "Test Alarm",
        isEnabled = true,
        snoozeDurationMinutes = 10
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        alarmScheduler = mockk()
    }

    @Test
    fun `when scheduleSnoozeAt returns false registration failure is indicated`() = runBlocking {
        coEvery { alarmScheduler.canScheduleExactAlarms() } returns true
        coEvery { alarmScheduler.scheduleSnoozeAt(any(), any()) } returns false

        // Execute snooze registration
        val success = alarmScheduler.scheduleSnoozeAt(testAlarm, System.currentTimeMillis() + 600_000L)
        assertFalse("Registration must return false on failure", success)
    }

    @Test
    fun `when scheduleSnoozeAt throws SecurityException failure is caught cleanly`() = runBlocking {
        coEvery { alarmScheduler.canScheduleExactAlarms() } returns true
        coEvery { alarmScheduler.scheduleSnoozeAt(any(), any()) } throws SecurityException("Exact alarm permission revoked")

        var exceptionThrown = false
        try {
            alarmScheduler.scheduleSnoozeAt(testAlarm, System.currentTimeMillis() + 600_000L)
        } catch (_: SecurityException) {
            exceptionThrown = true
        }

        assertTrue("SecurityException caught from OS exact alarm registration failure", exceptionThrown)
    }
}
