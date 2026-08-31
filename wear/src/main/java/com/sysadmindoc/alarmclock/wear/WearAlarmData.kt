package com.sysadmindoc.alarmclock.wear

import android.content.Context
import androidx.core.content.edit
import android.content.res.Resources
import com.sysadmindoc.alarmclock.wear.R
import com.google.android.gms.wearable.DataMap

object WearAlarmData {

    const val PATH_NEXT_ALARM = "/alarmclockxtreme/next_alarm"
    const val PATH_ACTION_SKIP = "/alarmclockxtreme/action/skip"
    const val PATH_ACTION_SNOOZE = "/alarmclockxtreme/action/snooze"
    const val PATH_ACTION_DISMISS = "/alarmclockxtreme/action/dismiss"

    const val KEY_HAS_ALARM = "has_alarm"
    const val KEY_ALARM_ID = "alarm_id"
    const val KEY_LABEL = "label"
    const val KEY_TIME_LABEL = "time_label"
    const val KEY_TRIGGER_TIME = "trigger_time"
    const val KEY_IS_FIRING = "is_firing"
    const val KEY_UPDATED_AT = "updated_at"
    const val KEY_TIMEZONE_POLICY = "timezone_policy"
    const val KEY_FIXED_TIMEZONE_ID = "fixed_timezone_id"

    // Tile clickable IDs for the wrist-side actions.
    const val CLICK_SKIP = "skip"
    const val CLICK_SNOOZE = "snooze"
    const val CLICK_DISMISS = "dismiss"

    /** Map a tile clickable ID to the Data Layer message path, or null if it is not an action. */
    fun actionPathForClick(clickableId: String): String? = when (clickableId) {
        CLICK_SKIP -> PATH_ACTION_SKIP
        CLICK_SNOOZE -> PATH_ACTION_SNOOZE
        CLICK_DISMISS -> PATH_ACTION_DISMISS
        else -> null
    }
}

data class WearAlarmSnapshot(
    val hasAlarm: Boolean = false,
    val alarmId: Long = -1L,
    val label: String = "",
    val timeLabel: String = "",
    val triggerTime: Long = 0L,
    val isFiring: Boolean = false,
    val updatedAt: Long = 0L,
    val timezonePolicy: String = "LOCAL",
    val fixedTimezoneId: String = "",
)

/**
 * Pure text formatting shared by the Wear tile and complication surfaces. Kept
 * free of Android/Wear types so it can be unit tested on the JVM; [now] is
 * injectable for deterministic countdown tests.
 */
object WearAlarmText {
    /**
     * A spaced hyphen reads as a subtraction on a watch face, and it is what
     * the house style bans in prose. The middle dot separates without
     * pretending to be punctuation.
     */
    private const val SEPARATOR = " · "
    const val SHORT_TITLE_LIMIT = 12
    const val STALE_AFTER_MS = 5 * 60_000L

    fun formatRemaining(resources: Resources, triggerTime: Long, now: Long = System.currentTimeMillis()): String {
        val diff = triggerTime - now
        if (diff <= 0L) return resources.getString(R.string.wear_remaining_due_now)
        val days = diff / 86_400_000L
        val hours = (diff % 86_400_000L) / 3_600_000L
        val minutes = (diff % 3_600_000L) / 60_000L
        return when {
            days > 0 -> resources.getString(R.string.wear_remaining_days, days, hours)
            hours > 0 -> resources.getString(R.string.wear_remaining_hours, hours, minutes)
            minutes > 0 -> resources.getString(R.string.wear_remaining_minutes, minutes)
            else -> resources.getString(R.string.wear_remaining_less_than_minute)
        }
    }

    fun isStale(snapshot: WearAlarmSnapshot, now: Long = System.currentTimeMillis()): Boolean =
        snapshot.hasAlarm && (snapshot.updatedAt <= 0L || now - snapshot.updatedAt > STALE_AFTER_MS)

    fun mainTimeLabel(resources: Resources, snapshot: WearAlarmSnapshot): String = when {
        !snapshot.hasAlarm -> resources.getString(R.string.wear_open_phone_app)
        snapshot.timeLabel.isNotBlank() -> snapshot.timeLabel
        else -> resources.getString(R.string.wear_scheduled)
    }

    fun secondaryLabel(
        resources: Resources,
        snapshot: WearAlarmSnapshot,
        actionStatus: String? = null,
        now: Long = System.currentTimeMillis()
    ): String {
        actionStatus?.let { return it }
        if (!snapshot.hasAlarm) return resources.getString(R.string.wear_waiting_phone_sync)
        if (isStale(snapshot, now)) return resources.getString(R.string.wear_phone_sync_stale)
        if (snapshot.isFiring) return resources.getString(R.string.wear_alarm_is_ringing)
        val remaining = formatRemaining(resources, snapshot.triggerTime, now)
        return listOf(snapshot.label, remaining, fixedZoneLabel(snapshot))
            .filter { it.isNotBlank() }
            .joinToString(SEPARATOR)
            .ifBlank { resources.getString(R.string.wear_ready_on_phone) }
    }

    fun contentDescription(resources: Resources, snapshot: WearAlarmSnapshot): String = when {
        isStale(snapshot) -> resources.getString(R.string.wear_phone_sync_stale)
        snapshot.isFiring -> resources.getString(R.string.wear_alarm_is_ringing)
        snapshot.hasAlarm -> resources.getString(R.string.wear_next_alarm) + " " + snapshot.timeLabel.ifBlank { resources.getString(R.string.wear_scheduled) }
        else -> resources.getString(R.string.wear_no_phone_alarm_synced)
    }

