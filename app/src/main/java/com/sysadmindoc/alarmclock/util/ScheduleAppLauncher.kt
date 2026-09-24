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
     */
    fun getScheduleAppLabel(context: Context, packageName: String): String {
        if (packageName.isBlank()) {
            return context.getString(R.string.schedule_app_system_default)
        }

        val pm = context.packageManager
        return runCatching {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        }.getOrDefault(packageName)
    }

    /**
     * Launches the schedule app for [targetPackage].
     * If [targetPackage] is specified and fails, falls back to generic Calendar intent.
     * Never throws unhandled exceptions.
     */
    fun launchScheduleApp(context: Context, targetPackage: String): ScheduleLaunchResult {
        val pm = context.packageManager

        if (targetPackage.isNotBlank()) {
            val primaryIntent = createCalendarIntent(targetPackage)
            if (canResolveIntent(pm, primaryIntent)) {
                val launched = runCatching {
                    context.startActivity(primaryIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.isSuccess
                if (launched) return ScheduleLaunchResult.Success
            }

            val launchIntent = runCatching { pm.getLaunchIntentForPackage(targetPackage) }.getOrNull()
            if (launchIntent != null) {
                val launched = runCatching {
                    context.startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.isSuccess
                if (launched) return ScheduleLaunchResult.Success
            }

            // Fallback to generic Calendar intent
            val fallbackIntent = createCalendarIntent("")
            if (canResolveIntent(pm, fallbackIntent)) {
                val launched = runCatching {
                    context.startActivity(fallbackIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.isSuccess
                if (launched) return ScheduleLaunchResult.FallbackSuccess
            }

            return ScheduleLaunchResult.Failure(R.string.schedule_app_launch_failed)
        }

        val defaultIntent = createCalendarIntent("")
        val launched = runCatching {
            context.startActivity(defaultIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess

        return if (launched) {
            ScheduleLaunchResult.Success
        } else {
            ScheduleLaunchResult.Failure(R.string.schedule_app_launch_failed)
        }
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
