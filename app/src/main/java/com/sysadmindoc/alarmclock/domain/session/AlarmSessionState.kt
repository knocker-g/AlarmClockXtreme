package com.sysadmindoc.alarmclock.domain.session

/**
 * State vocabulary for KNO-161 active alarm sessions.
 */
enum class AlarmSessionState {
    FIRING,
    SNOOZED,
    WAKE_CHECK_WAITING,
    WAKE_CHECK_ACTIVE,
    COMPLETED
}
