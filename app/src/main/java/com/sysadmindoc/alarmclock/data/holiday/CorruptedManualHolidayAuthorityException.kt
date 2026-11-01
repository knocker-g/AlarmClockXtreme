package com.sysadmindoc.alarmclock.data.holiday

/**
 * Thrown when durable manual Holiday override state is unreadable or corrupted.
 * Fails closed to prevent treating unknown or corrupted overrides as AUTO or WORKDAY.
 */
class CorruptedManualHolidayAuthorityException(
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)
