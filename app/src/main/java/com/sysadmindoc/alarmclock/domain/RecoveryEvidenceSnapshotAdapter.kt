package com.sysadmindoc.alarmclock.domain

enum class CoherenceStatus {
    COHERENT,
    UNPROVEN_COHERENCE
}

enum class AdapterFailureReason {
    READ_FAILURE,
    CORRUPT_EVIDENCE,
    INCONSISTENT_INPUT,
    UNPROVEN_COHERENCE
}

sealed interface AdapterResult {
    data class Success(val snapshot: HandshakeRecoverySnapshot) : AdapterResult
    data class Failure(val reason: AdapterFailureReason, val details: String) : AdapterResult
}

data class RawIncarnationEvidence(
    val markerSnapshot: IncarnationEvidenceSnapshot,
    val coherence: CoherenceStatus,
    val initAuthority: RecoveryEvidence,
    val historicalEnforcedLedger: RecoveryEvidence,
    val operationIdentity: RecoveryOperationIdentity,
    val phaseEvidence: RecoveryPhaseEvidence
)

object RecoveryEvidenceSnapshotAdapter {

    /**
     * Prepares a [HandshakeRecoverySnapshot] from raw, pre-read evidence according
     * to the KNO-227 P1B1c1b1b-R pure adapter contract.
     *
     * Evaluates marker classification, validates snapshot coherence, and
     * preserves evidence provenance without side effects or metadata normalization.
     */
    fun prepareSnapshot(raw: RawIncarnationEvidence): AdapterResult {
        // 1. Evaluate Incarnation Marker Classification via Canonical Classifier
        val classification = IncarnationEvidenceClassifier.classify(raw.markerSnapshot)

        // 2. Reject Hard Classifier Failures (Read Failure, Corrupt, Inconsistent)
        when (classification) {
            IncarnationClassificationOutcome.READ_FAILURE -> {
                return AdapterResult.Failure(
                    reason = AdapterFailureReason.READ_FAILURE,
                    details = "Marker evidence indicates a read failure"
                )
            }
            IncarnationClassificationOutcome.CORRUPT_EVIDENCE -> {
                return AdapterResult.Failure(
                    reason = AdapterFailureReason.CORRUPT_EVIDENCE,
                    details = "Marker evidence indicates corruption"
                )
            }
            IncarnationClassificationOutcome.INCONSISTENT_INPUT -> {
                return AdapterResult.Failure(
                    reason = AdapterFailureReason.INCONSISTENT_INPUT,
                    details = "Marker evidence contains inconsistent or malformed input"
                )
            }
            IncarnationClassificationOutcome.BOTH_ABSENT_AMBIGUOUS,
            IncarnationClassificationOutcome.ROOM_MISSING,
            IncarnationClassificationOutcome.LOCAL_MISSING,
            IncarnationClassificationOutcome.ID_MISMATCH,
            IncarnationClassificationOutcome.INCOMPLETE_HANDSHAKE,
            IncarnationClassificationOutcome.MATCHED_COMPLETED_PAIR -> {
                /* Proceed to coherence validation */
            }
        }

        // 3. Reject Unproven Coherence
        if (raw.coherence != CoherenceStatus.COHERENT) {
            return AdapterResult.Failure(
                reason = AdapterFailureReason.UNPROVEN_COHERENCE,
                details = "Snapshot observation coherence is unproven"
            )
        }

        // 4. Construct and Return Prepared HandshakeRecoverySnapshot
        val preparedSnapshot = HandshakeRecoverySnapshot(
            classification = classification,
            initAuthority = raw.initAuthority,
            operationIdentity = raw.operationIdentity,
            phaseEvidence = raw.phaseEvidence,
            historicalEnforcedLedger = raw.historicalEnforcedLedger
        )

        return AdapterResult.Success(preparedSnapshot)
    }
}
