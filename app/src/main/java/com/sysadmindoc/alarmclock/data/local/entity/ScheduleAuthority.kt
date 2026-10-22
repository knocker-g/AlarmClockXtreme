package com.sysadmindoc.alarmclock.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "schedule_authority")
data class ScheduleAuthority(
    @PrimaryKey
    val alarmId: Long,
    val instanceToken: String,
    val definitionRevision: Long,
    val scheduleRevision: Long,
    val lifecycle: String,
    val desiredOperation: String,
    val desiredTriggerMillis: Long?,
    val reconcileState: String,
    val restoreEpoch: Long,
    val actionKey: String?
)
