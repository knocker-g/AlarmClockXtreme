package com.sysadmindoc.alarmclock.domain.evaluator

/**
 * Typed classification outcome for Durable Reconcile Producer operations (KNO-154 Phase 0C-B Subtask 2 Safety Closure).
 */
enum class DurableReconcileProducerOutcome {
    /** Stored projection updated and OS reschedule intent committed pending OS dispatch */
    RECONCILE_COMMITTED_PENDING_OS,

    /** DB projection already matches post-skip target and OS registration is confirmed */
    ALREADY_UP_TO_DATE,

    /** DB projection matches post-skip target, but OS registration state remains unverified (requires retry/verification) */
    UNVERIFIED_OS_STATE_PENDING_RETRY,

    /** Authority fingerprint, schedule revision, or alarm generation mismatch */
    STALE_REJECTED,

    /** Alarm disabled, Quick Alarm, or uses unapproved HIDE features in Personal default mode */
    UNSUPPORTED_SCHEDULE
}
