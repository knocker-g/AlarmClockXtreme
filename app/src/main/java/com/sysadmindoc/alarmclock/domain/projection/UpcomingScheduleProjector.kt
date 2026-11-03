package com.sysadmindoc.alarmclock.domain.projection

import com.sysadmindoc.alarmclock.data.model.AlarmOccurrenceId
import com.sysadmindoc.alarmclock.data.local.AlarmDao
import com.sysadmindoc.alarmclock.data.local.ScheduleAuthorityDao
import com.sysadmindoc.alarmclock.data.local.ScheduleExceptionDao
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleException
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.domain.evaluator.HolidayPolicy
import com.sysadmindoc.alarmclock.domain.evaluator.HolidaySnapshot
import com.sysadmindoc.alarmclock.domain.evaluator.HolidayState
import com.sysadmindoc.alarmclock.domain.evaluator.LocalHolidayClassifier
import com.sysadmindoc.alarmclock.domain.evaluator.ScheduleOccurrenceEvaluator
import com.sysadmindoc.alarmclock.domain.evaluator.asHolidaySnapshotProvider
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UpcomingScheduleProjector @Inject constructor() {
    private var customEvaluator: ScheduleOccurrenceEvaluator? = null

    internal constructor(evaluator: ScheduleOccurrenceEvaluator) : this() {
        this.customEvaluator = evaluator
    }

    private val evaluator: ScheduleOccurrenceEvaluator
        get() = customEvaluator ?: ScheduleOccurrenceEvaluator()

    companion object {
        const val DEFAULT_HORIZON_DAYS = 14
        const val MAX_HORIZON_DAYS = 365

        fun resolveZonedDateTime(date: LocalDate, time: LocalTime, zone: ZoneId): ZonedDateTime {
            val local = LocalDateTime.of(date, time)
            val transition = zone.rules.getTransition(local)
            return if (transition != null && transition.isGap) {
                transition.instant.atZone(zone)
            } else {
                ZonedDateTime.of(date, time, zone)
            }
        }
    }

    fun projectUpcomingSchedule(
        alarms: List<Alarm>,
        nowEpochMs: Long,
        horizonDays: Int = DEFAULT_HORIZON_DAYS,
        evaluationZoneId: ZoneId = ZoneId.systemDefault(),
        exceptions: List<ScheduleException> = emptyList(),
        authorities: List<ScheduleAuthority> = emptyList(),
        holidayClassifier: LocalHolidayClassifier = LocalHolidayClassifier.ALL_UNKNOWN,
        holidaySnapshotProvider: ((LocalDate) -> HolidaySnapshot)? = null,
        activeFiringAlarmIds: Set<Long> = emptySet(),
        consumedOccurrenceKeys: Set<String> = emptySet(),
        reconcilingAlarmIds: Set<Long> = emptySet(),
        isGlobalReconciling: Boolean = false,
        allowHidePersonalRuntimeFeatures: Boolean = false
    ): UpcomingScheduleProjection {
        val effectiveSnapshotProvider = holidaySnapshotProvider ?: holidayClassifier.asHolidaySnapshotProvider()
        val fromZdt = Instant.ofEpochMilli(nowEpochMs).atZone(evaluationZoneId)
        val searchStartDate = fromZdt.toLocalDate()
        val boundedHorizonDays = horizonDays.coerceIn(1, MAX_HORIZON_DAYS)
        val endDateInclusive = searchStartDate.plusDays(boundedHorizonDays - 1L)

        val disabledAlarmIds = mutableListOf<Long>()
        val noCurrentOccurrenceAlarms = mutableListOf<Long>()
        val allOccurrences = mutableListOf<UpcomingOccurrenceOutcome>()
        var hasReconcilingAlarm = isGlobalReconciling
        var hasStaleAlarm = false
        var hasNeedsRepairAlarm = false

        for (alarm in alarms) {
            if (alarm.isQuickAlarm || alarm.kind == Alarm.KIND_QUICK) continue
            if (!alarm.isEnabled) {
                disabledAlarmIds.add(alarm.id)
                continue
            }

            val schedulingZone = alarm.schedulingZone(evaluationZoneId)
            val matchingAuthority = authorities.firstOrNull { it.alarmId == alarm.id }

            val authorityQuarantine = matchingAuthority?.reconcileState == "QUARANTINE"
            val authorityReconciling = !authorityQuarantine && (
                matchingAuthority?.reconcileState == "RECONCILING" ||
                alarm.id in reconcilingAlarmIds ||
                isGlobalReconciling
            )

            if (authorityReconciling) hasReconcilingAlarm = true
            else if (authorityQuarantine) hasStaleAlarm = true

            val isExplicitOneTime = alarm.scheduleKind == Alarm.SCHEDULE_KIND_ONE_TIME
            val isExplicitRepeating = alarm.scheduleKind == Alarm.SCHEDULE_KIND_REPEATING
            val policy = ScheduleOccurrenceEvaluator.resolveEffectiveHolidayPolicy(alarm)

            if (!isExplicitOneTime && !isExplicitRepeating) {
                hasNeedsRepairAlarm = true
                continue
            }

            if (!allowHidePersonalRuntimeFeatures) {
                val usesHideFeatures = alarm.shiftPattern.isNotBlank() ||
                    alarm.timezonePolicy == Alarm.TIMEZONE_POLICY_FIXED ||
                    alarm.solarOffsetMinutes != 0 ||
                    alarm.smartAlarmEnabled ||
                    alarm.weatherEarlyMinutes != 0 ||
                    (isExplicitRepeating && alarm.specificDate.isNotBlank())
                if (usesHideFeatures) {
                    hasNeedsRepairAlarm = true
                    continue
                }
            }

            if (isExplicitOneTime) {
                if (alarm.specificDate.isBlank()) {
                    hasNeedsRepairAlarm = true
                    continue
                }
                val parsedDate = runCatching { LocalDate.parse(alarm.specificDate) }.getOrNull()
                if (parsedDate == null) {
                    hasNeedsRepairAlarm = true
                    continue
                }
                val triggerZdt = resolveZonedDateTime(parsedDate, alarm.time, schedulingZone)
                val triggerEpochMs = triggerZdt.toInstant().toEpochMilli()

                if (triggerEpochMs <= nowEpochMs) {
                    noCurrentOccurrenceAlarms.add(alarm.id)
                    continue
                }

                val occurrenceId = AlarmOccurrenceId.fromAlarm(alarm, triggerEpochMs)
                if (occurrenceId.key in consumedOccurrenceKeys) {
                    noCurrentOccurrenceAlarms.add(alarm.id)
                    continue
                }

                if (!parsedDate.isAfter(endDateInclusive)) {
                    val isCachedStale = alarm.nextTriggerTime > 0 && alarm.nextTriggerTime != triggerEpochMs && !authorityReconciling
                    if (isCachedStale) {
                        hasStaleAlarm = true
                    }

                    val freshness = when {
                        authorityReconciling -> UpcomingProjectionFreshness.RECONCILING
                        authorityQuarantine || isCachedStale -> UpcomingProjectionFreshness.STALE
                        else -> UpcomingProjectionFreshness.CURRENT
                    }

                    allOccurrences.add(
                        UpcomingOccurrenceOutcome(
                            occurrenceId = occurrenceId,
                            alarmId = alarm.id,
                            alarmLabel = alarm.label,
                            generation = alarm.generation,
                            revision = alarm.revision,
                            scheduledInstant = triggerZdt.toInstant(),
                            targetTriggerTimeEpochMs = triggerEpochMs,
                            baseTriggerTimeEpochMs = triggerEpochMs,
                            localDate = triggerZdt.toLocalDate(),
                            localDisplayTime = triggerZdt.toLocalTime(),
                            reason = UpcomingOccurrenceReason.BASE_SCHEDULE,
                            willFire = true,
                            isNextForAlarm = true,
                            isOneTimeOverrideEligible = false,
                            projectionFreshness = freshness,
                            holidayPolicy = HolidayPolicy.FOLLOW_BASE,
                            holidayState = HolidayState.NOT_HOLIDAY
                    )
                    )
                }
                continue
            }

            if (isExplicitRepeating) {
                if (alarm.repeatDays.isEmpty() && alarm.shiftPattern.isBlank() && policy != HolidayPolicy.INCLUDE) {
                    hasNeedsRepairAlarm = true
                    continue
                }

                val alarmOccurrences = mutableListOf<UpcomingOccurrenceOutcome>()
                var currentDate = searchStartDate

                while (!currentDate.isAfter(endDateInclusive)) {
                    val holidaySnapshot = effectiveSnapshotProvider(currentDate)
                    val isBaseDay = ScheduleOccurrenceEvaluator.isBaseScheduleDate(alarm, currentDate)
                    val isHolidayIncludeDay = (policy == HolidayPolicy.INCLUDE && holidaySnapshot.state == HolidayState.IS_HOLIDAY)


                    if (!isBaseDay && !isHolidayIncludeDay) {
                        currentDate = currentDate.plusDays(1)
                        continue
                    }

                    val baseZdt = resolveZonedDateTime(currentDate, alarm.time, schedulingZone)
                    val baseEpochMs = baseZdt.toInstant().toEpochMilli()
                    val occurrenceId = AlarmOccurrenceId.fromAlarm(alarm, baseEpochMs)

                    if (occurrenceId.key in consumedOccurrenceKeys) {
                        currentDate = currentDate.plusDays(1)
                        continue
                    }

                    if (policy == HolidayPolicy.EXCLUDE && holidaySnapshot.state == HolidayState.IS_HOLIDAY) {
                        alarmOccurrences.add(
                            UpcomingOccurrenceOutcome(
                                occurrenceId = occurrenceId,
                                alarmId = alarm.id,
                                alarmLabel = alarm.label,
                                generation = alarm.generation,
                                revision = alarm.revision,
                                scheduledInstant = baseZdt.toInstant(),
                                targetTriggerTimeEpochMs = baseEpochMs,
                                baseTriggerTimeEpochMs = baseEpochMs,
                                localDate = currentDate,
                                localDisplayTime = baseZdt.toLocalTime(),
                                reason = UpcomingOccurrenceReason.SKIPPED_BY_HOLIDAY,
                                willFire = false,
                                isNextForAlarm = false,
                                isOneTimeOverrideEligible = false,
                                projectionFreshness = if (authorityReconciling) UpcomingProjectionFreshness.RECONCILING else if (authorityQuarantine) UpcomingProjectionFreshness.STALE else UpcomingProjectionFreshness.CURRENT,
                                holidayPolicy = policy,
                                holidayState = holidaySnapshot.state
                        )
                        )
                        currentDate = currentDate.plusDays(1)
                        continue
                    }

                    // Layer 2: Exceptions
                    val matchingException = exceptions.firstOrNull {
                        it.alarmId == alarm.id &&
                            it.generation == alarm.generation &&
                            it.baseTriggerTime == baseEpochMs
                    }

                    if (matchingException != null) {
                        when (matchingException.exceptionType) {
                            ScheduleException.TYPE_SKIP -> {
                                alarmOccurrences.add(
                                    UpcomingOccurrenceOutcome(
                                        occurrenceId = occurrenceId,
                                        alarmId = alarm.id,
                                        alarmLabel = alarm.label,
                                        generation = alarm.generation,
                                        revision = alarm.revision,
                                        scheduledInstant = baseZdt.toInstant(),
                                        targetTriggerTimeEpochMs = baseEpochMs,
                                        baseTriggerTimeEpochMs = baseEpochMs,
                                        localDate = currentDate,
                                        localDisplayTime = baseZdt.toLocalTime(),
                                        reason = UpcomingOccurrenceReason.SKIP_ONCE,
                                        willFire = false,
                                        isNextForAlarm = false,
                                        isOneTimeOverrideEligible = false,
                                        projectionFreshness = if (authorityReconciling) UpcomingProjectionFreshness.RECONCILING else if (authorityQuarantine) UpcomingProjectionFreshness.STALE else UpcomingProjectionFreshness.CURRENT,
                                        holidayPolicy = policy,
                                        holidayState = holidaySnapshot.state
                                   )
                                )
                                currentDate = currentDate.plusDays(1)
                                continue
                            }
                            ScheduleException.TYPE_TIME_OVERRIDE -> {
                                val overrideTime = matchingException.overrideTriggerTime
                                val isFuture = overrideTime > nowEpochMs
                                val overrideZdt = Instant.ofEpochMilli(overrideTime).atZone(schedulingZone)
                                alarmOccurrences.add(
                                    UpcomingOccurrenceOutcome(
                                        occurrenceId = occurrenceId,
                                        alarmId = alarm.id,
                                        alarmLabel = alarm.label,
                                        generation = alarm.generation,
                                        revision = alarm.revision,
                                        scheduledInstant = overrideZdt.toInstant(),
                                        targetTriggerTimeEpochMs = overrideTime,
                                        baseTriggerTimeEpochMs = baseEpochMs,
                                        localDate = overrideZdt.toLocalDate(),
                                        localDisplayTime = overrideZdt.toLocalTime(),
                                        reason = UpcomingOccurrenceReason.CHANGE_TIME_ONCE,
                                        willFire = isFuture,
                                        isNextForAlarm = false,
                                        isOneTimeOverrideEligible = false,
                                        projectionFreshness = if (authorityReconciling) UpcomingProjectionFreshness.RECONCILING else if (authorityQuarantine) UpcomingProjectionFreshness.STALE else UpcomingProjectionFreshness.CURRENT,
                                        holidayPolicy = policy,
                                        holidayState = holidaySnapshot.state
                                   )
                                )
                                currentDate = currentDate.plusDays(1)
                                continue
                            }
                            else -> {
                                alarmOccurrences.add(
                                    UpcomingOccurrenceOutcome(
                                        occurrenceId = occurrenceId,
                                        alarmId = alarm.id,
                                        alarmLabel = alarm.label,
                                        generation = alarm.generation,
                                        revision = alarm.revision,
                                        scheduledInstant = baseZdt.toInstant(),
                                        targetTriggerTimeEpochMs = baseEpochMs,
                                        baseTriggerTimeEpochMs = baseEpochMs,
                                        localDate = currentDate,
                                        localDisplayTime = baseZdt.toLocalTime(),
                                        reason = UpcomingOccurrenceReason.UNSUPPORTED_EXCEPTION,
                                        willFire = false,
                                        isNextForAlarm = false,
                                        isOneTimeOverrideEligible = false,
                                        projectionFreshness = UpcomingProjectionFreshness.NEEDS_REPAIR,
                                        holidayPolicy = policy,
                                        holidayState = holidaySnapshot.state
                                   )
                                )
                                hasNeedsRepairAlarm = true
                                currentDate = currentDate.plusDays(1)
                                continue
                            }
                        }
                    }

                    // Layer 3: Base Schedule / Holiday Include
                    val isFuture = baseEpochMs > nowEpochMs
                    if (!isFuture) {
                        currentDate = currentDate.plusDays(1)
                        continue
                    }

                    val reason = if (isHolidayIncludeDay && !isBaseDay) {
                        UpcomingOccurrenceReason.HOLIDAY_INCLUDE
                    } else {
                        UpcomingOccurrenceReason.BASE_SCHEDULE
                    }

                    alarmOccurrences.add(
                        UpcomingOccurrenceOutcome(
                            occurrenceId = occurrenceId,
                            alarmId = alarm.id,
                            alarmLabel = alarm.label,
                            generation = alarm.generation,
                            revision = alarm.revision,
                            scheduledInstant = baseZdt.toInstant(),
                            targetTriggerTimeEpochMs = baseEpochMs,
                            baseTriggerTimeEpochMs = baseEpochMs,
                            localDate = currentDate,
                            localDisplayTime = baseZdt.toLocalTime(),
                            reason = reason,
                            willFire = true,
                            isNextForAlarm = false,
                            isOneTimeOverrideEligible = false,
                            projectionFreshness = if (authorityReconciling) UpcomingProjectionFreshness.RECONCILING else if (authorityQuarantine) UpcomingProjectionFreshness.STALE else UpcomingProjectionFreshness.CURRENT,
                            holidayPolicy = policy,
                            holidayState = holidaySnapshot.state
                        )
                    )

                    currentDate = currentDate.plusDays(1)
                }

                val firingOccurrences = alarmOccurrences
                    .filter { it.willFire && it.targetTriggerTimeEpochMs > nowEpochMs }
                    .sortedBy { it.targetTriggerTimeEpochMs }


                val nextFiring = firingOccurrences.firstOrNull()
                if (nextFiring != null) {
                    val isCachedStale = alarm.nextTriggerTime > 0 && alarm.nextTriggerTime != nextFiring.targetTriggerTimeEpochMs && !authorityReconciling
                    if (isCachedStale) {
                        hasStaleAlarm = true
                    }

                    val updatedOccurrences = alarmOccurrences.map { occ ->
                        if (occ.occurrenceId == nextFiring.occurrenceId && occ.targetTriggerTimeEpochMs == nextFiring.targetTriggerTimeEpochMs) {
                            occ.copy(
                                isNextForAlarm = true,
                                isOneTimeOverrideEligible = true,
                                projectionFreshness = if (isCachedStale) UpcomingProjectionFreshness.STALE else occ.projectionFreshness
                            )
                        } else {
                            occ
                        }
                    }
                    allOccurrences.addAll(updatedOccurrences)
                } else {
                    noCurrentOccurrenceAlarms.add(alarm.id)
                    allOccurrences.addAll(alarmOccurrences)
                }
            }
        }

        allOccurrences.sort()

        val nextNormal = allOccurrences.firstOrNull {
            it.willFire &&
                it.targetTriggerTimeEpochMs > nowEpochMs &&
                it.alarmId !in activeFiringAlarmIds
        }

        val dayGroups = allOccurrences.groupBy { it.localDate }
            .map { (date, entries) ->
                UpcomingDayGroup(date = date, entries = entries.sorted())
            }
            .sortedBy { it.date }

        val compositeFreshness = when {
            hasReconcilingAlarm -> UpcomingProjectionFreshness.RECONCILING
            hasStaleAlarm -> UpcomingProjectionFreshness.STALE
            hasNeedsRepairAlarm -> UpcomingProjectionFreshness.NEEDS_REPAIR
            allOccurrences.isEmpty() && noCurrentOccurrenceAlarms.isNotEmpty() -> UpcomingProjectionFreshness.NO_CURRENT
            else -> UpcomingProjectionFreshness.CURRENT
        }

        return UpcomingScheduleProjection(
            nextNormalOccurrence = nextNormal,
            dayGroups = dayGroups,
            allOccurrences = allOccurrences,
            noCurrentOccurrenceAlarms = noCurrentOccurrenceAlarms,
            disabledAlarms = disabledAlarmIds,
            evaluationEpochMs = nowEpochMs,
            evaluationZoneId = evaluationZoneId,
            projectionFreshness = compositeFreshness
        )
    }

    suspend fun projectFromDaos(
        alarmDao: AlarmDao,
        scheduleExceptionDao: ScheduleExceptionDao,
        scheduleAuthorityDao: ScheduleAuthorityDao,
        holidayClassifier: LocalHolidayClassifier = LocalHolidayClassifier.ALL_UNKNOWN,
        nowEpochMs: Long,
        horizonDays: Int = DEFAULT_HORIZON_DAYS,
        evaluationZoneId: ZoneId = ZoneId.systemDefault(),
        activeFiringAlarmIds: Set<Long> = emptySet(),
        consumedOccurrenceKeys: Set<String> = emptySet(),
        reconcilingAlarmIds: Set<Long> = emptySet(),
        isGlobalReconciling: Boolean = false,
        allowHidePersonalRuntimeFeatures: Boolean = false
    ): UpcomingScheduleProjection {
        val alarms = alarmDao.getAllNormalAlarms()
        val exceptions = scheduleExceptionDao.getAll()
        val authorities = scheduleAuthorityDao.getAllAuthorities()
        return projectUpcomingSchedule(
            alarms = alarms,
            nowEpochMs = nowEpochMs,
            horizonDays = horizonDays,
            evaluationZoneId = evaluationZoneId,
            exceptions = exceptions,
            authorities = authorities,
            holidayClassifier = holidayClassifier,
            activeFiringAlarmIds = activeFiringAlarmIds,
            consumedOccurrenceKeys = consumedOccurrenceKeys,
            reconcilingAlarmIds = reconcilingAlarmIds,
            isGlobalReconciling = isGlobalReconciling,
            allowHidePersonalRuntimeFeatures = allowHidePersonalRuntimeFeatures
        )
    }
}
