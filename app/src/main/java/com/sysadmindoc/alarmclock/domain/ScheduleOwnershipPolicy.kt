package com.sysadmindoc.alarmclock.domain

enum class MutationOperation {
    SET,
    CANCEL
}

enum class ScheduleOwnershipDecision {
    ADMIT,
    REJECT_STALE,
    REJECT_DELETED,
    REJECT_INVALID,
    NEEDS_RECONCILE
}

data class DefinitionOwner(
    val alarmId: Long,
    val instanceToken: String,
    val definitionRevision: Long,
    val scheduleRevision: Long,
    val exists: Boolean = true,
    val enabled: Boolean = true,
    val reconciliationRequired: Boolean = false
)

data class MutationRequest(
    val alarmId: Long,
    val instanceToken: String,
    val expectedDefinitionRevision: Long,
    val expectedScheduleRevision: Long,
    val operation: MutationOperation,
    val triggerAtMillis: Long? = null
)

object ScheduleOwnershipPolicy {

    /**
     * Evaluates a mutation request against a definition owner according to the
     * normative KNO-227 P0 Design Lock decision order.
     */
    fun evaluate(owner: DefinitionOwner, request: MutationRequest): ScheduleOwnershipDecision {
        // 1. Invalid request -> REJECT_INVALID
        if (request.alarmId <= 0L ||
            request.instanceToken.isBlank() ||
            request.expectedDefinitionRevision <= 0L ||
            request.expectedScheduleRevision <= 0L
        ) {
            return ScheduleOwnershipDecision.REJECT_INVALID
        }

        if (request.operation == MutationOperation.SET) {
            if (request.triggerAtMillis == null || request.triggerAtMillis <= 0L) {
                return ScheduleOwnershipDecision.REJECT_INVALID
            }
        } else if (request.operation == MutationOperation.CANCEL) {
            if (request.triggerAtMillis != null) {
                return ScheduleOwnershipDecision.REJECT_INVALID
            }
        }

        // 2. Missing or deleted owner -> REJECT_DELETED
        if (!owner.exists) {
            return ScheduleOwnershipDecision.REJECT_DELETED
        }

        // 3. Invalid/corrupt owner -> NEEDS_RECONCILE
        if (owner.alarmId <= 0L ||
            owner.instanceToken.isBlank() ||
            owner.definitionRevision <= 0L ||
            owner.scheduleRevision <= 0L
        ) {
            return ScheduleOwnershipDecision.NEEDS_RECONCILE
        }

        // 4. Mismatched ID, token, definition revision or schedule revision -> REJECT_STALE
        if (request.alarmId != owner.alarmId ||
            request.instanceToken != owner.instanceToken ||
            request.expectedDefinitionRevision != owner.definitionRevision ||
            request.expectedScheduleRevision != owner.scheduleRevision
        ) {
            return ScheduleOwnershipDecision.REJECT_STALE
        }

        // 5. Owner requires reconciliation -> NEEDS_RECONCILE
        if (owner.reconciliationRequired) {
            return ScheduleOwnershipDecision.NEEDS_RECONCILE
        }

        // 6. SET against disabled owner -> REJECT_INVALID
        if (request.operation == MutationOperation.SET && !owner.enabled) {
            return ScheduleOwnershipDecision.REJECT_INVALID
        }

        // 7. Otherwise -> ADMIT
        return ScheduleOwnershipDecision.ADMIT
    }
}
