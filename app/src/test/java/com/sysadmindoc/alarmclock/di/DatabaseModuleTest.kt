package com.sysadmindoc.alarmclock.di

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class DatabaseModuleTest {

    @Test
    fun `provideExactPermissionChecker returns true when canScheduleExactAlarms is true`() {
        val mockContext = mockk<Context>()
        val mockAlarmManager = mockk<AlarmManager>()
        every { mockContext.getSystemService(Context.ALARM_SERVICE) } returns mockAlarmManager
        every { mockAlarmManager.canScheduleExactAlarms() } returns true

        val checker = DatabaseModule.provideExactPermissionChecker(mockContext)
        assertTrue(checker())
    }

    @Test
    fun `provideExactPermissionChecker fails closed returning false when canScheduleExactAlarms is false`() {
        val mockContext = mockk<Context>()
        val mockAlarmManager = mockk<AlarmManager>()
        every { mockContext.getSystemService(Context.ALARM_SERVICE) } returns mockAlarmManager
        every { mockAlarmManager.canScheduleExactAlarms() } returns false

        val checker = DatabaseModule.provideExactPermissionChecker(mockContext)
        assertFalse("Permission checker MUST fail closed and return false when exact alarm permission is denied", checker())
    }

    @Test
    fun `provideExactPermissionChecker fails closed returning false when AlarmManager is null`() {
        val mockContext = mockk<Context>()
        every { mockContext.getSystemService(Context.ALARM_SERVICE) } returns null

        val checker = DatabaseModule.provideExactPermissionChecker(mockContext)
        assertFalse("Permission checker MUST fail closed and return false when AlarmManager is null", checker())
    }

    @Test
    @Config(sdk = [28])
    fun `provideExactPermissionChecker returns true on SDK below 31`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val checker = DatabaseModule.provideExactPermissionChecker(context)
        assertTrue("Permission checker returns true on SDK < 31", checker())
    }
}
