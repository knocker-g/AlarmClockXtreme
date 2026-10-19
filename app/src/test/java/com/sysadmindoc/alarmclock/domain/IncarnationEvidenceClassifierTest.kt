package com.sysadmindoc.alarmclock.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class IncarnationEvidenceClassifierTest {

    private val validMatchedPairSnapshot = IncarnationEvidenceSnapshot(
        roomState = MarkerState.PRESENT_VALID,
        roomId = "marker-inc-100",
        roomCompletion = CompletionState.COMPLETE,
        localState = MarkerState.PRESENT_VALID,
        localId = "marker-inc-100",
        localCompletion = CompletionState.COMPLETE
    )

    private val absentSnapshot = IncarnationEvidenceSnapshot(
        roomState = MarkerState.ABSENT,
        roomId = null,
        roomCompletion = CompletionState.UNKNOWN,
        localState = MarkerState.ABSENT,
        localId = null,
        localCompletion = CompletionState.UNKNOWN
    )

    @Test
    fun `READ_FAILED outranks CORRUPT and all other states`() {
        val snapshot = IncarnationEvidenceSnapshot(
            roomState = MarkerState.READ_FAILED,
            roomId = null,
            roomCompletion = CompletionState.UNKNOWN,
            localState = MarkerState.CORRUPT,
            localId = null,
            localCompletion = CompletionState.UNKNOWN
        )
        val outcome = IncarnationEvidenceClassifier.classify(snapshot)
        assertEquals(IncarnationClassificationOutcome.READ_FAILURE, outcome)
    }

    @Test
    fun `CORRUPT outranks inconsistent or malformed input`() {
        val snapshot = IncarnationEvidenceSnapshot(
            roomState = MarkerState.CORRUPT,
            roomId = "some-id",
            roomCompletion = CompletionState.COMPLETE,
            localState = MarkerState.PRESENT_VALID,
            localId = "   ", // Malformed blank local ID
            localCompletion = CompletionState.UNKNOWN // Inconsistent state
        )
        val outcome = IncarnationEvidenceClassifier.classify(snapshot)
        assertEquals(IncarnationClassificationOutcome.CORRUPT_EVIDENCE, outcome)
    }

    @Test
    fun `inconsistent ABSENT marker with non-null ID or non-UNKNOWN completion produces INCONSISTENT_INPUT`() {
        // ABSENT with non-null ID
        val roomAbsentWithId = absentSnapshot.copy(roomId = "stray-id")
        assertEquals(
            IncarnationClassificationOutcome.INCONSISTENT_INPUT,
            IncarnationEvidenceClassifier.classify(roomAbsentWithId)
        )

        // ABSENT with non-UNKNOWN completion
        val localAbsentWithComplete = absentSnapshot.copy(
            localState = MarkerState.ABSENT,
            localCompletion = CompletionState.COMPLETE
        )
        assertEquals(
            IncarnationClassificationOutcome.INCONSISTENT_INPUT,
            IncarnationEvidenceClassifier.classify(localAbsentWithComplete)
        )
    }

    @Test
    fun `inconsistent PRESENT_VALID marker with blank ID or UNKNOWN completion produces INCONSISTENT_INPUT`() {
        // PRESENT_VALID with null ID
        val roomNullId = validMatchedPairSnapshot.copy(roomId = null)
        assertEquals(
            IncarnationClassificationOutcome.INCONSISTENT_INPUT,
            IncarnationEvidenceClassifier.classify(roomNullId)
        )

        // PRESENT_VALID with blank ID
        val localBlankId = validMatchedPairSnapshot.copy(localId = "   ")
        assertEquals(
            IncarnationClassificationOutcome.INCONSISTENT_INPUT,
            IncarnationEvidenceClassifier.classify(localBlankId)
        )

        // PRESENT_VALID with UNKNOWN completion state
        val roomUnknownCompletion = validMatchedPairSnapshot.copy(roomCompletion = CompletionState.UNKNOWN)
        assertEquals(
            IncarnationClassificationOutcome.INCONSISTENT_INPUT,
            IncarnationEvidenceClassifier.classify(roomUnknownCompletion)
        )
    }

    @Test
    fun `both markers ABSENT produces BOTH_ABSENT_AMBIGUOUS`() {
        val outcome = IncarnationEvidenceClassifier.classify(absentSnapshot)
        assertEquals(IncarnationClassificationOutcome.BOTH_ABSENT_AMBIGUOUS, outcome)
    }

    @Test
    fun `ROOM_MISSING and LOCAL_MISSING are classified separately`() {
        // ROOM_MISSING: Room ABSENT, Local PRESENT_VALID
        val roomMissingSnapshot = IncarnationEvidenceSnapshot(
            roomState = MarkerState.ABSENT,
            roomId = null,
            roomCompletion = CompletionState.UNKNOWN,
            localState = MarkerState.PRESENT_VALID,
            localId = "local-marker-123",
            localCompletion = CompletionState.COMPLETE
        )
        assertEquals(
            IncarnationClassificationOutcome.ROOM_MISSING,
            IncarnationEvidenceClassifier.classify(roomMissingSnapshot)
        )

        // LOCAL_MISSING: Local ABSENT, Room PRESENT_VALID
        val localMissingSnapshot = IncarnationEvidenceSnapshot(
            roomState = MarkerState.PRESENT_VALID,
            roomId = "room-marker-123",
            roomCompletion = CompletionState.COMPLETE,
            localState = MarkerState.ABSENT,
            localId = null,
            localCompletion = CompletionState.UNKNOWN
        )
        assertEquals(
            IncarnationClassificationOutcome.LOCAL_MISSING,
            IncarnationEvidenceClassifier.classify(localMissingSnapshot)
        )
    }

    @Test
    fun `both present with mismatched identifiers produces ID_MISMATCH`() {
        val mismatchedSnapshot = validMatchedPairSnapshot.copy(
            localId = "different-marker-200"
        )
        val outcome = IncarnationEvidenceClassifier.classify(mismatchedSnapshot)
        assertEquals(IncarnationClassificationOutcome.ID_MISMATCH, outcome)
    }

    @Test
    fun `case-sensitive identifier comparison produces ID_MISMATCH for casing differences`() {
        val casedSnapshot = validMatchedPairSnapshot.copy(
            roomId = "marker-abc-100",
            localId = "MARKER-ABC-100"
        )
        val outcome = IncarnationEvidenceClassifier.classify(casedSnapshot)
        assertEquals(IncarnationClassificationOutcome.ID_MISMATCH, outcome)
    }

    @Test
    fun `both present with matching identifiers but PREPARING completion produces INCOMPLETE_HANDSHAKE`() {
        // Room PREPARING
        val roomPreparingSnapshot = validMatchedPairSnapshot.copy(
            roomCompletion = CompletionState.PREPARING
        )
        assertEquals(
            IncarnationClassificationOutcome.INCOMPLETE_HANDSHAKE,
            IncarnationEvidenceClassifier.classify(roomPreparingSnapshot)
        )

        // Local PREPARING
        val localPreparingSnapshot = validMatchedPairSnapshot.copy(
            localCompletion = CompletionState.PREPARING
        )
        assertEquals(
            IncarnationClassificationOutcome.INCOMPLETE_HANDSHAKE,
            IncarnationEvidenceClassifier.classify(localPreparingSnapshot)
        )
    }

    @Test
    fun `both present with matching identifiers and COMPLETE produces MATCHED_COMPLETED_PAIR`() {
        val outcome = IncarnationEvidenceClassifier.classify(validMatchedPairSnapshot)
        assertEquals(IncarnationClassificationOutcome.MATCHED_COMPLETED_PAIR, outcome)
    }

    @Test
    fun `compound invalid precedence order is strictly obeyed`() {
        // 1. READ_FAILED outranks CORRUPT and INCONSISTENT
        val readFailedOverAll = IncarnationEvidenceSnapshot(
            roomState = MarkerState.READ_FAILED,
            roomId = "bad-id",
            roomCompletion = CompletionState.COMPLETE,
            localState = MarkerState.CORRUPT,
            localId = null,
            localCompletion = CompletionState.UNKNOWN
        )
        assertEquals(
            IncarnationClassificationOutcome.READ_FAILURE,
            IncarnationEvidenceClassifier.classify(readFailedOverAll)
        )

        // 2. CORRUPT outranks INCONSISTENT_INPUT
        val corruptOverInconsistent = IncarnationEvidenceSnapshot(
            roomState = MarkerState.CORRUPT,
            roomId = null,
            roomCompletion = CompletionState.UNKNOWN,
            localState = MarkerState.PRESENT_VALID,
            localId = "", // Inconsistent blank ID
            localCompletion = CompletionState.UNKNOWN // Inconsistent completion
        )
        assertEquals(
            IncarnationClassificationOutcome.CORRUPT_EVIDENCE,
            IncarnationEvidenceClassifier.classify(corruptOverInconsistent)
        )

        // 3. INCONSISTENT_INPUT outranks ID_MISMATCH / HANDSHAKE
        val inconsistentOverMismatch = validMatchedPairSnapshot.copy(
            roomId = "id1",
            localId = "id2",
            roomCompletion = CompletionState.UNKNOWN // Inconsistent
        )
        assertEquals(
            IncarnationClassificationOutcome.INCONSISTENT_INPUT,
            IncarnationEvidenceClassifier.classify(inconsistentOverMismatch)
        )
    }

    @Test
    fun `identical snapshots produce identical outcomes`() {
        val outcome1 = IncarnationEvidenceClassifier.classify(validMatchedPairSnapshot)
        val outcome2 = IncarnationEvidenceClassifier.classify(validMatchedPairSnapshot)
        assertEquals(outcome1, outcome2)
        assertEquals(IncarnationClassificationOutcome.MATCHED_COMPLETED_PAIR, outcome1)
    }
}
