package com.sysadmindoc.alarmclock.domain.evaluator

import com.sysadmindoc.alarmclock.data.local.entity.ScheduleException
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.model.AlarmOccurrenceId
import com.sysadmindoc.alarmclock.data.model.ShiftPattern
import com.sysadmindoc.alarmclock.domain.NextAlarmCalculator
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Pure, side-effect-free Schedule Candidate Occurrence Resolver for Normal Alarms (KNO-30 / KNO-31 / KNO-32 / KNO-76 / KNO-153).
 * Evaluates candidate occurrences, exception overrides, and holiday policies without mutating state.
 */
class ScheduleOccurrenceEvaluator(
    private val calculator: NextAlarmCalculator = NextAlarmCalculator()
) {
    private companion object {
        const val MAX_CANDIDATE_SEARCH_STEPS = 100
    }

    /**
     * Resolves ZonedDateTime with canonical DST Gap handling (resolves gap to first valid instant).
     */
    private fun resolveZoned(date: LocalDate, time: LocalTime, zone: ZoneId): ZonedDateTime {
        val local = LocalDateTime.of(date, time)
        val transition = zone.rules.getTransition(local)
        return if (transition != null && transition.isGap) {
            transition.instant.atZone(zone)
        } else {
            ZonedDateTime.of(date, time, zone)
        }
    }

    /**
     * Evaluates the next active candidate occurrence for a given Normal [alarm] evaluated from [fromEpochMs].
     * Returns null for disabled alarms or Quick alarms.
     */
    fun evaluateOccurrence(
        alarm: Alarm,
        fromEpochMs: Long,
        exceptions: List<ScheduleException> = emptyList(),
        holidaySnapshotProvider: (LocalDate) -> HolidaySnapshot = { HolidaySnapshot() },
        holidayPolicyOverride: HolidayPolicy? = null,
        consumedOccurrenceKeys: Set<String> = emptySet(),
        evaluationZoneId: ZoneId = ZoneId.systemDefault(),
        allowHidePersonalRuntimeFeatures: Boolean = false
    ): EvaluatedOccurrence? {
        // 1. Guard against disabled or Quick alarms (Quick alarms are handled by QuickAlarmScheduler)
        if (!alarm.isEnabled || alarm.isQuickAlarm) {
            return null
        }

        // 2. Validate Definition Authority (Alarm.scheduleKind as sole authority)
        val isExplicitOneTime = alarm.scheduleKind == Alarm.SCHEDULE_KIND_ONE_TIME
        val isExplicitRepeating = alarm.scheduleKind == Alarm.SCHEDULE_KIND_REPEATING

        // Resolve Effective Holiday Policy
        val policy = holidayPolicyOverride ?: if (alarm.skipOnHolidays) {
            HolidayPolicy.EXCLUDE
        } else {
            HolidayPolicy.FOLLOW_BASE
        }

        if (!isExplicitOneTime && !isExplicitRepeating) {
            return EvaluatedOccurrence(
                occurrenceId = null,
                alarmId = alarm.id,
                baseTriggerTimeEpochMs = 0L,
                targetTriggerTimeEpochMs = 0L,
                decision = OccurrenceDecision.INVALID_SCHEDULE_NEEDS_REPAIR,
                generation = alarm.generation,
                revision = alarm.revision,
                holidayPolicy = policy
            )
        }

        // KNO-76 Personal Runtime Boundary Check
        if (!allowHidePersonalRuntimeFeatures) {
            if (alarm.shiftPattern.isNotBlank() || alarm.timezonePolicy == Alarm.TIMEZONE_POLICY_FIXED) {
                return EvaluatedOccurrence(
                    occurrenceId = null,
                    alarmId = alarm.id,
                    baseTriggerTimeEpochMs = 0L,
                    targetTriggerTimeEpochMs = 0L,
                    decision = OccurrenceDecision.INVALID_SCHEDULE_NEEDS_REPAIR,
                    generation = alarm.generation,
                    revision = alarm.revision,
                    holidayPolicy = policy
                )
            }
        }

        // Validate Shift Pattern contract
        if (alarm.shiftPattern.isNotBlank()) {
            val validKey = ShiftPattern.fromKey(alarm.shiftPattern) != null
            val validDate = alarm.shiftPatternStartDate.isNotBlank() &&
                    runCatching { LocalDate.parse(alarm.shiftPatternStartDate) }.isSuccess

            if (!validKey || !validDate) {
                return EvaluatedOccurrence(
                    occurrenceId = null,
                    alarmId = alarm.id,
                    baseTriggerTimeEpochMs = 0L,
                    targetTriggerTimeEpochMs = 0L,
                    decision = OccurrenceDecision.INVALID_SCHEDULE_NEEDS_REPAIR,
                    generation = alarm.generation,
                    revision = alarm.revision,
                    holidayPolicy = policy
                )
            }
        }

        // Validate REPEATING schedule definition completeness
        if (isExplicitRepeating && alarm.repeatDays.isEmpty() && alarm.shiftPattern.isBlank() && policy != HolidayPolicy.INCLUDE) {
            return EvaluatedOccurrence(
                occurrenceId = null,
                alarmId = alarm.id,
                baseTriggerTimeEpochMs = 0L,
                targetTriggerTimeEpochMs = 0L,
                decision = OccurrenceDecision.INVALID_SCHEDULE_NEEDS_REPAIR,
                generation = alarm.generation,
                revision = alarm.revision,
                holidayPolicy = policy
            )
        }

        val schedulingZone = alarm.schedulingZone(evaluationZoneId)
        val fromZdt = Instant.ofEpochMilli(fromEpochMs).atZone(schedulingZone)

        // 3. Mixed Schedule Check: Future specificDate on REPEATING takes one-off priority
        if (isExplicitRepeating && alarm.specificDate.isNotBlank()) {
            val parsedSpecificDate = runCatching { LocalDate.parse(alarm.specificDate) }.getOrNull()
            if (parsedSpecificDate == null) {
                return EvaluatedOccurrence(
                    occurrenceId = null,
                    alarmId = alarm.id,
                    baseTriggerTimeEpochMs = 0L,
                    targetTriggerTimeEpochMs = 0L,
                    decision = OccurrenceDecision.INVALID_SCHEDULE_NEEDS_REPAIR,
                    generation = alarm.generation,
                    revision = alarm.revision,
                    holidayPolicy = policy
                )
            }
            val specificDateTime = resolveZoned(parsedSpecificDate, alarm.time, schedulingZone)
            val specificEpochMs = specificDateTime.toInstant().toEpochMilli()

            if (specificEpochMs > fromEpochMs) {
                val occurrenceId = AlarmOccurrenceId.fromAlarm(alarm, specificEpochMs)
                if (occurrenceId.key in consumedOccurrenceKeys) {
                    return EvaluatedOccurrence(
                        occurrenceId = occurrenceId,
                        alarmId = alarm.id,
                        baseTriggerTimeEpochMs = specificEpochMs,
                        targetTriggerTimeEpochMs = specificEpochMs,
                        decision = OccurrenceDecision.ALREADY_CONSUMED,
                        generation = alarm.generation,
                        revision = alarm.revision,
                        holidayPolicy = policy
                    )
                }
                return EvaluatedOccurrence(
                    occurrenceId = occurrenceId,
                    alarmId = alarm.id,
                    baseTriggerTimeEpochMs = specificEpochMs,
                    targetTriggerTimeEpochMs = specificEpochMs,
                    decision = OccurrenceDecision.NORMAL_TRIGGER,
                    generation = alarm.generation,
                    revision = alarm.revision,
                    holidayPolicy = policy
                )
            }
            // If specificDate <= fromEpochMs, the one-off override has expired. Fall through to REPEATING schedule loop below!
        }

        // 4. ONE_TIME Alarm Evaluation
        if (isExplicitOneTime) {
            if (alarm.specificDate.isBlank()) {
                return EvaluatedOccurrence(
                    occurrenceId = null,
                    alarmId = alarm.id,
                    baseTriggerTimeEpochMs = 0L,
                    targetTriggerTimeEpochMs = 0L,
                    decision = OccurrenceDecision.INVALID_SCHEDULE_NEEDS_REPAIR,
                    generation = alarm.generation,
                    revision = alarm.revision,
                    holidayPolicy = policy
                )
            }
            val parsedSpecificDate = runCatching { LocalDate.parse(alarm.specificDate) }.getOrNull()
            if (parsedSpecificDate == null) {
                return EvaluatedOccurrence(
                    occurrenceId = null,
                    alarmId = alarm.id,
                    baseTriggerTimeEpochMs = 0L,
                    targetTriggerTimeEpochMs = 0L,
                    decision = OccurrenceDecision.INVALID_SCHEDULE_NEEDS_REPAIR,
                    generation = alarm.generation,
                    revision = alarm.revision,
                    holidayPolicy = policy
                )
            }
            val specificDateTime = resolveZoned(parsedSpecificDate, alarm.time, schedulingZone)
            val specificEpochMs = specificDateTime.toInstant().toEpochMilli()

            if (specificEpochMs <= fromEpochMs) {
                return EvaluatedOccurrence(
                    occurrenceId = null,
                    alarmId = alarm.id,
                    baseTriggerTimeEpochMs = 0L,
                    targetTriggerTimeEpochMs = 0L,
                    decision = OccurrenceDecision.VALID_NO_CURRENT_OCCURRENCE,
                    generation = alarm.generation,
                    revision = alarm.revision,
                    holidayPolicy = policy
                )
            }

            val occurrenceId = AlarmOccurrenceId.fromAlarm(alarm, specificEpochMs)
            if (occurrenceId.key in consumedOccurrenceKeys) {
                return EvaluatedOccurrence(
                    occurrenceId = occurrenceId,
                    alarmId = alarm.id,
                    baseTriggerTimeEpochMs = specificEpochMs,
                    targetTriggerTimeEpochMs = specificEpochMs,
                    decision = OccurrenceDecision.ALREADY_CONSUMED,
                    generation = alarm.generation,
                    revision = alarm.revision,
                    holidayPolicy = policy
                )
            }

            return EvaluatedOccurrence(
                occurrenceId = occurrenceId,
                alarmId = alarm.id,
                baseTriggerTimeEpochMs = specificEpochMs,
                targetTriggerTimeEpochMs = specificEpochMs,
                decision = OccurrenceDecision.NORMAL_TRIGGER,
                generation = alarm.generation,
                revision = alarm.revision,
                holidayPolicy = policy
            )
        }

        // 5. Candidate Search Window Initialization (Search from 00:00 on evaluation date to catch same-day Time Overrides and Holiday candidates)
        val searchStartDate = fromZdt.toLocalDate()
        var currentDate = searchStartDate
        var steps = 0
        var firstValidNoCandidateResult: EvaluatedOccurrence? = null

        while (steps < MAX_CANDIDATE_SEARCH_STEPS) {
            steps++

            val holidaySnapshot = holidaySnapshotProvider(currentDate)

            // Determine if currentDate is a Base schedule day (AND evaluation when both repeatDays and shiftPattern are present)
            val matchesRepeat = alarm.repeatDays.isEmpty() || alarm.repeatDays.contains(currentDate.dayOfWeek)
            val matchesShift = if (alarm.shiftPattern.isNotBlank()) {
                val shiftStart = LocalDate.parse(alarm.shiftPatternStartDate)
                val pattern = ShiftPattern.fromKey(alarm.shiftPattern)
                pattern != null && !currentDate.isBefore(shiftStart) && pattern.isWorkDate(shiftStart, currentDate)
            } else true

            val isBaseDay = if (alarm.repeatDays.isNotEmpty() && alarm.shiftPattern.isNotBlank()) {
                matchesRepeat && matchesShift
            } else if (alarm.repeatDays.isNotEmpty()) {
                matchesRepeat
            } else if (alarm.shiftPattern.isNotBlank()) {
                matchesShift
            } else false

            val isHolidayIncludeDay = policy == HolidayPolicy.INCLUDE && holidaySnapshot.state == HolidayState.IS_HOLIDAY

            if (!isBaseDay && !isHolidayIncludeDay) {
                if (policy == HolidayPolicy.INCLUDE && holidaySnapshot.state == HolidayState.UNKNOWN && alarm.repeatDays.isEmpty() && alarm.shiftPattern.isBlank()) {
                    if (firstValidNoCandidateResult == null) {
                        firstValidNoCandidateResult = EvaluatedOccurrence(
                            occurrenceId = null,
                            alarmId = alarm.id,
                            baseTriggerTimeEpochMs = 0L,
                            targetTriggerTimeEpochMs = 0L,
                            decision = OccurrenceDecision.VALID_NO_CURRENT_OCCURRENCE,
                            generation = alarm.generation,
                            revision = alarm.revision,
                            holidayState = HolidayState.UNKNOWN,
                            holidayPolicy = policy
                        )
                    }
                }
                currentDate = currentDate.plusDays(1)
                continue
            }

            // Candidate date found: resolve base trigger ZonedDateTime on currentDate
            val baseDateTime = resolveZoned(currentDate, alarm.time, schedulingZone)
            val baseTriggerTime = baseDateTime.toInstant().toEpochMilli()
            val occurrenceId = AlarmOccurrenceId.fromAlarm(alarm, baseTriggerTime)

            // Pipeline Layer 1: Holiday Exclusion
            if (policy == HolidayPolicy.EXCLUDE && holidaySnapshot.state == HolidayState.IS_HOLIDAY) {
                currentDate = currentDate.plusDays(1)
                continue
            }

            // Pipeline Layer 2: User Exception Evaluation (Scoped strictly to alarmId + generation + baseTriggerTime)
            val matchingException = exceptions.firstOrNull {
                it.alarmId == alarm.id &&
                        it.generation == alarm.generation &&
                        it.baseTriggerTime == baseTriggerTime
            }

            if (matchingException != null) {
                when (matchingException.exceptionType) {
                    ScheduleException.TYPE_SKIP -> {
                        // Occurrence on currentDate is skipped by user -> advance to next date
                        currentDate = currentDate.plusDays(1)
                        continue
                    }
                    ScheduleException.TYPE_TIME_OVERRIDE -> {
                        val overrideTime = matchingException.overrideTriggerTime
                        val isFuture = overrideTime > fromEpochMs
                        val overrideZdt = Instant.ofEpochMilli(overrideTime).atZone(schedulingZone)
                        val isSameLocalDate = currentDate == overrideZdt.toLocalDate()

                        if (isSameLocalDate) {
                            if (isFuture) {
                                if (occurrenceId.key in consumedOccurrenceKeys) {
                                    currentDate = currentDate.plusDays(1)
                                    continue
                                }
                                return EvaluatedOccurrence(
                                    occurrenceId = occurrenceId,
                                    alarmId = alarm.id,
                                    baseTriggerTimeEpochMs = baseTriggerTime,
                                    targetTriggerTimeEpochMs = overrideTime,
                                    decision = OccurrenceDecision.TIME_OVERRIDDEN,
                                    generation = alarm.generation,
                                    revision = alarm.revision,
                                    isExceptionApplied = true,
                                    appliedExceptionType = ScheduleException.TYPE_TIME_OVERRIDE,
                                    holidayState = holidaySnapshot.state,
                                    holidayPolicy = policy
                                )
                            } else {
                                // Time override was on currentDate but has EXPIRED (in past) -> occurrence on currentDate has expired! Advance to next date!
                                currentDate = currentDate.plusDays(1)
                                continue
                            }
                        }
                    }
                    else -> {
                        // Unrecognized exception type or TYPE_CANCEL -> return UNSUPPORTED_EXCEPTION_TYPE (prevents silent trigger)
                        return EvaluatedOccurrence(
                            occurrenceId = occurrenceId,
                            alarmId = alarm.id,
                            baseTriggerTimeEpochMs = baseTriggerTime,
                            targetTriggerTimeEpochMs = baseTriggerTime,
                            decision = OccurrenceDecision.UNSUPPORTED_EXCEPTION_TYPE,
                            generation = alarm.generation,
                            revision = alarm.revision,
                            isExceptionApplied = true,
                            appliedExceptionType = matchingException.exceptionType,
                            holidayState = holidaySnapshot.state,
                            holidayPolicy = policy
                        )
                    }
                }
            }

            // Pipeline Layer 3: Base Trigger Time Check
            if (baseTriggerTime > fromEpochMs) {
                if (occurrenceId.key in consumedOccurrenceKeys) {
                    currentDate = currentDate.plusDays(1)
                    continue
                }
                return EvaluatedOccurrence(
                    occurrenceId = occurrenceId,
                    alarmId = alarm.id,
                    baseTriggerTimeEpochMs = baseTriggerTime,
                    targetTriggerTimeEpochMs = baseTriggerTime,
                    decision = OccurrenceDecision.NORMAL_TRIGGER,
                    generation = alarm.generation,
                    revision = alarm.revision,
                    holidayState = holidaySnapshot.state,
                    holidayPolicy = policy
                )
            }

            // Base trigger time on currentDate was in past (and had no active future override) -> advance to next date
            currentDate = currentDate.plusDays(1)
        }

        return firstValidNoCandidateResult ?: EvaluatedOccurrence(
            occurrenceId = null,
            alarmId = alarm.id,
            baseTriggerTimeEpochMs = 0L,
            targetTriggerTimeEpochMs = 0L,
            decision = OccurrenceDecision.VALID_NO_CURRENT_OCCURRENCE,
            generation = alarm.generation,
            revision = alarm.revision,
            holidayPolicy = policy
        )
    }
}
