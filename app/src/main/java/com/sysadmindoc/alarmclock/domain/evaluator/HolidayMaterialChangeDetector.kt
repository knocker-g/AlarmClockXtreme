package com.sysadmindoc.alarmclock.domain.evaluator

import com.sysadmindoc.alarmclock.data.local.entity.ScheduleException
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.model.AlarmOccurrenceId
import com.sysadmindoc.alarmclock.data.repository.HolidayClassificationKind
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * Pure, deterministic material Holiday truth change and reconcile scope contract
 * (KNO-159 P1 Section 10 / KNO-31).
 *
 * Responsibilities:
 * - Detect effective Holiday classification and occurrence-eligibility changes across the
 *   canonical bounded horizon ([ScheduleOccurrenceEvaluator.MAX_CANDIDATE_SEARCH_STEPS]),
 *   aligned to both [evaluationZoneId] and any fixed-timezone repeating alarms under evaluation.
 * - Filter out non-material events: cache reloads, `fetchedAtMillis` updates, freshness
 *   transitions (`STALE` <-> `FRESH`) that preserve effective classification, provider
 *   generation increments with identical Holiday truth, and repeated equivalent notifications.
 * - Determine exact affected repeating Normal Alarms (`EXCLUDE` / `INCLUDE` only; `FOLLOW_BASE`,
 *   `ONE_TIME`, Quick, invalid, and disabled alarms are excluded), affected `LocalDate`s, stale
 *   occurrence invalidation targets, and bounded projection reconcile candidates without
 *   invoking Room writes, WorkManager, or OS `AlarmManager`.
 */
enum class HolidayTruthChangeOutcome {
    /** Effective Holiday truth is unchanged across the bounded horizon. */
    NO_MATERIAL_CHANGE,

    /** Current effective Holiday truth matches an already-observed/dispatched signature. */
    IDEMPOTENT_ALREADY_OBSERVED,

    /** Effective Holiday truth changed; downstream bounded reconcile may be required. */
    MATERIAL_CHANGE_DETECTED
}

/** Inclusive calendar-date range inspected during a bounded Holiday evaluation. */
data class HolidayEvaluationDateRange(
    val startDate: LocalDate,
    val endDateInclusive: LocalDate
) {
    init {
        require(!endDateInclusive.isBefore(startDate)) {
            "endDateInclusive ($endDateInclusive) must not be before startDate ($startDate)"
        }
    }

    val dayCount: Int
        get() = (ChronoUnit.DAYS.between(startDate, endDateInclusive) + 1L).toInt()
}

/** Per-date effective Holiday classification transition within a bounded window. */
data class HolidayDateClassificationDelta(
    val date: LocalDate,
    val previousKind: HolidayClassificationKind,
    val currentKind: HolidayClassificationKind
) {
    /**
     * True when the date crossed the confirmed `HOLIDAY` boundary
     * (`WORKDAY <-> HOLIDAY` or `UNKNOWN <-> HOLIDAY`), which is the exact set of transitions
     * capable of altering `EXCLUDE` suppression or `INCLUDE` synthesis under KNO-31.
     */
    val isHolidayBoundaryChange: Boolean
        get() = (previousKind == HolidayClassificationKind.HOLIDAY) !=
                (currentKind == HolidayClassificationKind.HOLIDAY)
}

/**
 * Deterministic fingerprint of effective Holiday truth across a bounded date window.
 *
 * Excludes provider `generation`, `fetchedAtMillis`, [com.sysadmindoc.alarmclock.data.repository.HolidayProviderFreshness],
 * [com.sysadmindoc.alarmclock.data.repository.HolidayClassificationSource], and unknown reason metadata so
 * non-material refreshes and repeated notifications produce identical tokens.
 */
data class HolidayTruthSignature(
    val startDate: LocalDate,
    val horizonDays: Int,
    val sha256Hex: String
)

/** Input bundle for evaluating how a Holiday truth transition affects one [Alarm]. */
data class AlarmHolidayEvaluationInput(
    val alarm: Alarm,
    val holidayPolicyOverride: HolidayPolicy? = null,
    val exceptions: List<ScheduleException> = emptyList(),
    val consumedOccurrenceKeys: Set<String> = emptySet(),
    val allowHidePersonalRuntimeFeatures: Boolean = false
)

