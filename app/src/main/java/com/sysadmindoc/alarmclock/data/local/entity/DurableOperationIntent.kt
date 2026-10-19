package com.sysadmindoc.alarmclock.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "durable_operation_intent",
    indices = [
        Index(value = ["alarmId"])
    ]
)
data class DurableOperationIntent(
    @PrimaryKey
    val operationToken: String,
    val alarmId: Long,
    val operationEpoch: Long,
    val incarnationId: String,
    val operationKind: String,
    val expectedOwnerToken: String?,
    val expectedScheduleRevision: Long?,
    val expectedRestoreEpoch: Long?,
    val phase: String,
    val intentRevision: Long
)
