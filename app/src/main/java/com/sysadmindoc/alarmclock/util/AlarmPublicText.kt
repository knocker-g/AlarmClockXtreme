package com.sysadmindoc.alarmclock.util

import android.content.Context
import com.sysadmindoc.alarmclock.R

object AlarmPublicText {
    const val GENERIC_ALARM_LABEL = "Alarm"
    const val GENERIC_NEXT_ALARM_LABEL = "Next alarm"

    /**
     * Profile internal keys mapped to their localized string resources.
     * System profiles are still localized as they are technical identifiers.
     */
    val SYSTEM_PROFILES = mapOf(
        "calendar_auto" to R.string.alarm_edit_profile_calendar_auto,
        "Work" to R.string.alarm_edit_group_work,
        "School" to R.string.alarm_edit_group_school,
        "Gym" to R.string.alarm_edit_group_gym,
        "Medication" to R.string.alarm_edit_group_medication,
        "Personal" to R.string.alarm_edit_group_personal,
        "Calendar" to R.string.alarm_edit_group_calendar
    )

    fun requiredAlarmLabel(label: String, hideLabel: Boolean): String {
        return if (hideLabel) GENERIC_ALARM_LABEL else label.ifBlank { GENERIC_ALARM_LABEL }
    }

    fun optionalAlarmLabel(label: String, hideLabel: Boolean): String {
        return if (hideLabel) GENERIC_ALARM_LABEL else label
    }

    fun firingNotificationText(label: String, fallbackTime: String, hideLabel: Boolean): String {
        return if (hideLabel) GENERIC_ALARM_LABEL else label.ifBlank { fallbackTime }
    }

    fun wakeConfirmTitle(label: String, hideLabel: Boolean): String {
        return if (hideLabel || label.isBlank()) {
            "Are you awake?"
        } else {
            "Awake check: $label"
        }
    }

    fun quickSettingsSubtitle(label: String, hideLabel: Boolean): String {
        return if (hideLabel || label.isBlank()) GENERIC_NEXT_ALARM_LABEL else label
    }

    /**
     * Resolves a localized display name for a group or profile internal key.
     * Groups are no longer localized and return as-is.
     */
    fun getLocalizedName(key: String, context: Context): String {
        val resId = SYSTEM_PROFILES[key] ?: return key
        return context.getString(resId)
    }
}
