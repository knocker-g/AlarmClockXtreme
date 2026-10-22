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
    private val alarmManager: AlarmManager
        get() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private val mainSlotMutexMap = ConcurrentHashMap<Long, Mutex>()

    @androidx.annotation.VisibleForTesting
    internal var testOnPreOsRegistrationBarrier: (suspend (alarmId: Long, triggerTime: Long) -> Unit)? = null

    companion object {
        const val EXTRA_ALARM_ID = "alarm_id"
        const val EXTRA_ALARM_FIRE_ID = "alarm_fire_id"
        const val EXTRA_SCHEDULED_AT = "scheduled_at"

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
    ) {
        val sanitizedAlarm = alarm.sanitized()
        val originAuthority = repository.getAuthorityByAlarmId(sanitizedAlarm.id)
        val originRevision = originAuthority?.scheduleRevision
        val originInstanceToken = originAuthority?.instanceToken
        val originRestoreEpoch = originAuthority?.restoreEpoch
        val originDefinitionRevision = originAuthority?.definitionRevision

        if (!sanitizedAlarm.isEnabled) {
            cancelScheduledEntries(sanitizedAlarm.id, expectedRevision = originRevision)
            return
        }

        if (!canScheduleExactAlarms()) {
            cancelScheduledEntries(sanitizedAlarm.id, expectedRevision = originRevision)
            if (routeAuthority.isOwnerRouteActive() && originRevision != null) {
                repository.casUpdateNextTriggerInitialWithAuthority(sanitizedAlarm.id, 0, originRevision, originInstanceToken, originRestoreEpoch, originDefinitionRevision)
            } else {
                repository.updateNextTrigger(sanitizedAlarm.id, 0)
            }
            requestWidgetUpdateIfNeeded(requestWidgetUpdate)
            return
        }

        // v1.11.6 (roadmap N6): hard-suspend all alarms when the user has
        // tapped "Pause alarms for N days". Clears any prior PendingIntent
        // and zeroes nextTrigger so the next-alarm UI surfaces the paused
        // state. The pause expires naturally; the next reschedule pass
        // after the timestamp will re-arm the alarm.
        val currentSettings = preferencesManager.getCurrentSettings()
        if (currentSettings.isPaused()) {
            cancelScheduledEntries(sanitizedAlarm.id, expectedRevision = originRevision)
            if (routeAuthority.isOwnerRouteActive() && originRevision != null) {
                repository.casUpdateNextTriggerInitialWithAuthority(sanitizedAlarm.id, 0, originRevision, originInstanceToken, originRestoreEpoch, originDefinitionRevision)
            } else {
                repository.updateNextTrigger(sanitizedAlarm.id, 0)
            }
            requestWidgetUpdateIfNeeded(requestWidgetUpdate)
            return
        }

        var triggerTime = if (notBeforeMillis > System.currentTimeMillis()) {
            calculator.calculate(
                sanitizedAlarm,
                Instant.ofEpochMilli(notBeforeMillis).atZone(ZoneId.systemDefault())
            )
        } else {
            calculator.calculate(sanitizedAlarm)
        }
        if (triggerTime <= System.currentTimeMillis()) {
            cancelScheduledEntries(sanitizedAlarm.id, expectedRevision = originRevision)
            if (sanitizedAlarm.isRecurringSchedule) {
                if (routeAuthority.isOwnerRouteActive() && originRevision != null) {
                    repository.casUpdateNextTriggerInitialWithAuthority(sanitizedAlarm.id, 0, originRevision, originInstanceToken, originRestoreEpoch, originDefinitionRevision)
                } else {
                    repository.updateNextTrigger(sanitizedAlarm.id, 0)
                }
            } else {
                if (routeAuthority.isOwnerRouteActive() && originRevision != null) {
                    repository.casSetEnabledWithStrictAuthority(sanitizedAlarm.id, enabled = false, nextTrigger = 0, originRevision, originInstanceToken, originRestoreEpoch, originDefinitionRevision)
                } else {
                    repository.setEnabled(sanitizedAlarm.id, enabled = false, nextTrigger = 0)
                }
            }
            requestWidgetUpdateIfNeeded(requestWidgetUpdate)
            return
        }
        val settings = currentSettings  // already fetched for the pause-state check above

        // Vacation mode skips repeating-alarm occurrences inside the configured
        // window, then schedules the first occurrence after the window ends.
        val vacationAdjustment = VacationAlarmPolicy.adjustTrigger(
            alarm = sanitizedAlarm,
            initialTriggerTime = triggerTime,
            settings = settings,
            calculateFrom = calculator::calculate
        )
        triggerTime = vacationAdjustment.triggerTime

        // F13: Holiday auto-skip. Dates are resolved in the alarm's scheduling
        // zone: a fixed-zone alarm whose local fire time is across midnight
        // from the device zone would otherwise check the wrong calendar day.
        if (sanitizedAlarm.skipOnHolidays && settings.holidayAutoSkipEnabled) {
            val holidayZone = sanitizedAlarm.schedulingZone(ZoneId.systemDefault())
            if (!sanitizedAlarm.isRecurringSchedule) {
                // One-shot alarm: if the day is a holiday, don't fire at all.
                // Store 0, not the suppressed future trigger — a stored future
                // trigger survives reboot/app-update reschedules (which re-arm
                // any valid-looking nextTriggerTime without holiday checks)
                // and the alarm would fire on the holiday after a reboot.
                val triggerDate = Instant.ofEpochMilli(triggerTime)
                    .atZone(holidayZone).toLocalDate()
                if (holidayRepository.isHoliday(triggerDate)) {
                    cancelScheduledEntries(sanitizedAlarm.id)
                    repository.updateNextTrigger(sanitizedAlarm.id, 0)
                    requestWidgetUpdateIfNeeded(requestWidgetUpdate)
                    return
                }
            } else {
                var advanced = advanceTriggerPastHolidays(sanitizedAlarm, triggerTime, holidayZone)
                if (advanced != null && advanced != triggerTime) {
                    // Holiday advancement can push the occurrence inside the
                    // vacation window; re-apply vacation, then clear holidays
                    // once more from the vacation-adjusted date.
                    val revacationed = VacationAlarmPolicy.adjustTrigger(
                        alarm = sanitizedAlarm,
                        initialTriggerTime = advanced,
                        settings = settings,
                        calculateFrom = calculator::calculate
                    ).triggerTime
                    if (revacationed != advanced) {
                        advanced = advanceTriggerPastHolidays(
                            sanitizedAlarm, revacationed, holidayZone
                        )
                    }
                }
                if (advanced == null) {
                    // All candidates were holidays (corrupt data or an unusually
                    // long public-holiday run): suppress this occurrence rather
                    // than fire on a holiday. The alarm stays enabled; it
                    // reschedules once holiday data refreshes or the user re-saves.
                    cancelScheduledEntries(sanitizedAlarm.id)
                    repository.updateNextTrigger(sanitizedAlarm.id, 0)
                    requestWidgetUpdateIfNeeded(requestWidgetUpdate)
                    return
                }
                triggerTime = advanced
            }
        }

        if (sanitizedAlarm.weatherEarlyMinutes > 0 && triggerTime > 0) {
            val weatherSettings = preferencesManager.getCurrentSettings()
            val lat = weatherSettings.lastKnownLatitude
            val lng = weatherSettings.lastKnownLongitude
            val haveLocation = lat != 0.0 || lng != 0.0
            // Only trust cached weather that was fetched for the current location.
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
                        // Saving an alarm inside its weather-lead window must
                        // not produce a past trigger — setAlarmClock() fires a
                        // past time immediately. Keep at least a minute out.
                        triggerTime = maxOf(
                            triggerTime - sanitizedAlarm.weatherEarlyMinutes * 60_000L,
                            System.currentTimeMillis() + 60_000L
                        )
                    }
                }
            }
        }

        val success = scheduleAt(
            sanitizedAlarm,
            triggerTime,
            requestWidgetUpdate = requestWidgetUpdate,
            expectedRevision = originRevision,
            expectedInstanceToken = originInstanceToken,
            expectedRestoreEpoch = originRestoreEpoch,
            expectedDefinitionRevision = originDefinitionRevision
        )
        if (!success) {
            Log.i("AlarmScheduler", "schedule() for alarm ${sanitizedAlarm.id} at trigger $triggerTime was unfulfilled or superseded; preserving current DB state")
            return
        }
    }

    /**
     * Cancel a scheduled alarm and any associated workers/services.
     */
    fun cancel(alarmId: Long) {
        val originAuthority = runBlocking { repository.getAuthorityByAlarmId(alarmId) }
        val originRevision = originAuthority?.scheduleRevision
        cancelScheduledEntries(alarmId, includeFollowUpWorkers = true, expectedRevision = originRevision)
        // The occurrence is over, so its snooze tally must not carry into the
        // next one. A leftover count at or past the cap would otherwise make a
        // fresh ring open with snooze already withdrawn.
        AlarmRuntimeState.clear(context, alarmId)
        WidgetUpdater.requestUpdate(context)
    }

    /**
     * Schedule a snoozed alarm to fire after the snooze duration.
     * @param customMinutes Override snooze duration (null = use alarm's default)
     */
    suspend fun scheduleSnooze(alarm: Alarm, customMinutes: Int? = null): Boolean {
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
            return false
        }
        return scheduleAt(alarm, snoozeTime)
    }

    /**
     * Schedule a one-off snooze at a user-selected wall-clock time. The target
     * is stored in the alarm row before returning, so a service restart or
     * reboot reschedules the same occurrence instead of reverting to the
     * alarm's normal repeat calculation.
     */
    internal suspend fun scheduleSnoozeAt(
        alarm: Alarm,
        snoozeAtMillis: Long,
        targetGen: com.sysadmindoc.alarmclock.service.AlarmGeneration? = null,
        expectedRevision: Long? = null
    ): Boolean {
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
            return false
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
            return false
        }
        return scheduleAt(alarm, snoozeAtMillis, targetGen = targetGen, expectedRevision = expectedRevision)
    }

    /**
     * Register a precomputed trigger time without recalculating it.
     * Useful for snooze, skip-next, quick alarms, and restored exact alarms.
     */
    internal suspend fun scheduleAt(
        alarm: Alarm,
        triggerTime: Long,
        requestWidgetUpdate: Boolean = true,
        targetGen: com.sysadmindoc.alarmclock.service.AlarmGeneration? = null,
        expectedRevision: Long? = null,
        expectedInstanceToken: String? = null,
        expectedRestoreEpoch: Long? = null,
        expectedDefinitionRevision: Long? = null
    ): Boolean {
        val sanitizedAlarm = alarm.sanitized()
        if (!canScheduleExactAlarms()) {
            requestWidgetUpdateIfNeeded(requestWidgetUpdate)
            return false
        }
        // v1.11.6 (roadmap N6): respect "Pause alarms" for snooze + quick-add
        // paths too — snoozing inside a pause window shouldn't sneak past it.
        if (preferencesManager.getCurrentSettings().isPaused()) {
            requestWidgetUpdateIfNeeded(requestWidgetUpdate)
            return false
        }

        val slotMutex = mainSlotMutexMap.computeIfAbsent(sanitizedAlarm.id) { Mutex() }
        return slotMutex.withLock {
            val previousTrigger = repository.getById(sanitizedAlarm.id)?.nextTriggerTime ?: 0L

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
                        return@withLock false
                    }
                    if (currentDbTrigger == targetGen.scheduledAt) {
                        val currentFireId = AlarmIncidentEvent.fireIdFor(sanitizedAlarm.id, currentDbTrigger)
                        if (targetGen.fireId != currentFireId) {
                            Log.i("AlarmScheduler", "Aborting stale scheduleAt for alarm ${sanitizedAlarm.id}: targetGen fireId ${targetGen.fireId} does not match current DB fireId $currentFireId")
                            return@withLock false
                        }
                    }
                }
            }

            if (routeAuthority.isOwnerRouteActive() && expectedRevision != null) {
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
                    return@withLock false
                }
            } else {
                repository.updateNextTrigger(sanitizedAlarm.id, triggerTime)
            }

            // Verify nextTriggerTime is still triggerTime before making OS AlarmManager call
            val currentDbTrigger = repository.getById(sanitizedAlarm.id)?.nextTriggerTime
            if (currentDbTrigger != null && currentDbTrigger != triggerTime) {
                Log.i("AlarmScheduler", "Aborting OS registration for alarm ${sanitizedAlarm.id}: nextTriggerTime updated to $currentDbTrigger while operation for $triggerTime was in-flight")
                repository.casUpdateNextTrigger(id = sanitizedAlarm.id, expectedTrigger = triggerTime, newTrigger = previousTrigger)
                return@withLock false
            }

            testOnPreOsRegistrationBarrier?.invoke(sanitizedAlarm.id, triggerTime)

            val success = try {
                scheduleAlarmClock(sanitizedAlarm.id, triggerTime, expectedRevision = expectedRevision)
            } catch (e: Exception) {
                Log.e("AlarmScheduler", "OS AlarmManager registration threw exception for alarm ${sanitizedAlarm.id}", e)
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
                return@withLock false
            }
            DirectBootAlarmCache.saveIfEarlier(context, sanitizedAlarm, triggerTime)
            try { scheduleSupportingWork(sanitizedAlarm, triggerTime) } catch (e: Exception) { Log.w("AlarmScheduler", "Failed scheduleSupportingWork for alarm ${sanitizedAlarm.id}", e) }
            requestWidgetUpdateIfNeeded(requestWidgetUpdate)
            try { syncBedtimeDndRule() } catch (e: Exception) { Log.w("AlarmScheduler", "Failed syncBedtimeDndRule for alarm ${sanitizedAlarm.id}", e) }
            true
        }
    }

    /**
     * Reschedule all enabled alarms. Called after boot and app update.
     * Preserves existing future nextTriggerTime (e.g., from skip-next or snooze)
     * to avoid undoing user actions.
     */
    suspend fun rescheduleAll(forceRecalculate: Boolean = false) {
        rescheduleAllInBatches(forceRecalculate = forceRecalculate)
    }

    /**
     * Reschedule enabled alarms in batches so WorkManager recovery paths can
     * handle large alarm libraries without receiver ANRs or redundant widget
     * refreshes after every single AlarmManager registration.
     *
     * @return number of enabled alarms processed.
     */
    suspend fun rescheduleAllInBatches(
        forceRecalculate: Boolean = false,
        batchSize: Int = 25
    ): Int {
        val now = System.currentTimeMillis()
        val enabledAlarms = repository.getEnabled()
        val safeBatchSize = batchSize.coerceAtLeast(1)
        DirectBootAlarmCache.clear(context)
        enabledAlarms.chunked(safeBatchSize).forEach { batch ->
            batch.forEach { alarm ->
                if (DirectBootAlarmCache.consumeFiredOneShotMarker(context, alarm, now)) {
                    repository.setEnabled(alarm.id, enabled = false, nextTrigger = 0)
                    cancelScheduledEntries(alarm.id)
                    return@forEach
                }
                if (!forceRecalculate && alarm.nextTriggerTime > now) {
                    // Existing future trigger is still valid - just re-register with AlarmManager
                    scheduleExistingTrigger(
                        alarm = alarm,
                        triggerTime = alarm.nextTriggerTime,
                        requestWidgetUpdate = false
                    )
                } else if (forceRecalculate && alarm.nextTriggerTime > now &&
                    alarm.nextTriggerTime < calculator.calculate(alarm.sanitized())
                ) {
                    // A stored trigger earlier than the natural next occurrence
                    // is a live snooze (or weather/smart early fire). Forced
                    // recalculation passes — including the automatic dashboard
                    // location/solar refresh — must not silently un-snooze it.
                    // Snoozes are epoch-anchored, so they stay correct across
                    // TIME_SET/TIMEZONE_CHANGED too.
                    scheduleExistingTrigger(
                        alarm = alarm,
                        triggerTime = alarm.nextTriggerTime,
                        requestWidgetUpdate = false
                    )
                } else {
                    // Needs recalculation (past or unset trigger time)
                    schedule(alarm, requestWidgetUpdate = false)
                }
            }
            yield()
        }
        WidgetUpdater.requestUpdate(context)
        syncBedtimeDndRule()
        return enabledAlarms.size
    }

    /**
     * Internal helper to schedule with AlarmManager at a specific time without recalculating.
     */
    private suspend fun scheduleExistingTrigger(
        alarm: Alarm,
        triggerTime: Long,
        requestWidgetUpdate: Boolean = true
    ) {
        val sanitizedAlarm = alarm.sanitized()
        if (!canScheduleExactAlarms()) {
            cancelScheduledEntries(sanitizedAlarm.id)
            repository.updateNextTrigger(sanitizedAlarm.id, 0)
            requestWidgetUpdateIfNeeded(requestWidgetUpdate)
            return
        }

        val settings = preferencesManager.getCurrentSettings()
        val vacationAdjustment = VacationAlarmPolicy.adjustTrigger(
            alarm = sanitizedAlarm,
            initialTriggerTime = triggerTime,
            settings = settings,
            calculateFrom = calculator::calculate
        )

        scheduleAt(
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
    suspend fun handleAlarmFired(alarmId: Long, firedScheduledAt: Long = 0L) {
        val alarm = repository.getById(alarmId) ?: return

        if (!alarm.isRecurringSchedule) {
            // One-shot alarm: disable after firing. Also tear down any still-armed
            // exact-alarm entry. A normally-fired one-shot's PendingIntent was
            // already consumed by AlarmManager (this is a no-op), but a smart-wake
            // *early* fire bypasses that PendingIntent, so without this the original
            // exact alarm would fire a second time at the unmodified trigger minute.
            cancelMainScheduledAlarm(alarmId)
            repository.setEnabled(alarmId, enabled = false, nextTrigger = 0)
            syncBedtimeDndRule()
        } else {
            // Repeating alarm: schedule next occurrence (FLAG_UPDATE_CURRENT
            // replaces any stale early-fire PendingIntent with the same requestCode).
            schedule(
                alarm,
                notBeforeMillis = if (firedScheduledAt > 0L) firedScheduledAt + 60_000L else 0L
            )
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
        expectedScheduleRevision: Long? = null
    ): Boolean {
        val alarm = runBlocking { repository.getById(alarmId)?.sanitized() }
            ?: run {
                Log.w("AlarmScheduler", "Alarm $alarmId missing; rejecting SmartWake cancellation")
                return false
            }

        val authority = runBlocking { repository.getAuthorityByAlarmId(alarmId) }

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
            if (alarm.nextTriggerTime > expectedTriggerTime ||
                (authority != null && authority.desiredTriggerMillis != null && authority.desiredTriggerMillis > expectedTriggerTime)) {
                Log.w("AlarmScheduler", "Stale SmartWake cancellation ignored for alarm $alarmId (current ${alarm.nextTriggerTime} > expected $expectedTriggerTime)")
                return false
            }
        }

        val pendingIntent = lookupPendingIntentForCancel(alarmId)
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
            return true
        }
        return false
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

    /**
     * Walks a repeating alarm's trigger past consecutive holidays (max 30
     * candidates, covering multi-week national holiday clusters). Returns null
     * when every candidate was a holiday — the caller suppresses that occurrence.
     */
    private suspend fun advanceTriggerPastHolidays(
        alarm: Alarm,
        startTrigger: Long,
        zone: ZoneId
    ): Long? {
        var trigger = startTrigger
        var attempts = 0
        while (attempts < 30) {
            val date = Instant.ofEpochMilli(trigger).atZone(zone).toLocalDate()
            if (!holidayRepository.isHoliday(date)) return trigger
            trigger = calculator.calculate(alarm, date.plusDays(1).atStartOfDay(zone))
            attempts++
        }
        val finalDate = Instant.ofEpochMilli(trigger).atZone(zone).toLocalDate()
        return if (holidayRepository.isHoliday(finalDate)) null else trigger
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
            false
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

    private suspend fun scheduleAlarmClock(
        alarmId: Long,
        triggerTime: Long,
        expectedRevision: Long? = null
    ): Boolean {
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

    private fun cancelScheduledEntries(
        alarmId: Long,
        includeFollowUpWorkers: Boolean = false,
        targetGen: com.sysadmindoc.alarmclock.service.AlarmGeneration? = null,
        expectedRevision: Long? = null
    ) {
        val slotMutex = mainSlotMutexMap.computeIfAbsent(alarmId) { Mutex() }
        runBlocking {
            slotMutex.withLock {
                if (targetGen != null) {
                    val currentAlarm = repository.getById(alarmId)
                    if (currentAlarm != null && currentAlarm.nextTriggerTime > targetGen.scheduledAt) {
                        Log.i("AlarmScheduler", "Aborting stale cancelScheduledEntries for alarm $alarmId: targetGen $targetGen is older than current DB trigger ${currentAlarm.nextTriggerTime}")
                        return@withLock
                    }
                }
                val owner = if (routeAuthority.isOwnerRouteActive()) scheduleAuthorityOwnerProvider?.get() else null
                if (owner != null) {
                    val actionKey = "act-cancel-$alarmId-${System.currentTimeMillis()}"
                    owner.executeOperation(
                        alarmId = alarmId,
                        desiredOperation = "CANCEL",
                        desiredTriggerMillis = null,
                        actionKey = actionKey,
                        expectedRevision = expectedRevision
                    )
                } else {
                    applyRawOsCancel(alarmId)
                }
            }
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
