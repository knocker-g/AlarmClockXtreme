package com.sysadmindoc.alarmclock.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "issued_occurrences",
    indices = [
        Index(value = ["alarmId", "scheduledAt"]),
        Index(value = ["status"]),
        Index(value = ["alarmId", "baseTriggerTime"])
    ]
)
data class IssuedOccurrence(
    @PrimaryKey
    val issueToken: String,
    val alarmId: Long,
    val scheduledAt: Long,
    val fireId: String,
    val generationId: Long,
    val status: String,
    val activatedAt: Long? = null,
    val recoveryLeaseToken: String? = null,
    val recoveryLeaseExpiresAt: Long? = null,
    val outcome: String = OUTCOME_PENDING,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val baseTriggerTime: Long = 0L,
    val alarmGeneration: Int = 0,
    val authorityInstanceToken: String? = null,
    val restoreEpoch: Long = 0L,
    val scheduleRevision: Long = 0L,
    val definitionRevision: Long = 0L
) {
    companion object {
        const val STATUS_PREPARED = "PREPARED"
        const val STATUS_ISSUED = "ISSUED"
        const val STATUS_ADMITTED = "ADMITTED"
        const val STATUS_REVOKED = "REVOKED"
        const val STATUS_SET_FAILED = "SET_FAILED"

        const val OUTCOME_PENDING = "PENDING"
        const val OUTCOME_POSTED = "POSTED"
        const val OUTCOME_FAILED = "FAILED"
    }
}
