package com.sysadmindoc.alarmclock.domain

enum class MarkerState {
    ABSENT,
    PRESENT_VALID,
    CORRUPT,
    READ_FAILED
}

enum class CompletionState {
    COMPLETE,
    PREPARING,
    UNKNOWN
}

enum class IncarnationClassificationOutcome {
    READ_FAILURE,
    CORRUPT_EVIDENCE,
    INCONSISTENT_INPUT,
    BOTH_ABSENT_AMBIGUOUS,
    ROOM_MISSING,
    LOCAL_MISSING,
    ID_MISMATCH,
    INCOMPLETE_HANDSHAKE,
    MATCHED_COMPLETED_PAIR
}

data class IncarnationEvidenceSnapshot(
    val roomState: MarkerState,
    val roomId: String?,
    val roomCompletion: CompletionState,
    val localState: MarkerState,
    val localId: String?,
    val localCompletion: CompletionState
)

object IncarnationEvidenceClassifier {

    /**
     * Classifies incarnation marker evidence according to the normative
     * KNO-227 P1B1c1b0 Design Lock v3.1 precedence rules.
     */
    fun classify(snapshot: IncarnationEvidenceSnapshot): IncarnationClassificationOutcome {
        // 1. Read Failure Check (highest priority: READ_FAILED outranks CORRUPT and all others)
        if (snapshot.roomState == MarkerState.READ_FAILED || snapshot.localState == MarkerState.READ_FAILED) {
            return IncarnationClassificationOutcome.READ_FAILURE
        }

        // 2. Corrupt Evidence Check (CORRUPT outranks input inconsistency)
        if (snapshot.roomState == MarkerState.CORRUPT || snapshot.localState == MarkerState.CORRUPT) {
            return IncarnationClassificationOutcome.CORRUPT_EVIDENCE
        }

        // 3. Input Consistency Checks
        // ABSENT markers must have null ID and UNKNOWN completion state
        if (snapshot.roomState == MarkerState.ABSENT) {
            if (snapshot.roomId != null || snapshot.roomCompletion != CompletionState.UNKNOWN) {
                return IncarnationClassificationOutcome.INCONSISTENT_INPUT
            }
        }
        if (snapshot.localState == MarkerState.ABSENT) {
            if (snapshot.localId != null || snapshot.localCompletion != CompletionState.UNKNOWN) {
                return IncarnationClassificationOutcome.INCONSISTENT_INPUT
            }
        }

        // PRESENT_VALID markers must have a non-blank ID (UNKNOWN completion state is permitted as evidence of incomplete handshake)
        if (snapshot.roomState == MarkerState.PRESENT_VALID) {
            if (snapshot.roomId.isNullOrBlank()) {
                return IncarnationClassificationOutcome.INCONSISTENT_INPUT
            }
        }
        if (snapshot.localState == MarkerState.PRESENT_VALID) {
            if (snapshot.localId.isNullOrBlank()) {
                return IncarnationClassificationOutcome.INCONSISTENT_INPUT
            }
        }

        // 4. Both Absent Check
        if (snapshot.roomState == MarkerState.ABSENT && snapshot.localState == MarkerState.ABSENT) {
            return IncarnationClassificationOutcome.BOTH_ABSENT_AMBIGUOUS
        }

        // 5. Single Missing State Checks
        if (snapshot.roomState == MarkerState.ABSENT && snapshot.localState == MarkerState.PRESENT_VALID) {
            return IncarnationClassificationOutcome.ROOM_MISSING
        }
        if (snapshot.localState == MarkerState.ABSENT && snapshot.roomState == MarkerState.PRESENT_VALID) {
            return IncarnationClassificationOutcome.LOCAL_MISSING
        }

        // 6. Both Present Checks (roomState == PRESENT_VALID && localState == PRESENT_VALID)
        if (snapshot.roomId != snapshot.localId) {
            return IncarnationClassificationOutcome.ID_MISMATCH
        }

        if (snapshot.roomCompletion != CompletionState.COMPLETE || snapshot.localCompletion != CompletionState.COMPLETE) {
            return IncarnationClassificationOutcome.INCOMPLETE_HANDSHAKE
        }

        return IncarnationClassificationOutcome.MATCHED_COMPLETED_PAIR
    }
}
