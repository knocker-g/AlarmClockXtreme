package com.sysadmindoc.alarmclock.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class HandshakeRecoveryPolicyTest {

    private val validIdentity = RecoveryOperationIdentity(
        epoch = 100L,
        token = "token-rec-100"
    )

    private val readyMatchedSnapshot = HandshakeRecoverySnapshot(
        classification = IncarnationClassificationOutcome.MATCHED_COMPLETED_PAIR,
        initAuthority = RecoveryEvidence.PROVEN,
        operationIdentity = validIdentity,
        phaseEvidence = RecoveryPhaseEvidence(phaseName = "COMPLETED"),
        historicalEnforcedLedger = RecoveryEvidence.NEGATED
    )

    @Test
    fun `READ_FAILURE classification produces BLOCK_READ_FAILURE regardless of other fields`() {
        val snapshot = readyMatchedSnapshot.copy(
            classification = IncarnationClassificationOutcome.READ_FAILURE,
            historicalEnforcedLedger = RecoveryEvidence.PROVEN,
            initAuthority = RecoveryEvidence.UNKNOWN
        )
        val decision = HandshakeRecoveryPolicy.evaluate(snapshot)
        assertEquals(HandshakeRecoveryDecision.BLOCK_READ_FAILURE, decision)
    }

    @Test
    fun `CORRUPT_EVIDENCE classification produces BLOCK_CORRUPT_EVIDENCE`() {
        val snapshot = readyMatchedSnapshot.copy(
            classification = IncarnationClassificationOutcome.CORRUPT_EVIDENCE
        )
        val decision = HandshakeRecoveryPolicy.evaluate(snapshot)
        assertEquals(HandshakeRecoveryDecision.BLOCK_CORRUPT_EVIDENCE, decision)
    }

    @Test
    fun `INCONSISTENT_INPUT classification produces BLOCK_INCONSISTENT_INPUT`() {
        val snapshot = readyMatchedSnapshot.copy(
            classification = IncarnationClassificationOutcome.INCONSISTENT_INPUT
        )
        val decision = HandshakeRecoveryPolicy.evaluate(snapshot)
        assertEquals(HandshakeRecoveryDecision.BLOCK_INCONSISTENT_INPUT, decision)
    }

    @Test
    fun `proven historical ENFORCED ledger produces BLOCK_HISTORICAL_ENFORCED`() {
        val snapshot = readyMatchedSnapshot.copy(
            historicalEnforcedLedger = RecoveryEvidence.PROVEN
        )
        val decision = HandshakeRecoveryPolicy.evaluate(snapshot)
        assertEquals(HandshakeRecoveryDecision.BLOCK_HISTORICAL_ENFORCED, decision)
    }

    @Test
    fun `unknown historical ENFORCED ledger produces INSPECT_UNKNOWN_HISTORICAL_LEDGER`() {
        val snapshot = readyMatchedSnapshot.copy(
            historicalEnforcedLedger = RecoveryEvidence.UNKNOWN
        )
        val decision = HandshakeRecoveryPolicy.evaluate(snapshot)
        assertEquals(HandshakeRecoveryDecision.INSPECT_UNKNOWN_HISTORICAL_LEDGER, decision)
    }

    @Test
    fun `negated init authority produces BLOCK_UNPROVEN_INIT_AUTHORITY`() {
        val snapshot = readyMatchedSnapshot.copy(
            initAuthority = RecoveryEvidence.NEGATED
        )
        val decision = HandshakeRecoveryPolicy.evaluate(snapshot)
        assertEquals(HandshakeRecoveryDecision.BLOCK_UNPROVEN_INIT_AUTHORITY, decision)
    }

    @Test
    fun `unknown init authority produces INSPECT_UNKNOWN_INIT_AUTHORITY`() {
        val snapshot = readyMatchedSnapshot.copy(
            initAuthority = RecoveryEvidence.UNKNOWN
        )
        val decision = HandshakeRecoveryPolicy.evaluate(snapshot)
        assertEquals(HandshakeRecoveryDecision.INSPECT_UNKNOWN_INIT_AUTHORITY, decision)
    }

    @Test
    fun `BOTH_ABSENT_AMBIGUOUS classification produces BLOCK_AMBIGUOUS_ABSENCE`() {
        val snapshot = readyMatchedSnapshot.copy(
            classification = IncarnationClassificationOutcome.BOTH_ABSENT_AMBIGUOUS
        )
        val decision = HandshakeRecoveryPolicy.evaluate(snapshot)
        assertEquals(HandshakeRecoveryDecision.BLOCK_AMBIGUOUS_ABSENCE, decision)
    }

    @Test
    fun `ID_MISMATCH classification produces REQUIRE_RESTORE_RECOVERY`() {
        val snapshot = readyMatchedSnapshot.copy(
            classification = IncarnationClassificationOutcome.ID_MISMATCH
        )
        val decision = HandshakeRecoveryPolicy.evaluate(snapshot)
        assertEquals(HandshakeRecoveryDecision.REQUIRE_RESTORE_RECOVERY, decision)
    }

    @Test
    fun `missing or invalid operation identity produces BLOCK_OPERATION_IDENTITY`() {
        // Null epoch
        val nullEpochSnapshot = readyMatchedSnapshot.copy(
            operationIdentity = validIdentity.copy(epoch = null)
        )
        assertEquals(
            HandshakeRecoveryDecision.BLOCK_OPERATION_IDENTITY,
            HandshakeRecoveryPolicy.evaluate(nullEpochSnapshot)
        )

        // Zero epoch
        val zeroEpochSnapshot = readyMatchedSnapshot.copy(
            operationIdentity = validIdentity.copy(epoch = 0L)
        )
        assertEquals(
            HandshakeRecoveryDecision.BLOCK_OPERATION_IDENTITY,
            HandshakeRecoveryPolicy.evaluate(zeroEpochSnapshot)
        )

        // Negative epoch
        val negativeEpochSnapshot = readyMatchedSnapshot.copy(
            operationIdentity = validIdentity.copy(epoch = -100L)
        )
        assertEquals(
            HandshakeRecoveryDecision.BLOCK_OPERATION_IDENTITY,
            HandshakeRecoveryPolicy.evaluate(negativeEpochSnapshot)
        )

        // Null token
        val nullTokenSnapshot = readyMatchedSnapshot.copy(
            operationIdentity = validIdentity.copy(token = null)
        )
        assertEquals(
            HandshakeRecoveryDecision.BLOCK_OPERATION_IDENTITY,
            HandshakeRecoveryPolicy.evaluate(nullTokenSnapshot)
        )

        // Blank token
        val blankTokenSnapshot = readyMatchedSnapshot.copy(
            operationIdentity = validIdentity.copy(token = "   ")
        )
        assertEquals(
            HandshakeRecoveryDecision.BLOCK_OPERATION_IDENTITY,
            HandshakeRecoveryPolicy.evaluate(blankTokenSnapshot)
        )
    }

    @Test
    fun `ROOM_MISSING direction with valid phase produces RESUME_ROOM_FIRST and invalid phase produces BLOCK_PHASE_MISMATCH`() {
        val preparingSnapshot = readyMatchedSnapshot.copy(
            classification = IncarnationClassificationOutcome.ROOM_MISSING,
            phaseEvidence = RecoveryPhaseEvidence(phaseName = "LOCAL_PREPARING")
        )
        assertEquals(
            HandshakeRecoveryDecision.RESUME_ROOM_FIRST,
            HandshakeRecoveryPolicy.evaluate(preparingSnapshot)
        )

        val committedSnapshot = readyMatchedSnapshot.copy(
            classification = IncarnationClassificationOutcome.ROOM_MISSING,
            phaseEvidence = RecoveryPhaseEvidence(phaseName = "LOCAL_COMMITTED")
        )
        assertEquals(
            HandshakeRecoveryDecision.RESUME_ROOM_FIRST,
            HandshakeRecoveryPolicy.evaluate(committedSnapshot)
        )

        val invalidPhaseSnapshot = readyMatchedSnapshot.copy(
            classification = IncarnationClassificationOutcome.ROOM_MISSING,
            phaseEvidence = RecoveryPhaseEvidence(phaseName = "ROOM_PREPARING")
        )
        assertEquals(
            HandshakeRecoveryDecision.BLOCK_PHASE_MISMATCH,
            HandshakeRecoveryPolicy.evaluate(invalidPhaseSnapshot)
        )
    }

    @Test
    fun `LOCAL_MISSING direction with valid phase produces RESUME_LOCAL_FIRST and invalid phase produces BLOCK_PHASE_MISMATCH`() {
        val preparingSnapshot = readyMatchedSnapshot.copy(
            classification = IncarnationClassificationOutcome.LOCAL_MISSING,
            phaseEvidence = RecoveryPhaseEvidence(phaseName = "ROOM_PREPARING")
        )
        assertEquals(
            HandshakeRecoveryDecision.RESUME_LOCAL_FIRST,
            HandshakeRecoveryPolicy.evaluate(preparingSnapshot)
        )

        val committedSnapshot = readyMatchedSnapshot.copy(
            classification = IncarnationClassificationOutcome.LOCAL_MISSING,
            phaseEvidence = RecoveryPhaseEvidence(phaseName = "ROOM_COMMITTED")
        )
        assertEquals(
            HandshakeRecoveryDecision.RESUME_LOCAL_FIRST,
            HandshakeRecoveryPolicy.evaluate(committedSnapshot)
        )

        val invalidPhaseSnapshot = readyMatchedSnapshot.copy(
            classification = IncarnationClassificationOutcome.LOCAL_MISSING,
            phaseEvidence = RecoveryPhaseEvidence(phaseName = "LOCAL_PREPARING")
        )
        assertEquals(
            HandshakeRecoveryDecision.BLOCK_PHASE_MISMATCH,
            HandshakeRecoveryPolicy.evaluate(invalidPhaseSnapshot)
        )
    }

    @Test
    fun `INCOMPLETE_HANDSHAKE classification with valid phase produces RESUME_HANDSHAKE_COMPLETION and invalid produces BLOCK_PHASE_MISMATCH`() {
        val validPhases = listOf("ROOM_PREPARING", "LOCAL_PREPARING", "ROOM_COMMITTED", "LOCAL_COMMITTED")
        for (phase in validPhases) {
            val snapshot = readyMatchedSnapshot.copy(
                classification = IncarnationClassificationOutcome.INCOMPLETE_HANDSHAKE,
                phaseEvidence = RecoveryPhaseEvidence(phaseName = phase)
            )
            assertEquals(
                HandshakeRecoveryDecision.RESUME_HANDSHAKE_COMPLETION,
                HandshakeRecoveryPolicy.evaluate(snapshot)
            )
        }

        val invalidPhases = listOf("COMPLETED", "UNKNOWN", "INVALID_PHASE")
        for (phase in invalidPhases) {
            val snapshot = readyMatchedSnapshot.copy(
                classification = IncarnationClassificationOutcome.INCOMPLETE_HANDSHAKE,
                phaseEvidence = RecoveryPhaseEvidence(phaseName = phase)
            )
            assertEquals(
                HandshakeRecoveryDecision.BLOCK_PHASE_MISMATCH,
                HandshakeRecoveryPolicy.evaluate(snapshot)
            )
        }
    }

    @Test
    fun `MATCHED_COMPLETED_PAIR with COMPLETED phase produces REUSE_COMPLETED_SHADOW_EVIDENCE and invalid produces BLOCK_PHASE_MISMATCH`() {
        val decision = HandshakeRecoveryPolicy.evaluate(readyMatchedSnapshot)
        assertEquals(HandshakeRecoveryDecision.REUSE_COMPLETED_SHADOW_EVIDENCE, decision)

        val invalidPhaseSnapshot = readyMatchedSnapshot.copy(
            phaseEvidence = RecoveryPhaseEvidence(phaseName = "ROOM_COMMITTED")
        )
        assertEquals(
            HandshakeRecoveryDecision.BLOCK_PHASE_MISMATCH,
            HandshakeRecoveryPolicy.evaluate(invalidPhaseSnapshot)
        )
    }

    @Test
    fun `exhaustive classification and phase matrix test exercises all decisions and asserts independent oracle`() {
        val classifications = IncarnationClassificationOutcome.entries
        val phases = listOf("ROOM_PREPARING", "LOCAL_PREPARING", "ROOM_COMMITTED", "LOCAL_COMMITTED", "COMPLETED", "UNKNOWN")
        val evidenceValues = RecoveryEvidence.entries
        val identities = listOf(
            validIdentity,
            RecoveryOperationIdentity(epoch = null, token = null)
        )
        val exercisedDecisions = mutableSetOf<HandshakeRecoveryDecision>()
        var totalEvaluated = 0

        for (classification in classifications) {
            for (phase in phases) {
                for (initAuth in evidenceValues) {
                    for (histLedger in evidenceValues) {
                        for (identity in identities) {
                            totalEvaluated++
                            val snapshot = HandshakeRecoverySnapshot(
                                classification = classification,
                                initAuthority = initAuth,
                                operationIdentity = identity,
                                phaseEvidence = RecoveryPhaseEvidence(phaseName = phase),
                                historicalEnforcedLedger = histLedger
                            )

                            val actualDecision = HandshakeRecoveryPolicy.evaluate(snapshot)
                            val expectedDecision = expectedDecisionFor(snapshot)

                            exercisedDecisions.add(actualDecision)
                            assertEquals(
                                "Mismatch for snapshot $snapshot: expected $expectedDecision but got $actualDecision",
                                expectedDecision,
                                actualDecision
                            )
                        }
                    }
                }
            }
        }

        assertEquals(972, totalEvaluated)
        assertEquals(
            "Every single decision enum value must be exercised across the test matrix",
            HandshakeRecoveryDecision.entries.toSet(),
            exercisedDecisions
        )
    }

    private fun expectedDecisionFor(snapshot: HandshakeRecoverySnapshot): HandshakeRecoveryDecision {
        return when {
            // 1. Hard Classification Failures
            snapshot.classification == IncarnationClassificationOutcome.READ_FAILURE -> HandshakeRecoveryDecision.BLOCK_READ_FAILURE
            snapshot.classification == IncarnationClassificationOutcome.CORRUPT_EVIDENCE -> HandshakeRecoveryDecision.BLOCK_CORRUPT_EVIDENCE
            snapshot.classification == IncarnationClassificationOutcome.INCONSISTENT_INPUT -> HandshakeRecoveryDecision.BLOCK_INCONSISTENT_INPUT

            // 2. Historical Enforced Ledger Check
            snapshot.historicalEnforcedLedger == RecoveryEvidence.PROVEN -> HandshakeRecoveryDecision.BLOCK_HISTORICAL_ENFORCED
            snapshot.historicalEnforcedLedger == RecoveryEvidence.UNKNOWN -> HandshakeRecoveryDecision.INSPECT_UNKNOWN_HISTORICAL_LEDGER

            // 3. Initialization Authority Check
            snapshot.initAuthority == RecoveryEvidence.NEGATED -> HandshakeRecoveryDecision.BLOCK_UNPROVEN_INIT_AUTHORITY
            snapshot.initAuthority == RecoveryEvidence.UNKNOWN -> HandshakeRecoveryDecision.INSPECT_UNKNOWN_INIT_AUTHORITY

            // 4. Classification Specific Evaluation
            snapshot.classification == IncarnationClassificationOutcome.BOTH_ABSENT_AMBIGUOUS -> HandshakeRecoveryDecision.BLOCK_AMBIGUOUS_ABSENCE
            snapshot.classification == IncarnationClassificationOutcome.ID_MISMATCH -> HandshakeRecoveryDecision.REQUIRE_RESTORE_RECOVERY

            // 5. Operation Identity Check
            snapshot.operationIdentity.epoch == null || snapshot.operationIdentity.epoch <= 0L || snapshot.operationIdentity.token.isNullOrBlank() -> HandshakeRecoveryDecision.BLOCK_OPERATION_IDENTITY

            // 6. Phase Agreement Check
            snapshot.classification == IncarnationClassificationOutcome.ROOM_MISSING && snapshot.phaseEvidence.phaseName in setOf("LOCAL_PREPARING", "LOCAL_COMMITTED") -> HandshakeRecoveryDecision.RESUME_ROOM_FIRST
            snapshot.classification == IncarnationClassificationOutcome.LOCAL_MISSING && snapshot.phaseEvidence.phaseName in setOf("ROOM_PREPARING", "ROOM_COMMITTED") -> HandshakeRecoveryDecision.RESUME_LOCAL_FIRST
            snapshot.classification == IncarnationClassificationOutcome.INCOMPLETE_HANDSHAKE && snapshot.phaseEvidence.phaseName in setOf("ROOM_PREPARING", "LOCAL_PREPARING", "ROOM_COMMITTED", "LOCAL_COMMITTED") -> HandshakeRecoveryDecision.RESUME_HANDSHAKE_COMPLETION
            snapshot.classification == IncarnationClassificationOutcome.MATCHED_COMPLETED_PAIR && snapshot.phaseEvidence.phaseName == "COMPLETED" -> HandshakeRecoveryDecision.REUSE_COMPLETED_SHADOW_EVIDENCE

            else -> HandshakeRecoveryDecision.BLOCK_PHASE_MISMATCH
        }
    }

    @Test
    fun `priority conflict order is strictly obeyed`() {
        // Precedence 1: Hard failure > Historical Ledger
        val failOverLedger = readyMatchedSnapshot.copy(
            classification = IncarnationClassificationOutcome.READ_FAILURE,
            historicalEnforcedLedger = RecoveryEvidence.PROVEN
        )
        assertEquals(HandshakeRecoveryDecision.BLOCK_READ_FAILURE, HandshakeRecoveryPolicy.evaluate(failOverLedger))

        // Precedence 2: Historical Ledger > Init Authority
        val ledgerOverInit = readyMatchedSnapshot.copy(
            historicalEnforcedLedger = RecoveryEvidence.PROVEN,
            initAuthority = RecoveryEvidence.NEGATED
        )
        assertEquals(HandshakeRecoveryDecision.BLOCK_HISTORICAL_ENFORCED, HandshakeRecoveryPolicy.evaluate(ledgerOverInit))

        // Precedence 3: Init Authority > Classification Specific
        val initOverClassification = readyMatchedSnapshot.copy(
            initAuthority = RecoveryEvidence.NEGATED,
            classification = IncarnationClassificationOutcome.ID_MISMATCH
        )
        assertEquals(HandshakeRecoveryDecision.BLOCK_UNPROVEN_INIT_AUTHORITY, HandshakeRecoveryPolicy.evaluate(initOverClassification))

        // Precedence 4: Classification Specific > Operation Identity
        val classificationOverIdentity = readyMatchedSnapshot.copy(
            classification = IncarnationClassificationOutcome.ID_MISMATCH,
            operationIdentity = validIdentity.copy(epoch = null)
        )
        assertEquals(HandshakeRecoveryDecision.REQUIRE_RESTORE_RECOVERY, HandshakeRecoveryPolicy.evaluate(classificationOverIdentity))

        // Precedence 5: Operation Identity > Phase Match
        val identityOverPhase = readyMatchedSnapshot.copy(
            operationIdentity = validIdentity.copy(token = ""),
            phaseEvidence = RecoveryPhaseEvidence(phaseName = "INVALID_PHASE")
        )
        assertEquals(HandshakeRecoveryDecision.BLOCK_OPERATION_IDENTITY, HandshakeRecoveryPolicy.evaluate(identityOverPhase))
    }

    @Test
    fun `identical snapshots produce identical decisions`() {
        val decision1 = HandshakeRecoveryPolicy.evaluate(readyMatchedSnapshot)
        val decision2 = HandshakeRecoveryPolicy.evaluate(readyMatchedSnapshot)
        assertEquals(decision1, decision2)
        assertEquals(HandshakeRecoveryDecision.REUSE_COMPLETED_SHADOW_EVIDENCE, decision1)
    }
}
