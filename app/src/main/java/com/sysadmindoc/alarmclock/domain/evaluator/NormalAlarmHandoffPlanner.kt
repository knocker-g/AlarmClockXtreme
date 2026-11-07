package com.sysadmindoc.alarmclock.domain.evaluator

import com.sysadmindoc.alarmclock.data.local.entity.IssuedOccurrence
import com.sysadmindoc.alarmclock.data.model.AlarmOccurrenceId

/**
 * Explicit, typed actions for Normal Alarm Handoff decisions (KNO-37 Phase H2-C1).
 */
enum class NormalAlarmHandoffAction {
    ADMIT,
    SUPPRESS,
    DUPLICATE,
    ALREADY_SUPPRESSED,
    REJECT
}

/**
 * Verified, immutable facts presented to [NormalAlarmHandoffPlanner] by a caller
 * (e.g. future transactional coordinator).
 *
 * This value model represents immutable preconditions and does not perform database queries.
 */
data class NormalAlarmHandoffFacts(
    val occurrenceId: AlarmOccurrenceId,
    val fireId: String,
    val scheduledAt: Long,
    val issueToken: String,
    val occurrenceStatus: String,
    val isIdentityValid: Boolean,
    val isProvenanceValid: Boolean,
    val isGenerationValid: Boolean,
    val isIssueSlotValid: Boolean,
    val isAlreadyAdmitted: Boolean,
    val hasActiveSession: Boolean
) {
    val occurrenceKey: String
        get() = occurrenceId.key

    val admissionKey: String
        get() = "admission_${occurrenceId.alarmId}_$occurrenceKey"
}

/**
 * Pure, deterministic result of [NormalAlarmHandoffPlanner.plan].
 */
data class NormalAlarmHandoffPlan(
    val action: NormalAlarmHandoffAction,
    val occurrenceId: AlarmOccurrenceId,
    val occurrenceKey: String,
    val admissionKey: String,
    val fireId: String,
    val issueToken: String,
    val decisionReason: String
) {
    val isAdmitted: Boolean get() = action == NormalAlarmHandoffAction.ADMIT
    val isSuppressed: Boolean get() = action == NormalAlarmHandoffAction.SUPPRESS
    val isDuplicate: Boolean get() = action == NormalAlarmHandoffAction.DUPLICATE
    val isAlreadySuppressed: Boolean get() = action == NormalAlarmHandoffAction.ALREADY_SUPPRESSED
    val isRejected: Boolean get() = action == NormalAlarmHandoffAction.REJECT
}

/**
 * Pure, side-effect-free evaluator for Normal Alarm Handoff and Session Admission decisions (KNO-37 Phase H2-C1).
 *
 * Enforces a strict, deterministic 9-step decision priority:
 * 1. Invalid/stale identity -> REJECT
 * 2. Invalid provenance -> REJECT
 * 3. Stale generation -> REJECT
 * 4. Stale IssueSlot -> REJECT
 * 5. Already admitted/duplicate delivery -> DUPLICATE
 * 6. Already suppressed matching identity -> ALREADY_SUPPRESSED
 * 7. Unsupported starting status -> REJECT
 * 8. Active Session exists -> SUPPRESS
 * 9. No Active Session -> ADMIT
 */
class NormalAlarmHandoffPlanner {

    companion object {
        const val REASON_ADMIT_NEW_SESSION = "ADMIT_NEW_SESSION"
        const val REASON_SUPPRESS_ACTIVE_SESSION = "SUPPRESS_ACTIVE_SESSION"
        const val REASON_DUPLICATE_DELIVERY = "DUPLICATE_DELIVERY"
        const val REASON_ALREADY_SUPPRESSED = "ALREADY_SUPPRESSED"
        const val REASON_REJECT_INVALID_IDENTITY = "REJECT_INVALID_IDENTITY"
        const val REASON_REJECT_INVALID_PROVENANCE = "REJECT_INVALID_PROVENANCE"
        const val REASON_REJECT_STALE_GENERATION = "REJECT_STALE_GENERATION"
        const val REASON_REJECT_STALE_ISSUE_SLOT = "REJECT_STALE_ISSUE_SLOT"
        const val REASON_REJECT_UNSUPPORTED_STATUS = "REJECT_UNSUPPORTED_STATUS"
    }

