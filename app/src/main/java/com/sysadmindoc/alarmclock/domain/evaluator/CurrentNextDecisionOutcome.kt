package com.sysadmindoc.alarmclock.domain.evaluator

/**
 * Typed classification outcome for current-next occurrence decision planning (KNO-154).
 */
enum class CurrentNextDecisionOutcome {
    /** Expected target matches active current occurrence derived by candidate resolver */
    CURRENT_NEXT_VALID,

    /** Expected target is stale (trigger time, generation, or revision mismatch) */
    STALE_OCCURRENCE,

    /** Candidate occurrence has already been consumed / handoff-delivered */
    ALREADY_CONSUMED,

    /** Alarm is disabled, Quick Alarm, HIDE-only schedule, invalid schedule definition, or unsupported exception type */
    UNSUPPORTED_SCHEDULE,

    /** Alarm is currently firing / in active firing session */
    ACTIVE_SESSION,

    /** Valid schedule definition, but no current future occurrence candidate exists */
    NO_CURRENT_OCCURRENCE
}
