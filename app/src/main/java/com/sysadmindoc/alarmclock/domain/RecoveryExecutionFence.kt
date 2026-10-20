package com.sysadmindoc.alarmclock.domain

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

enum class ExecutionFenceVerificationOutcome {
    MATCHED_OBSERVATION,
    STALE_GENERATION,
    NEEDS_EVIDENCE,
    CONTRADICTORY_EVIDENCE
}

data class RecoveryExecutionFenceReport(
    val alarmId: Long,
    val outcome: ExecutionFenceVerificationOutcome,
    val reasonCode: String,
    val freshCollectionReport: RecoveryEvidenceCollectionReport
) {
    val isDiagnosticOnly: Boolean get() = true
    val isExecutable: Boolean get() = false
    val isActionable: Boolean get() = false
}

/**
 * Diagnostic-only verifier comparing a frozen RecoveryPlanFingerprint
 * against independently refreshed evidence.
 *
 * Guarantees:
 * - Read-only: does NOT perform OS operations, invoke Owner, start Services, or mutate persistence.
 * - Reads fresh evidence using [RecoveryEvidenceCollector].
 * - Compares frozen [RecoveryPlanFingerprint] against freshly collected evidence.
 * - Structurally diagnostic-only: [MATCHED_OBSERVATION] does NOT grant OS or Session execution permission.
 *   [isExecutable] and [isActionable] getters always return false.
 */
