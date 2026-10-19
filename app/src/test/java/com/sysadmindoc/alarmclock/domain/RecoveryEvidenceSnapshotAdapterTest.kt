package com.sysadmindoc.alarmclock.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryEvidenceSnapshotAdapterTest {

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
    fun `E01 - Coherent valid evidence produces prepared snapshot Success`() {
        val result = RecoveryEvidenceSnapshotAdapter.prepareSnapshot(coherentRawEvidence)
        assertTrue("Result must be Success", result is AdapterResult.Success)
        val success = result as AdapterResult.Success

        assertEquals(IncarnationClassificationOutcome.MATCHED_COMPLETED_PAIR, success.snapshot.classification)
        assertEquals(RecoveryEvidence.PROVEN, success.snapshot.initAuthority)
        assertEquals(RecoveryEvidence.NEGATED, success.snapshot.historicalEnforcedLedger)
        assertEquals(validOperationIdentity, success.snapshot.operationIdentity)
        assertEquals(validPhaseEvidence, success.snapshot.phaseEvidence)
    }

    @Test
    fun `E02 - Explicit Room-side absence remains explicit in snapshot`() {
        val roomMissingSnapshot = validMarkerSnapshot.copy(
            roomState = MarkerState.ABSENT,
            roomId = null,
            roomCompletion = CompletionState.UNKNOWN
        )
        val raw = coherentRawEvidence.copy(
            markerSnapshot = roomMissingSnapshot,
            phaseEvidence = RecoveryPhaseEvidence(phaseName = "LOCAL_PREPARING")
        )

        val result = RecoveryEvidenceSnapshotAdapter.prepareSnapshot(raw)
        assertTrue(result is AdapterResult.Success)
        val success = result as AdapterResult.Success
        assertEquals(IncarnationClassificationOutcome.ROOM_MISSING, success.snapshot.classification)
    }

    @Test
    fun `E03 - Explicit Local-side absence remains explicit in snapshot`() {
        val localMissingSnapshot = validMarkerSnapshot.copy(
            localState = MarkerState.ABSENT,
            localId = null,
            localCompletion = CompletionState.UNKNOWN
        )
        val raw = coherentRawEvidence.copy(
            markerSnapshot = localMissingSnapshot,
            phaseEvidence = RecoveryPhaseEvidence(phaseName = "ROOM_PREPARING")
        )

        val result = RecoveryEvidenceSnapshotAdapter.prepareSnapshot(raw)
        assertTrue(result is AdapterResult.Success)
        val success = result as AdapterResult.Success
        assertEquals(IncarnationClassificationOutcome.LOCAL_MISSING, success.snapshot.classification)
    }

    @Test
    fun `E04 - Both identifiers absent produces BOTH_ABSENT_AMBIGUOUS without assuming first install`() {
        val bothAbsentSnapshot = IncarnationEvidenceSnapshot(
            roomState = MarkerState.ABSENT,
            roomId = null,
            roomCompletion = CompletionState.UNKNOWN,
            localState = MarkerState.ABSENT,
            localId = null,
            localCompletion = CompletionState.UNKNOWN
        )
        val raw = coherentRawEvidence.copy(markerSnapshot = bothAbsentSnapshot)

        val result = RecoveryEvidenceSnapshotAdapter.prepareSnapshot(raw)
        assertTrue(result is AdapterResult.Success)
        val success = result as AdapterResult.Success
        assertEquals(IncarnationClassificationOutcome.BOTH_ABSENT_AMBIGUOUS, success.snapshot.classification)
    }

    @Test
    fun `E05 - Room read failure produces non-success Failure`() {
        val roomFailedSnapshot = validMarkerSnapshot.copy(
            roomState = MarkerState.READ_FAILED,
            roomId = null,
            roomCompletion = CompletionState.UNKNOWN
        )
        val raw = coherentRawEvidence.copy(markerSnapshot = roomFailedSnapshot)

        val result = RecoveryEvidenceSnapshotAdapter.prepareSnapshot(raw)
        assertTrue(result is AdapterResult.Failure)
        val failure = result as AdapterResult.Failure
        assertEquals(AdapterFailureReason.READ_FAILURE, failure.reason)
    }

    @Test
    fun `E06 - Local read failure produces non-success Failure`() {
        val localFailedSnapshot = validMarkerSnapshot.copy(
            localState = MarkerState.READ_FAILED,
            localId = null,
            localCompletion = CompletionState.UNKNOWN
        )
        val raw = coherentRawEvidence.copy(markerSnapshot = localFailedSnapshot)

        val result = RecoveryEvidenceSnapshotAdapter.prepareSnapshot(raw)
        assertTrue(result is AdapterResult.Failure)
        val failure = result as AdapterResult.Failure
        assertEquals(AdapterFailureReason.READ_FAILURE, failure.reason)
    }

    @Test
    fun `E07 - Corrupt evidence produces non-success Failure`() {
        val corruptSnapshot = validMarkerSnapshot.copy(
            roomState = MarkerState.CORRUPT,
            roomId = null,
            roomCompletion = CompletionState.UNKNOWN
        )
        val raw = coherentRawEvidence.copy(markerSnapshot = corruptSnapshot)

        val result = RecoveryEvidenceSnapshotAdapter.prepareSnapshot(raw)
        assertTrue(result is AdapterResult.Failure)
        val failure = result as AdapterResult.Failure
        assertEquals(AdapterFailureReason.CORRUPT_EVIDENCE, failure.reason)
    }

    @Test
    fun `E08 - Inconsistent identity produces non-success Failure`() {
        val inconsistentSnapshot = validMarkerSnapshot.copy(
            localState = MarkerState.PRESENT_VALID,
            localId = "   " // Blank ID -> INCONSISTENT_INPUT
        )
        val raw = coherentRawEvidence.copy(markerSnapshot = inconsistentSnapshot)

        val result = RecoveryEvidenceSnapshotAdapter.prepareSnapshot(raw)
        assertTrue(result is AdapterResult.Failure)
        val failure = result as AdapterResult.Failure
        assertEquals(AdapterFailureReason.INCONSISTENT_INPUT, failure.reason)
    }

    @Test
    fun `E09 - Snapshot coherence unproven produces non-success Failure`() {
        val unprovenRaw = coherentRawEvidence.copy(
            coherence = CoherenceStatus.UNPROVEN_COHERENCE
        )

        val result = RecoveryEvidenceSnapshotAdapter.prepareSnapshot(unprovenRaw)
        assertTrue(result is AdapterResult.Failure)
        val failure = result as AdapterResult.Failure
        assertEquals(AdapterFailureReason.UNPROVEN_COHERENCE, failure.reason)
    }

    @Test
    fun `E10 - Unknown historical ledger remains UNKNOWN in prepared snapshot`() {
        val unknownLedgerRaw = coherentRawEvidence.copy(
            historicalEnforcedLedger = RecoveryEvidence.UNKNOWN
        )

        val result = RecoveryEvidenceSnapshotAdapter.prepareSnapshot(unknownLedgerRaw)
        assertTrue(result is AdapterResult.Success)
        val success = result as AdapterResult.Success
        assertEquals(RecoveryEvidence.UNKNOWN, success.snapshot.historicalEnforcedLedger)
    }

    @Test
    fun `E11 - Unknown initialization authority remains UNKNOWN in prepared snapshot`() {
        val unknownAuthRaw = coherentRawEvidence.copy(
            initAuthority = RecoveryEvidence.UNKNOWN
        )

        val result = RecoveryEvidenceSnapshotAdapter.prepareSnapshot(unknownAuthRaw)
        assertTrue(result is AdapterResult.Success)
        val success = result as AdapterResult.Success
        assertEquals(RecoveryEvidence.UNKNOWN, success.snapshot.initAuthority)
    }

    @Test
    fun `E12 - Invalid operation epoch is preserved without normalization`() {
        val invalidEpochIdentity = validOperationIdentity.copy(epoch = null)
        val raw = coherentRawEvidence.copy(operationIdentity = invalidEpochIdentity)

        val result = RecoveryEvidenceSnapshotAdapter.prepareSnapshot(raw)
        assertTrue(result is AdapterResult.Success)
        val success = result as AdapterResult.Success
        assertEquals(invalidEpochIdentity, success.snapshot.operationIdentity)
    }

    @Test
    fun `E13 - Blank operation token is preserved without fabrication`() {
        val blankTokenIdentity = validOperationIdentity.copy(token = "   ")
        val raw = coherentRawEvidence.copy(operationIdentity = blankTokenIdentity)

        val result = RecoveryEvidenceSnapshotAdapter.prepareSnapshot(raw)
        assertTrue(result is AdapterResult.Success)
        val success = result as AdapterResult.Success
        assertEquals(blankTokenIdentity, success.snapshot.operationIdentity)
    }

    @Test
    fun `E14 - Conflicting evidence fails closed with READ_FAILURE first`() {
        val readFailedSnapshot = validMarkerSnapshot.copy(roomState = MarkerState.READ_FAILED)
        val conflictingRaw = coherentRawEvidence.copy(
            markerSnapshot = readFailedSnapshot,
            coherence = CoherenceStatus.UNPROVEN_COHERENCE
        )

        val result = RecoveryEvidenceSnapshotAdapter.prepareSnapshot(conflictingRaw)
        assertTrue(result is AdapterResult.Failure)
        val failure = result as AdapterResult.Failure
        assertEquals(AdapterFailureReason.READ_FAILURE, failure.reason)
    }

    @Test
    fun `E15 - Repeated identical input produces identical AdapterResult`() {
        val result1 = RecoveryEvidenceSnapshotAdapter.prepareSnapshot(coherentRawEvidence)
        val result2 = RecoveryEvidenceSnapshotAdapter.prepareSnapshot(coherentRawEvidence)
        assertEquals(result1, result2)
    }

    @Test
    fun `E16 - Prepared snapshot evaluates cleanly in HandshakeRecoveryPolicy without precedence modification`() {
        val result = RecoveryEvidenceSnapshotAdapter.prepareSnapshot(coherentRawEvidence)
        assertTrue(result is AdapterResult.Success)
        val success = result as AdapterResult.Success

        val policyDecision = HandshakeRecoveryPolicy.evaluate(success.snapshot)
        assertEquals(HandshakeRecoveryDecision.REUSE_COMPLETED_SHADOW_EVIDENCE, policyDecision)
    }
}
