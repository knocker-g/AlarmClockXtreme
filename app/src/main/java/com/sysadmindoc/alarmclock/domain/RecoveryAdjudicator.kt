package com.sysadmindoc.alarmclock.domain

import com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import com.sysadmindoc.alarmclock.service.AlarmService.Companion.ActiveAlarmSnapshot
import javax.inject.Inject
import javax.inject.Singleton

enum class RecoveryDecisionOutcome {
    NO_ACTION,
    RECONCILE_AUTHORITY,
    RESUME_COMMITTED_SESSION_EFFECTS,
    QUARANTINE,
    NEEDS_EVIDENCE
}

enum class OsOperationOutcome {
    ACCEPTED,
    REJECTED,
    UNKNOWN,
    NOT_ATTEMPTED
}

data class RecoveryEvidenceSnapshot(
    val alarmId: Long,
    val authority: ScheduleAuthority?,
    val restoreEpochMeta: Long?,
    val durableIntent: DurableOperationIntent?,
    val activeSession: ActiveAlarmSnapshot?,
    val localIncarnationId: String?,
    val osOutcome: OsOperationOutcome = OsOperationOutcome.NOT_ATTEMPTED,
    val definitionExists: Boolean = false, // Default false: fail closed unless explicitly proven true!
    val evaluationTimeMillis: Long? = null // Explicit evaluation time input for deterministic Snooze expiry checking
)

data class RecoveryDecision(
    val outcome: RecoveryDecisionOutcome,
    val reasonCode: String,
    val alarmId: Long
)

/**
 * Deterministic, read-only evidence evaluation component for interrupted,
 * ambiguous, or generation-conflicting alarm operations.
 *
 * Guarantees:
 * - Read-only: does NOT perform OS operations, start Services, or mutate persistence.
 * - Pure & Deterministic: zero hidden system clock calls (does NOT call System.currentTimeMillis()).
 * - Requires explicit [snapshot.evaluationTimeMillis] for evaluating Snooze session expiry.
 * - Missing evaluation time returns NEEDS_EVIDENCE ("EVALUATION_TIME_MISSING").
 * - Evaluates generation integrity across Authority, DurableIntent, ActiveAlarmSnapshot, and LocalIncarnation.
 * - UNKNOWN and NOT_ATTEMPTED OS operation outcomes are NEVER promoted to fresh OS registration success.
 * - Fail-closed: missing, unverified, or contradictory evidence returns QUARANTINE or NEEDS_EVIDENCE.
 */
@Singleton
class RecoveryAdjudicator @Inject constructor() {