@Singleton
class RecoveryExecutionFence @Inject constructor(
    private val collector: RecoveryEvidenceCollector
) {

    companion object {
        private const val TAG = "RecoveryExecutionFence"
    }

    suspend fun verifyFence(planReport: RecoveryPlanReport): RecoveryExecutionFenceReport {
        val alarmId = planReport.alarmId
        val frozenFingerprint = planReport.fingerprint

        val freshCollection = collector.collectEvidence(alarmId)

        // 1. Check Fresh Evidence Collection Read Status
        if (freshCollection.overallStatus == EvidenceReadStatus.READ_FAILURE) {
            return makeReport(alarmId, ExecutionFenceVerificationOutcome.NEEDS_EVIDENCE, "FRESH_READ_FAILURE", freshCollection)
        }
        if (freshCollection.overallStatus == EvidenceReadStatus.STORAGE_UNAVAILABLE) {
            return makeReport(alarmId, ExecutionFenceVerificationOutcome.NEEDS_EVIDENCE, "FRESH_STORAGE_UNAVAILABLE", freshCollection)
        }

        // 2. Check Global Active Session Drift
        val globalSession = freshCollection.globalSessionEvidence
        if (globalSession.hasGlobalActiveSession && !globalSession.isSameAlarm) {
            return makeReport(alarmId, ExecutionFenceVerificationOutcome.CONTRADICTORY_EVIDENCE, "NEW_GLOBAL_ACTIVE_SESSION", freshCollection)
        }

        // 3. Verify Authority Fingerprint
        val frozenAuth = frozenFingerprint.authorityFingerprint
        val freshAuth = freshCollection.roomEvidence.authority
        val freshRestoreEpoch = freshCollection.roomEvidence.restoreEpoch
        val freshLocalMarker = freshCollection.localMarkerEvidence

        if (frozenAuth != null) {
            if (freshAuth == null || freshRestoreEpoch == null || freshLocalMarker.status != EvidenceReadStatus.PROVEN_PRESENT) {
                return makeReport(alarmId, ExecutionFenceVerificationOutcome.NEEDS_EVIDENCE, "FRESH_AUTHORITY_EVIDENCE_MISSING", freshCollection)
            }

            if (freshAuth.instanceToken != frozenAuth.instanceToken) {
                return makeReport(alarmId, ExecutionFenceVerificationOutcome.STALE_GENERATION, "INCARNATION_TOKEN_CHANGED", freshCollection)
            }
            if (freshRestoreEpoch != frozenAuth.restoreEpoch) {
                return makeReport(alarmId, ExecutionFenceVerificationOutcome.STALE_GENERATION, "RESTORE_EPOCH_CHANGED", freshCollection)
            }
            if (freshAuth.definitionRevision != frozenAuth.definitionRevision) {
                return makeReport(alarmId, ExecutionFenceVerificationOutcome.STALE_GENERATION, "DEFINITION_REVISION_CHANGED", freshCollection)
            }
            if (freshAuth.scheduleRevision != frozenAuth.scheduleRevision) {
                return makeReport(alarmId, ExecutionFenceVerificationOutcome.STALE_GENERATION, "SCHEDULE_REVISION_CHANGED", freshCollection)
            }
            if (freshAuth.lifecycle != frozenAuth.lifecycle) {
                return makeReport(alarmId, ExecutionFenceVerificationOutcome.STALE_GENERATION, "LIFECYCLE_CHANGED", freshCollection)
            }
            if (freshAuth.actionKey != frozenAuth.actionKey) {
                return makeReport(alarmId, ExecutionFenceVerificationOutcome.STALE_GENERATION, "ACTION_KEY_CHANGED", freshCollection)
            }
            if (freshAuth.desiredOperation != frozenAuth.desiredOperation) {
                return makeReport(alarmId, ExecutionFenceVerificationOutcome.STALE_GENERATION, "DESIRED_OPERATION_CHANGED", freshCollection)
            }
            if (freshAuth.desiredTriggerMillis != frozenAuth.desiredTriggerMillis) {
                return makeReport(alarmId, ExecutionFenceVerificationOutcome.STALE_GENERATION, "DESIRED_TRIGGER_CHANGED", freshCollection)
            }
        }

        // 4. Verify Operation Fingerprint
        val frozenOp = frozenFingerprint.operationFingerprint
        if (frozenOp != null) {
            val matchingIntents = freshCollection.roomEvidence.intents.filter { it.operationToken == frozenOp.operationToken }
            if (matchingIntents.isEmpty()) {
                return makeReport(alarmId, ExecutionFenceVerificationOutcome.NEEDS_EVIDENCE, "OPERATION_TOKEN_MISSING", freshCollection)
            }
            if (matchingIntents.size > 1) {
                return makeReport(alarmId, ExecutionFenceVerificationOutcome.CONTRADICTORY_EVIDENCE, "AMBIGUOUS_DUPLICATE_OPERATION_TOKEN", freshCollection)
            }

            val freshOp = matchingIntents.first()
            if (freshOp.operationEpoch != frozenOp.operationEpoch) {
                return makeReport(alarmId, ExecutionFenceVerificationOutcome.STALE_GENERATION, "OPERATION_EPOCH_CHANGED", freshCollection)
            }
            if (freshOp.intentRevision != frozenOp.intentRevision) {
                return makeReport(alarmId, ExecutionFenceVerificationOutcome.STALE_GENERATION, "INTENT_REVISION_CHANGED", freshCollection)
            }
            if (freshOp.operationKind != frozenOp.operationKind) {
                return makeReport(alarmId, ExecutionFenceVerificationOutcome.STALE_GENERATION, "OPERATION_KIND_CHANGED", freshCollection)
            }
            if (freshOp.phase != frozenOp.phase) {
                return makeReport(alarmId, ExecutionFenceVerificationOutcome.STALE_GENERATION, "INTENT_PHASE_CHANGED", freshCollection)
            }
        }

        // 5. Verify Session Fingerprint
        val frozenSession = frozenFingerprint.sessionFingerprint
        val freshSession = freshCollection.sessionEvidence.snapshot
        if (frozenSession != null) {
            if (freshSession == null || freshCollection.sessionEvidence.status != EvidenceReadStatus.PROVEN_PRESENT) {
                return makeReport(alarmId, ExecutionFenceVerificationOutcome.NEEDS_EVIDENCE, "FRESH_SESSION_EVIDENCE_MISSING", freshCollection)
            }

            if (freshSession.fireId != frozenSession.fireId) {
                return makeReport(alarmId, ExecutionFenceVerificationOutcome.STALE_GENERATION, "SESSION_FIRE_ID_CHANGED", freshCollection)
            }
            if (freshSession.scheduledAt != frozenSession.scheduledAt) {
                return makeReport(alarmId, ExecutionFenceVerificationOutcome.STALE_GENERATION, "SESSION_SCHEDULED_AT_CHANGED", freshCollection)
            }
            if (freshSession.state != frozenSession.state) {
                return makeReport(alarmId, ExecutionFenceVerificationOutcome.STALE_GENERATION, "SESSION_STATE_CHANGED", freshCollection)
            }
            if (freshSession.refireAt != frozenSession.refireAt) {
                return makeReport(alarmId, ExecutionFenceVerificationOutcome.STALE_GENERATION, "SESSION_REFIRE_AT_CHANGED", freshCollection)
            }
        }

        // 6. Check Overall Evidence Coherence
        if (freshCollection.coherenceClassification == CrossStoreCoherenceClassification.CONTRADICTORY) {
            return makeReport(alarmId, ExecutionFenceVerificationOutcome.CONTRADICTORY_EVIDENCE, "FRESH_COHERENCE_CONTRADICTORY", freshCollection)
        }
        if (freshCollection.coherenceClassification == CrossStoreCoherenceClassification.UNPROVEN) {
            return makeReport(alarmId, ExecutionFenceVerificationOutcome.NEEDS_EVIDENCE, "FRESH_COHERENCE_UNPROVEN", freshCollection)
        }

        Log.i(TAG, "Execution fence verified MATCHED_OBSERVATION for alarm $alarmId")
        return makeReport(alarmId, ExecutionFenceVerificationOutcome.MATCHED_OBSERVATION, "MATCHED_OBSERVATION", freshCollection)
    }

    private fun makeReport(
        alarmId: Long,
        outcome: ExecutionFenceVerificationOutcome,
        reasonCode: String,
        freshCollectionReport: RecoveryEvidenceCollectionReport
    ): RecoveryExecutionFenceReport {
        Log.i(TAG, "Verified execution fence for alarm $alarmId: outcome=$outcome, reason=$reasonCode")
        return RecoveryExecutionFenceReport(
            alarmId = alarmId,
            outcome = outcome,
            reasonCode = reasonCode,
            freshCollectionReport = freshCollectionReport
        )
    }
}
