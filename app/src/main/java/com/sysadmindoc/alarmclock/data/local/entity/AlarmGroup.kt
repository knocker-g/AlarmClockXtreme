package com.sysadmindoc.alarmclock.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persists group names even when not currently assigned to any alarm,
 * enabling reusability and future management features.
 */
@Entity(tableName = "alarm_groups")
data class AlarmGroup(
    @PrimaryKey
    val name: String
)
