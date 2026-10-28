package com.sysadmindoc.alarmclock.data.model

import com.squareup.moshi.JsonClass

/**
 * Value model for a stable Normal Alarm Occurrence identity (KNO-152).
 * Uniquely identifies a specific occurrence instance of an alarm without relying
 * solely on the volatile nextTriggerTime projection value.
 *
 * KNO-32 Alignment: Occurrence identity depends on schedule definition generation,
 * so secondary settings updates (label, volume, ringtoneUri) that increment revision
 * preserve occurrence identity and keep active overrides intact.
 */
@JsonClass(generateAdapter = true)
data class AlarmOccurrenceId(
    val alarmId: Long,
    val baseTriggerTime: Long,
    val generation: Int = 1
) {
    val key: String
        get() = "alarm_${alarmId}_gen_${generation}_base_${baseTriggerTime}"

    companion object {
        fun fromAlarm(alarm: Alarm, baseTriggerTime: Long): AlarmOccurrenceId {
            return AlarmOccurrenceId(
                alarmId = alarm.id,
                baseTriggerTime = baseTriggerTime,
                generation = alarm.generation
            )
        }
    }
}
