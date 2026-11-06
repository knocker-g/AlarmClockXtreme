package com.sysadmindoc.alarmclock.domain.session

import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.preferences.AppSettings

/**
 * Domain events driving session state transitions in KNO-161.
 */
sealed class AlarmSessionEvent {
    data class AdmitSession(
        val alarm: Alarm,
        val settings: AppSettings,
        val effectiveNormalAutoSilenceMinutes: Int? = null,
        val fireId: String,
        val occurrenceKey: String,
        val canonicalBaseTriggerMs: Long,
        val scheduledTargetEpochMs: Long,
        val nowEpochMs: Long,
        val elapsedRealtimeMs: Long,
        val bootCount: Long
    ) : AlarmSessionEvent()

    data class Stop(val timestampEpochMs: Long) : AlarmSessionEvent()
    data class Snooze(val timestampEpochMs: Long, val customMinutes: Int? = null) : AlarmSessionEvent()
    data class SnoozeRefire(val timestampEpochMs: Long) : AlarmSessionEvent()
    data class ActivateWakeCheck(val timestampEpochMs: Long) : AlarmSessionEvent()
    data class WakeCheckPassed(val timestampEpochMs: Long) : AlarmSessionEvent()
    data class WakeCheckTimeout(val timestampEpochMs: Long) : AlarmSessionEvent()
    data class AutoEndTimeout(val timestampEpochMs: Long) : AlarmSessionEvent()
    data class AbsoluteTimeout(val timestampEpochMs: Long, val reason: String = "ABSOLUTE_TIMEOUT") : AlarmSessionEvent()
    data class Supersede(val timestampEpochMs: Long) : AlarmSessionEvent()
    data class Abort(val timestampEpochMs: Long) : AlarmSessionEvent()
    data class EmergencyDismiss(val timestampEpochMs: Long) : AlarmSessionEvent()
}
