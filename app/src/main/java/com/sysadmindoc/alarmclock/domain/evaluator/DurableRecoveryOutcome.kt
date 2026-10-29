package com.sysadmindoc.alarmclock.domain.evaluator

/**
 * Typed classification outcome for In-DB Durable Reconcile & Recovery operations (KNO-154 Phase 0C-B Subtask 3).
 */
enum class DurableRecoveryOutcome {
    /** Valid pending intent bound to current live authority; eligible for OS dispatch retry */
    RECOVERY_DISPATCH_ELIGIBLE,

    /** Stale owner token, restore epoch mismatch, or revision mismatch; intent phase updated to ABORTED in DB */
    INTENT_QUARANTINED_ABORTED,

    /** DB projection matches target and occurrence already issued; intent marked COMPLETED with 0 duplicate mutations */
    ALREADY_SETTLED_IDEMPOTENT,

    /** Required metadata or evidence missing in DB; deferred without mutating state */
    NEEDS_EVIDENCE_DEFERRED,

    /** Alarm missing, disabled, or Quick alarm; intent aborted in DB */
    UNSUPPORTED_SCHEDULE
}
