package com.sysadmindoc.alarmclock.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Single-row sequence generator table stored in Room database (Schema 37).
 * Provides a durable, monotonic sequence allocator for session creation ordering.
 */
@Entity(tableName = "session_sequence_generator")
data class SessionSequenceGeneratorEntity(
    @PrimaryKey
    val id: Int = 1,
    val lastAllocatedSequence: Long
)
