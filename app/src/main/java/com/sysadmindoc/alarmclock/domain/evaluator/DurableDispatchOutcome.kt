package com.sysadmindoc.alarmclock.domain.evaluator

/**
 * Typed classification outcome for OS Dispatch Admission & Settlement Verification (KNO-154 Phase 0C-B Subtask 4).
 */
enum class DurableDispatchOutcome {
    /** Admission granted; intent transitioned to PHASE_IN_FLIGHT with active dispatch lease */
    ADMISSION_GRANTED_IN_FLIGHT,

    /** Admission rejected due to stale owner token, restore epoch mismatch, or revision mismatch; intent aborted in DB */
    ADMISSION_REJECTED_QUARANTINED,

    /** Admission rejected because intent is currently in-flight with an unexpired active dispatch lease */
    ADMISSION_REJECTED_IN_FLIGHT_LEASE_ACTIVE,

    /** Settlement granted; authentic OS registration proof verified and intent marked PHASE_COMPLETED */
    SETTLEMENT_SUCCESS_COMPLETED,

    /** Settlement rejected because provided OS registration proof is missing, forged, or mismatched */
    SETTLEMENT_REJECTED_FORGED_PROOF,

    /** Alarm missing, disabled, or Quick alarm; intent aborted in DB */
    UNSUPPORTED_SCHEDULE
}
