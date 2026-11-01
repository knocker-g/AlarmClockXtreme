package com.sysadmindoc.alarmclock.domain

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.preferences.isPaused
import com.sysadmindoc.alarmclock.data.local.entity.AlarmIncidentEvent
import com.sysadmindoc.alarmclock.data.repository.AlarmIncidentRepository
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.data.repository.HolidayRepository
import com.sysadmindoc.alarmclock.directboot.DirectBootAlarmCache
import com.sysadmindoc.alarmclock.receiver.AlarmReceiver
import com.sysadmindoc.alarmclock.service.BedtimeZenRuleManager
import com.sysadmindoc.alarmclock.service.SmartAlarmService
import com.sysadmindoc.alarmclock.service.AlarmRuntimeState
import com.sysadmindoc.alarmclock.widget.WidgetUpdater
import com.sysadmindoc.alarmclock.worker.FireWatchdogPolicy
import com.sysadmindoc.alarmclock.worker.FireWatchdogWorker
import com.sysadmindoc.alarmclock.worker.HueSunriseNotifications
import com.sysadmindoc.alarmclock.worker.HueSunriseWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.sysadmindoc.alarmclock.domain.evaluator.MAX_HOLIDAY_EVALUATION_WINDOW_DAYS
import com.sysadmindoc.alarmclock.domain.evaluator.OccurrenceDecision
import com.sysadmindoc.alarmclock.domain.evaluator.ScheduleOccurrenceEvaluator
import com.sysadmindoc.alarmclock.domain.evaluator.asLocalHolidayClassifier

