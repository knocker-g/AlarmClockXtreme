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
) {
    companion object {
        const val PHASE_PENDING = "PENDING"
        const val PHASE_IN_FLIGHT = "IN_FLIGHT"
        const val PHASE_COMPLETED = "COMPLETED"
        const val PHASE_ABORTED = "ABORTED"
        const val PHASE_EXPIRED = "EXPIRED"

        val UNRESOLVED_PHASES = setOf(PHASE_PENDING, PHASE_IN_FLIGHT)
        val VALID_PHASES = setOf(PHASE_PENDING, PHASE_IN_FLIGHT, PHASE_COMPLETED, PHASE_ABORTED, PHASE_EXPIRED)

        fun isValidPhase(phase: String): Boolean = phase in VALID_PHASES

        fun isUnresolvedPhase(phase: String): Boolean = phase in UNRESOLVED_PHASES

        fun validateForInsert(intent: DurableOperationIntent) {
            require(intent.operationToken.isNotBlank()) { "operationToken must not be blank" }
            require(intent.alarmId > 0L) { "alarmId must be positive" }
            require(intent.operationEpoch > 0L) { "operationEpoch must be positive" }
            require(intent.incarnationId.isNotBlank()) { "incarnationId must not be blank" }
            require(intent.operationKind.isNotBlank()) { "operationKind must not be blank" }
            require(intent.intentRevision > 0L) { "intentRevision must be positive" }
            require(isValidPhase(intent.phase)) { "Invalid operation phase: ${intent.phase}" }
        }
    }
}
