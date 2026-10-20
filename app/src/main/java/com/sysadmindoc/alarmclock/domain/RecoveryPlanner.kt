package com.sysadmindoc.alarmclock.domain

import android.util.Log
import com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import com.sysadmindoc.alarmclock.service.AlarmService.Companion.ActiveAlarmSnapshot
import javax.inject.Inject
import javax.inject.Singleton

enum class RecoveryPlanOutcome {
    NO_ACTION,
    INSPECT_AUTHORITY,
    INSPECT_SESSION_EFFECTS,
    BLOCKED_MISSING_EVIDENCE,
    BLOCKED_CONFLICT
}

data class AuthorityFingerprint(
    val alarmId: Long,
    val instanceToken: String,
    val restoreEpoch: Long,
    val definitionRevision: Long,
    val scheduleRevision: Long,
    val lifecycle: String,
    val actionKey: String?,
    val desiredOperation: String,
    val desiredTriggerMillis: Long?
)

data class OperationFingerprint(
    val operationToken: String,
    val operationEpoch: Long,
    val intentRevision: Long,
    val operationKind: String,
    val phase: String
)

data class SessionFingerprint(
    val fireId: String,
    val scheduledAt: Long,
    val state: String,
    val refireAt: Long
)

data class RecoveryPlanFingerprint(
    val alarmId: Long,
    val authorityFingerprint: AuthorityFingerprint?,
    val operationFingerprint: OperationFingerprint?,
    val sessionFingerprint: SessionFingerprint?
)

data class RecoveryPlanReport(
    val alarmId: Long,
    val outcome: RecoveryPlanOutcome,
    val reasonCode: String,
    val fingerprint: RecoveryPlanFingerprint,
    val bridgeReport: RecoveryDiagnosticBridgeReport
) {
    val isDiagnosticOnly: Boolean get() = true
    val isExecutable: Boolean get() = false
    val isActionable: Boolean get() = false
}

/**
 * Pure, deterministic, diagnostic-only Recovery Planner component.
 *
 * Guarantees:
 * - Pure: does NOT perform OS operations, invoke Owner, start Services, or mutate persistence.
 * - Does NOT use system clock or wall-clock timing.
 * - Evaluates [RecoveryDiagnosticBridgeReport] evidence deterministically.
 * - Candidate intent selection requires a unique unresolved intent with proven operation identity.
 * - Disallows blank originalFireId or non-positive originalScheduledAt from proving session operation identity.
 * - Structurally diagnostic-only: [isExecutable] and [isActionable] getters always return false.
 */
@Singleton
class RecoveryPlanner @Inject constructor() {

    companion object {
        private const val TAG = "RecoveryPlanner"

        fun extractAuthorityFingerprint(authority: ScheduleAuthority?): AuthorityFingerprint? {
            return authority?.let {
                AuthorityFingerprint(
                    alarmId = it.alarmId,
                    instanceToken = it.instanceToken,
                    restoreEpoch = it.restoreEpoch,
                    definitionRevision = it.definitionRevision,
                    scheduleRevision = it.scheduleRevision,
                    lifecycle = it.lifecycle,
                    actionKey = it.actionKey,
                    desiredOperation = it.desiredOperation,
                    desiredTriggerMillis = it.desiredTriggerMillis
                )
            }
        }

        fun extractOperationFingerprint(intent: DurableOperationIntent?): OperationFingerprint? {
            return intent?.let {
                OperationFingerprint(
                    operationToken = it.operationToken,
                    operationEpoch = it.operationEpoch,
                    intentRevision = it.intentRevision,
                    operationKind = it.operationKind,
                    phase = it.phase
                )
            }
        }

        fun extractSessionFingerprint(snapshot: ActiveAlarmSnapshot?): SessionFingerprint? {
            return snapshot?.let {
                SessionFingerprint(
                    fireId = it.fireId,
                    scheduledAt = it.scheduledAt,
                    state = it.state,
                    refireAt = it.refireAt
                )
            }
        }
    }

