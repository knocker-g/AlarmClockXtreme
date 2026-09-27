package com.sysadmindoc.alarmclock.util

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ScheduleAppLauncherTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `createCalendarIntent creates unconstrained generic calendar intent`() {
        val intent = ScheduleAppLauncher.createCalendarIntent()
        assertEquals(Intent.ACTION_MAIN, intent.action)
        assertNull("Generic calendar intent must not specify package", intent.`package`)
        assertNull("Generic calendar intent must not specify component", intent.component)
        assertTrue(
            "Generic calendar intent selector must include APP_CALENDAR category",
            intent.selector?.hasCategory(Intent.CATEGORY_APP_CALENDAR) == true
        )
    }

    @Test
    fun `launchScheduleApp returns Success when direct launch succeeds`() {
        var launchedIntent: Intent? = null

        val result = ScheduleAppLauncher.launchScheduleApp(
            context = context,
            intentLauncher = { intent ->
                launchedIntent = intent
                true
            }
        )

        assertEquals(ScheduleLaunchResult.Success, result)
        assertEquals(Intent.ACTION_MAIN, launchedIntent?.action)
        assertNull("Launched intent must remain unconstrained by package", launchedIntent?.`package`)
        assertNull("Launched intent must remain unconstrained by component", launchedIntent?.component)
    }

    @Test
    fun `launchScheduleApp returns Failure when direct launch returns false`() {
        val result = ScheduleAppLauncher.launchScheduleApp(
            context = context,
            intentLauncher = { false }
        )

        assertTrue(result is ScheduleLaunchResult.Failure)
        assertEquals(
            R.string.schedule_app_launch_failed,
            (result as ScheduleLaunchResult.Failure).errorMessageRes
        )
    }

    @Test
    fun `launchScheduleApp returns Failure without crashing when direct launch throws exception`() {
        val result = ScheduleAppLauncher.launchScheduleApp(
            context = context,
            intentLauncher = { throw RuntimeException("Launch failed") }
        )

        assertTrue(result is ScheduleLaunchResult.Failure)
        assertEquals(
            R.string.schedule_app_launch_failed,
            (result as ScheduleLaunchResult.Failure).errorMessageRes
        )
    }
}
