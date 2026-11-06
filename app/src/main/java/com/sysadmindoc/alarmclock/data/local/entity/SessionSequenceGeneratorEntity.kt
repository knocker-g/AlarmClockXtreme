package com.sysadmindoc.alarmclock.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Single-row sequence generator table stored in Room database (Schema 37).
 * Provides a durable, monotonic sequence allocator for session creation ordering.
 */
@Entity(tableName = "session_sequence_generator")
data class SessionSequenceGeneratorEntity(
    @PrimaryKey
    @ColumnInfo(defaultValue = "1")
    val id: Int = 1,
    val lastAllocatedSequence: Long
)
