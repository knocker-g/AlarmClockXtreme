package com.sysadmindoc.alarmclock.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "issue_slots")
data class IssueSlot(
    @PrimaryKey
    val alarmId: Long,
    val currentIssueToken: String? = null,
    val generationId: Long = 1L
)