    fun complicationShortText(resources: Resources, snapshot: WearAlarmSnapshot): String = when {
        isStale(snapshot) -> resources.getString(R.string.wear_sync)
        snapshot.isFiring -> resources.getString(R.string.wear_ringing)
        snapshot.hasAlarm && snapshot.timeLabel.isNotBlank() -> snapshot.timeLabel
        snapshot.hasAlarm -> resources.getString(R.string.wear_alarm)
        else -> resources.getString(R.string.wear_no_alarm)
    }

    fun complicationShortTitle(snapshot: WearAlarmSnapshot): String = when {
        snapshot.isFiring -> "ACX"
        snapshot.hasAlarm -> snapshot.label.ifBlank { "Next" }.take(SHORT_TITLE_LIMIT)
        else -> "ACX"
    }

    fun complicationLongText(
        resources: Resources,
        snapshot: WearAlarmSnapshot,
        now: Long = System.currentTimeMillis()
    ): String = when {
        isStale(snapshot, now) -> resources.getString(R.string.wear_phone_sync_stale)
        snapshot.isFiring -> resources.getString(R.string.wear_alarm_is_ringing)
        snapshot.hasAlarm -> listOf(
            snapshot.timeLabel.ifBlank { resources.getString(R.string.wear_scheduled) },
            snapshot.label,
            fixedZoneLabel(snapshot),
            formatRemaining(resources, snapshot.triggerTime, now)
        ).filter { it.isNotBlank() }.joinToString(SEPARATOR)
        else -> resources.getString(R.string.wear_no_phone_alarm_synced)
    }

    private fun fixedZoneLabel(snapshot: WearAlarmSnapshot): String =
        snapshot.fixedTimezoneId
            .takeIf { snapshot.timezonePolicy == "FIXED" && it.isNotBlank() }
            ?.replace('_', ' ')
            .orEmpty()
}

object WearAlarmStore {
    private const val PREFS = "wear_alarm_snapshot"

    fun load(context: Context): WearAlarmSnapshot {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return WearAlarmSnapshot(
            hasAlarm = prefs.getBoolean(WearAlarmData.KEY_HAS_ALARM, false),
            alarmId = prefs.getLong(WearAlarmData.KEY_ALARM_ID, -1L),
            label = prefs.getString(WearAlarmData.KEY_LABEL, "").orEmpty(),
            timeLabel = prefs.getString(WearAlarmData.KEY_TIME_LABEL, "").orEmpty(),
            triggerTime = prefs.getLong(WearAlarmData.KEY_TRIGGER_TIME, 0L),
            isFiring = prefs.getBoolean(WearAlarmData.KEY_IS_FIRING, false),
            updatedAt = prefs.getLong(WearAlarmData.KEY_UPDATED_AT, 0L),
            timezonePolicy = prefs.getString(WearAlarmData.KEY_TIMEZONE_POLICY, "LOCAL").orEmpty(),
            fixedTimezoneId = prefs.getString(WearAlarmData.KEY_FIXED_TIMEZONE_ID, "").orEmpty(),
        )
    }

    fun save(context: Context, snapshot: WearAlarmSnapshot) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit {
                putBoolean(WearAlarmData.KEY_HAS_ALARM, snapshot.hasAlarm)
                putLong(WearAlarmData.KEY_ALARM_ID, snapshot.alarmId)
                putString(WearAlarmData.KEY_LABEL, snapshot.label)
                putString(WearAlarmData.KEY_TIME_LABEL, snapshot.timeLabel)
                putLong(WearAlarmData.KEY_TRIGGER_TIME, snapshot.triggerTime)
                putBoolean(WearAlarmData.KEY_IS_FIRING, snapshot.isFiring)
                putLong(WearAlarmData.KEY_UPDATED_AT, snapshot.updatedAt)
                putString(WearAlarmData.KEY_TIMEZONE_POLICY, snapshot.timezonePolicy)
                putString(WearAlarmData.KEY_FIXED_TIMEZONE_ID, snapshot.fixedTimezoneId)
            }
    }

    fun fromDataMap(dataMap: DataMap): WearAlarmSnapshot {
        return WearAlarmSnapshot(
            hasAlarm = dataMap.getBoolean(WearAlarmData.KEY_HAS_ALARM, false),
            alarmId = dataMap.getLong(WearAlarmData.KEY_ALARM_ID, -1L),
            label = dataMap.getString(WearAlarmData.KEY_LABEL, "").orEmpty(),
            timeLabel = dataMap.getString(WearAlarmData.KEY_TIME_LABEL, "").orEmpty(),
            triggerTime = dataMap.getLong(WearAlarmData.KEY_TRIGGER_TIME, 0L),
            isFiring = dataMap.getBoolean(WearAlarmData.KEY_IS_FIRING, false),
            updatedAt = dataMap.getLong(WearAlarmData.KEY_UPDATED_AT, 0L),
            timezonePolicy = dataMap.getString(WearAlarmData.KEY_TIMEZONE_POLICY, "LOCAL").orEmpty(),
            fixedTimezoneId = dataMap.getString(WearAlarmData.KEY_FIXED_TIMEZONE_ID, "").orEmpty(),
        )
    }
}