    fun planRecovery(bridgeReport: RecoveryDiagnosticBridgeReport): RecoveryPlanReport {
        val alarmId = bridgeReport.alarmId
        val collection = bridgeReport.collectionReport
        val room = collection.roomEvidence

        val unresolvedIntents = room.intents.filter { it.phase in setOf("PENDING", "IN_FLIGHT") }
        val historicalIntents = room.intents.filter { it.phase !in setOf("PENDING", "IN_FLIGHT") }

        // Candidate operation identity is derived ONLY from a unique unresolved intent
        // or a unique historical intent. Multiple intents or ambiguous intents return null.
        val candidateIntent: DurableOperationIntent? = when {
            unresolvedIntents.size == 1 -> unresolvedIntents.first()
            unresolvedIntents.isEmpty() && historicalIntents.size == 1 -> historicalIntents.first()
            else -> null
        }

        val authFingerprint = extractAuthorityFingerprint(room.authority)
        val opFingerprint = extractOperationFingerprint(candidateIntent)
        val sessionFingerprint = extractSessionFingerprint(collection.sessionEvidence.snapshot)

        val planFingerprint = RecoveryPlanFingerprint(
            alarmId = alarmId,
            authorityFingerprint = authFingerprint,
            operationFingerprint = opFingerprint,
            sessionFingerprint = sessionFingerprint
        )

        // 1. Check NOT_EVALUATED Bridge Status
        if (bridgeReport.status == BridgeStatus.NOT_EVALUATED) {
            val reason = bridgeReport.reasonCode
            val outcome = when {
                reason.contains("CONTRADICTORY") || reason.contains("CONFLICT") || reason.contains("MULTIPLE") || reason.contains("AMBIGUOUS") || reason.contains("UNRESOLVED") -> {
                    RecoveryPlanOutcome.BLOCKED_CONFLICT
                }
                else -> RecoveryPlanOutcome.BLOCKED_MISSING_EVIDENCE
            }
            return makeReport(alarmId, outcome, "BRIDGE_NOT_EVALUATED_$reason", planFingerprint, bridgeReport)
        }

        // 2. Check Coherence Classification
        when (collection.coherenceClassification) {
            CrossStoreCoherenceClassification.CONTRADICTORY -> {
                return makeReport(alarmId, RecoveryPlanOutcome.BLOCKED_CONFLICT, "COHERENCE_CONTRADICTORY", planFingerprint, bridgeReport)
            }
            CrossStoreCoherenceClassification.UNPROVEN -> {
                return makeReport(alarmId, RecoveryPlanOutcome.BLOCKED_MISSING_EVIDENCE, "COHERENCE_UNPROVEN", planFingerprint, bridgeReport)
            }
            CrossStoreCoherenceClassification.UNAVAILABLE -> {
                return makeReport(alarmId, RecoveryPlanOutcome.BLOCKED_MISSING_EVIDENCE, "COHERENCE_UNAVAILABLE", planFingerprint, bridgeReport)
            }
            CrossStoreCoherenceClassification.CONSISTENT_OBSERVATION -> { /* Proceed */ }
        }

        // 3. Check Multiple Unresolved Operations
        if (collection.hasMultipleUnresolvedIntents) {
            return makeReport(alarmId, RecoveryPlanOutcome.BLOCKED_CONFLICT, "MULTIPLE_UNRESOLVED_INTENTS", planFingerprint, bridgeReport)
        }

        // 4. Map P1 Adjudicator Decision
        val decision = bridgeReport.diagnosticDecision
            ?: return makeReport(alarmId, RecoveryPlanOutcome.BLOCKED_MISSING_EVIDENCE, "DIAGNOSTIC_DECISION_MISSING", planFingerprint, bridgeReport)

        return when (decision.outcome) {
            RecoveryDecisionOutcome.NO_ACTION -> {
                makeReport(alarmId, RecoveryPlanOutcome.NO_ACTION, "P1_NO_ACTION", planFingerprint, bridgeReport)
            }
            RecoveryDecisionOutcome.RECONCILE_AUTHORITY -> {
                makeReport(alarmId, RecoveryPlanOutcome.INSPECT_AUTHORITY, "P1_RECONCILE_AUTHORITY_INSPECTION", planFingerprint, bridgeReport)
            }
            RecoveryDecisionOutcome.RESUME_COMMITTED_SESSION_EFFECTS -> {
                val session = collection.sessionEvidence.snapshot
                if (collection.sessionEvidence.status == EvidenceReadStatus.PROVEN_PRESENT &&
                    session != null &&
                    session.alarmId == alarmId &&
                    session.state in setOf("FIRING", "SNOOZED") &&
                    session.fireId.isNotBlank() &&
                    session.scheduledAt > 0L) {

                    if (candidateIntent == null) {
                        return makeReport(alarmId, RecoveryPlanOutcome.BLOCKED_MISSING_EVIDENCE, "CANDIDATE_OPERATION_INTENT_MISSING", planFingerprint, bridgeReport)
                    }

                    if (candidateIntent.originalFireId.isNullOrBlank() || candidateIntent.originalScheduledAt == null || candidateIntent.originalScheduledAt <= 0L) {
                        return makeReport(alarmId, RecoveryPlanOutcome.BLOCKED_MISSING_EVIDENCE, "INTENT_IDENTITY_EVIDENCE_MISSING", planFingerprint, bridgeReport)
                    }

                    if (candidateIntent.originalFireId != session.fireId) {
                        return makeReport(alarmId, RecoveryPlanOutcome.BLOCKED_CONFLICT, "INTENT_FIRE_ID_MISMATCH", planFingerprint, bridgeReport)
                    }

                    if (candidateIntent.originalScheduledAt != session.scheduledAt) {
                        return makeReport(alarmId, RecoveryPlanOutcome.BLOCKED_CONFLICT, "INTENT_SCHEDULED_AT_MISMATCH", planFingerprint, bridgeReport)
                    }

                    makeReport(alarmId, RecoveryPlanOutcome.INSPECT_SESSION_EFFECTS, "P1_SESSION_EFFECTS_INSPECTION", planFingerprint, bridgeReport)
                } else {
                    makeReport(alarmId, RecoveryPlanOutcome.BLOCKED_MISSING_EVIDENCE, "SESSION_COMMIT_PROOF_MISSING", planFingerprint, bridgeReport)
                }
            }
            RecoveryDecisionOutcome.QUARANTINE -> {
                makeReport(alarmId, RecoveryPlanOutcome.BLOCKED_CONFLICT, "P1_QUARANTINE_${decision.reasonCode}", planFingerprint, bridgeReport)
            }
            RecoveryDecisionOutcome.NEEDS_EVIDENCE -> {
                makeReport(alarmId, RecoveryPlanOutcome.BLOCKED_MISSING_EVIDENCE, "P1_NEEDS_EVIDENCE_${decision.reasonCode}", planFingerprint, bridgeReport)
            }
        }
    }

    private fun makeReport(
        alarmId: Long,
        outcome: RecoveryPlanOutcome,
        reasonCode: String,
        fingerprint: RecoveryPlanFingerprint,
        bridgeReport: RecoveryDiagnosticBridgeReport
    ): RecoveryPlanReport {
        Log.i(TAG, "Planned diagnostic recovery for alarm $alarmId: outcome=$outcome, reason=$reasonCode")
        return RecoveryPlanReport(
            alarmId = alarmId,
            outcome = outcome,
            reasonCode = reasonCode,
            fingerprint = fingerprint,
            bridgeReport = bridgeReport
        )
    }
}