/** Bounded reconcile impact for a single Holiday-policy-eligible repeating Normal Alarm. */
data class AffectedAlarmHolidayDelta(
    val alarmId: Long,
    val generation: Int,
    val effectivePolicy: HolidayPolicy,
    val eligibilityChangedDates: Set<LocalDate>,
    val previousEvaluation: EvaluatedOccurrence?,
    val currentEvaluation: EvaluatedOccurrence?,
    val nextTargetChanged: Boolean,
    val staleOccurrenceIdToInvalidate: AlarmOccurrenceId?,
    val storedProjectionNeedsReconcile: Boolean
)

/** Result of evaluating a Holiday truth transition across a bounded horizon and alarm set. */
data class HolidayMaterialChangeDecision(
    val outcome: HolidayTruthChangeOutcome,
    val previousSignature: HolidayTruthSignature,
    val currentSignature: HolidayTruthSignature,
    val classificationChangedDates: List<HolidayDateClassificationDelta>,
    val holidayBoundaryChangedDates: Set<LocalDate>,
    val affectedAlarms: List<AffectedAlarmHolidayDelta>,
    val decisionReason: String
) {
    val hasMaterialHolidayChange: Boolean
        get() = outcome == HolidayTruthChangeOutcome.MATERIAL_CHANGE_DETECTED

    /**
     * True only when effective Holiday truth materially changed AND at least one eligible
     * repeating Normal Alarm in the evaluated set is impacted.
     */
    val shouldDispatchReconcile: Boolean
        get() = outcome == HolidayTruthChangeOutcome.MATERIAL_CHANGE_DETECTED &&
                affectedAlarms.any {
                    it.nextTargetChanged ||
                            it.storedProjectionNeedsReconcile ||
                            it.eligibilityChangedDates.isNotEmpty()
                }
}

