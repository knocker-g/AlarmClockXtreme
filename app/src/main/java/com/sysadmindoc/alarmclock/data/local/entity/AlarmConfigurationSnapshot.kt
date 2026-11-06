package com.sysadmindoc.alarmclock.data.local.entity

import com.squareup.moshi.JsonClass
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.preferences.AppSettings

/**
 * Immutable snapshot of Alarm configuration definition captured at session admission time.
 * Serialized to Moshi JSON inside [AlarmSessionEntity.configurationSnapshotJson].
 */
@JsonClass(generateAdapter = true)
data class AlarmConfigurationSnapshot(
    val snapshotVersion: Int = 1,
    val alarmId: Long,
    val kind: String = "NORMAL",                       // "NORMAL" or "QUICK"
    val quickTriggerEpochMs: Long = 0L,
    val label: String = "",
    val group: String = "",
    val ringtoneUri: String = "",
    val ringtonePool: String = "",
    val vibrationEnabled: Boolean = true,
    val vibrationIntensity: Int = 2,
    val vibrationPattern: String = "default",
    val volume: Int = 100,
    val overrideSystemVolume: Boolean = true,
    val gradualVolumeSeconds: Int = 60,
    val snoozeEnabled: Boolean = true,
    val snoozeDurationMinutes: Int = 10,
    val maxSnoozeCount: Int = 3,                       // 0 = unlimited, -1 = disabled
    val progressiveSnoozeEnabled: Boolean = false,
    val progressiveSnoozeDecreaseMinutes: Int = 1,     // Each snooze shortens by 1 minute
    val progressiveSnoozeMinMinutes: Int = 1,          // Minimum snooze duration floor
    val wakeConfirmEnabled: Boolean = false,
    val wakeConfirmDelayMinutes: Int = 10,
    val autoSilenceMinutes: Int = 10,                  // Effective per-alarm auto-silence
    val quickAutoSilenceMinutes: Int = 10,             // Common Quick Alarm auto-end
    val challengeType: String = "NONE",
    val challengeChain: String = "",
    val walkStepsRequired: Int = 30,
    val requiredSquats: Int = 10,
    val holdToDismissEnabled: Boolean = false,
    val hardwareButtonAction: String = "NONE",
    val dismissAtRingtoneEnd: Boolean = false
) {
    companion object {
        fun from(alarm: Alarm, settings: AppSettings): AlarmConfigurationSnapshot {
            val isQuick = alarm.kind.equals("QUICK", ignoreCase = true)
            val effectiveSnoozeMinutes = if (alarm.snoozeDurationMinutes > 0) {
                alarm.snoozeDurationMinutes
            } else {
                settings.defaultSnoozeDuration
            }

            return AlarmConfigurationSnapshot(
                snapshotVersion = 1,
                alarmId = alarm.id,
                kind = if (isQuick) "QUICK" else "NORMAL",
                quickTriggerEpochMs = if (isQuick) alarm.quickTriggerEpochMs else 0L,
                label = alarm.label,
                group = alarm.group,
                ringtoneUri = alarm.ringtoneUri,
                ringtonePool = alarm.ringtonePool,
                vibrationEnabled = alarm.vibrationEnabled,
                vibrationIntensity = alarm.vibrationIntensity,
                vibrationPattern = alarm.vibrationPattern,
                volume = alarm.volume,
                overrideSystemVolume = alarm.overrideSystemVolume,
                gradualVolumeSeconds = alarm.gradualVolumeSeconds,
                snoozeEnabled = if (isQuick) false else alarm.maxSnoozeCount != -1,
                snoozeDurationMinutes = if (isQuick) 0 else effectiveSnoozeMinutes,
                maxSnoozeCount = if (isQuick) -1 else alarm.maxSnoozeCount,
                progressiveSnoozeEnabled = if (isQuick) false else alarm.progressiveSnooze,
                progressiveSnoozeDecreaseMinutes = 1,
                progressiveSnoozeMinMinutes = 1,
                wakeConfirmEnabled = if (isQuick) false else alarm.wakeConfirmEnabled,
                wakeConfirmDelayMinutes = if (isQuick) 0 else alarm.wakeConfirmDelayMinutes,
                autoSilenceMinutes = settings.autoSilenceMinutes,
                quickAutoSilenceMinutes = settings.autoSilenceMinutes,
                challengeType = if (isQuick) "NONE" else alarm.challengeType,
                challengeChain = if (isQuick) "" else alarm.challengeChain,
                walkStepsRequired = if (isQuick) 0 else alarm.walkStepsRequired,
                requiredSquats = if (isQuick) 0 else alarm.requiredSquats,
                holdToDismissEnabled = alarm.holdToDismissEnabled,
                hardwareButtonAction = alarm.hardwareButtonAction,
                dismissAtRingtoneEnd = alarm.dismissAtRingtoneEnd
            )
        }
    }
}
