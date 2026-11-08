package com.sysadmindoc.alarmclock.domain.evaluator

/**
 * Explicit, typed post-fire schedule actions proposed by [NormalAlarmPostFirePlanner] (KNO-37 Phase H2-C4-P1).
 *
 * These actions are proposals only; the planner performs zero side-effects and zero database/OS operations.
 */
enum class NormalAlarmPostFireAction {
    /**
     * Propose advancing repeating alarm to its next scheduled occurrence
     * using the canonical not-before schedule floor (scheduledAt + 60_000L).
     */
    ADVANCE_REPEATING_SCHEDULE,

    /**
     * Propose disabling a one-time alarm definition via the existing Scheduler owner.
     */
    DISABLE_ONE_TIME,

    /**
     * Propose attaching to an existing authoritative active session for a duplicate delivery.
     */
    ATTACH_TO_ACTIVE_SESSION,

    /**
     * Fail-closed / no-op drop: no schedule mutation, no session change, no OS side effect.
     */
    NO_OP_DROP
}

/**
 * Immutable facts presented to [NormalAlarmPostFirePlanner].
 * All fields represent verified caller facts; no database reads are performed by the planner.
 */
data class NormalAlarmPostFireFacts(
    val alarmId: Long,
    val isRecurringSchedule: Boolean,
    val scheduledAt: Long,
    val handoffResult: NormalAlarmHandoffResult,
    val activeSessionId: String? = null,
    val activeSessionState: String? = null
)

/**
 * Pure, deterministic result of [NormalAlarmPostFirePlanner.plan].
 */
data class NormalAlarmPostFirePlan(
    val action: NormalAlarmPostFireAction,
    val alarmId: Long,
    val notBeforeMillis: Long = 0L,
    val targetSessionId: String? = null,
    val decisionReason: String
) {
    val isAdvancing: Boolean get() = action == NormalAlarmPostFireAction.ADVANCE_REPEATING_SCHEDULE
    val isDisabling: Boolean get() = action == NormalAlarmPostFireAction.DISABLE_ONE_TIME
    val isAttaching: Boolean get() = action == NormalAlarmPostFireAction.ATTACH_TO_ACTIVE_SESSION
    val isDropped: Boolean get() = action == NormalAlarmPostFireAction.NO_OP_DROP
}

/**
 * Pure domain planner determining the appropriate post-fire / post-handoff action
 * without performing Room mutations, OS effects, or runtime activation (KNO-37 Phase H2-C4-P1).
 */
class NormalAlarmPostFirePlanner {

    companion object {
        const val REASON_ADMITTED_REPEATING_ADVANCE = "ADMITTED_REPEATING_ADVANCE"
        const val REASON_ADMITTED_ONE_TIME_DISABLE = "ADMITTED_ONE_TIME_DISABLE"
        const val REASON_SUPPRESSED_REPEATING_ADVANCE = "SUPPRESSED_REPEATING_ADVANCE"
        const val REASON_SUPPRESSED_ONE_TIME_NOOP = "SUPPRESSED_ONE_TIME_NOOP"
        const val REASON_SUPPRESSED_UNPROVEN_ACTION = "SUPPRESSED_UNPROVEN_ACTION"
        const val REASON_DUPLICATE_ACTIVE_ATTACH = "DUPLICATE_ACTIVE_ATTACH"
        const val REASON_DUPLICATE_COMPLETED_DROP = "DUPLICATE_COMPLETED_DROP"
        const val REASON_DUPLICATE_NO_ACTIVE_SESSION_DROP = "DUPLICATE_NO_ACTIVE_SESSION_DROP"
        const val REASON_ALREADY_SUPPRESSED_DROP = "ALREADY_SUPPRESSED_DROP"
        const val REASON_REJECTED_DROP = "REJECTED_DROP"
        const val REASON_CORRUPTED_STATE_DROP = "CORRUPTED_STATE_DROP"
        const val REASON_CAS_CONFLICT_DROP = "CAS_CONFLICT_DROP"
        const val REASON_REPOSITORY_FAILURE_DROP = "REPOSITORY_FAILURE_DROP"
        const val REASON_INVALID_ALARM_ID = "INVALID_ALARM_ID"
        const val REASON_INVALID_SCHEDULED_AT = "INVALID_SCHEDULED_AT"
        const val REASON_TIMESTAMP_OVERFLOW = "TIMESTAMP_OVERFLOW"
        const val REASON_CONTRADICTORY_ADMISSION = "CONTRADICTORY_ADMISSION"

        private const val SCHEDULE_ADVANCEMENT_FLOOR_OFFSET_MS = 60_000L

        private val ALLOWED_ACTIVE_SESSION_STATES = setOf(
            "FIRING",
            "SNOOZED",
            "WAKE_CHECK_WAITING",
            "WAKE_CHECK_ACTIVE"
        )
    }

