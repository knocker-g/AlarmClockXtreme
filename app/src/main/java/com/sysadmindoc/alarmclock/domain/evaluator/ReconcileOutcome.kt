package com.sysadmindoc.alarmclock.domain.evaluator

/**
 * Typed classification outcome for Normal Alarm schedule reconciliation planning (KNO-154 Phase 0C-A).
 */
enum class ReconcileOutcome {
    /** Stored projection alarm.nextTriggerTime differs from evaluated post-skip next target time -> OS reschedule command candidate */
    PROJECTION_DIFFERS_RECONCILE_REQUIRED,

    /** Stored projection matches evaluated post-skip next target time, but OS AlarmManager registration remains UNVERIFIED (P0C-B) */
    PROJECTION_UPDATED_UNVERIFIED_OS,

    /** No active TYPE_SKIP exception exists in DB for active alarm generation */
    NO_ACTIVE_SKIP_EXCEPTION,

    /** Exception or request is for an older generation or authority mismatch */
    STALE_GENERATION,

    /** Authority fingerprint or storedAuthority missing (null) */
    MISSING_AUTHORITY,

    /** Alarm disabled, Quick Alarm, or uses unapproved HIDE features in Personal default mode */
    UNSUPPORTED_SCHEDULE,

    /** Valid schedule definition, but no current future occurrence candidate exists */
    NO_VALID_CANDIDATE_OCCURRENCE
}