    fun adjudicate(snapshot: RecoveryEvidenceSnapshot): RecoveryDecision {
        val alarmId = snapshot.alarmId

        // 1. Definition Existence (default false)
        if (!snapshot.definitionExists) {
            return RecoveryDecision(RecoveryDecisionOutcome.QUARANTINE, "DEFINITION_MISSING", alarmId)
        }

        // 2. Metadata / Restore Epoch & Local Incarnation presence
        val restoreEpoch = snapshot.restoreEpochMeta
            ?: return RecoveryDecision(RecoveryDecisionOutcome.NEEDS_EVIDENCE, "RESTORE_EPOCH_META_MISSING", alarmId)

        val localIncarnationId = snapshot.localIncarnationId
        if (localIncarnationId.isNullOrBlank()) {
            return RecoveryDecision(RecoveryDecisionOutcome.NEEDS_EVIDENCE, "LOCAL_INCARNATION_MISSING", alarmId)
        }

        val auth = snapshot.authority
        val intent = snapshot.durableIntent
        val session = snapshot.activeSession

        // 3. Strict Identity Cross-Check across all provided evidence
        if (auth != null && auth.alarmId != alarmId) {
            return RecoveryDecision(RecoveryDecisionOutcome.QUARANTINE, "AUTHORITY_ALARM_ID_MISMATCH", alarmId)
        }
        if (intent != null && intent.alarmId != alarmId) {
            return RecoveryDecision(RecoveryDecisionOutcome.QUARANTINE, "INTENT_ALARM_ID_MISMATCH", alarmId)
        }
        if (session != null && session.alarmId != alarmId) {
            return RecoveryDecision(RecoveryDecisionOutcome.QUARANTINE, "SESSION_ALARM_ID_MISMATCH", alarmId)
        }

        // 4. Validate Authority generation fingerprint if present
        if (auth != null) {
            if (auth.restoreEpoch != restoreEpoch) {
                return RecoveryDecision(RecoveryDecisionOutcome.QUARANTINE, "RESTORE_EPOCH_MISMATCH", alarmId)
            }
            if (auth.instanceToken != localIncarnationId) {
                return RecoveryDecision(RecoveryDecisionOutcome.QUARANTINE, "STALE_INCARNATION", alarmId)
            }
            if (auth.reconcileState == "QUARANTINE") {
                return RecoveryDecision(RecoveryDecisionOutcome.QUARANTINE, "AUTHORITY_QUARANTINED", alarmId)
            }
        }

        // 5. Validate OS Operation Outcome (UNKNOWN OS outcome fails closed)
        if (snapshot.osOutcome == OsOperationOutcome.UNKNOWN) {
            return RecoveryDecision(RecoveryDecisionOutcome.QUARANTINE, "OS_OUTCOME_UNKNOWN", alarmId)
        }

        // 6. Validate DurableOperationIntent if present
        if (intent != null) {
            if (intent.expectedRestoreEpoch != null && intent.expectedRestoreEpoch != restoreEpoch) {
                return RecoveryDecision(RecoveryDecisionOutcome.QUARANTINE, "INTENT_RESTORE_EPOCH_MISMATCH", alarmId)
            }
            if (intent.incarnationId != localIncarnationId) {
                return RecoveryDecision(RecoveryDecisionOutcome.QUARANTINE, "INTENT_STALE_INCARNATION", alarmId)
            }
            if (intent.expectedOwnerToken != null && auth != null && intent.expectedOwnerToken != auth.instanceToken) {
                return RecoveryDecision(RecoveryDecisionOutcome.QUARANTINE, "INTENT_OWNER_TOKEN_MISMATCH", alarmId)
            }
            if (intent.expectedScheduleRevision != null && auth != null && intent.expectedScheduleRevision != auth.scheduleRevision) {
                return RecoveryDecision(RecoveryDecisionOutcome.QUARANTINE, "INTENT_SCHEDULE_REVISION_MISMATCH", alarmId)
            }

            // Session operation kind separation: SNOOZE and DISMISS belong to Session Manager flow
            if (intent.operationKind in setOf("SNOOZE", "DISMISS")) {
                if (intent.phase in setOf("PENDING", "IN_FLIGHT")) {
                    return RecoveryDecision(RecoveryDecisionOutcome.NEEDS_EVIDENCE, "SESSION_OPERATION_UNCOMMITTED", alarmId)
                }
            }

            when (intent.phase) {
                "PENDING" -> return RecoveryDecision(RecoveryDecisionOutcome.RECONCILE_AUTHORITY, "DURABLE_INTENT_PENDING", alarmId)
                "IN_FLIGHT" -> {
                    if (snapshot.osOutcome == OsOperationOutcome.REJECTED) {
                        return RecoveryDecision(RecoveryDecisionOutcome.QUARANTINE, "INTENT_OS_REJECTED", alarmId)
                    }
                    return RecoveryDecision(RecoveryDecisionOutcome.RECONCILE_AUTHORITY, "DURABLE_INTENT_IN_FLIGHT", alarmId)
                }
                "COMPLETED" -> {
                    if (session != null) {
                        val fireIdMatches = intent.originalFireId.isNullOrBlank() || intent.originalFireId == session.fireId
                        if (!fireIdMatches) {
                            return RecoveryDecision(RecoveryDecisionOutcome.QUARANTINE, "INTENT_FIRE_ID_MISMATCH", alarmId)
                        }
                        val scheduledAtMatches = intent.originalScheduledAt == null || intent.originalScheduledAt == 0L || intent.originalScheduledAt == session.scheduledAt
                        if (!scheduledAtMatches) {
                            return RecoveryDecision(RecoveryDecisionOutcome.QUARANTINE, "INTENT_SCHEDULED_AT_MISMATCH", alarmId)
                        }
                        if (session.state == "SNOOZED") {
                            val evalTime = snapshot.evaluationTimeMillis
                                ?: return RecoveryDecision(RecoveryDecisionOutcome.NEEDS_EVIDENCE, "EVALUATION_TIME_MISSING", alarmId)
                            if (evalTime >= session.refireAt) {
                                return RecoveryDecision(RecoveryDecisionOutcome.QUARANTINE, "EXPIRED_SNOOZED_SESSION_EVIDENCE", alarmId)
                            }
                        }
                        if (session.state in setOf("FIRING", "SNOOZED")) {
                            return RecoveryDecision(RecoveryDecisionOutcome.RESUME_COMMITTED_SESSION_EFFECTS, "COMPLETED_INTENT_WITH_SESSION", alarmId)
                        }
                    }
                    return RecoveryDecision(RecoveryDecisionOutcome.QUARANTINE, "COMPLETED_INTENT_MISSING_SESSION_PROOF", alarmId)
                }
                "ABORTED", "EXPIRED" -> {
                    return RecoveryDecision(RecoveryDecisionOutcome.QUARANTINE, "INTENT_TERMINATED_${intent.phase}", alarmId)
                }
            }
        }

        // 7. Evaluate Active Session Commit Proof
        if (session != null) {
            if (session.state == "SNOOZED") {
                val evalTime = snapshot.evaluationTimeMillis
                    ?: return RecoveryDecision(RecoveryDecisionOutcome.NEEDS_EVIDENCE, "EVALUATION_TIME_MISSING", alarmId)
                if (evalTime >= session.refireAt) {
                    return RecoveryDecision(RecoveryDecisionOutcome.QUARANTINE, "EXPIRED_SNOOZED_SESSION_EVIDENCE", alarmId)
                }
            }
            if (session.state in setOf("FIRING", "SNOOZED")) {
                if (auth != null && auth.reconcileState == "SETTLED") {
                    return RecoveryDecision(RecoveryDecisionOutcome.RESUME_COMMITTED_SESSION_EFFECTS, "ACTIVE_SESSION_COMMITTED", alarmId)
                }
                if (auth == null) {
                    return RecoveryDecision(RecoveryDecisionOutcome.NEEDS_EVIDENCE, "ACTIVE_SESSION_MISSING_AUTHORITY", alarmId)
                }
                return RecoveryDecision(RecoveryDecisionOutcome.QUARANTINE, "ACTIVE_SESSION_UNSETTLED_AUTHORITY", alarmId)
            }
            return RecoveryDecision(RecoveryDecisionOutcome.QUARANTINE, "ACTIVE_SESSION_UNKNOWN_STATE", alarmId)
        }

        // 8. Evaluate Authority Reconcile State
        if (auth != null) {
            if (auth.reconcileState == "RECONCILING") {
                return RecoveryDecision(RecoveryDecisionOutcome.RECONCILE_AUTHORITY, "AUTHORITY_RECONCILING", alarmId)
            }
            if (auth.reconcileState == "SETTLED") {
                return RecoveryDecision(RecoveryDecisionOutcome.NO_ACTION, "AUTHORITY_SETTLED_NO_ACTION", alarmId)
            }
        }

        // 9. Default: No authority and no active session
        return RecoveryDecision(RecoveryDecisionOutcome.NEEDS_EVIDENCE, "AUTHORITY_ABSENT", alarmId)
    }
}