class HolidayMaterialChangeDetector(
    private val evaluator: ScheduleOccurrenceEvaluator = ScheduleOccurrenceEvaluator()
) {
    companion object {
        const val REASON_NO_EFFECTIVE_CHANGE = "NO_EFFECTIVE_HOLIDAY_TRUTH_CHANGE"
        const val REASON_ALREADY_OBSERVED = "EQUIVALENT_HOLIDAY_TRUTH_ALREADY_OBSERVED"
        const val REASON_MATERIAL_TRUTH_CHANGED = "MATERIAL_HOLIDAY_TRUTH_CHANGED"

        /**
         * Resolves the exact inclusive `[startDate, endDateInclusive]` date range covering both
         * [evaluationZoneId] and every repeating Normal Alarm's `schedulingZone(evaluationZoneId)`
         * across [horizonDays], so fixed-timezone alarms at UTC day boundaries are never missed.
         */
        fun resolveEvaluationDateRange(
            evaluationEpochMs: Long,
            evaluationZoneId: ZoneId,
            alarms: List<Alarm> = emptyList(),
            horizonDays: Int = ScheduleOccurrenceEvaluator.MAX_CANDIDATE_SEARCH_STEPS
        ): HolidayEvaluationDateRange {
            val boundedDays = horizonDays.coerceIn(1, ScheduleOccurrenceEvaluator.MAX_CANDIDATE_SEARCH_STEPS)
            val evalInstant = Instant.ofEpochMilli(evaluationEpochMs)
            val zoneStart = evalInstant.atZone(evaluationZoneId).toLocalDate()
            var minStart = zoneStart
            var maxEnd = zoneStart.plusDays(boundedDays - 1L)

            for (alarm in alarms) {
                if (!alarm.isEnabled || alarm.isQuickAlarm || alarm.scheduleKind != Alarm.SCHEDULE_KIND_REPEATING) {
                    continue
                }
                val alarmStart = evalInstant.atZone(alarm.schedulingZone(evaluationZoneId)).toLocalDate()
                val alarmEnd = alarmStart.plusDays(boundedDays - 1L)
                if (alarmStart.isBefore(minStart)) minStart = alarmStart
                if (alarmEnd.isAfter(maxEnd)) maxEnd = alarmEnd
            }

            return HolidayEvaluationDateRange(
                startDate = minStart,
                endDateInclusive = maxEnd
            )
        }
    }

    /**
     * Computes a canonical [HolidayTruthSignature] for `[startDate, startDate + horizonDays - 1]`
     * based on effective confirmed-Holiday membership per date (`H` = `HOLIDAY`, `N` = `WORKDAY`/`UNKNOWN`)
     * as well as raw classification kind when [includeUnknownVsWorkday] is enabled.
     *
     * Default `includeUnknownVsWorkday = false` hashes exact occurrence-eligibility truth
     * (`HOLIDAY` vs non-`HOLIDAY`) because under KNO-31 Section 5 both `WORKDAY` and `UNKNOWN`
     * produce identical occurrence eligibility across `FOLLOW_BASE`, `EXCLUDE`, and `INCLUDE`.
     */
    fun computeSignature(
        classifier: LocalHolidayClassifier,
        startDate: LocalDate,
        horizonDays: Int = ScheduleOccurrenceEvaluator.MAX_CANDIDATE_SEARCH_STEPS,
        includeUnknownVsWorkday: Boolean = false
    ): HolidayTruthSignature {
        val boundedDays = horizonDays.coerceIn(1, MAX_HOLIDAY_EVALUATION_WINDOW_DAYS)
        val payload = buildString(boundedDays * 2 + 32) {
            append(startDate).append('|').append(boundedDays).append('|')
            for (offset in 0 until boundedDays) {
                val date = startDate.plusDays(offset.toLong())
                val kind = classifier.classify(date).kind
                val code = if (includeUnknownVsWorkday) {
                    when (kind) {
                        HolidayClassificationKind.HOLIDAY -> 'H'
                        HolidayClassificationKind.WORKDAY -> 'W'
                        HolidayClassificationKind.UNKNOWN -> 'U'
                    }
                } else {
                    if (kind == HolidayClassificationKind.HOLIDAY) 'H' else 'N'
                }
                append(code)
            }
        }
        val hex = MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return HolidayTruthSignature(
            startDate = startDate,
            horizonDays = boundedDays,
            sha256Hex = hex
        )
    }

    /**
     * Evaluates whether transitioning from [previousClassifier] to [currentClassifier] at
     * [evaluationEpochMs] represents a material Holiday truth change and computes the exact
     * bounded set of affected repeating Normal Alarms.
     *
     * Pass [lastObservedSignature] (the `currentSignature` returned by the most recently
     * processed/dispatched transition for the same window) to guarantee idempotency across
     * duplicate notifications or repeated Worker runs.
     */
    fun detectMaterialChange(
        previousClassifier: LocalHolidayClassifier,
        currentClassifier: LocalHolidayClassifier,
        evaluationEpochMs: Long,
        evaluationZoneId: ZoneId,
        alarms: List<AlarmHolidayEvaluationInput> = emptyList(),
        lastObservedSignature: HolidayTruthSignature? = null,
        horizonDays: Int = ScheduleOccurrenceEvaluator.MAX_CANDIDATE_SEARCH_STEPS
    ): HolidayMaterialChangeDecision {
        val boundedDays = horizonDays.coerceIn(1, ScheduleOccurrenceEvaluator.MAX_CANDIDATE_SEARCH_STEPS)
        val range = resolveEvaluationDateRange(
            evaluationEpochMs = evaluationEpochMs,
            evaluationZoneId = evaluationZoneId,
            alarms = alarms.map { it.alarm },
            horizonDays = boundedDays
        )
        val spanDays = range.dayCount.coerceIn(1, MAX_HOLIDAY_EVALUATION_WINDOW_DAYS)

        val previousSig = computeSignature(previousClassifier, range.startDate, spanDays)
        val currentSig = computeSignature(currentClassifier, range.startDate, spanDays)

        val deltas = ArrayList<HolidayDateClassificationDelta>()
        val boundaryDates = LinkedHashSet<LocalDate>()
        for (offset in 0 until spanDays) {
            val date = range.startDate.plusDays(offset.toLong())
            val prevKind = previousClassifier.classify(date).kind
            val currKind = currentClassifier.classify(date).kind
            if (prevKind != currKind) {
                val delta = HolidayDateClassificationDelta(date, prevKind, currKind)
                deltas.add(delta)
                if (delta.isHolidayBoundaryChange) {
                    boundaryDates.add(date)
                }
            }
        }

        // Idempotency guard: already observed/dispatched exact effective Holiday truth for this window
        if (lastObservedSignature != null &&
            lastObservedSignature.startDate == currentSig.startDate &&
            lastObservedSignature.horizonDays == currentSig.horizonDays &&
            lastObservedSignature.sha256Hex == currentSig.sha256Hex
        ) {
            return HolidayMaterialChangeDecision(
                outcome = HolidayTruthChangeOutcome.IDEMPOTENT_ALREADY_OBSERVED,
                previousSignature = previousSig,
                currentSignature = currentSig,
                classificationChangedDates = deltas,
                holidayBoundaryChangedDates = boundaryDates,
                affectedAlarms = emptyList(),
                decisionReason = REASON_ALREADY_OBSERVED
            )
        }

        val affectedAlarms = evaluateAffectedAlarms(
            alarms = alarms,
            previousClassifier = previousClassifier,
            currentClassifier = currentClassifier,
            evaluationEpochMs = evaluationEpochMs,
            evaluationZoneId = evaluationZoneId,
            boundedDays = boundedDays
        )

        val hasWindowBoundaryDelta = boundaryDates.isNotEmpty()
        val hasAlarmDelta = affectedAlarms.isNotEmpty()

        if (!hasWindowBoundaryDelta && !hasAlarmDelta) {
            return HolidayMaterialChangeDecision(
                outcome = HolidayTruthChangeOutcome.NO_MATERIAL_CHANGE,
                previousSignature = previousSig,
                currentSignature = currentSig,
                classificationChangedDates = deltas,
                holidayBoundaryChangedDates = emptySet(),
                affectedAlarms = emptyList(),
                decisionReason = REASON_NO_EFFECTIVE_CHANGE
            )
        }

        return HolidayMaterialChangeDecision(
            outcome = HolidayTruthChangeOutcome.MATERIAL_CHANGE_DETECTED,
            previousSignature = previousSig,
            currentSignature = currentSig,
            classificationChangedDates = deltas,
            holidayBoundaryChangedDates = boundaryDates,
            affectedAlarms = affectedAlarms,
            decisionReason = REASON_MATERIAL_TRUTH_CHANGED
        )
    }

    private fun evaluateAffectedAlarms(
        alarms: List<AlarmHolidayEvaluationInput>,
        previousClassifier: LocalHolidayClassifier,
        currentClassifier: LocalHolidayClassifier,
        evaluationEpochMs: Long,
        evaluationZoneId: ZoneId,
        boundedDays: Int
    ): List<AffectedAlarmHolidayDelta> {
        if (alarms.isEmpty()) return emptyList()
        val results = ArrayList<AffectedAlarmHolidayDelta>()

        for (input in alarms) {
            val alarm = input.alarm
            if (!alarm.isEnabled || alarm.isQuickAlarm || alarm.scheduleKind != Alarm.SCHEDULE_KIND_REPEATING) {
                continue
            }
            val effectivePolicy = ScheduleOccurrenceEvaluator.resolveEffectiveHolidayPolicy(
                alarm = alarm,
                holidayPolicyOverride = input.holidayPolicyOverride
            )
            // FOLLOW_BASE ignores Holiday truth completely
            if (effectivePolicy == HolidayPolicy.FOLLOW_BASE) {
                continue
            }

            val previousEval = evaluator.evaluateWithHolidayClassifier(
                alarm = alarm,
                fromEpochMs = evaluationEpochMs,
                holidayClassifier = previousClassifier,
                exceptions = input.exceptions,
                holidayPolicyOverride = input.holidayPolicyOverride,
                consumedOccurrenceKeys = input.consumedOccurrenceKeys,
                evaluationZoneId = evaluationZoneId,
                allowHidePersonalRuntimeFeatures = input.allowHidePersonalRuntimeFeatures
            )
            val currentEval = evaluator.evaluateWithHolidayClassifier(
                alarm = alarm,
                fromEpochMs = evaluationEpochMs,
                holidayClassifier = currentClassifier,
                exceptions = input.exceptions,
                holidayPolicyOverride = input.holidayPolicyOverride,
                consumedOccurrenceKeys = input.consumedOccurrenceKeys,
                evaluationZoneId = evaluationZoneId,
                allowHidePersonalRuntimeFeatures = input.allowHidePersonalRuntimeFeatures
            )

            // Invalid/quarantined schedule definitions are unaffected by Holiday truth transitions
            if (previousEval?.decision == OccurrenceDecision.INVALID_SCHEDULE_NEEDS_REPAIR &&
                currentEval?.decision == OccurrenceDecision.INVALID_SCHEDULE_NEEDS_REPAIR
            ) {
                continue
            }

            val alarmZone = alarm.schedulingZone(evaluationZoneId)
            val alarmStartDate = Instant.ofEpochMilli(evaluationEpochMs).atZone(alarmZone).toLocalDate()
            val eligibilityChangedDates = LinkedHashSet<LocalDate>()

            for (offset in 0 until boundedDays) {
                val date = alarmStartDate.plusDays(offset.toLong())
                val prevHoliday = previousClassifier.classify(date).kind == HolidayClassificationKind.HOLIDAY
                val currHoliday = currentClassifier.classify(date).kind == HolidayClassificationKind.HOLIDAY
                if (prevHoliday == currHoliday) continue

                if (offset == 0 && !isSameDayCandidateStillReachable(
                        alarm = alarm,
                        date = date,
                        alarmZone = alarmZone,
                        evaluationEpochMs = evaluationEpochMs,
                        exceptions = input.exceptions,
                        consumedOccurrenceKeys = input.consumedOccurrenceKeys
                    )
                ) {
                    continue
                }

                val isBase = ScheduleOccurrenceEvaluator.isBaseScheduleDate(alarm, date)
                val prevEligible = isDateCandidateUnderPolicy(effectivePolicy, isBase, prevHoliday)
                val currEligible = isDateCandidateUnderPolicy(effectivePolicy, isBase, currHoliday)
                if (prevEligible != currEligible) {
                    eligibilityChangedDates.add(date)
                }
            }

            val prevTarget = previousEval?.targetTriggerTimeEpochMs ?: 0L
            val currTarget = currentEval?.targetTriggerTimeEpochMs ?: 0L
            val nextTargetChanged = previousEval?.occurrenceId != currentEval?.occurrenceId ||
                    prevTarget != currTarget ||
                    previousEval?.decision != currentEval?.decision

            if (eligibilityChangedDates.isEmpty() && !nextTargetChanged) {
                continue
            }

            val staleOccurrenceId = previousEval
                ?.takeIf {
                    it.decision == OccurrenceDecision.NORMAL_TRIGGER ||
                            it.decision == OccurrenceDecision.TIME_OVERRIDDEN
                }
                ?.occurrenceId
                ?.takeIf { prevId ->
                    prevId != currentEval?.occurrenceId ||
                            prevTarget != currTarget ||
                            previousEval.decision != currentEval?.decision
                }

            val expectedStoredTarget = if (
                currentEval != null &&
                (currentEval.decision == OccurrenceDecision.NORMAL_TRIGGER ||
                        currentEval.decision == OccurrenceDecision.TIME_OVERRIDDEN)
            ) {
                currentEval.targetTriggerTimeEpochMs
            } else {
                0L
            }
            val storedProjectionNeedsReconcile = alarm.nextTriggerTime != expectedStoredTarget

            results.add(
                AffectedAlarmHolidayDelta(
                    alarmId = alarm.id,
                    generation = alarm.generation,
                    effectivePolicy = effectivePolicy,
                    eligibilityChangedDates = eligibilityChangedDates,
                    previousEvaluation = previousEval,
                    currentEvaluation = currentEval,
                    nextTargetChanged = nextTargetChanged,
                    staleOccurrenceIdToInvalidate = staleOccurrenceId,
                    storedProjectionNeedsReconcile = storedProjectionNeedsReconcile
                )
            )
        }

        return results
    }

    private fun isSameDayCandidateStillReachable(
        alarm: Alarm,
        date: LocalDate,
        alarmZone: ZoneId,
        evaluationEpochMs: Long,
        exceptions: List<ScheduleException>,
        consumedOccurrenceKeys: Set<String>
    ): Boolean {
        val baseEpochMs = resolveZoned(date, alarm.time, alarmZone).toInstant().toEpochMilli()
        val occurrenceKey = AlarmOccurrenceId.fromAlarm(alarm, baseEpochMs).key
        if (occurrenceKey in consumedOccurrenceKeys) return false
        val matchingException = exceptions.firstOrNull {
            it.alarmId == alarm.id &&
                    it.generation == alarm.generation &&
                    it.baseTriggerTime == baseEpochMs
        }
        if (matchingException != null && matchingException.exceptionType == ScheduleException.TYPE_TIME_OVERRIDE) {
            val overrideTime = matchingException.overrideTriggerTime
            val overrideLocalDate = Instant.ofEpochMilli(overrideTime).atZone(alarmZone).toLocalDate()
            if (overrideLocalDate == date) {
                return overrideTime > evaluationEpochMs
            }
        }
        return baseEpochMs > evaluationEpochMs
    }

    private fun resolveZoned(date: LocalDate, time: LocalTime, zone: ZoneId): ZonedDateTime {
        val local = LocalDateTime.of(date, time)
        val transition = zone.rules.getTransition(local)
        return if (transition != null && transition.isGap) {
            transition.instant.atZone(zone)
        } else {
            ZonedDateTime.of(date, time, zone)
        }
    }

    private fun isDateCandidateUnderPolicy(
        policy: HolidayPolicy,
        isBaseDay: Boolean,
        isConfirmedHoliday: Boolean
    ): Boolean = when (policy) {
        HolidayPolicy.FOLLOW_BASE -> isBaseDay
        HolidayPolicy.EXCLUDE -> isBaseDay && !isConfirmedHoliday
        HolidayPolicy.INCLUDE -> isBaseDay || isConfirmedHoliday
    }
}
