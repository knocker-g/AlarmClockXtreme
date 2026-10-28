package com.sysadmindoc.alarmclock.domain.evaluator

/**
 * Holiday classification status for an occurrence date (KNO-31 / KNO-153).
 */
enum class HolidayState {
    NOT_HOLIDAY,
    IS_HOLIDAY,
    UNKNOWN
}

/**
 * Snapshot of holiday evaluation result for a candidate occurrence date.
 */
data class HolidaySnapshot(
    val state: HolidayState = HolidayState.NOT_HOLIDAY,
    val holidayName: String = "",
    val checkedAtEpochMs: Long = 0L
)
