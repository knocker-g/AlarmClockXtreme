package com.sysadmindoc.alarmclock.domain.evaluator

import com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent
import com.sysadmindoc.alarmclock.data.local.entity.IssueSlot
import com.sysadmindoc.alarmclock.data.local.entity.IssuedOccurrence

/**
 * Pure, deterministic, side-effect-free production validator for B-Specific CANCEL intents (KNO-75 R2-S1-C4-B3-P2D).
 */
object DurableCancelIntentValidator {

    /**
     * Strictly classifies whether [intent] is a B-Specific CANCEL intent.
     *
     * MUST satisfy:
     * 1. [DurableOperationIntent.operationKind] == "CANCEL"
     * 2. [DurableOperationIntent.intentRevision] == 2L (EXACT 2L; intentRevision >= 2L is prohibited!)
     * 3. [DurableOperationIntent.expectedOwnerToken] is non-null and non-blank
     * 4. [DurableOperationIntent.originalFireId] is non-null and non-blank
     * 5. [DurableOperationIntent.originalScheduledAt] is non-null and > 0L
     */
    fun isBSpecificCancelIntent(intent: DurableOperationIntent): Boolean {
        if (intent.operationKind != "CANCEL") return false
        if (intent.intentRevision != 2L) return false // EXACT 2L required!
        if (intent.expectedOwnerToken.isNullOrBlank()) return false
        if (intent.originalFireId.isNullOrBlank()) return false
        if (intent.originalScheduledAt == null || intent.originalScheduledAt <= 0L) return false
        return true
    }

    /**
     * Pure, side-effect-free binding verification between a B-Specific CANCEL [intent],
     * [targetOccurrence] B, and [currentSlot] state.
     *
     * Returns `true` IF AND ONLY IF all strict binding conditions pass.
     */
    fun verifyBSpecificCancelBinding(
        intent: DurableOperationIntent,
        targetOccurrence: IssuedOccurrence?,
        currentSlot: IssueSlot?
    ): Boolean {
        // 1. Strict Provenance Check
        if (!isBSpecificCancelIntent(intent)) return false

        // 2. Target Occurrence presence and Primary Key / OperationToken Binding
        if (targetOccurrence == null || intent.operationToken != targetOccurrence.issueToken) return false

        // 3. Payload Witness Consistency
        if (targetOccurrence.alarmId != intent.alarmId) return false
        if (targetOccurrence.fireId != intent.originalFireId) return false
        if (targetOccurrence.scheduledAt != intent.originalScheduledAt) return false
        if (targetOccurrence.status != IssuedOccurrence.STATUS_PREPARED) return false

        // 4. Non-Current Slot Owner & Slot AlarmId Alignment Verification
        if (currentSlot == null || currentSlot.alarmId != targetOccurrence.alarmId) return false
        if (currentSlot.currentIssueToken != null && currentSlot.currentIssueToken == targetOccurrence.issueToken) return false

        // 5. Slot Generation Verification
        if (targetOccurrence.generationId != currentSlot.generationId) return false

        // 6. Authority & Epoch Lineage
        if (targetOccurrence.authorityInstanceToken.isNullOrBlank() || intent.expectedOwnerToken != targetOccurrence.authorityInstanceToken) return false
        if (intent.expectedRestoreEpoch == null || intent.expectedRestoreEpoch <= 0L || targetOccurrence.restoreEpoch <= 0L || intent.expectedRestoreEpoch != targetOccurrence.restoreEpoch) return false
        if (intent.expectedScheduleRevision == null || intent.expectedScheduleRevision <= 0L || targetOccurrence.scheduleRevision <= 0L || intent.expectedScheduleRevision != targetOccurrence.scheduleRevision) return false

        // 7. Durable Phase Verification
        if (!DurableOperationIntent.isUnresolvedPhase(intent.phase)) return false

        return true
    }

    enum class CancelIntentRevisionClass {
        LEGACY_REV1,
        REV2_B_SPECIFIC,
        UNKNOWN_FAIL_CLOSED
    }

    /**
     * Strictly classifies `operationKind == "CANCEL"` intent revisions.
     * Returns `null` for non-CANCEL intents.
     */
    fun classifyCancelIntentRevision(intent: DurableOperationIntent): CancelIntentRevisionClass? {
        if (intent.operationKind != "CANCEL") return null
        return when (intent.intentRevision) {
            1L -> CancelIntentRevisionClass.LEGACY_REV1
            2L -> CancelIntentRevisionClass.REV2_B_SPECIFIC
            else -> CancelIntentRevisionClass.UNKNOWN_FAIL_CLOSED
        }
    }

    fun isLegacyRev1CancelIntent(intent: DurableOperationIntent): Boolean =
        classifyCancelIntentRevision(intent) == CancelIntentRevisionClass.LEGACY_REV1

    fun isNonLegacyCancelIntent(intent: DurableOperationIntent): Boolean {
        val revClass = classifyCancelIntentRevision(intent) ?: return false
        return revClass != CancelIntentRevisionClass.LEGACY_REV1
    }
}
