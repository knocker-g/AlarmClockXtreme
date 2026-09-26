package com.sysadmindoc.alarmclock.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.sysadmindoc.alarmclock.R

sealed interface ScheduleLaunchResult {
    data object Success : ScheduleLaunchResult
    data class Failure(val errorMessageRes: Int) : ScheduleLaunchResult
}

object ScheduleAppLauncher {

    /**
     * Launches the default calendar/schedule app using a generic Calendar semantic intent
     * (ACTION_MAIN + CATEGORY_APP_CALENDAR) unconstrained by package or activity class name.
     * Delegates default application handling completely to Android OS.
     * Never throws unhandled exceptions.
     */
    fun launchScheduleApp(
        context: Context,
        intentResolver: (Intent) -> Boolean = { intent -> canResolveIntent(context.packageManager, intent) },
        intentLauncher: (Intent) -> Boolean = { intent ->
            runCatching {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
        }
    ): ScheduleLaunchResult {
        val calendarIntent = createCalendarIntent()
        val isResolved = runCatching { intentResolver(calendarIntent) }.getOrDefault(false)
        if (!isResolved) {
            return ScheduleLaunchResult.Failure(R.string.schedule_app_launch_failed)
        }

        val isLaunched = runCatching { intentLauncher(calendarIntent) }.getOrDefault(false)
        if (isLaunched) {
            return ScheduleLaunchResult.Success
        }

        return ScheduleLaunchResult.Failure(R.string.schedule_app_launch_failed)
    }

    internal fun createCalendarIntent(): Intent {
        return Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR)
    }

    internal fun canResolveIntent(pm: PackageManager, intent: Intent): Boolean {
        return runCatching { intent.resolveActivity(pm) != null }.getOrDefault(false)
    }
}
