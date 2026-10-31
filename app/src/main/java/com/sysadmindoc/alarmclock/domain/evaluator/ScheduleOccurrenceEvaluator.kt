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
    companion object {
        const val MAX_CANDIDATE_SEARCH_STEPS = 100

        /**
         * Resolves the effective [HolidayPolicy] for [alarm].
         *
         * Per KNO-31 / KNO-159 Section 5:
         * - Holiday policy applies ONLY to eligible repeating Normal Alarms (`SCHEDULE_KIND_REPEATING`).
         * - Normal `ONE_TIME` and Quick alarms do not apply Holiday policy.
         */
        fun resolveEffectiveHolidayPolicy(
            alarm: Alarm,
            holidayPolicyOverride: HolidayPolicy? = null
        ): HolidayPolicy {
            if (alarm.isQuickAlarm || alarm.scheduleKind != Alarm.SCHEDULE_KIND_REPEATING) {
                return HolidayPolicy.FOLLOW_BASE
            }
            return holidayPolicyOverride ?: alarm.canonicalHolidayPolicy()
        }

        /**
         * Canonical Base schedule date predicate shared with [HolidayMaterialChangeDetector]
         * so Base-vs-Holiday eligibility stays 1:1 aligned across evaluation and delta detection.
         */
        fun isBaseScheduleDate(alarm: Alarm, date: LocalDate): Boolean {
            val matchesRepeat = alarm.repeatDays.isEmpty() || alarm.repeatDays.contains(date.dayOfWeek)
            val matchesShift = if (alarm.shiftPattern.isNotBlank()) {
                val shiftStart = runCatching { LocalDate.parse(alarm.shiftPatternStartDate) }.getOrNull()
                val pattern = ShiftPattern.fromKey(alarm.shiftPattern)
                shiftStart != null && pattern != null && !date.isBefore(shiftStart) && pattern.isWorkDate(shiftStart, date)
            } else {
                true
            }
            val matchesSpecificDate = if (alarm.specificDate.isNotBlank()) {
                runCatching { LocalDate.parse(alarm.specificDate) }.getOrNull() == date
            } else {
                false
            }
            val matchesRecurringPattern = when {
                alarm.repeatDays.isNotEmpty() && alarm.shiftPattern.isNotBlank() -> matchesRepeat && matchesShift
                alarm.repeatDays.isNotEmpty() -> matchesRepeat
                alarm.shiftPattern.isNotBlank() -> matchesShift
                else -> false
            }
            return matchesSpecificDate || matchesRecurringPattern
        }
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
     * Evaluates the next active candidate occurrence using KNO-158's typed local
     * [LocalHolidayClassifier] (`HOLIDAY | WORKDAY | UNKNOWN`).
     *
     * Pure local evaluation; never performs inline network I/O and never consults
     * legacy `HolidayRepository.isHoliday(): Boolean`.
     */
    fun evaluateWithHolidayClassifier(
        alarm: Alarm,
        fromEpochMs: Long,
        holidayClassifier: LocalHolidayClassifier,
        exceptions: List<ScheduleException> = emptyList(),
        holidayPolicyOverride: HolidayPolicy? = null,
        consumedOccurrenceKeys: Set<String> = emptySet(),
        evaluationZoneId: ZoneId = ZoneId.systemDefault(),
        allowHidePersonalRuntimeFeatures: Boolean = false
    ): EvaluatedOccurrence? = evaluateOccurrence(
        alarm = alarm,
        fromEpochMs = fromEpochMs,
        exceptions = exceptions,
        holidaySnapshotProvider = holidayClassifier.asHolidaySnapshotProvider(),
        holidayPolicyOverride = holidayPolicyOverride,
        consumedOccurrenceKeys = consumedOccurrenceKeys,
        evaluationZoneId = evaluationZoneId,
        allowHidePersonalRuntimeFeatures = allowHidePersonalRuntimeFeatures
    )

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

        // Resolve Effective Holiday Policy (applies only to repeating Normal Alarms)
        val policy = resolveEffectiveHolidayPolicy(alarm, holidayPolicyOverride)

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
            val usesHideFeatures = alarm.shiftPattern.isNotBlank() ||
                    alarm.timezonePolicy == Alarm.TIMEZONE_POLICY_FIXED ||
                    alarm.solarOffsetMinutes != 0 ||
                    alarm.smartAlarmEnabled ||
                    alarm.weatherEarlyMinutes != 0 ||
                    (isExplicitRepeating && alarm.specificDate.isNotBlank())

            if (usesHideFeatures) {
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

        // 3. ONE_TIME Alarm Evaluation
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

        // 4. Candidate Search Window Initialization (Search from 00:00 on evaluation date)
        val searchStartDate = fromZdt.toLocalDate()
        var currentDate = searchStartDate
        var steps = 0
        var firstValidNoCandidateResult: EvaluatedOccurrence? = null

        while (steps < MAX_CANDIDATE_SEARCH_STEPS) {
            steps++

            val holidaySnapshot = holidaySnapshotProvider(currentDate)

            val isBaseDay = isBaseScheduleDate(alarm, currentDate)

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
                if (firstValidNoCandidateResult == null) {
                    firstValidNoCandidateResult = EvaluatedOccurrence(
                        occurrenceId = null,
                        alarmId = alarm.id,
                        baseTriggerTimeEpochMs = 0L,
                        targetTriggerTimeEpochMs = 0L,
                        decision = OccurrenceDecision.VALID_NO_CURRENT_OCCURRENCE,
                        generation = alarm.generation,
                        revision = alarm.revision,
                        holidayState = HolidayState.IS_HOLIDAY,
                        holidayPolicy = policy
                    )
                }
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