    /**
     * Evaluates verified facts and returns a deterministic post-fire plan.
     */
    fun plan(facts: NormalAlarmPostFireFacts): NormalAlarmPostFirePlan {
        // 0. Strict precondition validation (Fail-Closed)
        if (facts.alarmId <= 0L) {
            return NormalAlarmPostFirePlan(
                action = NormalAlarmPostFireAction.NO_OP_DROP,
                alarmId = facts.alarmId,
                decisionReason = REASON_INVALID_ALARM_ID
            )
        }

        if (facts.scheduledAt <= 0L) {
            return NormalAlarmPostFirePlan(
                action = NormalAlarmPostFireAction.NO_OP_DROP,
                alarmId = facts.alarmId,
                decisionReason = REASON_INVALID_SCHEDULED_AT
            )
        }

        if (facts.scheduledAt > Long.MAX_VALUE - SCHEDULE_ADVANCEMENT_FLOOR_OFFSET_MS) {
            return NormalAlarmPostFirePlan(
                action = NormalAlarmPostFireAction.NO_OP_DROP,
                alarmId = facts.alarmId,
                decisionReason = REASON_TIMESTAMP_OVERFLOW
            )
        }

        val handoffResult = facts.handoffResult

        return when (handoffResult.outcome) {
            NormalAlarmHandoffOutcome.ADMITTED -> {
                if (handoffResult.sessionId.isNullOrBlank()) {
                    return NormalAlarmPostFirePlan(
                        action = NormalAlarmPostFireAction.NO_OP_DROP,
                        alarmId = facts.alarmId,
                        decisionReason = REASON_CONTRADICTORY_ADMISSION
                    )
                }

                if (facts.isRecurringSchedule) {
                    NormalAlarmPostFirePlan(
                        action = NormalAlarmPostFireAction.ADVANCE_REPEATING_SCHEDULE,
                        alarmId = facts.alarmId,
                        notBeforeMillis = facts.scheduledAt + SCHEDULE_ADVANCEMENT_FLOOR_OFFSET_MS,
                        targetSessionId = handoffResult.sessionId,
                        decisionReason = REASON_ADMITTED_REPEATING_ADVANCE
                    )
                } else {
                    NormalAlarmPostFirePlan(
                        action = NormalAlarmPostFireAction.DISABLE_ONE_TIME,
                        alarmId = facts.alarmId,
                        notBeforeMillis = 0L,
                        targetSessionId = handoffResult.sessionId,
                        decisionReason = REASON_ADMITTED_ONE_TIME_DISABLE
                    )
                }
            }

            NormalAlarmHandoffOutcome.SUPPRESSED -> {
                // Must verify authoritative occurrence consumption
                if (handoffResult.actionTaken != NormalAlarmHandoffAction.SUPPRESS) {
                    return NormalAlarmPostFirePlan(
                        action = NormalAlarmPostFireAction.NO_OP_DROP,
                        alarmId = facts.alarmId,
                        decisionReason = REASON_SUPPRESSED_UNPROVEN_ACTION
                    )
                }

                if (facts.isRecurringSchedule) {
                    NormalAlarmPostFirePlan(
                        action = NormalAlarmPostFireAction.ADVANCE_REPEATING_SCHEDULE,
                        alarmId = facts.alarmId,
                        notBeforeMillis = facts.scheduledAt + SCHEDULE_ADVANCEMENT_FLOOR_OFFSET_MS,
                        decisionReason = REASON_SUPPRESSED_REPEATING_ADVANCE
                    )
                } else {
                    NormalAlarmPostFirePlan(
                        action = NormalAlarmPostFireAction.NO_OP_DROP,
                        alarmId = facts.alarmId,
                        decisionReason = REASON_SUPPRESSED_ONE_TIME_NOOP
                    )
                }
            }

            NormalAlarmHandoffOutcome.DUPLICATE -> {
                val activeId = facts.activeSessionId
                val activeState = facts.activeSessionState

                if (!activeId.isNullOrBlank() && activeState != null) {
                    if (activeState in ALLOWED_ACTIVE_SESSION_STATES && activeId == handoffResult.sessionId) {
                        NormalAlarmPostFirePlan(
                            action = NormalAlarmPostFireAction.ATTACH_TO_ACTIVE_SESSION,
                            alarmId = facts.alarmId,
                            targetSessionId = activeId,
                            decisionReason = REASON_DUPLICATE_ACTIVE_ATTACH
                        )
                    } else {
                        NormalAlarmPostFirePlan(
                            action = NormalAlarmPostFireAction.NO_OP_DROP,
                            alarmId = facts.alarmId,
                            decisionReason = REASON_DUPLICATE_COMPLETED_DROP
                        )
                    }
                } else {
                    NormalAlarmPostFirePlan(
                        action = NormalAlarmPostFireAction.NO_OP_DROP,
                        alarmId = facts.alarmId,
                        decisionReason = REASON_DUPLICATE_NO_ACTIVE_SESSION_DROP
                    )
                }
            }

            NormalAlarmHandoffOutcome.ALREADY_SUPPRESSED -> {
                NormalAlarmPostFirePlan(
                    action = NormalAlarmPostFireAction.NO_OP_DROP,
                    alarmId = facts.alarmId,
                    decisionReason = REASON_ALREADY_SUPPRESSED_DROP
                )
            }

            NormalAlarmHandoffOutcome.REJECTED_STALE_IDENTITY,
            NormalAlarmHandoffOutcome.REJECTED_STALE_PROVENANCE,
            NormalAlarmHandoffOutcome.REJECTED_STALE_GENERATION,
            NormalAlarmHandoffOutcome.REJECTED_STALE_ISSUE_SLOT,
            NormalAlarmHandoffOutcome.REJECTED_UNSUPPORTED_STATUS,
            NormalAlarmHandoffOutcome.REJECTED_UNSUPPORTED_SCHEDULE -> {
                NormalAlarmPostFirePlan(
                    action = NormalAlarmPostFireAction.NO_OP_DROP,
                    alarmId = facts.alarmId,
                    decisionReason = REASON_REJECTED_DROP
                )
            }

            NormalAlarmHandoffOutcome.CORRUPTED_STATE -> {
                NormalAlarmPostFirePlan(
                    action = NormalAlarmPostFireAction.NO_OP_DROP,
                    alarmId = facts.alarmId,
                    decisionReason = REASON_CORRUPTED_STATE_DROP
                )
            }

            NormalAlarmHandoffOutcome.CAS_CONFLICT -> {
                NormalAlarmPostFirePlan(
                    action = NormalAlarmPostFireAction.NO_OP_DROP,
                    alarmId = facts.alarmId,
                    decisionReason = REASON_CAS_CONFLICT_DROP
                )
            }

            NormalAlarmHandoffOutcome.REPOSITORY_FAILURE -> {
                NormalAlarmPostFirePlan(
                    action = NormalAlarmPostFireAction.NO_OP_DROP,
                    alarmId = facts.alarmId,
                    decisionReason = REASON_REPOSITORY_FAILURE_DROP
                )
            }
        }
    }
}
