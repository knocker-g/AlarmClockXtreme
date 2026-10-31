package com.sysadmindoc.alarmclock.domain.evaluator

import java.util.Locale

/**
 * Holiday policy classification mode for an alarm schedule (KNO-31 / KNO-153 / KNO-159).
 */
enum class HolidayPolicy {
    /** Exclude/skip base schedule occurrence on a confirmed holiday */
    EXCLUDE,

    /** Fire exclusively or additionally on holidays */
    INCLUDE,

    /** Follow base schedule regardless of holiday status */
    FOLLOW_BASE;

    companion object {
        fun parseCanonicalOrNull(raw: String?): HolidayPolicy? {
            if (raw.isNullOrBlank()) return null
            return when (raw.trim().uppercase(Locale.US)) {
                "FOLLOW_BASE" -> FOLLOW_BASE
                "EXCLUDE" -> EXCLUDE
                "INCLUDE" -> INCLUDE
                else -> null
            }
        }

        fun fromLegacySkipOnHolidays(skipOnHolidays: Boolean): HolidayPolicy =
            if (skipOnHolidays) EXCLUDE else FOLLOW_BASE
    }
}
