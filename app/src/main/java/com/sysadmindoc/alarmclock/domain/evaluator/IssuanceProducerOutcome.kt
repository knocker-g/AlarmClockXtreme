package com.sysadmindoc.alarmclock.domain.evaluator

/**
 * Typed classification outcome for Occurrence Issuance Producer operations (KNO-154 Phase 0C-B Subtask 2 Safety Closure).
 */
enum class IssuanceProducerOutcome {
    /** Occurrence issuance with complete V35 provenance committed to Room DB */
    ISSUED_COMMITTED,

    /** Authority fingerprint, schedule revision, or alarm generation mismatch */
    STALE_GENERATION,

    /** ScheduleAuthority record or fingerprint missing */
    MISSING_AUTHORITY,

    /** Alarm disabled, Quick Alarm, invalid base/target timestamps, or uses unapproved HIDE features */
    UNSUPPORTED_SCHEDULE,

    /** IssueSlot CAS atomic update failed due to concurrent modification */
    CONFLICT_ALREADY_ISSUED
}
