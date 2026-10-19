package com.sysadmindoc.alarmclock.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "durable_unresolved_intent_slot")
data class DurableUnresolvedIntentSlot(
    @PrimaryKey
    val alarmId: Long,
    val operationToken: String,
    val operationEpoch: Long,
    val incarnationId: String,
    val operationKind: String,
    val phase: String
)
