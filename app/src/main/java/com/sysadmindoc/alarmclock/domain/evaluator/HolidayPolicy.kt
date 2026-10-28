package com.sysadmindoc.alarmclock.domain.evaluator

/**
 * Holiday policy classification mode for an alarm schedule (KNO-31 / KNO-153).
 */
enum class HolidayPolicy {
    /** Exclude/skip base schedule occurrence on a confirmed holiday (skipOnHolidays = true) */
    EXCLUDE,

    /** Fire exclusively or additionally on holidays */
    INCLUDE,

    /** Follow base schedule regardless of holiday status (skipOnHolidays = false) */
    FOLLOW_BASE
}
