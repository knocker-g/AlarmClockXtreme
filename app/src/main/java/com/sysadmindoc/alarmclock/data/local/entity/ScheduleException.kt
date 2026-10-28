package com.sysadmindoc.alarmclock.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persisted Room entity for Normal Schedule Exceptions (KNO-152 / KNO-30).
 * Captures occurrence-level exceptions (SKIP, TIME_OVERRIDE, CANCEL) attached to a specific
 * alarm's base trigger time and schedule generation.
 */
@Entity(
    tableName = "schedule_exceptions",
    indices = [
        Index(value = ["alarmId", "baseTriggerTime", "generation", "revision"], unique = true),
        Index(value = ["alarmId"])
    ]
)
data class ScheduleException(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val alarmId: Long,
    val baseTriggerTime: Long,
    val exceptionType: String,
    val overrideTriggerTime: Long = 0L,
    val generation: Int = 1,
    val revision: Int = 1,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val TYPE_SKIP = "SKIP"
        const val TYPE_TIME_OVERRIDE = "TIME_OVERRIDE"
        const val TYPE_CANCEL = "CANCEL"
    }
}
