package com.sysadmindoc.alarmclock.domain

import android.util.Log
import com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent
import javax.inject.Inject
import javax.inject.Singleton

enum class BridgeStatus {
    NOT_EVALUATED,
    EVALUATED_DIAGNOSTICALLY
}

data class RecoveryDiagnosticBridgeReport(
    val alarmId: Long,
    val status: BridgeStatus,
    val reasonCode: String,
    val collectionReport: RecoveryEvidenceCollectionReport,
    val diagnosticDecision: RecoveryDecision?
) {
    val isDiagnosticOnly: Boolean get() = true
    val isActionable: Boolean get() = false
}

/**
 * Strictly read-only bridge between RecoveryEvidenceCollector (P2) and RecoveryAdjudicator (P1).
 *
 * Guarantees:
 * - Read-only: does NOT perform OS operations, start Services, or mutate persistence.
 * - Evaluates evidence admission criteria before constructing P1 snapshot.
 * - Rejects ambiguous or multiple unresolved/historical DurableOperationIntents (does NOT select by list position).
 * - Structural diagnostic contract: [isDiagnosticOnly] is always true and [isActionable] is always false.
 * - Adjudication outcomes are DIAGNOSTIC ONLY. Never invokes Owner or AlarmScheduler.
 */
@Singleton
class RecoveryDiagnosticBridge @Inject constructor(
    private val collector: RecoveryEvidenceCollector,
    private val adjudicator: RecoveryAdjudicator
) {

    companion object {
        private const val TAG = "RecoveryDiagnosticBridge"
    }

    suspend fun evaluateDiagnosticRecovery(alarmId: Long): RecoveryDiagnosticBridgeReport {
        val collectionReport = collector.collectEvidence(alarmId)

        // 1. Check Global Active Session
        val globalSession = collectionReport.globalSessionEvidence
        if (globalSession.hasGlobalActiveSession && !globalSession.isSameAlarm) {
            return notEvaluated(alarmId, "OTHER_ALARM_ACTIVE_SESSION", collectionReport)
        }

        // 2. Check Coherence Classification & Overall Status
        if (collectionReport.coherenceClassification == CrossStoreCoherenceClassification.CONTRADICTORY) {
            return notEvaluated(alarmId, "COHERENCE_CONTRADICTORY", collectionReport)
        }
        if (collectionReport.coherenceClassification == CrossStoreCoherenceClassification.UNPROVEN) {
            return notEvaluated(alarmId, "COHERENCE_UNPROVEN", collectionReport)
        }
        if (collectionReport.coherenceClassification == CrossStoreCoherenceClassification.UNAVAILABLE) {
            return notEvaluated(alarmId, "COHERENCE_UNAVAILABLE", collectionReport)
        }
        if (collectionReport.overallStatus == EvidenceReadStatus.READ_FAILURE) {
            return notEvaluated(alarmId, "EVIDENCE_READ_FAILURE", collectionReport)
        }
        if (collectionReport.overallStatus == EvidenceReadStatus.STORAGE_UNAVAILABLE) {
            return notEvaluated(alarmId, "STORAGE_UNAVAILABLE", collectionReport)
        }
        if (collectionReport.overallStatus == EvidenceReadStatus.CORRUPT_DATA) {
            return notEvaluated(alarmId, "CORRUPT_EVIDENCE_DATA", collectionReport)
        }

        // 3. Check Room Evidence (Alarm Definition & Authority & Restore Epoch)
        val room = collectionReport.roomEvidence
        if (room.alarm == null) {
            return notEvaluated(alarmId, "ALARM_DEFINITION_MISSING", collectionReport)
        }
        val auth = room.authority
            ?: return notEvaluated(alarmId, "AUTHORITY_ABSENT", collectionReport)
        val restoreEpoch = room.restoreEpoch
            ?: return notEvaluated(alarmId, "RESTORE_EPOCH_MISSING", collectionReport)

        // 4. Check Local Incarnation Marker
        val localMarker = collectionReport.localMarkerEvidence
        if (localMarker.status != EvidenceReadStatus.PROVEN_PRESENT || localMarker.localId.isNullOrBlank()) {
            return notEvaluated(alarmId, "LOCAL_MARKER_INCOMPLETE_OR_MISSING", collectionReport)
        }

        // 5. Check DurableIntents Ambiguity
        if (collectionReport.hasMultipleUnresolvedIntents) {
            return notEvaluated(alarmId, "MULTIPLE_UNRESOLVED_INTENTS", collectionReport)
        }

        val unresolvedIntents = room.intents.filter { it.phase in setOf("PENDING", "IN_FLIGHT") }
        val historicalIntents = room.intents.filter { it.phase !in setOf("PENDING", "IN_FLIGHT") }

        val candidateIntent: DurableOperationIntent? = when {
            unresolvedIntents.size == 1 -> unresolvedIntents.first()
            unresolvedIntents.size > 1 -> return notEvaluated(alarmId, "AMBIGUOUS_UNRESOLVED_INTENTS", collectionReport)
            historicalIntents.size == 1 -> historicalIntents.first()
            historicalIntents.size > 1 -> return notEvaluated(alarmId, "AMBIGUOUS_HISTORICAL_INTENTS", collectionReport)
            else -> null
        }

        // 6. Construct P1 Snapshot for Diagnostic Adjudication
        val snapshot = RecoveryEvidenceSnapshot(
            alarmId = alarmId,
            authority = auth,
            restoreEpochMeta = restoreEpoch,
            durableIntent = candidateIntent,
            activeSession = collectionReport.sessionEvidence.snapshot,
            localIncarnationId = localMarker.localId,
            osOutcome = OsOperationOutcome.NOT_ATTEMPTED,
            definitionExists = true
        )

        val diagnosticDecision = adjudicator.adjudicate(snapshot)
        Log.i(TAG, "Evaluated diagnostic recovery for alarm $alarmId: decision=${diagnosticDecision.outcome}, reason=${diagnosticDecision.reasonCode}")

        return RecoveryDiagnosticBridgeReport(
            alarmId = alarmId,
            status = BridgeStatus.EVALUATED_DIAGNOSTICALLY,
            reasonCode = "EVALUATED_${diagnosticDecision.outcome}",
            collectionReport = collectionReport,
            diagnosticDecision = diagnosticDecision
        )
    }

    private fun notEvaluated(
        alarmId: Long,
        reasonCode: String,
        collectionReport: RecoveryEvidenceCollectionReport
    ): RecoveryDiagnosticBridgeReport {
        Log.w(TAG, "Diagnostic recovery NOT_EVALUATED for alarm $alarmId: $reasonCode")
        return RecoveryDiagnosticBridgeReport(
            alarmId = alarmId,
            status = BridgeStatus.NOT_EVALUATED,
            reasonCode = reasonCode,
            collectionReport = collectionReport,
            diagnosticDecision = null
        )
    }
}
