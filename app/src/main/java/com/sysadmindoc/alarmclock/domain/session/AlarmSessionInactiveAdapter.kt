package com.sysadmindoc.alarmclock.domain.session

import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.preferences.AppSettings

/**
 * Pure, offline-testable inactive adapter mapping legacy lifecycle facts
 * into KNO-161 domain events & canonical transition envelopes.
 *
 * 0 Room dependency, 0 Service dependency, 0 OS scheduling, 0 DataStore mutation.
 */
object AlarmSessionInactiveAdapter {

    fun createAdmitEvent(
        alarm: Alarm,
        settings: AppSettings,
        effectiveNormalAutoSilenceMinutes: Int?,
        fireId: String,
        occurrenceKey: String,
        canonicalBaseTriggerMs: Long,
        scheduledTargetEpochMs: Long,
        nowEpochMs: Long,
        elapsedRealtimeMs: Long,
        bootCount: Long
    ): AlarmSessionEvent.AdmitSession {
        require(alarm.id > 0L) { "Invalid alarm.id <= 0" }
        require(fireId.isNotBlank()) { "fireId cannot be blank" }
        require(occurrenceKey.isNotBlank()) { "occurrenceKey cannot be blank" }

        return AlarmSessionEvent.AdmitSession(
            alarm = alarm,
            settings = settings,
            effectiveNormalAutoSilenceMinutes = effectiveNormalAutoSilenceMinutes,
            fireId = fireId,
            occurrenceKey = occurrenceKey,
            canonicalBaseTriggerMs = canonicalBaseTriggerMs,
            scheduledTargetEpochMs = scheduledTargetEpochMs,
            nowEpochMs = nowEpochMs,
            elapsedRealtimeMs = elapsedRealtimeMs,
            bootCount = bootCount
        )
    }

    fun createTransitionRequest(
        sessionId: String,
        expectedVersion: Long,
        expectedState: String,
        expectedAdmissionKey: String,
        expectedGeneration: Int,
        expectedRevision: Int,
        currentWallClockMs: Long,
        currentElapsedRealtimeMs: Long,
        currentBootCount: Long,
        event: AlarmSessionEvent
    ): AlarmSessionTransitionRequest {
        return AlarmSessionTransitionRequest(
            sessionId = sessionId,
            expectedVersion = expectedVersion,
            expectedState = expectedState,
            expectedAdmissionKey = expectedAdmissionKey,
            expectedGeneration = expectedGeneration,
            expectedRevision = expectedRevision,
            currentWallClockMs = currentWallClockMs,
            currentElapsedRealtimeMs = currentElapsedRealtimeMs,
            currentBootCount = currentBootCount,
            event = event
        )
    }
}
