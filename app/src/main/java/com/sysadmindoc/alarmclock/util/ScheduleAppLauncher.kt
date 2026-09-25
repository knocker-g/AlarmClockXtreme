package com.sysadmindoc.alarmclock.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.sysadmindoc.alarmclock.R

data class ScheduleAppInfo(
    val packageName: String,
    val label: String
)

sealed interface ScheduleLaunchResult {
    data object Success : ScheduleLaunchResult
    data object FallbackSuccess : ScheduleLaunchResult
    data class Failure(val errorMessageRes: Int) : ScheduleLaunchResult
}

object ScheduleAppLauncher {

    /**
     * Queries PackageManager for installed apps capable of handling Calendar semantic intents,
     * returning [ScheduleAppInfo] items with [packageName] and user-facing [label].
     * Always includes System Default ([packageName] = "") at the beginning.
     */
    fun getInstalledScheduleApps(context: Context): List<ScheduleAppInfo> {
        val systemDefaultLabel = context.getString(R.string.schedule_app_system_default)
        val defaultInfo = ScheduleAppInfo(packageName = "", label = systemDefaultLabel)

        val pm = context.packageManager
        val intent = createCalendarIntent("")
        val resolveInfos = runCatching {
            pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }.getOrDefault(emptyList())

        val discoveredApps = resolveInfos.mapNotNull { resolveInfo ->
            val pkg = resolveInfo.activityInfo?.packageName ?: return@mapNotNull null
            val label = resolveInfo.loadLabel(pm)?.toString()?.ifBlank { pkg } ?: pkg
            ScheduleAppInfo(packageName = pkg, label = label)
        }.distinctBy { it.packageName }

        return listOf(defaultInfo) + discoveredApps
    }

    /**
     * Returns the human-readable label for a given [packageName].
     * Returns [R.string.schedule_app_system_default] if [packageName] is blank ("").
     * Returns [R.string.schedule_app_unavailable] if the package is stale or uninstalled.
     */
    fun getScheduleAppLabel(
        context: Context,
        packageName: String,
        appInfoResolver: (String) -> String? = { pkg ->
            runCatching {
                val appInfo = context.packageManager.getApplicationInfo(pkg, 0)
                context.packageManager.getApplicationLabel(appInfo).toString()
            }.getOrNull()
        }
    ): String {
        if (packageName.isBlank()) {
            return context.getString(R.string.schedule_app_system_default)
        }

        val resolvedLabel = appInfoResolver(packageName)
        return if (!resolvedLabel.isNullOrBlank()) {
            resolvedLabel
        } else {
            context.getString(R.string.schedule_app_unavailable)
        }
    }

    /**
     * Launches the schedule app for [targetPackage].
     * If [targetPackage] is specified and fails, falls back to generic Calendar semantic intent.
     * Never throws unhandled exceptions.
     */
    fun launchScheduleApp(
        context: Context,
        targetPackage: String,
        intentResolver: (Intent) -> Boolean = { intent -> canResolveIntent(context.packageManager, intent) },
        intentLauncher: (Intent) -> Boolean = { intent ->
            runCatching {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
        }
    ): ScheduleLaunchResult {
        if (targetPackage.isNotBlank()) {
            val primaryIntent = createCalendarIntent(targetPackage)
            if (intentResolver(primaryIntent) && intentLauncher(primaryIntent)) {
                return ScheduleLaunchResult.Success
            }

            // Fallback to generic Calendar semantic intent
            val fallbackIntent = createCalendarIntent("")
            if (intentResolver(fallbackIntent) && intentLauncher(fallbackIntent)) {
                return ScheduleLaunchResult.FallbackSuccess
            }

            return ScheduleLaunchResult.Failure(R.string.schedule_app_launch_failed)
        }

        val defaultIntent = createCalendarIntent("")
        if (intentResolver(defaultIntent) && intentLauncher(defaultIntent)) {
            return ScheduleLaunchResult.Success
        }

        return ScheduleLaunchResult.Failure(R.string.schedule_app_launch_failed)
    }

    internal fun createCalendarIntent(targetPackage: String): Intent {
        val intent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR)
        if (targetPackage.isNotBlank()) {
            intent.setPackage(targetPackage)
        }
        return intent
    }

    internal fun canResolveIntent(pm: PackageManager, intent: Intent): Boolean {
        return runCatching { intent.resolveActivity(pm) != null }.getOrDefault(false)
    }
}
