package com.sysadmindoc.alarmclock.domain.evaluator

/**
 * Typed classification outcome for Normal Alarm firing safety validation (KNO-154 Phase 0C-A).
 */
enum class FireSafetyOutcome {
    /** Confirmed matching TYPE_SKIP exception exists -> Firing MUST BE SUPPRESSED! */
    SUPPRESS_CONFIRMED_SKIP,

    /** Active valid occurrence WITH verified issuance proof -> Allow confirmed firing */
    ALLOW_FIRE_VERIFIED,

    /** Fired Intent generation is older than active alarm.generation -> Reject stale generation */
    REJECT_STALE_GENERATION,

    /** Fired occurrence key is in consumed / handoff set -> Reject already consumed */
    REJECT_CONSUMED_OCCURRENCE,

    /** Missing required timestamps (<= 0L) or missing issuance proof -> Unverified intent data */
    UNVERIFIED_INTENT_DATA
}
