package com.sysadmindoc.alarmclock.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "schedule_authority_meta")
data class ScheduleAuthorityMeta(
    @PrimaryKey
    val id: Int,
    val restoreEpoch: Long,
    val bootstrapState: String
)
