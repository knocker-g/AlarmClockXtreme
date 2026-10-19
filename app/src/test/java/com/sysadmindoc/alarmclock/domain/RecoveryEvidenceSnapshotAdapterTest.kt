package com.sysadmindoc.alarmclock.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryEvidenceSnapshotAdapterTest {

    // CoherenceStatus.COHERENT is caller-supplied.
    // This adapter does not prove Room/Local atomic observation.

    private val validMarkerSnapshot = IncarnationEvidenceSnapshot(
        roomState = MarkerState.PRESENT_VALID,
        roomId = "marker-100",
        roomCompletion = CompletionState.COMPLETE,
        localState = MarkerState.PRESENT_VALID,
        localId = "marker-100",
        localCompletion = CompletionState.COMPLETE
    )

    private val validOperationIdentity = RecoveryOperationIdentity(
        epoch = 100L,
        token = "token-100"
    )

    private val validPhaseEvidence = RecoveryPhaseEvidence(
        phaseName = "COMPLETED"
    )

    private val coherentRawEvidence = RawIncarnationEvidence(
        markerSnapshot = validMarkerSnapshot,
        coherence = CoherenceStatus.COHERENT,
        initAuthority = RecoveryEvidence.PROVEN,
        historicalEnforcedLedger = RecoveryEvidence.NEGATED,
        operationIdentity = validOperationIdentity,
        phaseEvidence = validPhaseEvidence
    )

    @Test
    fun `A01 - COHERENT is caller-supplied and adapter operates without external storage or side effects`() {
        // CoherenceStatus.COHERENT is caller-supplied.
        // This adapter does not prove Room/Local atomic observation.
        val result = RecoveryEvidenceSnapshotAdapter.prepareSnapshot(coherentRawEvidence)
        assertTrue("Result must be Success for valid coherent input", result is AdapterResult.Success)
    }

    @Test
    fun `A02 - UNPROVEN_COHERENCE never yields Success even when marker identifiers match`() {
        val matchingUnproven = coherentRawEvidence.copy(
            coherence = CoherenceStatus.UNPROVEN_COHERENCE
        )
        val result = RecoveryEvidenceSnapshotAdapter.prepareSnapshot(matchingUnproven)
        assertFalse("Result must NOT be Success when coherence is unproven", result is AdapterResult.Success)
        assertTrue("Result must be Failure", result is AdapterResult.Failure)
        assertEquals(AdapterFailureReason.UNPROVEN_COHERENCE, (result as AdapterResult.Failure).reason)
    }

    @Test
    fun `A03 - UNPROVEN_COHERENCE with a valid completed pair fails closed to UNPROVEN_COHERENCE`() {
        val completedUnproven = coherentRawEvidence.copy(
            markerSnapshot = validMarkerSnapshot, // Valid completed pair
            coherence = CoherenceStatus.UNPROVEN_COHERENCE
        )
        val result = RecoveryEvidenceSnapshotAdapter.prepareSnapshot(completedUnproven)
        assertTrue(result is AdapterResult.Failure)
        assertEquals(AdapterFailureReason.UNPROVEN_COHERENCE, (result as AdapterResult.Failure).reason)
    }

    @Test
    fun `A04 - Classifier READ_FAILURE and UNPROVEN_COHERENCE together preserves READ_FAILURE precedence`() {
        val readFailureUnproven = coherentRawEvidence.copy(
            markerSnapshot = validMarkerSnapshot.copy(roomState = MarkerState.READ_FAILED),
            coherence = CoherenceStatus.UNPROVEN_COHERENCE
        )
        val result = RecoveryEvidenceSnapshotAdapter.prepareSnapshot(readFailureUnproven)
        assertTrue(result is AdapterResult.Failure)
        assertEquals(AdapterFailureReason.READ_FAILURE, (result as AdapterResult.Failure).reason)
    }

    @Test
    fun `A05 - Classifier CORRUPT_EVIDENCE and UNPROVEN_COHERENCE together preserves CORRUPT_EVIDENCE precedence`() {
        val corruptUnproven = coherentRawEvidence.copy(
            markerSnapshot = validMarkerSnapshot.copy(roomState = MarkerState.CORRUPT),
            coherence = CoherenceStatus.UNPROVEN_COHERENCE
        )
        val result = RecoveryEvidenceSnapshotAdapter.prepareSnapshot(corruptUnproven)
        assertTrue(result is AdapterResult.Failure)
        assertEquals(AdapterFailureReason.CORRUPT_EVIDENCE, (result as AdapterResult.Failure).reason)
    }

    @Test
    fun `A06 - Classifier INCONSISTENT_INPUT and UNPROVEN_COHERENCE together preserves INCONSISTENT_INPUT precedence`() {
        val inconsistentUnproven = coherentRawEvidence.copy(
            markerSnapshot = validMarkerSnapshot.copy(localId = "   "), // Blank ID -> INCONSISTENT_INPUT
            coherence = CoherenceStatus.UNPROVEN_COHERENCE
        )
        val result = RecoveryEvidenceSnapshotAdapter.prepareSnapshot(inconsistentUnproven)
        assertTrue(result is AdapterResult.Failure)
        assertEquals(AdapterFailureReason.INCONSISTENT_INPUT, (result as AdapterResult.Failure).reason)
    }

    @Test
    fun `A07 - BOTH_ABSENT_AMBIGUOUS is not converted into fresh-install or initialization authority`() {
        val bothAbsentSnapshot = IncarnationEvidenceSnapshot(
            roomState = MarkerState.ABSENT,
            roomId = null,
            roomCompletion = CompletionState.UNKNOWN,
            localState = MarkerState.ABSENT,
            localId = null,
            localCompletion = CompletionState.UNKNOWN
        )
        val raw = coherentRawEvidence.copy(
            markerSnapshot = bothAbsentSnapshot,
            initAuthority = RecoveryEvidence.PROVEN
        )

        val result = RecoveryEvidenceSnapshotAdapter.prepareSnapshot(raw)
        assertTrue(result is AdapterResult.Success)
        val success = result as AdapterResult.Success
        assertEquals(IncarnationClassificationOutcome.BOTH_ABSENT_AMBIGUOUS, success.snapshot.classification)

        // Evaluate in downstream policy: MUST NOT yield fresh install or authorization
        val decision = HandshakeRecoveryPolicy.evaluate(success.snapshot)
        assertEquals(HandshakeRecoveryDecision.BLOCK_AMBIGUOUS_ABSENCE, decision)
    }

    @Test
    fun `A08 - ID_MISMATCH remains ID_MISMATCH and does not become MATCHED_COMPLETED_PAIR`() {
        val mismatchedMarkers = validMarkerSnapshot.copy(
            localId = "marker-different-200"
        )
        val raw = coherentRawEvidence.copy(markerSnapshot = mismatchedMarkers)

        val result = RecoveryEvidenceSnapshotAdapter.prepareSnapshot(raw)
        assertTrue(result is AdapterResult.Success)
        val success = result as AdapterResult.Success
        assertEquals(IncarnationClassificationOutcome.ID_MISMATCH, success.snapshot.classification)

        val decision = HandshakeRecoveryPolicy.evaluate(success.snapshot)
        assertEquals(HandshakeRecoveryDecision.REQUIRE_RESTORE_RECOVERY, decision)
    }

    @Test
    fun `A09 - UNKNOWN historical ENFORCED evidence remains UNKNOWN and leads to INSPECT_UNKNOWN_HISTORICAL_LEDGER`() {
        val unknownLedgerRaw = coherentRawEvidence.copy(
            historicalEnforcedLedger = RecoveryEvidence.UNKNOWN
        )

        val result = RecoveryEvidenceSnapshotAdapter.prepareSnapshot(unknownLedgerRaw)
        assertTrue(result is AdapterResult.Success)
        val success = result as AdapterResult.Success
        assertEquals(RecoveryEvidence.UNKNOWN, success.snapshot.historicalEnforcedLedger)

        val decision = HandshakeRecoveryPolicy.evaluate(success.snapshot)
        assertEquals(HandshakeRecoveryDecision.INSPECT_UNKNOWN_HISTORICAL_LEDGER, decision)
    }

    @Test
    fun `A10 - UNKNOWN initialization authority remains UNKNOWN and leads to INSPECT_UNKNOWN_INIT_AUTHORITY`() {
        val unknownAuthRaw = coherentRawEvidence.copy(
            initAuthority = RecoveryEvidence.UNKNOWN
        )

        val result = RecoveryEvidenceSnapshotAdapter.prepareSnapshot(unknownAuthRaw)
        assertTrue(result is AdapterResult.Success)
        val success = result as AdapterResult.Success
        assertEquals(RecoveryEvidence.UNKNOWN, success.snapshot.initAuthority)

        val decision = HandshakeRecoveryPolicy.evaluate(success.snapshot)
        assertEquals(HandshakeRecoveryDecision.INSPECT_UNKNOWN_INIT_AUTHORITY, decision)
    }

    @Test
    fun `A11 - Invalid epoch is not repaired and downstream policy produces BLOCK_OPERATION_IDENTITY`() {
        val invalidEpochs = listOf<Long?>(null, 0L, -100L)
        for (invalidEpoch in invalidEpochs) {
            val invalidIdentity = validOperationIdentity.copy(epoch = invalidEpoch)
            val raw = coherentRawEvidence.copy(operationIdentity = invalidIdentity)

            val result = RecoveryEvidenceSnapshotAdapter.prepareSnapshot(raw)
            assertTrue(result is AdapterResult.Success)
            val success = result as AdapterResult.Success
            assertEquals(invalidEpoch, success.snapshot.operationIdentity.epoch)

            val decision = HandshakeRecoveryPolicy.evaluate(success.snapshot)
            assertEquals(HandshakeRecoveryDecision.BLOCK_OPERATION_IDENTITY, decision)
        }
    }

    @Test
    fun `A12 - Invalid token is not repaired and downstream policy produces BLOCK_OPERATION_IDENTITY`() {
        val invalidTokens = listOf<String?>(null, "", "   ")
        for (invalidToken in invalidTokens) {
            val invalidIdentity = validOperationIdentity.copy(token = invalidToken)
            val raw = coherentRawEvidence.copy(operationIdentity = invalidIdentity)

            val result = RecoveryEvidenceSnapshotAdapter.prepareSnapshot(raw)
            assertTrue(result is AdapterResult.Success)
            val success = result as AdapterResult.Success
            assertEquals(invalidToken, success.snapshot.operationIdentity.token)

            val decision = HandshakeRecoveryPolicy.evaluate(success.snapshot)
            assertEquals(HandshakeRecoveryDecision.BLOCK_OPERATION_IDENTITY, decision)
        }
    }

    @Test
    fun `A13 - Valid completed pair does not establish ENFORCED authority and produces REUSE_COMPLETED_SHADOW_EVIDENCE`() {
        val result = RecoveryEvidenceSnapshotAdapter.prepareSnapshot(coherentRawEvidence)
        assertTrue(result is AdapterResult.Success)
        val success = result as AdapterResult.Success

        val decision = HandshakeRecoveryPolicy.evaluate(success.snapshot)
        assertEquals(HandshakeRecoveryDecision.REUSE_COMPLETED_SHADOW_EVIDENCE, decision)
    }

    @Test
    fun `A14 - Repeated identical immutable inputs produce identical AdapterResult values`() {
        val result1 = RecoveryEvidenceSnapshotAdapter.prepareSnapshot(coherentRawEvidence)
        val result2 = RecoveryEvidenceSnapshotAdapter.prepareSnapshot(coherentRawEvidence)
        assertEquals(result1, result2)
    }

    @Test
    fun `A15 - Input fixture mutation using copy does not affect previously prepared snapshot`() {
        val originalRaw = coherentRawEvidence.copy()
        val result1 = RecoveryEvidenceSnapshotAdapter.prepareSnapshot(originalRaw)
        assertTrue(result1 is AdapterResult.Success)
        val snapshot1 = (result1 as AdapterResult.Success).snapshot

        // Mutate raw evidence via copy
        val mutatedRaw = originalRaw.copy(
            initAuthority = RecoveryEvidence.NEGATED,
            operationIdentity = validOperationIdentity.copy(epoch = 999L)
        )
        val result2 = RecoveryEvidenceSnapshotAdapter.prepareSnapshot(mutatedRaw)
        assertTrue(result2 is AdapterResult.Success)
        val snapshot2 = (result2 as AdapterResult.Success).snapshot

        // First snapshot MUST remain immutable and unaffected
        assertEquals(RecoveryEvidence.PROVEN, snapshot1.initAuthority)
        assertEquals(100L, snapshot1.operationIdentity.epoch)

        assertEquals(RecoveryEvidence.NEGATED, snapshot2.initAuthority)
        assertEquals(999L, snapshot2.operationIdentity.epoch)
    }

    @Test
    fun `A16 - Every Failure is distinguishable from Success and exposes no usable snapshot`() {
        val failures = listOf(
            coherentRawEvidence.copy(markerSnapshot = validMarkerSnapshot.copy(roomState = MarkerState.READ_FAILED)),
            coherentRawEvidence.copy(markerSnapshot = validMarkerSnapshot.copy(roomState = MarkerState.CORRUPT)),
            coherentRawEvidence.copy(markerSnapshot = validMarkerSnapshot.copy(roomId = "")),
            coherentRawEvidence.copy(coherence = CoherenceStatus.UNPROVEN_COHERENCE)
        )

        for (failingRaw in failures) {
            val result = RecoveryEvidenceSnapshotAdapter.prepareSnapshot(failingRaw)
            assertTrue("Failing raw input must produce AdapterResult.Failure", result is AdapterResult.Failure)
            assertFalse("Failing result must NOT be AdapterResult.Success", result is AdapterResult.Success)
        }
    }
}
