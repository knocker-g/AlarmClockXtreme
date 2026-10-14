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
        fun fromIntent(intent: Intent?): AlarmGeneration? {
            if (intent == null) return null
            val alarmId = intent.getLongExtra(AlarmScheduler.EXTRA_ALARM_ID, -1L)
            if (alarmId <= 0L) return null
            val scheduledAt = intent.getLongExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, 0L)
            val rawFireId = intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID)
            val fireId = if (!rawFireId.isNullOrBlank()) {
                rawFireId
            } else if (scheduledAt > 0L) {
                AlarmIncidentEvent.fireIdFor(alarmId, scheduledAt)
            } else {
                ""
            }
            if (scheduledAt <= 0L || fireId.isBlank()) return null
            return AlarmGeneration(alarmId, scheduledAt, fireId)
        }
    }
}
