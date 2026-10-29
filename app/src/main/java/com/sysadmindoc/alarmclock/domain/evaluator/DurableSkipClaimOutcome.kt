package com.sysadmindoc.alarmclock.domain.evaluator

/**
 * Outcome enum for Durable Skip Claim operations in Room transaction (KNO-154 Phase 0B).
 */
enum class DurableSkipClaimOutcome {
    /** TYPE_SKIP exception successfully persisted in Room DB, pending OS AlarmManager reconcile (P0C) */
    COMMITTED_PENDING_RECONCILE,

    /** Exact TYPE_SKIP exception already exists in DB for this occurrence (idempotent no-op) */
    ALREADY_COMMITTED,

    /** An overriding exception (e.g. TYPE_TIME_OVERRIDE) exists on the same base occurrence key; overwriting prohibited */
    CONFLICT_OVERRIDE_EXISTS,

    /** Target, generation, authority fingerprint, or stored projection mismatch */
    STALE_OCCURRENCE,

    /** Alarm is currently in an active FIRING session */
    ACTIVE_SESSION,

    /** Candidate occurrence has already been consumed / handoff-delivered */
    ALREADY_CONSUMED,

    /** Disabled, Quick Alarm, HIDE-only schedule, or invalid schedule definition */
    UNSUPPORTED_SCHEDULE,

    /** Valid schedule definition, but no current future occurrence candidate exists */
    NO_CURRENT_OCCURRENCE
}
