package com.sysadmindoc.alarmclock.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Core active alarm session entity stored in Room database (Schema 37).
 * Represents a single firing/active alarm session instance.
 */
@Entity(
    tableName = "alarm_sessions",
    indices = [
        Index(value = ["alarmId", "generation", "scheduledTargetEpochMs"]),
        Index(value = ["sessionState"]),
        Index(value = ["alarmId", "sessionState"]),
        Index(value = ["fireId"])
    ]
)
data class AlarmSessionEntity(
    @PrimaryKey
    val sessionId: String,
    val admissionKey: String,             // Reference to unique admission tombstone key
    val alarmId: Long,
    val originKind: String,              // "NORMAL" or "QUICK"
    val originScheduleKind: String,      // "REPEATING", "ONE_TIME", "QUICK"
    val generation: Int,
    val revision: Int,
    val fireId: String,
    val occurrenceKey: String,
    val canonicalBaseTriggerMs: Long,
    val scheduledTargetEpochMs: Long,
    val sessionState: String,            // "FIRING", "SNOOZED", "WAKE_CHECK_WAITING", "WAKE_CHECK_ACTIVE", "COMPLETED"
    @ColumnInfo(defaultValue = "1")
    val sessionVersion: Long = 1L,       // Monotonic CAS session version counter
    @ColumnInfo(defaultValue = "0")
    val creationSequence: Long = 0L,     // Monotonic creation order sequence
    @ColumnInfo(defaultValue = "0")
    val snoozeCount: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val refireEpochMs: Long = 0L,
    @ColumnInfo(defaultValue = "0")
    val challengeUnlocked: Boolean = false, // Separate persisted challenge unlock state
    val createdAtEpochMs: Long,
    val createdElapsedRealtimeMs: Long,  // Monotonic system runtime anchor
    @ColumnInfo(defaultValue = "-1")
    val bootCount: Long = -1L,           // System boot counter
    val updatedAtEpochMs: Long,
    val absoluteDeadlineEpochMs: Long,   // createdAtEpochMs + min(maxTimeoutMs, 18_000_000L)
    val configurationSnapshotJson: String,
    @ColumnInfo(defaultValue = "1")
    val snapshotVersion: Int = 1,
    val terminalReason: String? = null,  // "DISMISSED", "TIMEOUT", "SUPERSEDED", "ABORTED", "EMERGENCY_DISMISSED"
    val completedAtEpochMs: Long? = null
)
