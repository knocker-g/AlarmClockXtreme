package com.sysadmindoc.alarmclock.service

import android.content.Intent
import com.sysadmindoc.alarmclock.data.local.entity.AlarmIncidentEvent
import com.sysadmindoc.alarmclock.domain.AlarmScheduler

internal data class AlarmGeneration(
    val alarmId: Long,
    val scheduledAt: Long,
    val fireId: String
) {
    val isValid: Boolean get() = alarmId > 0L && scheduledAt > 0L && fireId.isNotBlank()

    fun matches(other: AlarmGeneration?): Boolean {
        if (other == null) return false
        return alarmId == other.alarmId &&
            scheduledAt == other.scheduledAt &&
            fireId == other.fireId
    }

    companion object {
        /**
         * Strictly parses intent for actions (Snooze, Dismiss, Challenge Ducking).
         * Requires alarmId > 0, scheduledAt > 0, AND explicit non-blank fireId.
         * Fails closed (returns null) if any field is missing or blank.
         */
        fun fromActionIntent(intent: Intent?): AlarmGeneration? {
            if (intent == null) return null
            val alarmId = intent.getLongExtra(AlarmScheduler.EXTRA_ALARM_ID, -1L)
            if (alarmId <= 0L) return null
            val scheduledAt = intent.getLongExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, 0L)
            if (scheduledAt <= 0L) return null
            val fireId = intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID)?.trim() ?: ""
            if (fireId.isBlank()) return null
            return AlarmGeneration(alarmId, scheduledAt, fireId)
        }

        /**
         * Parses intent for START commands.
         * Requires alarmId > 0 and scheduledAt > 0.
         * Auto-completes fireId if EXTRA_ALARM_FIRE_ID is absent for legacy START producer compatibility.
         */
        fun fromStartIntent(intent: Intent?): AlarmGeneration? {
            if (intent == null) return null
            val alarmId = intent.getLongExtra(AlarmScheduler.EXTRA_ALARM_ID, -1L)
            if (alarmId <= 0L) return null
            val scheduledAt = intent.getLongExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, 0L)
            if (scheduledAt <= 0L) return null
            val rawFireId = intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID)?.trim()
            val fireId = if (!rawFireId.isNullOrBlank()) {
                rawFireId
            } else {
                AlarmIncidentEvent.fireIdFor(alarmId, scheduledAt)
            }
            if (fireId.isBlank()) return null
            return AlarmGeneration(alarmId, scheduledAt, fireId)
        }
    }
}
