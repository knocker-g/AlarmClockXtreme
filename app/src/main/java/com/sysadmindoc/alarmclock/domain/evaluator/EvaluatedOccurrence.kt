package com.sysadmindoc.alarmclock.domain.evaluator

import com.sysadmindoc.alarmclock.data.model.AlarmOccurrenceId

/**
 * Classification decision for a candidate occurrence evaluation (KNO-31 / KNO-153).
 */
enum class OccurrenceDecision {
    /** Regular base schedule occurrence trigger */
    NORMAL_TRIGGER,

    /** Occurrence skipped by explicit user ScheduleException (SKIP) */
    SKIPPED_BY_EXCEPTION,

    /** Occurrence skipped by EXCLUDE holiday policy on a confirmed holiday */
    SKIPPED_BY_HOLIDAY,

    /** Occurrence time overridden by explicit user ScheduleException (TIME_OVERRIDE) */
    TIME_OVERRIDDEN,

    /** Occurrence already consumed or handoff-delivered */
    ALREADY_CONSUMED,

    /** ScheduleException has an unrecognized, cancelled, or unsupported exceptionType */
    UNSUPPORTED_EXCEPTION_TYPE,

    /** Alarm model has an invalid shiftPattern key or unparseable startDate requiring user repair */
    INVALID_SCHEDULE_NEEDS_REPAIR,

    /** Valid alarm, but no active current candidate (e.g. expired ONE_TIME date or holiday INCLUDE with UNKNOWN status) */
    VALID_NO_CURRENT_OCCURRENCE
}

/**
 * Pure value model representing the evaluated decision for an alarm occurrence candidate.
 */
data class EvaluatedOccurrence(
    val occurrenceId: AlarmOccurrenceId?,
    val alarmId: Long,
    val baseTriggerTimeEpochMs: Long,
    val targetTriggerTimeEpochMs: Long,
    val decision: OccurrenceDecision,
    val generation: Int,
    val revision: Int,
    val isExceptionApplied: Boolean = false,
    val appliedExceptionType: String? = null,
    val holidayState: HolidayState = HolidayState.NOT_HOLIDAY,
    val holidayPolicy: HolidayPolicy = HolidayPolicy.FOLLOW_BASE
)
