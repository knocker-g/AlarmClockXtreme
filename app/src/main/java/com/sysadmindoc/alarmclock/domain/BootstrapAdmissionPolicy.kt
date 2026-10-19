package com.sysadmindoc.alarmclock.domain

enum class BootstrapMetaState {
    ABSENT,
    PREPARING,
    ENFORCED,
    CORRUPT,
    UNKNOWN
}

enum class BootstrapOwnerState {
    ABSENT,
    VALID_CURRENT,
    CONFLICT,
    UNKNOWN
}

enum class BootstrapIntentState {
    RESOLVED_SET,
    RESOLVED_CANCEL,
    UNRESOLVED,
    INVALID
}

enum class BootstrapAdmissionDecision {
    BLOCK_PREREQUISITES,
    BLOCK_INVALID_META,
    BLOCK_UNKNOWN_META,
    BLOCK_ENFORCED,
    INITIALIZE_META,
    REUSE_META,
    BLOCK_EPOCH,
    REPLAN_STALE_DEFINITION,
    BLOCK_OWNER_CONFLICT,
    INSPECT_UNKNOWN_OWNER,
    KEEP_UNRESOLVED,
    REUSE_OWNER,
    PREPARE_OWNER,
    BLOCK_INVALID_INTENT
}

data class BootstrapAdmissionSnapshot(
    val prerequisitesReady: Boolean,
    val metaState: BootstrapMetaState,
    val metaEpoch: Long?,
    val ownerState: BootstrapOwnerState,
    val expectedEpoch: Long?,
    val intentState: BootstrapIntentState,
    val definitionStillCurrent: Boolean,
    val expectedOwnerTokenMatches: Boolean
)

object BootstrapAdmissionPolicy {

    /**
     * Evaluates a bootstrap admission snapshot according to the normative
     * KNO-227 P1B1c0 Design Lock decision precedence order.
     */
    fun evaluate(snapshot: BootstrapAdmissionSnapshot): BootstrapAdmissionDecision {
        // 1. Unmet Prerequisites -> BLOCK_PREREQUISITES
        if (!snapshot.prerequisitesReady) {
            return BootstrapAdmissionDecision.BLOCK_PREREQUISITES
        }

        // 2. Metadata Validity Checks
        when (snapshot.metaState) {
            BootstrapMetaState.CORRUPT -> return BootstrapAdmissionDecision.BLOCK_INVALID_META
            BootstrapMetaState.UNKNOWN -> return BootstrapAdmissionDecision.BLOCK_UNKNOWN_META
            BootstrapMetaState.ENFORCED -> return BootstrapAdmissionDecision.BLOCK_ENFORCED
            BootstrapMetaState.ABSENT,
            BootstrapMetaState.PREPARING -> { /* proceed */ }
        }

        // 3. Epoch Validation
        if (snapshot.metaState == BootstrapMetaState.ABSENT) {
            if (snapshot.expectedEpoch == null || snapshot.expectedEpoch <= 0L) {
                return BootstrapAdmissionDecision.BLOCK_EPOCH
            }
        } else if (snapshot.metaState == BootstrapMetaState.PREPARING) {
            if (snapshot.metaEpoch == null ||
                snapshot.metaEpoch <= 0L ||
                snapshot.expectedEpoch == null ||
                snapshot.metaEpoch != snapshot.expectedEpoch
            ) {
                return BootstrapAdmissionDecision.BLOCK_EPOCH
            }
        }

        // 4. Alarm Definition Currency
        if (!snapshot.definitionStillCurrent) {
            return BootstrapAdmissionDecision.REPLAN_STALE_DEFINITION
        }

        // 5. Owner State Checks
        when (snapshot.ownerState) {
            BootstrapOwnerState.CONFLICT -> return BootstrapAdmissionDecision.BLOCK_OWNER_CONFLICT
            BootstrapOwnerState.UNKNOWN -> return BootstrapAdmissionDecision.INSPECT_UNKNOWN_OWNER
            BootstrapOwnerState.ABSENT,
            BootstrapOwnerState.VALID_CURRENT -> { /* proceed */ }
        }

        // 6. Intent State Checks
        when (snapshot.intentState) {
            BootstrapIntentState.INVALID -> return BootstrapAdmissionDecision.BLOCK_INVALID_INTENT
            BootstrapIntentState.UNRESOLVED -> return BootstrapAdmissionDecision.KEEP_UNRESOLVED
            BootstrapIntentState.RESOLVED_SET,
            BootstrapIntentState.RESOLVED_CANCEL -> { /* proceed */ }
        }

        // 7. Owner Action Decision
        if (snapshot.ownerState == BootstrapOwnerState.VALID_CURRENT && snapshot.expectedOwnerTokenMatches) {
            return BootstrapAdmissionDecision.REUSE_OWNER
        }

        return BootstrapAdmissionDecision.PREPARE_OWNER
    }
}
