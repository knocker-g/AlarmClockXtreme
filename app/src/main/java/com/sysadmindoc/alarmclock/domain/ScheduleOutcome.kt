package com.sysadmindoc.alarmclock.domain

/**
 * Compact, truthful scheduler outcome contract for alarm scheduling and rescheduling.
 */
sealed interface ScheduleOutcome {
    /**
     * AlarmManager registration successfully completed and confirmed.
     * @param alarmId The ID of the scheduled alarm.
     * @param triggerTime The exact epoch timestamp (ms) scheduled in AlarmManager.
     */
    data class Armed(
        val alarmId: Long,
        val triggerTime: Long
    ) : ScheduleOutcome

    /**
     * Scheduling was blocked by system permissions or exact-alarm capabilities.
     * @param alarmId The ID of the alarm.
     * @param reason Failure code (e.g. "EXACT_ALARM_PERMISSION_REVOKED", "PAUSED").
     */
    data class PermissionBlocked(
        val alarmId: Long,
        val reason: String
    ) : ScheduleOutcome

    /**
     * AlarmManager registration failed due to an OS exception or transient error.
     * @param alarmId The ID of the alarm.
     * @param cause Throwable or description of the registration failure.
     */
    data class RegistrationFailed(
        val alarmId: Long,
        val cause: Throwable? = null
    ) : ScheduleOutcome

    /**
     * Intentional no-arm (e.g. alarm is disabled, paused, vacation/holiday skipped, or no future trigger time).
     * @param alarmId The ID of the alarm.
     * @param reason Reason code (e.g. "DISABLED", "PAUSED", "NO_NEXT_OCCURRENCE", "HOLIDAY_SKIPPED").
     */
    data class NoArm(
        val alarmId: Long,
        val reason: String
    ) : ScheduleOutcome

    /**
     * Batch rescheduling result containing successes and failures.
     * @param armedCount Number of alarms successfully armed.
     * @param blockedCount Number of alarms blocked by permissions/capabilities.
     * @param failedCount Number of alarms with registration failures.
     * @param noArmCount Number of alarms intentionally not armed.
     * @param outcomes Map of alarmId to its individual ScheduleOutcome.
     */
    data class BatchResult(
        val armedCount: Int,
        val blockedCount: Int,
        val failedCount: Int,
        val noArmCount: Int,
        val outcomes: Map<Long, ScheduleOutcome>
    ) : ScheduleOutcome {
        val totalProcessed: Int get() = outcomes.size
        val isFullSuccess: Boolean get() = failedCount == 0 && blockedCount == 0
    }

    val isArmed: Boolean get() = this is Armed
}
