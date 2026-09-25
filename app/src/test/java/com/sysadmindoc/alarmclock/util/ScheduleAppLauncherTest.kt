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
    fun `createCalendarIntent with empty package creates generic calendar intent`() {
        val intent = ScheduleAppLauncher.createCalendarIntent("")
        assertEquals(Intent.ACTION_MAIN, intent.action)
        assertNull(intent.`package`)
    }

    @Test
    fun `createCalendarIntent with package constrains intent to package`() {
        val targetPkg = "com.google.android.calendar"
        val intent = ScheduleAppLauncher.createCalendarIntent(targetPkg)
        assertEquals(Intent.ACTION_MAIN, intent.action)
        assertEquals(targetPkg, intent.`package`)
    }

    @Test
    fun `getScheduleAppLabel returns system default label when package is blank`() {
        val label = ScheduleAppLauncher.getScheduleAppLabel(context, "")
        assertEquals(context.getString(R.string.schedule_app_system_default), label)
    }

    @Test
    fun `getScheduleAppLabel returns app label when package resolves successfully`() {
        val label = ScheduleAppLauncher.getScheduleAppLabel(
            context = context,
            packageName = "com.google.android.calendar",
            appInfoResolver = { "Google Calendar" }
        )
        assertEquals("Google Calendar", label)
    }

    @Test
    fun `getScheduleAppLabel returns unavailable label instead of raw package when package is stale or uninstalled`() {
        val label = ScheduleAppLauncher.getScheduleAppLabel(
            context = context,
            packageName = "com.missing.calendar",
            appInfoResolver = { null }
        )
        assertEquals(context.getString(R.string.schedule_app_unavailable), label)
    }

    @Test
    fun `launchScheduleApp returns Success when selected package resolves and launches without fallback`() {
        var fallbackAttempted = false

        val result = ScheduleAppLauncher.launchScheduleApp(
            context = context,
            targetPackage = "com.google.android.calendar",
            intentResolver = { intent ->
                if (intent.`package` == null) {
                    fallbackAttempted = true
                }
                true
            },
            intentLauncher = { true }
        )

        assertEquals(ScheduleLaunchResult.Success, result)
        assertTrue("Fallback should not be attempted when primary launch succeeds", !fallbackAttempted)
    }

    @Test
    fun `launchScheduleApp falls back to generic calendar intent when selected package fails to resolve`() {
        var launchedIntent: Intent? = null

        val result = ScheduleAppLauncher.launchScheduleApp(
            context = context,
            targetPackage = "com.missing.calendar",
            intentResolver = { intent ->
                // Primary (with package) cannot resolve, but generic (null package) can
                intent.`package` == null
            },
            intentLauncher = { intent ->
                launchedIntent = intent
                true
            }
        )

        assertEquals(ScheduleLaunchResult.FallbackSuccess, result)
        assertNull("Fallback intent must not be constrained to missing package", launchedIntent?.`package`)
    }

    @Test
    fun `launchScheduleApp returns Failure when selected package and generic fallback both fail`() {
        val result = ScheduleAppLauncher.launchScheduleApp(
            context = context,
            targetPackage = "com.missing.calendar",
            intentResolver = { false },
            intentLauncher = { false }
        )

        assertTrue(result is ScheduleLaunchResult.Failure)
        assertEquals(R.string.schedule_app_launch_failed, (result as ScheduleLaunchResult.Failure).errorMessageRes)
    }

    @Test
    fun `launchScheduleApp returns Success for System Default when generic intent launches`() {
        val result = ScheduleAppLauncher.launchScheduleApp(
            context = context,
            targetPackage = "",
            intentResolver = { true },
            intentLauncher = { true }
        )

        assertEquals(ScheduleLaunchResult.Success, result)
    }

    @Test
    fun `launchScheduleApp returns Failure for System Default when generic intent fails`() {
        val result = ScheduleAppLauncher.launchScheduleApp(
            context = context,
            targetPackage = "",
            intentResolver = { false },
            intentLauncher = { false }
        )

        assertTrue(result is ScheduleLaunchResult.Failure)
    }
}