@Singleton
class AlarmScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: AlarmRepository,
    private val calculator: NextAlarmCalculator,
    private val preferencesManager: com.sysadmindoc.alarmclock.data.preferences.PreferencesManager,
    private val holidayRepository: HolidayRepository,
    private val alarmIncidentRepository: AlarmIncidentRepository,
    private val weatherRepository: com.sysadmindoc.alarmclock.data.repository.WeatherRepository,
    private val scheduleAuthorityOwnerProvider: javax.inject.Provider<ScheduleAuthorityOwner>? = null,
    private val routeAuthority: ProductionSchedulingRouteAuthority = ProductionSchedulingRouteAuthority()
) {
    @androidx.annotation.VisibleForTesting
    internal var testAlarmManager: AlarmManager? = null

    private val alarmManager: AlarmManager
        get() = testAlarmManager ?: (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)

    @androidx.annotation.VisibleForTesting
    internal var testOccurrenceEvaluator: ScheduleOccurrenceEvaluator? = null

    private val occurrenceEvaluator: ScheduleOccurrenceEvaluator
        get() = testOccurrenceEvaluator ?: ScheduleOccurrenceEvaluator(calculator)

    private val mainSlotMutexMap = ConcurrentHashMap<Long, Mutex>()

    @androidx.annotation.VisibleForTesting
    internal var testOnPreOsRegistrationBarrier: (suspend (alarmId: Long, triggerTime: Long) -> Unit)? = null

    @androidx.annotation.VisibleForTesting
    internal var testOnImmediatePreOsBarrier: (suspend (alarmId: Long, triggerTime: Long) -> Unit)? = null

    companion object {
        const val EXTRA_ALARM_ID = "alarm_id"
        const val EXTRA_ALARM_FIRE_ID = "alarm_fire_id"
        const val EXTRA_SCHEDULED_AT = "scheduled_at"
        const val EXTRA_ISSUE_TOKEN = "issue_token"

        private const val REQUEST_CODE_ISSUED_OCCURRENCE = 80001

        private val SNOW_ICE_CODES = setOf(56, 57, 66, 67, 71, 73, 75, 77, 85, 86)
        fun isSnowOrIceCode(code: Int): Boolean = code in SNOW_ICE_CODES
    }

    /**
     * Schedule an alarm using setAlarmClock() for maximum reliability.
     * Checks vacation mode and holiday skip before scheduling.
     * Also starts SmartAlarmService window and enqueues HueSunriseWorker if enabled.
     *
     * @param notBeforeMillis Floor for the computed occurrence. Used after a
     * fire/dismiss/skip of a known occurrence so the recomputed trigger can
     * never land back on that same occurrence — a smart-wake early fire
     * dismissed at 06:45 for a 07:00 repeating alarm would otherwise
     * recompute "next" as today's still-future 07:00 and ring again.
     */
    suspend fun schedule(
        alarm: Alarm,
        requestWidgetUpdate: Boolean = true,
        notBeforeMillis: Long = 0L
    ): ScheduleOutcome {
        val sanitizedAlarm = alarm.sanitized()
        val originAuthority = repository.getAuthorityByAlarmId(sanitizedAlarm.id)
        val originRevision = originAuthority?.scheduleRevision
        val originInstanceToken = originAuthority?.instanceToken
        val originRestoreEpoch = originAuthority?.restoreEpoch
        val originDefinitionRevision = originAuthority?.definitionRevision

        if (routeAuthority.isOwnerRouteActive() && originAuthority == null) {
            Log.w("AlarmScheduler", "Aborting schedule() for alarm ${sanitizedAlarm.id}: Owner route active but authority record absent")
            return ScheduleOutcome.RegistrationFailed(sanitizedAlarm.id, IllegalStateException("Owner route active but authority record absent"))
        }

        if (!sanitizedAlarm.isEnabled) {
            cancelScheduledEntries(sanitizedAlarm.id, expectedRevision = originRevision)
            return ScheduleOutcome.NoArm(sanitizedAlarm.id, "DISABLED")
        }

        if (!canScheduleExactAlarms()) {
            cancelScheduledEntries(sanitizedAlarm.id, expectedRevision = originRevision)
            if (routeAuthority.isOwnerRouteActive()) {
                if (originRevision != null && originInstanceToken != null && originRestoreEpoch != null && originDefinitionRevision != null) {
                    repository.casUpdateNextTriggerInitialWithAuthority(sanitizedAlarm.id, 0, originRevision, originInstanceToken, originRestoreEpoch, originDefinitionRevision)
                } else {
                    Log.w("AlarmScheduler", "Aborting zeroing for alarm ${sanitizedAlarm.id}: Owner route active but origin authority fingerprint incomplete")
                }
            } else {
                repository.updateNextTrigger(sanitizedAlarm.id, 0)
            }
            requestWidgetUpdateIfNeeded(requestWidgetUpdate)
            return ScheduleOutcome.PermissionBlocked(sanitizedAlarm.id, "EXACT_ALARM_PERMISSION_REVOKED")
        }

        val currentSettings = preferencesManager.getCurrentSettings()
        if (currentSettings.isPaused()) {
            cancelScheduledEntries(sanitizedAlarm.id, expectedRevision = originRevision)
            if (routeAuthority.isOwnerRouteActive()) {
                if (originRevision != null && originInstanceToken != null && originRestoreEpoch != null && originDefinitionRevision != null) {
                    repository.casUpdateNextTriggerInitialWithAuthority(sanitizedAlarm.id, 0, originRevision, originInstanceToken, originRestoreEpoch, originDefinitionRevision)
                } else {
                    Log.w("AlarmScheduler", "Aborting zeroing for alarm ${sanitizedAlarm.id}: Owner route active but origin authority fingerprint incomplete")
                }
            } else {
                repository.updateNextTrigger(sanitizedAlarm.id, 0)
            }
            requestWidgetUpdateIfNeeded(requestWidgetUpdate)
            return ScheduleOutcome.NoArm(sanitizedAlarm.id, "PAUSED")
        }

        val isRepeatingNormalAlarm = !sanitizedAlarm.isQuickAlarm &&
            sanitizedAlarm.scheduleKind == Alarm.SCHEDULE_KIND_REPEATING

        var triggerTime = if (isRepeatingNormalAlarm) {
            val schedulingZone = sanitizedAlarm.schedulingZone(ZoneId.systemDefault())
            val notBefore = if (notBeforeMillis > System.currentTimeMillis()) notBeforeMillis else System.currentTimeMillis()
            val notBeforeDate = Instant.ofEpochMilli(notBefore).atZone(schedulingZone).toLocalDate()
            val authorityWindow = holidayRepository.captureAuthorityWindow(
                startDate = notBeforeDate,
                endDateInclusive = notBeforeDate.plusDays(MAX_HOLIDAY_EVALUATION_WINDOW_DAYS - 1L)
            )
            val classifier = authorityWindow.asLocalHolidayClassifier()
            val exceptions = try {
                repository.database.scheduleExceptionDao().getExceptionsForAlarmGeneration(
                    sanitizedAlarm.id,
                    sanitizedAlarm.generation
                )
            } catch (e: Exception) {
                Log.e("AlarmScheduler", "Failed to load schedule exceptions for alarm ${sanitizedAlarm.id}; aborting schedule to fail-closed", e)
                return ScheduleOutcome.RegistrationFailed(sanitizedAlarm.id, e)
            }
            val evaluated = occurrenceEvaluator.evaluateWithHolidayClassifier(
                alarm = sanitizedAlarm,
                fromEpochMs = notBefore,
                holidayClassifier = classifier,
                exceptions = exceptions,
                evaluationZoneId = schedulingZone,
                allowHidePersonalRuntimeFeatures = true
            )
            if (evaluated != null && (evaluated.decision == OccurrenceDecision.NORMAL_TRIGGER || evaluated.decision == OccurrenceDecision.TIME_OVERRIDDEN)) {
                evaluated.targetTriggerTimeEpochMs
            } else {
                0L
            }
        } else {
            if (notBeforeMillis > System.currentTimeMillis()) {
                calculator.calculate(
                    sanitizedAlarm,
                    Instant.ofEpochMilli(notBeforeMillis).atZone(ZoneId.systemDefault())
                )
            } else {
                calculator.calculate(sanitizedAlarm)
            }
        }
        if (triggerTime <= System.currentTimeMillis()) {
            cancelScheduledEntries(sanitizedAlarm.id, expectedRevision = originRevision)
            if (sanitizedAlarm.isRecurringSchedule) {
                if (routeAuthority.isOwnerRouteActive()) {
                    if (originRevision != null && originInstanceToken != null && originRestoreEpoch != null && originDefinitionRevision != null) {
                        repository.casUpdateNextTriggerInitialWithAuthority(sanitizedAlarm.id, 0, originRevision, originInstanceToken, originRestoreEpoch, originDefinitionRevision)
                    } else {
                        Log.w("AlarmScheduler", "Aborting zeroing for alarm ${sanitizedAlarm.id}: Owner route active but origin authority fingerprint incomplete")
                    }
                } else {
                    repository.updateNextTrigger(sanitizedAlarm.id, 0)
                }
            } else {
                if (routeAuthority.isOwnerRouteActive()) {
                    if (originRevision != null && originInstanceToken != null && originRestoreEpoch != null && originDefinitionRevision != null) {
                        repository.casSetEnabledWithStrictAuthority(sanitizedAlarm.id, enabled = false, nextTrigger = 0, originRevision, originInstanceToken, originRestoreEpoch, originDefinitionRevision)
                    } else {
                        Log.w("AlarmScheduler", "Aborting setEnabled(false) for alarm ${sanitizedAlarm.id}: Owner route active but origin authority fingerprint incomplete")
                    }
                } else {
                    repository.setEnabled(sanitizedAlarm.id, enabled = false, nextTrigger = 0)
                }
            }
            requestWidgetUpdateIfNeeded(requestWidgetUpdate)
            return ScheduleOutcome.NoArm(sanitizedAlarm.id, "NO_NEXT_OCCURRENCE")
        }
        val settings = currentSettings

        val vacationAdjustment = VacationAlarmPolicy.adjustTrigger(
            alarm = sanitizedAlarm,
            initialTriggerTime = triggerTime,
            settings = settings,
            calculateFrom = calculator::calculate
        )
        triggerTime = vacationAdjustment.triggerTime

        if (!isRepeatingNormalAlarm && sanitizedAlarm.skipOnHolidays && settings.holidayAutoSkipEnabled) {
            val holidayZone = sanitizedAlarm.schedulingZone(ZoneId.systemDefault())
            val triggerDate = Instant.ofEpochMilli(triggerTime)
                .atZone(holidayZone).toLocalDate()
            if (holidayRepository.isHoliday(triggerDate)) {
                cancelScheduledEntries(sanitizedAlarm.id)
                repository.updateNextTrigger(sanitizedAlarm.id, 0)
                requestWidgetUpdateIfNeeded(requestWidgetUpdate)
                return ScheduleOutcome.NoArm(sanitizedAlarm.id, "HOLIDAY_SKIPPED")
            }
        }

        if (sanitizedAlarm.weatherEarlyMinutes > 0 && triggerTime > 0) {
            val weatherSettings = preferencesManager.getCurrentSettings()
            val lat = weatherSettings.lastKnownLatitude
            val lng = weatherSettings.lastKnownLongitude
            val haveLocation = lat != 0.0 || lng != 0.0
            var weather = weatherRepository.getCachedWeather(
                latitude = lat.takeIf { haveLocation },
                longitude = lng.takeIf { haveLocation }
            )
            if (weather == null && haveLocation) {
                weather = weatherRepository.getWeather(lat, lng, weatherSettings.temperatureUnit)
                    .getOrNull()
                    ?.response
            }
            if (weather != null) {
                val triggerDate = java.time.Instant.ofEpochMilli(triggerTime)
                    .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                val dayIndex = weather.daily?.time?.indexOfFirst { it == triggerDate.toString() } ?: -1
                if (dayIndex >= 0) {
                    val code = weather.daily?.weatherCode?.getOrNull(dayIndex)
                    if (code != null && isSnowOrIceCode(code)) {
                        triggerTime = maxOf(
                            triggerTime - sanitizedAlarm.weatherEarlyMinutes * 60_000L,
                            System.currentTimeMillis() + 60_000L
                        )
                    }
                }
            }
        }

        return scheduleAt(
            sanitizedAlarm,
            triggerTime,
            requestWidgetUpdate = requestWidgetUpdate,
            expectedRevision = originRevision,
            expectedInstanceToken = originInstanceToken,
            expectedRestoreEpoch = originRestoreEpoch,
            expectedDefinitionRevision = originDefinitionRevision
        )
    }

    fun cancel(alarmId: Long) {
        val originAuthority = runBlocking { repository.getAuthorityByAlarmId(alarmId) }
        val originRevision = originAuthority?.scheduleRevision
        cancelScheduledEntries(alarmId, includeFollowUpWorkers = true, expectedRevision = originRevision)
        AlarmRuntimeState.clear(context, alarmId)
        WidgetUpdater.requestUpdate(context)
    }

    suspend fun scheduleSnooze(alarm: Alarm, customMinutes: Int? = null): ScheduleOutcome {
        val minutes = customMinutes ?: alarm.snoozeDurationMinutes
        val snoozeTime = System.currentTimeMillis() + (minutes * 60 * 1000L)
        if (!canScheduleExactAlarms()) {
            val sanitizedAlarm = alarm.sanitized()
            val fireId = AlarmIncidentEvent.fireIdFor(sanitizedAlarm.id, snoozeTime)
            recordScheduleIncident(
                alarmId = sanitizedAlarm.id,
                fireId = fireId,
                triggerTime = snoozeTime,
                status = AlarmIncidentEvent.STATUS_FAILED,
                reasonCode = "SNOOZE_EXACT_PERMISSION_REVOKED"
            )
            return ScheduleOutcome.PermissionBlocked(sanitizedAlarm.id, "SNOOZE_EXACT_PERMISSION_REVOKED")
        }
        return scheduleAt(alarm, snoozeTime)
    }

    internal suspend fun scheduleSnoozeAt(
        alarm: Alarm,
        snoozeAtMillis: Long,
        targetGen: com.sysadmindoc.alarmclock.service.AlarmGeneration? = null,
        expectedRevision: Long? = null
    ): ScheduleOutcome {
        if (snoozeAtMillis <= System.currentTimeMillis()) {
            val sanitizedAlarm = alarm.sanitized()
            val fireId = AlarmIncidentEvent.fireIdFor(sanitizedAlarm.id, snoozeAtMillis)
            recordScheduleIncident(
                alarmId = sanitizedAlarm.id,
                fireId = fireId,
                triggerTime = snoozeAtMillis,
                status = AlarmIncidentEvent.STATUS_FAILED,
                reasonCode = "SNOOZE_AT_INVALID_PAST_TARGET"
            )
            return ScheduleOutcome.NoArm(sanitizedAlarm.id, "SNOOZE_AT_INVALID_PAST_TARGET")
        }
        if (!canScheduleExactAlarms()) {
            val sanitizedAlarm = alarm.sanitized()
            val fireId = AlarmIncidentEvent.fireIdFor(sanitizedAlarm.id, snoozeAtMillis)
            recordScheduleIncident(
                alarmId = sanitizedAlarm.id,
                fireId = fireId,
                triggerTime = snoozeAtMillis,
                status = AlarmIncidentEvent.STATUS_FAILED,
                reasonCode = "SNOOZE_AT_EXACT_PERMISSION_REVOKED"
            )
            return ScheduleOutcome.PermissionBlocked(sanitizedAlarm.id, "SNOOZE_AT_EXACT_PERMISSION_REVOKED")
        }
        return scheduleAt(alarm, snoozeAtMillis, targetGen = targetGen, expectedRevision = expectedRevision)
    }

    suspend fun reconcileRegistration(
        alarm: Alarm,
        targetTrigger: Long,
        isOsConfirmed: Boolean = false
    ): ScheduleOutcome {
        val sanitizedAlarm = alarm.sanitized()
        if (isOsConfirmed && sanitizedAlarm.nextTriggerTime == targetTrigger) {
            return ScheduleOutcome.Armed(sanitizedAlarm.id, targetTrigger)
        }
        return scheduleAt(sanitizedAlarm, targetTrigger)
    }

    internal suspend fun scheduleAt(
        alarm: Alarm,
        triggerTime: Long,
        requestWidgetUpdate: Boolean = true,
        targetGen: com.sysadmindoc.alarmclock.service.AlarmGeneration? = null,
        expectedRevision: Long? = null,
        expectedInstanceToken: String? = null,
        expectedRestoreEpoch: Long? = null,
        expectedDefinitionRevision: Long? = null
    ): ScheduleOutcome {
        val sanitizedAlarm = alarm.sanitized()
        if (!canScheduleExactAlarms()) {
            requestWidgetUpdateIfNeeded(requestWidgetUpdate)
            return ScheduleOutcome.PermissionBlocked(sanitizedAlarm.id, "EXACT_ALARM_PERMISSION_REVOKED")
        }
        if (preferencesManager.getCurrentSettings().isPaused()) {
            requestWidgetUpdateIfNeeded(requestWidgetUpdate)
            return ScheduleOutcome.NoArm(sanitizedAlarm.id, "PAUSED")
        }

        val slotMutex = mainSlotMutexMap.computeIfAbsent(sanitizedAlarm.id) { Mutex() }
        return slotMutex.withLock {
            val currentDbAlarm = repository.getById(sanitizedAlarm.id)
            if (currentDbAlarm != null && !sanitizedAlarm.isQuickAlarm && currentDbAlarm.generation > sanitizedAlarm.generation) {
                Log.i("AlarmScheduler", "Aborting stale scheduleAt for alarm ${sanitizedAlarm.id}: current DB generation ${currentDbAlarm.generation} > requested generation ${sanitizedAlarm.generation}")
                return@withLock ScheduleOutcome.RegistrationFailed(sanitizedAlarm.id, IllegalStateException("Stale scheduleAt: generation mismatch"))
            }

            val previousTrigger = currentDbAlarm?.nextTriggerTime ?: 0L

            if (targetGen != null) {
                val currentDbTrigger = repository.getById(sanitizedAlarm.id)?.nextTriggerTime ?: 0L
                val activeSettings = preferencesManager.getCachedSettings()

                val isActiveFiringSession = activeSettings.activeAlarmId == targetGen.alarmId &&
                    activeSettings.activeAlarmScheduledAt == targetGen.scheduledAt &&
                    activeSettings.activeAlarmFireId == targetGen.fireId &&
                    activeSettings.activeAlarmState == "FIRING"

                if (!isActiveFiringSession && currentDbTrigger > 0L) {
                    if (currentDbTrigger > targetGen.scheduledAt) {
                        Log.i("AlarmScheduler", "Aborting stale scheduleAt for alarm ${sanitizedAlarm.id}: targetGen $targetGen is older than current DB trigger $currentDbTrigger")
                        return@withLock ScheduleOutcome.RegistrationFailed(sanitizedAlarm.id, IllegalStateException("Stale scheduleAt: targetGen older than current DB trigger"))
                    }
                    if (currentDbTrigger == targetGen.scheduledAt) {
                        val currentFireId = AlarmIncidentEvent.fireIdFor(sanitizedAlarm.id, currentDbTrigger)
                        if (targetGen.fireId != currentFireId) {
                            Log.i("AlarmScheduler", "Aborting stale scheduleAt for alarm ${sanitizedAlarm.id}: targetGen fireId ${targetGen.fireId} does not match current DB fireId $currentFireId")
                            return@withLock ScheduleOutcome.RegistrationFailed(sanitizedAlarm.id, IllegalStateException("Stale scheduleAt: fireId mismatch"))
                        }
                    }
                }
            }

            if (routeAuthority.isOwnerRouteActive()) {
                val hasFullFingerprint = expectedRevision != null &&
                    expectedInstanceToken != null &&
                    expectedRestoreEpoch != null &&
                    expectedDefinitionRevision != null
                if (!hasFullFingerprint) {
                    Log.w("AlarmScheduler", "Aborting initial write for alarm ${sanitizedAlarm.id}: Owner route active but authority fingerprint incomplete")
                    return@withLock ScheduleOutcome.RegistrationFailed(sanitizedAlarm.id, IllegalStateException("Authority fingerprint incomplete"))
                }
                val initialRows = repository.casUpdateNextTriggerInitialWithAuthority(
                    id = sanitizedAlarm.id,
                    newTrigger = triggerTime,
                    expectedRevision = expectedRevision,
                    instanceToken = expectedInstanceToken,
                    restoreEpoch = expectedRestoreEpoch,
                    definitionRevision = expectedDefinitionRevision
                )
                if (initialRows == 0) {
                    Log.i("AlarmScheduler", "Aborting initial write for alarm ${sanitizedAlarm.id}: authority fingerprint mismatch or successor B intervened")
                    return@withLock ScheduleOutcome.RegistrationFailed(sanitizedAlarm.id, IllegalStateException("Authority fingerprint mismatch"))
                }
            } else {
                repository.updateNextTrigger(sanitizedAlarm.id, triggerTime)
            }

            val currentDbTrigger = repository.getById(sanitizedAlarm.id)?.nextTriggerTime
            if (currentDbTrigger != null && currentDbTrigger != triggerTime) {
                Log.i("AlarmScheduler", "Aborting OS registration for alarm ${sanitizedAlarm.id}: nextTriggerTime updated to $currentDbTrigger while operation for $triggerTime was in-flight")
                repository.casUpdateNextTrigger(id = sanitizedAlarm.id, expectedTrigger = triggerTime, newTrigger = previousTrigger)
                return@withLock ScheduleOutcome.RegistrationFailed(sanitizedAlarm.id, IllegalStateException("nextTriggerTime updated while operation in-flight"))
            }

            testOnPreOsRegistrationBarrier?.invoke(sanitizedAlarm.id, triggerTime)

            val postBarrierAlarm = repository.getById(sanitizedAlarm.id)
            if (postBarrierAlarm != null && !sanitizedAlarm.isQuickAlarm && postBarrierAlarm.generation > sanitizedAlarm.generation) {
                Log.i("AlarmScheduler", "Aborting OS registration for alarm ${sanitizedAlarm.id}: DB generation advanced to ${postBarrierAlarm.generation} while operation was in-flight")
                return@withLock ScheduleOutcome.RegistrationFailed(sanitizedAlarm.id, IllegalStateException("DB generation advanced while operation in-flight"))
            }

            var registrationCause: Throwable? = null
            val success = try {
                scheduleAlarmClock(sanitizedAlarm.id, triggerTime, expectedRevision = expectedRevision, expectedGeneration = sanitizedAlarm.generation)
            } catch (e: Exception) {
                Log.e("AlarmScheduler", "OS AlarmManager registration threw exception for alarm ${sanitizedAlarm.id}", e)
                registrationCause = e
                false
            }
            if (!success) {
                if (expectedRevision != null && expectedInstanceToken != null && expectedRestoreEpoch != null && expectedDefinitionRevision != null) {
                    val rowsUpdated = repository.casUpdateNextTriggerWithStrictAuthority(
                        id = sanitizedAlarm.id,
                        expectedTrigger = triggerTime,
                        newTrigger = previousTrigger,
                        expectedRevision = expectedRevision,
                        instanceToken = expectedInstanceToken,
                        restoreEpoch = expectedRestoreEpoch,
                        definitionRevision = expectedDefinitionRevision
                    )
                    if (rowsUpdated > 0) {
                        requestWidgetUpdateIfNeeded(requestWidgetUpdate)
                        try { syncBedtimeDndRule() } catch (e: Exception) { Log.w("AlarmScheduler", "Failed syncBedtimeDndRule for alarm ${sanitizedAlarm.id}", e) }
                    } else {
                        Log.i("AlarmScheduler", "Atomic CAS rollback rejected expected rev $expectedRevision for alarm ${sanitizedAlarm.id}: strict authority fingerprint mismatch or successor B intervened")
                    }
                } else {
                    Log.i("AlarmScheduler", "Incomplete authority fingerprint for alarm ${sanitizedAlarm.id}; suppressing destructive compensation")
                }
                return@withLock if (registrationCause is SecurityException) {
                    ScheduleOutcome.PermissionBlocked(sanitizedAlarm.id, "EXACT_ALARM_PERMISSION_REVOKED")
                } else {
                    ScheduleOutcome.RegistrationFailed(
                        sanitizedAlarm.id,
                        registrationCause ?: IllegalStateException("OS AlarmManager registration unfulfilled")
                    )
                }
            }
            DirectBootAlarmCache.saveIfEarlier(context, sanitizedAlarm, triggerTime)
            try { scheduleSupportingWork(sanitizedAlarm, triggerTime) } catch (e: Exception) { Log.w("AlarmScheduler", "Failed scheduleSupportingWork for alarm ${sanitizedAlarm.id}", e) }
            requestWidgetUpdateIfNeeded(requestWidgetUpdate)
            try { syncBedtimeDndRule() } catch (e: Exception) { Log.w("AlarmScheduler", "Failed syncBedtimeDndRule for alarm ${sanitizedAlarm.id}", e) }
            ScheduleOutcome.Armed(sanitizedAlarm.id, triggerTime)
        }
    }

    suspend fun rescheduleAll(forceRecalculate: Boolean = false) {
        rescheduleAllInBatches(forceRecalculate = forceRecalculate)
    }

    suspend fun rescheduleAllInBatches(
        forceRecalculate: Boolean = false,
        batchSize: Int = 25
    ): ScheduleOutcome.BatchResult {
        val now = System.currentTimeMillis()
        val enabledAlarms = repository.getEnabledNormalAlarms()
        val safeBatchSize = batchSize.coerceAtLeast(1)
        val outcomesMap = mutableMapOf<Long, ScheduleOutcome>()
        var armed = 0
        var blocked = 0
        var failed = 0
        var noArm = 0

        DirectBootAlarmCache.clear(context)
        enabledAlarms.chunked(safeBatchSize).forEach { batch ->
            batch.forEach { alarm ->
                if (DirectBootAlarmCache.consumeFiredOneShotMarker(context, alarm, now)) {
                    repository.setEnabled(alarm.id, enabled = false, nextTrigger = 0)
                    cancelScheduledEntries(alarm.id)
                    val outcome = ScheduleOutcome.NoArm(alarm.id, "ONE_SHOT_CONSUMED")
                    outcomesMap[alarm.id] = outcome
                    noArm++
                    return@forEach
                }
                val outcome = if (!forceRecalculate && alarm.nextTriggerTime > now) {
                    scheduleExistingTrigger(
                        alarm = alarm,
                        triggerTime = alarm.nextTriggerTime,
                        requestWidgetUpdate = false
                    )
                } else if (forceRecalculate && alarm.nextTriggerTime > now &&
                    alarm.nextTriggerTime < calculator.calculate(alarm.sanitized())
                ) {
                    scheduleExistingTrigger(
                        alarm = alarm,
                        triggerTime = alarm.nextTriggerTime,
                        requestWidgetUpdate = false
                    )
                } else {
                    schedule(alarm, requestWidgetUpdate = false)
                }
                outcomesMap[alarm.id] = outcome
                when (outcome) {
                    is ScheduleOutcome.Armed -> armed++
                    is ScheduleOutcome.PermissionBlocked -> blocked++
                    is ScheduleOutcome.RegistrationFailed -> failed++
                    is ScheduleOutcome.NoArm -> noArm++
                    else -> {}
                }
            }
            yield()
        }
        WidgetUpdater.requestUpdate(context)
        syncBedtimeDndRule()

        val enabledIds = enabledAlarms.map { it.id }.toSet()
        val issuedDao = repository.issuedOccurrenceDao()
        val activeDbAlarmIds = issuedDao.getAlarmIdsWithActiveOccurrences()
        activeDbAlarmIds.filter { it !in enabledIds }.forEach { disabledAlarmId ->
            cancelTokenizedScheduledAlarm(disabledAlarmId)
            cancelScheduledEntries(disabledAlarmId)
            issuedDao.revokeIssuedOccurrence(disabledAlarmId)
        }

        try {
            val durableIntentDao = repository.durableOperationIntentDao()
            val unresolvedCancelIntents = durableIntentDao.getUnresolvedCancellationIntents()
            unresolvedCancelIntents.forEach { cancelIntent ->
                if (cancelIntent.originalScheduledAt != null) {
                    val pendingIntent = lookupTokenizedPendingIntentForCancel(cancelIntent.alarmId, cancelIntent.operationToken)
                    val retrySuccess = try {
                        if (pendingIntent != null) {
                            alarmManager.cancel(pendingIntent)
                            pendingIntent.cancel()
                        }
                        true
                    } catch (e: Exception) {
                        Log.e("AlarmScheduler", "Retry OS cancel failed for token ${cancelIntent.operationToken}", e)
                        false
                    }
                    if (retrySuccess) {
                        try {
                            durableIntentDao.updateIntentPhase(cancelIntent.operationToken, com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent.PHASE_COMPLETED)
                        } catch (_: Exception) {}
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("AlarmScheduler", "Failed to reconcile unresolved cancellation intents during rescheduleAllInBatches", e)
        }

        return ScheduleOutcome.BatchResult(
            armedCount = armed,
            blockedCount = blocked,
            failedCount = failed,
            noArmCount = noArm,
            outcomes = outcomesMap
        )
    }

    /**
     * Internal helper to schedule with AlarmManager at a specific time without recalculating.
     */
    private suspend fun scheduleExistingTrigger(
        alarm: Alarm,
        triggerTime: Long,
        requestWidgetUpdate: Boolean = true
    ): ScheduleOutcome {
        val sanitizedAlarm = alarm.sanitized()
        if (!canScheduleExactAlarms()) {
            cancelScheduledEntries(sanitizedAlarm.id)
            repository.updateNextTrigger(sanitizedAlarm.id, 0)
            requestWidgetUpdateIfNeeded(requestWidgetUpdate)
            return ScheduleOutcome.PermissionBlocked(sanitizedAlarm.id, "EXACT_ALARM_PERMISSION_REVOKED")
        }

        val settings = preferencesManager.getCurrentSettings()
        val vacationAdjustment = VacationAlarmPolicy.adjustTrigger(
            alarm = sanitizedAlarm,
            initialTriggerTime = triggerTime,
            settings = settings,
            calculateFrom = calculator::calculate
        )

        return scheduleAt(
            alarm = sanitizedAlarm,
            triggerTime = vacationAdjustment.triggerTime,
            requestWidgetUpdate = requestWidgetUpdate
        )
    }

    /**
     * After an alarm fires: if repeating, schedule next occurrence.
     * If one-shot, disable it.
     *
     * @param firedScheduledAt The occurrence that just completed (0 when
     * unknown). For repeating alarms the next occurrence is computed no
     * earlier than one minute past it: a smart-wake early fire dismissed
     * before the original minute would otherwise recompute "next" as the
     * same still-future occurrence and ring again minutes later.
     */
    suspend fun handleAlarmFired(alarmId: Long, firedScheduledAt: Long = 0L): ScheduleOutcome {
        val alarm = repository.getById(alarmId) ?: return ScheduleOutcome.NoArm(alarmId, "MISSING_ALARM")

        return if (!alarm.isRecurringSchedule) {
            // One-shot alarm: set enabled = false in Room DB first so process death cannot leave an enabled unarmed definition.
            repository.setEnabled(alarmId, enabled = false, nextTrigger = 0)
            cancelMainScheduledAlarm(alarmId)
            syncBedtimeDndRule()
            ScheduleOutcome.NoArm(alarmId, "ONE_SHOT_DISABLED")
        } else {
            // Repeating alarm: schedule next occurrence (FLAG_UPDATE_CURRENT
            // replaces any stale early-fire PendingIntent with the same requestCode).
            val outcome = schedule(
                alarm,
                notBeforeMillis = if (firedScheduledAt > 0L) firedScheduledAt + 60_000L else 0L
            )
            if (outcome is ScheduleOutcome.RegistrationFailed) {
                Log.w("AlarmScheduler", "Post-fire re-arm registration failed for alarm $alarmId; enqueuing BootRescheduleWorker for durable recovery")
                try {
                    com.sysadmindoc.alarmclock.worker.BootRescheduleWorker.enqueue(
                        context = context,
                        sourceAction = "POST_FIRE_REGISTRATION_FAILED",
                        forceRecalculate = false
                    )
                } catch (e: Exception) {
                    Log.e("AlarmScheduler", "Failed to enqueue BootRescheduleWorker after post-fire registration failure for alarm $alarmId", e)
                }
            }
            outcome
        }
    }

    /**
     * Cancels the main AlarmManager exact-alarm entry for [alarmId] without
     * touching the alarm row. Used by [SmartAlarmService] when it fires an alarm
     * early: the original exact alarm scheduled at the unmodified trigger time is
     * still armed and would otherwise fire a spurious second time. Safe no-op when
     * nothing is armed.
     *
     * Validates full generation fence ([expectedTriggerTime], [expectedInstanceToken],
     * [expectedRestoreEpoch], [expectedScheduleRevision]): if a newer occurrence is already
     * scheduled or incarnation/generation changed, stale SmartWake cancellation is rejected.
     */
    fun cancelMainScheduledAlarm(
        alarmId: Long,
        expectedTriggerTime: Long = 0L,
        expectedInstanceToken: String? = null,
        expectedRestoreEpoch: Long? = null,
        expectedScheduleRevision: Long? = null,
        expectedIssueToken: String? = null,
        expectedGeneration: Int? = null
    ): Boolean {
        val alarm = runBlocking { repository.getById(alarmId)?.sanitized() }
            ?: run {
                Log.w("AlarmScheduler", "Alarm $alarmId missing; rejecting SmartWake cancellation")
                return false
            }

        if (expectedGeneration != null && !alarm.isQuickAlarm && alarm.generation > expectedGeneration) {
            Log.w("AlarmScheduler", "Stale SmartWake cancellation ignored for alarm $alarmId (generation mismatch: current ${alarm.generation} > expected $expectedGeneration)")
            return false
        }

        val authority = runBlocking { repository.getAuthorityByAlarmId(alarmId) }
        val activeSlotToken = runBlocking { repository.issuedOccurrenceDao().getSlotCurrentToken(alarmId) }

        // If alarm has migrated to tokenized authority (activeSlotToken is present in issue_slots):
        // Cancellation requires an expectedIssueToken matching activeSlotToken.
        // A missing, blank, or mismatched expectedIssueToken fails closed to protect the active alarm.
        if (!activeSlotToken.isNullOrBlank()) {
            if (expectedIssueToken.isNullOrBlank() || expectedIssueToken != activeSlotToken) {
                Log.w("AlarmScheduler", "SmartWake cancellation rejected for alarm $alarmId: active tokenized authority '$activeSlotToken' requires matching token (got '$expectedIssueToken')")
                return false
            }
        }

        // Generation Fence Verification
        if (expectedInstanceToken != null && authority != null && authority.instanceToken != expectedInstanceToken) {
            Log.w("AlarmScheduler", "Stale SmartWake cancellation ignored for alarm $alarmId (instanceToken mismatch)")
            return false
        }

        if (expectedRestoreEpoch != null && authority != null && authority.restoreEpoch != expectedRestoreEpoch) {
            Log.w("AlarmScheduler", "Stale SmartWake cancellation ignored for alarm $alarmId (restoreEpoch mismatch)")
            return false
        }

        if (expectedScheduleRevision != null && authority != null && authority.scheduleRevision != expectedScheduleRevision) {
            Log.w("AlarmScheduler", "Stale SmartWake cancellation ignored for alarm $alarmId (scheduleRevision mismatch)")
            return false
        }

        if (expectedTriggerTime > 0L) {
            if (alarm.nextTriggerTime != expectedTriggerTime ||
                (authority != null && authority.desiredTriggerMillis != null && authority.desiredTriggerMillis != expectedTriggerTime)) {
                Log.w("AlarmScheduler", "Stale SmartWake cancellation ignored for alarm $alarmId (current ${alarm.nextTriggerTime} != expected $expectedTriggerTime)")
                return false
            }
        }

        val pendingIntent = lookupPendingIntentForCancel(alarmId, expectedTriggerTime, "")
        if (pendingIntent != null) {
            return try {
                alarmManager.cancel(pendingIntent)
                pendingIntent.cancel()
                true
            } catch (e: Exception) {
                Log.e("AlarmScheduler", "cancelMainScheduledAlarm OS cancel failed for alarm $alarmId", e)
                false
            }
        }
        return true
    }

    /**
     * Keeps the app-owned bedtime DND condition aligned with whichever enabled
     * alarm currently wakes the user next. No-ops unless the user enabled the
     * Bedtime DND preference and granted notification policy access.
     */
    suspend fun syncBedtimeDndRule() {
        val settings = preferencesManager.getCurrentSettings()
        if (!settings.bedtimeDndEnabled &&
            !BedtimeZenRuleManager.isPolicyAccessGranted(context)) {
            return
        }
        val nextAlarmTrigger = repository.getNextAlarm()?.nextTriggerTime
        BedtimeZenRuleManager.syncRule(context, settings, nextAlarmTrigger)
    }



    internal fun canScheduleExactAlarms(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
    }

    private fun requestWidgetUpdateIfNeeded(requestWidgetUpdate: Boolean) {
        if (requestWidgetUpdate) {
            WidgetUpdater.requestUpdate(context)
        }
    }

    /**
     * Executes raw MAIN_ALARM_SLOT exact OS registration for [alarmId] at [triggerTime].
     * Isolated raw OS primitive used by [RealMainSlotApplier].
     * Excludes repository mutations, widget updates, DirectBoot cache writes, and WorkManager jobs.
     */
    internal fun applyRawOsSet(alarmId: Long, triggerTime: Long): Boolean {
        if (!canScheduleExactAlarms()) return false
        val fireId = AlarmIncidentEvent.fireIdFor(alarmId, triggerTime)
        val showAlarmClockIcon = preferencesManager.getCachedSettings().showAlarmClockIcon
        val pendingIntent = createPendingIntent(alarmId, triggerTime, fireId)
        return try {
            if (showAlarmClockIcon) {
                alarmManager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(triggerTime, createShowIntent(alarmId)),
                    pendingIntent
                )
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            }
            true
        } catch (e: Exception) {
            Log.e("AlarmScheduler", "applyRawOsSet failed for alarm $alarmId", e)
            recordScheduleIncident(
                alarmId = alarmId,
                fireId = fireId,
                triggerTime = triggerTime,
                status = AlarmIncidentEvent.STATUS_FAILED,
                reasonCode = "EXACT_SCHEDULE_FAILED_${e.javaClass.simpleName}"
            )
            throw e
        }
    }

    /**
     * Executes raw MAIN_ALARM_SLOT OS cancellation for [alarmId].
     * Isolated raw OS primitive used by [RealMainSlotApplier].
     * Uses [lookupPendingIntentForCancel] (FLAG_NO_CREATE) to avoid mutating intent extras or creating stray intents.
     * Excludes repository mutations, widget updates, DirectBoot cache writes, and WorkManager jobs.
     */
    internal fun applyRawOsCancel(alarmId: Long): Boolean {
        val pendingIntent = lookupPendingIntentForCancel(alarmId) ?: return true
        return try {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
            true
        } catch (e: Exception) {
            Log.e("AlarmScheduler", "applyRawOsCancel failed for alarm $alarmId", e)
            false
        }
    }

    /**
     * Performs a complete tokenized occurrence reservation, OS AlarmManager registration,
     * and atomic activation sequence for [alarmId] at [triggerTime].
     * DORMANT helper for P2B4 tokenized scheduling integration.
     */
    @androidx.annotation.VisibleForTesting
    internal suspend fun applyTokenizedOsSet(
        alarmId: Long,
        triggerTime: Long,
        issueToken: String = java.util.UUID.randomUUID().toString()
    ): Boolean {
        if (!canScheduleExactAlarms() || issueToken.isBlank()) return false
        val fireId = AlarmIncidentEvent.fireIdFor(alarmId, triggerTime)
        val issuedDao = repository.issuedOccurrenceDao()

        // 1. Reserve occurrence in PREPARED status
        val occurrence = issuedDao.reserveIssuedOccurrence(
            issueToken = issueToken,
            alarmId = alarmId,
            scheduledAt = triggerTime,
            fireId = fireId
        ) ?: return false

        // 2. Create tokenized PendingIntent
        val pendingIntent = createTokenizedPendingIntent(alarmId, triggerTime, fireId, issueToken)
        val showAlarmClockIcon = preferencesManager.getCachedSettings().showAlarmClockIcon

        // 3. Register with OS AlarmManager
        val osSuccess = try {
            if (showAlarmClockIcon) {
                alarmManager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(triggerTime, createShowIntent(alarmId)),
                    pendingIntent
                )
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            }
            true
        } catch (e: Exception) {
            Log.e("AlarmScheduler", "applyTokenizedOsSet registration failed for alarm $alarmId", e)
            issuedDao.casUpdateOccurrenceStatus(
                issueToken = issueToken,
                expectedStatus = com.sysadmindoc.alarmclock.data.local.entity.IssuedOccurrence.STATUS_PREPARED,
                newStatus = com.sysadmindoc.alarmclock.data.local.entity.IssuedOccurrence.STATUS_SET_FAILED,
                updatedAt = System.currentTimeMillis()
            )
            false
        }

        if (!osSuccess) return false

        // 4. On OS success, activate replacement occurrence
        val activated = issuedDao.activateIssuedOccurrence(issueToken)
        if (activated) {
            applyRawOsCancel(alarmId)
        }
        return activated
    }

    /**
     * Cancels a tokenized OS registration for [alarmId] and revokes active/pending occurrences.
     * DORMANT helper for P2B4.
     */
    @androidx.annotation.VisibleForTesting
    internal suspend fun cancelTokenizedScheduledAlarm(alarmId: Long): Boolean {
        val issuedDao = repository.issuedOccurrenceDao()
        val durableIntentDao = repository.durableOperationIntentDao()
        val currentToken = issuedDao.getSlotCurrentToken(alarmId)

        if (!currentToken.isNullOrBlank()) {
            val occurrence = issuedDao.getOccurrence(currentToken)
            if (occurrence != null) {
                val now = System.currentTimeMillis()
                val cancelIntent = com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent(
                    operationToken = currentToken,
                    alarmId = alarmId,
                    operationEpoch = now,
                    incarnationId = occurrence.fireId,
                    operationKind = "CANCEL",
                    expectedOwnerToken = null,
                    expectedScheduleRevision = null,
                    expectedRestoreEpoch = null,
                    phase = com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent.PHASE_PENDING,
                    intentRevision = 1L,
                    originalFireId = occurrence.fireId,
                    originalScheduledAt = occurrence.scheduledAt
                )
                try {
                    durableIntentDao.insertValidatedIntent(cancelIntent)
                } catch (_: Exception) {
                    // Intent record already exists
                }

                val pendingIntent = lookupTokenizedPendingIntentForCancel(alarmId, currentToken)
                val osCancelSuccess = try {
                    if (pendingIntent != null) {
                        alarmManager.cancel(pendingIntent)
                        pendingIntent.cancel()
                    }
                    true
                } catch (e: Exception) {
                    Log.e("AlarmScheduler", "cancelTokenizedScheduledAlarm failed for alarm $alarmId", e)
                    false
                }

                if (osCancelSuccess) {
                    try {
                        durableIntentDao.updateIntentPhase(
                            currentToken,
                            com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent.PHASE_COMPLETED
                        )
                    } catch (_: Exception) {}
                }
            }
        }

        issuedDao.revokeIssuedOccurrence(alarmId)
        applyRawOsCancel(alarmId)
        return true
    }

    private suspend fun scheduleAlarmClock(
        alarmId: Long,
        triggerTime: Long,
        expectedRevision: Long? = null,
        expectedGeneration: Int? = null
    ): Boolean {
        testOnImmediatePreOsBarrier?.invoke(alarmId, triggerTime)

        if (expectedGeneration != null) {
            val currentAlarm = repository.getById(alarmId)
            if (currentAlarm != null && !currentAlarm.isQuickAlarm && currentAlarm.generation > expectedGeneration) {
                Log.i("AlarmScheduler", "Aborting raw OS set for alarm $alarmId: DB generation advanced to ${currentAlarm.generation} > expected $expectedGeneration")
                return false
            }
        }

        val fireId = AlarmIncidentEvent.fireIdFor(alarmId, triggerTime)
        val showAlarmClockIcon = preferencesManager.getCachedSettings().showAlarmClockIcon
        recordScheduleIncident(
            alarmId = alarmId,
            fireId = fireId,
            triggerTime = triggerTime,
            status = AlarmIncidentEvent.STATUS_REQUESTED,
            reasonCode = if (showAlarmClockIcon) "SET_ALARM_CLOCK" else "SET_EXACT_ALLOW_WHILE_IDLE"
        )
        val owner = if (routeAuthority.isOwnerRouteActive()) scheduleAuthorityOwnerProvider?.get() else null
        var freshOsRegistration = false
        val success = if (owner != null) {
            val actionKey = "act-sched-$alarmId-$triggerTime"
            val ownerRes = owner.executeOperation(
                alarmId = alarmId,
                desiredOperation = "SET",
                desiredTriggerMillis = triggerTime,
                actionKey = actionKey,
                expectedRevision = expectedRevision
            )
            if (ownerRes is OwnerResult.OsAccepted) {
                freshOsRegistration = true
                true
            } else {
                ownerRes is OwnerResult.DuplicateIgnored
            }
        } else {
            val osSuccess = applyRawOsSet(alarmId, triggerTime)
            if (osSuccess) freshOsRegistration = true
            osSuccess
        }

        if (success) {
            if (freshOsRegistration) {
                DirectBootAlarmCache.cancelScheduledFallback(context, alarmId)
            }
            recordScheduleIncident(
                alarmId = alarmId,
                fireId = fireId,
                triggerTime = triggerTime,
                status = AlarmIncidentEvent.STATUS_SUCCEEDED,
                reasonCode = if (showAlarmClockIcon) "SET_ALARM_CLOCK" else "SET_EXACT_ALLOW_WHILE_IDLE"
            )
        }
        return success
    }

    private fun recordScheduleIncident(
        alarmId: Long,
        fireId: String,
        triggerTime: Long,
        status: String,
        reasonCode: String
    ) {
        alarmIncidentRepository.recordAsync(
            alarmId = alarmId,
            fireId = fireId,
            scheduledAt = triggerTime,
            type = AlarmIncidentEvent.TYPE_SCHEDULE,
            status = status,
            reasonCode = reasonCode,
            source = "AlarmScheduler"
        )
    }

    private fun scheduleSupportingWork(alarm: Alarm, triggerTime: Long) {
        scheduleSmartAlarmStart(alarm, triggerTime)
        scheduleHueSunrise(alarm, triggerTime)
        scheduleFireWatchdog(alarm, triggerTime)
    }

    /**
     * Enqueue a proactive fire-verification check shortly after the scheduled
     * fire. [FireWatchdogWorker] re-fires the alarm if AlarmManager silently
     * failed to deliver it. The "repeat missed alarms" opt-in is checked at run
     * time by the worker, so toggling the setting needs no rescheduling.
     */
    private fun scheduleFireWatchdog(alarm: Alarm, triggerTime: Long) {
        val workManager = WorkManager.getInstance(context)
        val checkAtMs = triggerTime + FireWatchdogPolicy.WATCHDOG_DELAY_MS
        val delayMs = (checkAtMs - System.currentTimeMillis()).coerceAtLeast(0)
        val inputData = Data.Builder()
            .putLong(FireWatchdogWorker.KEY_ALARM_ID, alarm.id)
            .putLong(FireWatchdogWorker.KEY_SCHEDULED_AT, triggerTime)
            .build()
        val workRequest = OneTimeWorkRequestBuilder<FireWatchdogWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(inputData)
            .build()
        workManager.enqueueUniqueWork(
            FireWatchdogWorker.uniqueName(alarm.id),
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }

    private fun scheduleSmartAlarmStart(alarm: Alarm, triggerTime: Long) {
        if (!alarm.smartAlarmEnabled || alarm.smartAlarmWindowMinutes <= 0) {
            cancelSmartAlarmStart(alarm.id)
            return
        }

        val windowMs = alarm.smartAlarmWindowMinutes * 60_000L
        val serviceStartTime = triggerTime - windowMs
        val delayMs = (serviceStartTime - System.currentTimeMillis()).coerceAtLeast(0)
        val smartIntent = Intent(context, SmartAlarmService::class.java).apply {
            action = SmartAlarmService.ACTION_START_SMART
            putExtra(SmartAlarmService.EXTRA_ALARM_ID, alarm.id)
            putExtra(SmartAlarmService.EXTRA_TARGET_TIME, triggerTime)
        }
        val smartPending = PendingIntent.getForegroundService(
            context,
            (alarm.id + 50000).toInt(),
            smartIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (delayMs == 0L) {
            // v1.5.1: Android 14+ can refuse startForegroundService() if the
            // scheduler is invoked from a background-restricted path. Fall
            // back to AlarmManager — the service will start when the pending
            // intent fires (one tick later, negligible for a motion-tracking
            // window that's normally 30 min long).
            try {
                context.startForegroundService(smartIntent)
            } catch (_: Exception) {
                try {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        System.currentTimeMillis() + 1_000L,
                        smartPending
                    )
                } catch (fallback: Exception) {
                    android.util.Log.e(
                        "AlarmScheduler",
                        "Smart-alarm immediate fallback failed for alarm ${alarm.id}",
                        fallback
                    )
                }
            }
        } else {
            try {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    serviceStartTime,
                    smartPending
                )
            } catch (e: Exception) {
                android.util.Log.e(
                    "AlarmScheduler",
                    "Smart-alarm window registration failed for alarm ${alarm.id}",
                    e
                )
            }
        }
    }

    private fun scheduleHueSunrise(alarm: Alarm, triggerTime: Long) {
        val workManager = WorkManager.getInstance(context)
        if (!alarm.hueEnabled || alarm.huePreWakeMinutes <= 0) {
            workManager.cancelUniqueWork(HueSunriseWorker.uniqueName(alarm.id))
            HueSunriseNotifications.cancel(context, alarm.id)
            return
        }

        val hueStartMs = triggerTime - (alarm.huePreWakeMinutes * 60_000L)
        val hueDelayMs = (hueStartMs - System.currentTimeMillis()).coerceAtLeast(0)
        // Anchor the ramp to wall-clock time rather than to whenever the worker
        // happens to start. setInitialDelay is a floor, so a worker that starts
        // late would otherwise finish brightening well after the alarm.
        val inputData = Data.Builder()
            .putLong(HueSunriseWorker.KEY_ALARM_ID, alarm.id)
            .putLong(HueSunriseWorker.KEY_RAMP_START, hueStartMs)
            .putLong(HueSunriseWorker.KEY_RAMP_END, triggerTime)
            .build()
        val workRequest = OneTimeWorkRequestBuilder<HueSunriseWorker>()
            .setInitialDelay(hueDelayMs, TimeUnit.MILLISECONDS)
            .setInputData(inputData)
            .build()
        workManager.enqueueUniqueWork(
            HueSunriseWorker.uniqueName(alarm.id),
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }

    internal fun cancelScheduledEntries(
        alarmId: Long,
        includeFollowUpWorkers: Boolean = false,
        targetGen: com.sysadmindoc.alarmclock.service.AlarmGeneration? = null,
        expectedRevision: Long? = null,
        expectedGeneration: Int? = null
    ) {
        val slotMutex = mainSlotMutexMap.computeIfAbsent(alarmId) { Mutex() }
        var isCancelAccepted = false
        runBlocking {
            slotMutex.withLock {
                val currentAlarm = repository.getById(alarmId)
                if (expectedGeneration != null && currentAlarm != null && !currentAlarm.isQuickAlarm && currentAlarm.generation > expectedGeneration) {
                    Log.i("AlarmScheduler", "Aborting stale cancelScheduledEntries for alarm $alarmId: current DB generation ${currentAlarm.generation} > expected $expectedGeneration")
                    return@withLock
                }
                if (targetGen != null) {
                    if (currentAlarm != null && currentAlarm.nextTriggerTime > targetGen.scheduledAt) {
                        Log.i("AlarmScheduler", "Aborting stale cancelScheduledEntries for alarm $alarmId: targetGen $targetGen is older than current DB trigger ${currentAlarm.nextTriggerTime}")
                        return@withLock
                    }
                }
                val owner = if (routeAuthority.isOwnerRouteActive()) scheduleAuthorityOwnerProvider?.get() else null
                if (owner != null) {
                    val actionKey = "act-cancel-$alarmId-${System.currentTimeMillis()}"
                    val cancelResult = owner.executeOperation(
                        alarmId = alarmId,
                        desiredOperation = "CANCEL",
                        desiredTriggerMillis = null,
                        actionKey = actionKey,
                        expectedRevision = expectedRevision
                    )
                    isCancelAccepted = cancelResult is OwnerResult.OsAccepted || cancelResult is OwnerResult.DuplicateIgnored
                } else {
                    if (routeAuthority.isOwnerRouteActive()) {
                        Log.w("AlarmScheduler", "Owner route active but scheduleAuthorityOwnerProvider is null for alarm $alarmId; aborting cancel")
                        isCancelAccepted = false
                    } else {
                        applyRawOsCancel(alarmId)
                        isCancelAccepted = true
                    }
                }
            }
        }

        if (!isCancelAccepted && routeAuthority.isOwnerRouteActive()) {
            Log.w("AlarmScheduler", "Cancellation unfulfilled or rejected for alarm $alarmId under OWNER_EXCLUSIVE; skipping destructive side effects")
            return
        }

        DirectBootAlarmCache.removeIfMatches(context, alarmId)

        cancelSmartAlarmStart(alarmId)

        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(HueSunriseWorker.uniqueName(alarmId))
        // A cancelled continuation never runs, so nothing else would clear the
        // ongoing "Sunrise in progress" notification.
        HueSunriseNotifications.cancel(context, alarmId)
        workManager.cancelUniqueWork(FireWatchdogWorker.uniqueName(alarmId))
        if (includeFollowUpWorkers) {
            workManager.cancelUniqueWork("guardian_$alarmId")
            workManager.cancelUniqueWork("wake_confirm_$alarmId")
        }
    }

    private fun cancelSmartAlarmStart(alarmId: Long) {
        val smartIntent = Intent(context, SmartAlarmService::class.java).apply {
            action = SmartAlarmService.ACTION_START_SMART
        }
        val smartPending = PendingIntent.getForegroundService(
            context,
            (alarmId + 50000).toInt(),
            smartIntent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (smartPending != null) {
            alarmManager.cancel(smartPending)
            smartPending.cancel()
        }
    }

    private fun createPendingIntent(
        alarmId: Long,
        triggerTime: Long = 0L,
        fireId: String = ""
    ): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = "com.sysadmindoc.alarmclock.ALARM_FIRE"
            putExtra(EXTRA_ALARM_ID, alarmId)
            putExtra(EXTRA_SCHEDULED_AT, triggerTime)
            putExtra(EXTRA_ALARM_FIRE_ID, fireId.ifBlank { AlarmIncidentEvent.fireIdFor(alarmId, triggerTime) })
        }
        return PendingIntent.getBroadcast(
            context,
            alarmId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun lookupPendingIntentForCancel(alarmId: Long): PendingIntent? {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = "com.sysadmindoc.alarmclock.ALARM_FIRE"
        }
        return PendingIntent.getBroadcast(
            context,
            alarmId.toInt(),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun lookupPendingIntentForCancel(
        alarmId: Long,
        triggerTime: Long,
        fireId: String
    ): PendingIntent? {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = "com.sysadmindoc.alarmclock.ALARM_FIRE"
            putExtra(EXTRA_ALARM_ID, alarmId)
            if (triggerTime > 0L) {
                putExtra(EXTRA_SCHEDULED_AT, triggerTime)
                putExtra(EXTRA_ALARM_FIRE_ID, fireId.ifBlank { AlarmIncidentEvent.fireIdFor(alarmId, triggerTime) })
            }
        }
        return PendingIntent.getBroadcast(
            context,
            alarmId.toInt(),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Creates a tokenized PendingIntent for a specific issued occurrence.
     * Uses filterEquals-distinct Intent.data URI ("alarmclock://alarm/issue?alarmId=$alarmId&token=$issueToken")
     * and a constant requestCode so that different issueTokens produce distinct PendingIntent slots
     * without alarmId.toInt() integer truncation collisions.
     * DORMANT helper for P2A.
     */
    @androidx.annotation.VisibleForTesting
    internal fun createTokenizedPendingIntent(
        alarmId: Long,
        triggerTime: Long,
        fireId: String,
        issueToken: String
    ): PendingIntent {
        require(issueToken.isNotBlank()) { "issueToken MUST NOT be blank for tokenized PendingIntent" }
        require(alarmId > 0L) { "alarmId MUST be positive for tokenized PendingIntent" }
        require(triggerTime > 0L) { "triggerTime MUST be positive for tokenized PendingIntent" }

        val uri = android.net.Uri.parse(
            "alarmclock://alarm/issue?alarmId=$alarmId&token=${android.net.Uri.encode(issueToken)}"
        )
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = "com.sysadmindoc.alarmclock.ALARM_FIRE"
            data = uri
            putExtra(EXTRA_ALARM_ID, alarmId)
            putExtra(EXTRA_SCHEDULED_AT, triggerTime)
            putExtra(EXTRA_ALARM_FIRE_ID, fireId.ifBlank { AlarmIncidentEvent.fireIdFor(alarmId, triggerTime) })
            putExtra(EXTRA_ISSUE_TOKEN, issueToken)
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_ISSUED_OCCURRENCE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Performs a FLAG_NO_CREATE lookup for a tokenized PendingIntent matching [alarmId] and [issueToken].
     * DORMANT helper for P2A.
     */
    @androidx.annotation.VisibleForTesting
    internal fun lookupTokenizedPendingIntentForCancel(alarmId: Long, issueToken: String): PendingIntent? {
        if (issueToken.isBlank()) return null
        val uri = android.net.Uri.parse(
            "alarmclock://alarm/issue?alarmId=$alarmId&token=${android.net.Uri.encode(issueToken)}"
        )
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = "com.sysadmindoc.alarmclock.ALARM_FIRE"
            data = uri
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_ISSUED_OCCURRENCE,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * The "tap the alarm icon in the status bar" intent used by AlarmClockInfo.
     * Falls back to an explicit MainActivity launch if the package launch intent
     * is unavailable (e.g., on devices that strip MAIN/LAUNCHER from system rebuilds).
     */
    private fun createShowIntent(alarmId: Long): PendingIntent {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent().setClassName(
                context.packageName,
                "com.sysadmindoc.alarmclock.MainActivity"
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        return PendingIntent.getActivity(
            context,
            alarmId.toInt(),
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