    /**
     * Purely evaluates the handoff plan for the given [facts].
     * Has zero side-effects, does not access database, framework, clock, or RNG.
     */
    fun plan(facts: NormalAlarmHandoffFacts): NormalAlarmHandoffPlan {
        val occurrenceKey = facts.occurrenceKey
        val admissionKey = facts.admissionKey

        // 1. Invalid/stale identity -> REJECT
        val isIdentityValid = facts.isIdentityValid &&
                facts.scheduledAt > 0L &&
                facts.fireId.isNotBlank() &&
                facts.issueToken.isNotBlank() &&
                facts.occurrenceId.alarmId > 0L &&
                facts.occurrenceId.baseTriggerTime > 0L &&
                facts.occurrenceId.generation > 0

        if (!isIdentityValid) {
            return NormalAlarmHandoffPlan(
                action = NormalAlarmHandoffAction.REJECT,
                occurrenceId = facts.occurrenceId,
                occurrenceKey = occurrenceKey,
                admissionKey = admissionKey,
                fireId = facts.fireId,
                issueToken = facts.issueToken,
                decisionReason = REASON_REJECT_INVALID_IDENTITY
            )
        }

        // 2. Invalid provenance -> REJECT
        if (!facts.isProvenanceValid) {
            return NormalAlarmHandoffPlan(
                action = NormalAlarmHandoffAction.REJECT,
                occurrenceId = facts.occurrenceId,
                occurrenceKey = occurrenceKey,
                admissionKey = admissionKey,
                fireId = facts.fireId,
                issueToken = facts.issueToken,
                decisionReason = REASON_REJECT_INVALID_PROVENANCE
            )
        }

        // 3. Stale generation -> REJECT
        if (!facts.isGenerationValid) {
            return NormalAlarmHandoffPlan(
                action = NormalAlarmHandoffAction.REJECT,
                occurrenceId = facts.occurrenceId,
                occurrenceKey = occurrenceKey,
                admissionKey = admissionKey,
                fireId = facts.fireId,
                issueToken = facts.issueToken,
                decisionReason = REASON_REJECT_STALE_GENERATION
            )
        }

        // 4. Stale IssueSlot -> REJECT
        if (!facts.isIssueSlotValid) {
            return NormalAlarmHandoffPlan(
                action = NormalAlarmHandoffAction.REJECT,
                occurrenceId = facts.occurrenceId,
                occurrenceKey = occurrenceKey,
                admissionKey = admissionKey,
                fireId = facts.fireId,
                issueToken = facts.issueToken,
                decisionReason = REASON_REJECT_STALE_ISSUE_SLOT
            )
        }

        // 5. Already admitted/duplicate delivery -> DUPLICATE
        if (facts.isAlreadyAdmitted) {
            return NormalAlarmHandoffPlan(
                action = NormalAlarmHandoffAction.DUPLICATE,
                occurrenceId = facts.occurrenceId,
                occurrenceKey = occurrenceKey,
                admissionKey = admissionKey,
                fireId = facts.fireId,
                issueToken = facts.issueToken,
                decisionReason = REASON_DUPLICATE_DELIVERY
            )
        }

        // 6. Already suppressed matching identity -> ALREADY_SUPPRESSED
        if (facts.occurrenceStatus == IssuedOccurrence.STATUS_SUPPRESSED) {
            return NormalAlarmHandoffPlan(
                action = NormalAlarmHandoffAction.ALREADY_SUPPRESSED,
                occurrenceId = facts.occurrenceId,
                occurrenceKey = occurrenceKey,
                admissionKey = admissionKey,
                fireId = facts.fireId,
                issueToken = facts.issueToken,
                decisionReason = REASON_ALREADY_SUPPRESSED
            )
        }

        // 7. Unsupported starting status -> REJECT
        if (facts.occurrenceStatus != IssuedOccurrence.STATUS_ISSUED) {
            return NormalAlarmHandoffPlan(
                action = NormalAlarmHandoffAction.REJECT,
                occurrenceId = facts.occurrenceId,
                occurrenceKey = occurrenceKey,
                admissionKey = admissionKey,
                fireId = facts.fireId,
                issueToken = facts.issueToken,
                decisionReason = REASON_REJECT_UNSUPPORTED_STATUS
            )
        }

        // 8. Active Session exists -> SUPPRESS
        if (facts.hasActiveSession) {
            return NormalAlarmHandoffPlan(
                action = NormalAlarmHandoffAction.SUPPRESS,
                occurrenceId = facts.occurrenceId,
                occurrenceKey = occurrenceKey,
                admissionKey = admissionKey,
                fireId = facts.fireId,
                issueToken = facts.issueToken,
                decisionReason = REASON_SUPPRESS_ACTIVE_SESSION
            )
        }

        // 9. No Active Session -> ADMIT
        return NormalAlarmHandoffPlan(
            action = NormalAlarmHandoffAction.ADMIT,
            occurrenceId = facts.occurrenceId,
            occurrenceKey = occurrenceKey,
            admissionKey = admissionKey,
            fireId = facts.fireId,
            issueToken = facts.issueToken,
            decisionReason = REASON_ADMIT_NEW_SESSION
        )
    }
}
