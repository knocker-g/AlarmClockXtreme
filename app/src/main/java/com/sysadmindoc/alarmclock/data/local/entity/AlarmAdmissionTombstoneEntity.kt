package com.sysadmindoc.alarmclock.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Permanent admission tombstone entity stored in Room database (Schema 37).
 * Guarantees at most 1 active/firing session can ever be admitted for a given logical occurrence.
 */
@Entity(
    tableName = "alarm_admission_tombstones",
    indices = [
        Index(value = ["admissionKey"], unique = true),
        Index(value = ["alarmId", "occurrenceKey"])
    ]
)
data class AlarmAdmissionTombstoneEntity(
    @PrimaryKey
    val admissionKey: String,          // Unique key enforcing at most 1 session per occurrence/Quick trigger
    val alarmId: Long,
    val sessionId: String,
    val occurrenceKey: String,
    val createdAtEpochMs: Long,
    val horizonEpochMs: Long           // Horizon boundary before which tombstone must be retained
)
