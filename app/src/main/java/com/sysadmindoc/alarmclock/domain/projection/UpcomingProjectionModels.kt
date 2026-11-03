package com.sysadmindoc.alarmclock.domain.projection

import com.sysadmindoc.alarmclock.data.model.AlarmOccurrenceId
import com.sysadmindoc.alarmclock.domain.evaluator.HolidayPolicy
import com.sysadmindoc.alarmclock.domain.evaluator.HolidayState
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Freshness status of the upcoming schedule projection (KNO-155 / KNO-34).
 */
enum class UpcomingProjectionFreshness {
    /** Confidently current and settled with authority and database state. */
    CURRENT,

    /** Reconcile or state transition currently in progress (e.g. ScheduleAuthority reconcileState == RECONCILING). */
    RECONCILING,

    /** Stale or mismatched (e.g. cached nextTriggerTime in Alarm table disagrees with evaluated canonical target). */
    STALE,

    /** Alarm schedule definition is invalid or corrupt and requires user repair. */
    NEEDS_REPAIR,

    /** Valid schedule definition, but no future active candidate exists in search horizon. */
    NO_CURRENT,

    /** Alarm is explicitly disabled. */
    DISABLED
}

/**
 * Provenance reason for an occurrence outcome in the upcoming schedule (KNO-155 / KNO-34).
 */
enum class UpcomingOccurrenceReason {
    /** Regular base recurring or one-time schedule occurrence. */
    BASE_SCHEDULE,

    /** Occurrence added or fired due to HolidayPolicy.INCLUDE on a confirmed holiday. */
    HOLIDAY_INCLUDE,

    /** Occurrence scheduled time overridden once via ScheduleException (Change Time Once). */
    CHANGE_TIME_ONCE,

    /** Occurrence skipped once via ScheduleException (Skip Once). */
    SKIP_ONCE,

    /** Occurrence excluded on a confirmed holiday via HolidayPolicy.EXCLUDE. */
    SKIPPED_BY_HOLIDAY,

    /** Occurrence has an unrecognized, cancelled, or unsupported exception type. */
    UNSUPPORTED_EXCEPTION
}

/**
 * Canonical outcome model for an upcoming alarm occurrence (KNO-155 / KNO-34).
 *
 * Immutable, stable tuple:
 * `(occurrenceId, generation, scheduledInstant, localDisplayTime, reason, projectionFreshness)`
 *
- Implements [Comparable] for deterministic chronological ordering:
 * 1. [targetTriggerTimeEpochMs] ASC
 * 2. [alarmId] ASC
 * 3. [baseTriggerTimeEpochMs] ASC
 */
data class UpcomingOccurrenceOutcome(
    val occurrenceId: AlarmOccurrenceId?,
    val alarmId: Long,
    val alarmLabel: String,
    val generation: Int,
    val revision: Int,
    val scheduledInstant: Instant,
    val targetTriggerTimeEpochMs: Long,
    val baseTriggerTimeEpochMs: Long,
    val localDate: LocalDate,
    val localDisplayTime: LocalTime,
    val reason: UpcomingOccurrenceReason,
    val willFire: Boolean,
    val isNextForAlarm: Boolean,
    val isOneTimeOverrideEligible: Boolean,
    val projectionFreshness: UpcomingProjectionFreshness,
    val holidayPolicy: HolidayPolicy = HolidayPolicy.FOLLOW_BASE,
    val holidayState: HolidayState = HolidayState.NOT_HOLIDAY
) : Comparable<UpcomingOccurrenceOutcome> {

    override fun compareTo(other: UpcomingOccurrenceOutcome): Int {
        val timeComp = targetTriggerTimeEpochMs.compareTo(other.targetTriggerTimeEpochMs)
        if (timeComp != 0) return timeComp
        val alarmComp = alarmId.compareTo(other.alarmId)
        if (alarmComp != 0) return alarmComp
        return baseTriggerTimeEpochMs.compareTo(other.baseTriggerTimeEpochMs)
    }

    val isSkipped: Boolean get() = !willFire
    val isOverridden: Boolean get() = reason == UpcomingOccurrenceReason.CHANGE_TIME_ONCE
    val isHolidayDerived: Boolean get() = reason == UpcomingOccurrenceReason.HOLIDAY_INCLUDE || reason == UpcomingOccurrenceReason.SKIPPED_BY_HOLIDAY
}

/**
 * Occurrences grouped under a single local calendar date for timeline / list presentation (KNO-155 / KNO-34).
 */
data class UpcomingDayGroup(
    val date: LocalDate,
    val entries: List<UpcomingOccurrenceOutcome>
)

/**
 * Pure, reusable read projection over Phase 3 Schedule truth for Normal Alarms (KNO-155 / KNO-34 Slice A).
 *
 * Answers:
 * - [nextNormalOccurrence]: the earliest currently valid future Normal occurrence across all alarms
 * - [dayGroups]: date-grouped chronological timeline of upcoming occurrences
 * - [allOccurrences]: flat chronological list of upcoming occurrence outcomes
 * - [noCurrentOccurrenceAlarms]: list of enabled alarm IDs with valid definition but no future occurrence
 * - [disabledAlarms]: list of disabled alarm IDs
 * - [projectionFreshness]: overall composite freshness
 */
data class UpcomingScheduleProjection(
    val nextNormalOccurrence: UpcomingOccurrenceOutcome?,
    val dayGroups: List<UpcomingDayGroup>,
    val allOccurrences: List<UpcomingOccurrenceOutcome>,
    val noCurrentOccurrenceAlarms: List<Long>,
    val disabledAlarms: List<Long>,
    val evaluationEpochMs: Long,
    val evaluationZoneId: ZoneId,
    val projectionFreshness: UpcomingProjectionFreshness
)
/**
 * Invalidation source or reason triggering an upcoming schedule projection update (KNO-156 / KNO-34 Slice B).
 */
enum class InvalidationReason {
    /** Alarm definition was edited, created, or deleted. */
    ALARM_DEFINITION_CHANGED,

    /** Alarm was toggled enabled or disabled. */
    ALARM_TOGGLED,

    /** One-time override was created, changed, or cleared. */
    OVERRIDE_CHANGED,

    /** Holiday classification or calendar authority changed. */
    HOLIDAY_TRUTH_CHANGED,

    /** Clock changed (TIME_SET), timezone changed, or boot/process recovery. */
    SYSTEM_RECOVERY,

    /** Explicit on-demand or periodic refresh. */
    ON_DEMAND_REFRESH
}
