package com.sysadmindoc.alarmclock.domain

enum class RecoveryEvidence {
    PROVEN,
    NEGATED,
    UNKNOWN
}

data class RecoveryOperationIdentity(
    val epoch: Long?,
    val token: String?
)

data class RecoveryPhaseEvidence(
    val phaseName: String
)

enum class HandshakeRecoveryDecision {
    BLOCK_READ_FAILURE,
    BLOCK_CORRUPT_EVIDENCE,
    BLOCK_INCONSISTENT_INPUT,
    BLOCK_HISTORICAL_ENFORCED,
    INSPECT_UNKNOWN_HISTORICAL_LEDGER,
    BLOCK_UNPROVEN_INIT_AUTHORITY,
    INSPECT_UNKNOWN_INIT_AUTHORITY,
    BLOCK_AMBIGUOUS_ABSENCE,
    REQUIRE_RESTORE_RECOVERY,
    BLOCK_OPERATION_IDENTITY,
    BLOCK_PHASE_MISMATCH,
    RESUME_ROOM_FIRST,
    RESUME_LOCAL_FIRST,
    RESUME_HANDSHAKE_COMPLETION,
    REUSE_COMPLETED_SHADOW_EVIDENCE
}

data class HandshakeRecoverySnapshot(
    val classification: IncarnationClassificationOutcome,
    val initAuthority: RecoveryEvidence,
    val operationIdentity: RecoveryOperationIdentity,
    val phaseEvidence: RecoveryPhaseEvidence,
    val historicalEnforcedLedger: RecoveryEvidence
)

object HandshakeRecoveryPolicy {

    private val ROOM_MISSING_VALID_PHASES = setOf("LOCAL_PREPARING", "LOCAL_COMMITTED")
    private val LOCAL_MISSING_VALID_PHASES = setOf("ROOM_PREPARING", "ROOM_COMMITTED")
    private val INCOMPLETE_HANDSHAKE_VALID_PHASES = setOf(
        "ROOM_PREPARING", "LOCAL_PREPARING", "ROOM_COMMITTED", "LOCAL_COMMITTED"
    )

    /**
     * Evaluates a handshake recovery snapshot according to the normative
     * KNO-227 P1B1c1b1a Design Lock v3.3 decision precedence rules.
     */
    fun evaluate(snapshot: HandshakeRecoverySnapshot): HandshakeRecoveryDecision {
        // 1. Classification Hard Failures (Highest Priority)
        when (snapshot.classification) {
            IncarnationClassificationOutcome.READ_FAILURE -> return HandshakeRecoveryDecision.BLOCK_READ_FAILURE
            IncarnationClassificationOutcome.CORRUPT_EVIDENCE -> return HandshakeRecoveryDecision.BLOCK_CORRUPT_EVIDENCE
            IncarnationClassificationOutcome.INCONSISTENT_INPUT -> return HandshakeRecoveryDecision.BLOCK_INCONSISTENT_INPUT
            else -> { /* proceed */ }
        }

        // 2. Historical Enforced Ledger Check
        when (snapshot.historicalEnforcedLedger) {
            RecoveryEvidence.PROVEN -> return HandshakeRecoveryDecision.BLOCK_HISTORICAL_ENFORCED
            RecoveryEvidence.UNKNOWN -> return HandshakeRecoveryDecision.INSPECT_UNKNOWN_HISTORICAL_LEDGER
            RecoveryEvidence.NEGATED -> { /* proceed */ }
        }

        // 3. Initialization Authority Check
        when (snapshot.initAuthority) {
            RecoveryEvidence.NEGATED -> return HandshakeRecoveryDecision.BLOCK_UNPROVEN_INIT_AUTHORITY
            RecoveryEvidence.UNKNOWN -> return HandshakeRecoveryDecision.INSPECT_UNKNOWN_INIT_AUTHORITY
            RecoveryEvidence.PROVEN -> { /* proceed */ }
        }

        // 4. Classification Specific Evaluations
        when (snapshot.classification) {
            IncarnationClassificationOutcome.BOTH_ABSENT_AMBIGUOUS -> return HandshakeRecoveryDecision.BLOCK_AMBIGUOUS_ABSENCE
            IncarnationClassificationOutcome.ID_MISMATCH -> return HandshakeRecoveryDecision.REQUIRE_RESTORE_RECOVERY
            else -> { /* proceed to operation identity check */ }
        }

        // 5. Operation Identity Check
        val epoch = snapshot.operationIdentity.epoch
        val token = snapshot.operationIdentity.token
        if (epoch == null || epoch <= 0L || token.isNullOrBlank()) {
            return HandshakeRecoveryDecision.BLOCK_OPERATION_IDENTITY
        }

        // 6. Phase & Classification Agreement Check
        val phase = snapshot.phaseEvidence.phaseName
        return when (snapshot.classification) {
            IncarnationClassificationOutcome.ROOM_MISSING -> {
                if (phase in ROOM_MISSING_VALID_PHASES) {
                    HandshakeRecoveryDecision.RESUME_ROOM_FIRST
                } else {
                    HandshakeRecoveryDecision.BLOCK_PHASE_MISMATCH
                }
            }
            IncarnationClassificationOutcome.LOCAL_MISSING -> {
                if (phase in LOCAL_MISSING_VALID_PHASES) {
                    HandshakeRecoveryDecision.RESUME_LOCAL_FIRST
                } else {
                    HandshakeRecoveryDecision.BLOCK_PHASE_MISMATCH
                }
            }
            IncarnationClassificationOutcome.INCOMPLETE_HANDSHAKE -> {
                if (phase in INCOMPLETE_HANDSHAKE_VALID_PHASES) {
                    HandshakeRecoveryDecision.RESUME_HANDSHAKE_COMPLETION
                } else {
                    HandshakeRecoveryDecision.BLOCK_PHASE_MISMATCH
                }
            }
            IncarnationClassificationOutcome.MATCHED_COMPLETED_PAIR -> {
                if (phase == "COMPLETED") {
                    HandshakeRecoveryDecision.REUSE_COMPLETED_SHADOW_EVIDENCE
                } else {
                    HandshakeRecoveryDecision.BLOCK_PHASE_MISMATCH
                }
            }
            else -> HandshakeRecoveryDecision.BLOCK_PHASE_MISMATCH
        }
    }
}
