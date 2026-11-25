package com.sysadmindoc.alarmclock.domain

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.room.withTransaction
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.sysadmindoc.alarmclock.data.backup.JournalDiskState
import com.sysadmindoc.alarmclock.data.backup.PreparedRestoreRecoveryContext
import com.sysadmindoc.alarmclock.data.backup.RestoreJournalCoordinator
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
import kotlin.coroutines.coroutineContext
import com.sysadmindoc.alarmclock.domain.evaluator.HolidayPolicy
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
    private val routeAuthority: ProductionSchedulingRouteAuthority = ProductionSchedulingRouteAuthority(),
    private val directBootNormalProjectorProvider: javax.inject.Provider<com.sysadmindoc.alarmclock.directboot.DirectBootNormalProjector>? = null
) {
    @androidx.annotation.VisibleForTesting
    internal var testAlarmManager: AlarmManager? = null

    @androidx.annotation.VisibleForTesting
    internal var testBootstrapAdapterProvider: (() -> ScheduleAuthorityBootstrapAdapter)? = null

    @androidx.annotation.VisibleForTesting
    internal var testLocalIncarnationEvidenceReaderProvider: (() -> com.sysadmindoc.alarmclock.data.local.LocalIncarnationEvidenceReader)? = null

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

    sealed interface DirectBootProjectionHookOutcome {
        data object SkippedLegacyOnly : DirectBootProjectionHookOutcome
        data object ProjectorUnavailable : DirectBootProjectionHookOutcome
        data class Succeeded(
            val result: com.sysadmindoc.alarmclock.directboot.DirectBootNormalProjectionResult.Success
        ) : DirectBootProjectionHookOutcome
        data class Blocked(
            val result: com.sysadmindoc.alarmclock.directboot.DirectBootNormalProjectionResult
        ) : DirectBootProjectionHookOutcome
        data class Failed(
            val result: com.sysadmindoc.alarmclock.directboot.DirectBootNormalProjectionResult?,
            val cause: Throwable? = null
        ) : DirectBootProjectionHookOutcome
    }

    @androidx.annotation.VisibleForTesting
    internal var lastDirectBootProjectionOutcome: DirectBootProjectionHookOutcome? = null

    @androidx.annotation.VisibleForTesting
    internal var onDirectBootProjectionOutcome: ((DirectBootProjectionHookOutcome) -> Unit)? = null

    @androidx.annotation.VisibleForTesting
    internal var v2RegistrationCapableProvider: () -> Boolean = { false }

    @androidx.annotation.VisibleForTesting
    internal var testDirectBootReconcilerHook: ((Context) -> com.sysadmindoc.alarmclock.directboot.ReconcilerResult)? = null

    @androidx.annotation.VisibleForTesting
    internal var lastDirectBootReconcileResult: com.sysadmindoc.alarmclock.directboot.ReconcilerResult? = null

    internal suspend fun runDirectBootProjectionHook(
        reason: String = "CE_MUTATION"
    ): DirectBootProjectionHookOutcome {
        val outcome: DirectBootProjectionHookOutcome = if (!routeAuthority.isOwnerRouteActive()) {
            lastDirectBootReconcileResult = null
            DirectBootProjectionHookOutcome.SkippedLegacyOnly
        } else {
            val projector = directBootNormalProjectorProvider?.get()
            if (projector == null) {
                Log.w("AlarmScheduler", "DirectBootNormalProjector unavailable during $reason under active owner route")
                lastDirectBootReconcileResult = null
                DirectBootProjectionHookOutcome.ProjectorUnavailable
            } else {
                try {
                    when (val result = projector.project()) {
                        is com.sysadmindoc.alarmclock.directboot.DirectBootNormalProjectionResult.Success -> {
                            if (v2RegistrationCapableProvider()) {
                                val recResult = testDirectBootReconcilerHook?.invoke(context)
                                    ?: com.sysadmindoc.alarmclock.directboot.DirectBootRegistrationReconciler
                                        .getInstance(context)
                                        .reconcile(
                                            com.sysadmindoc.alarmclock.directboot.RegistrationRequest(
                                                requestId = "P1_$reason",
                                                reason = reason
                                            )
                                        )
                                lastDirectBootReconcileResult = recResult
                            } else {
                                lastDirectBootReconcileResult = null
                            }
                            DirectBootProjectionHookOutcome.Succeeded(result)
                        }
                        is com.sysadmindoc.alarmclock.directboot.DirectBootNormalProjectionResult.CoordinatorBlocked,
                        is com.sysadmindoc.alarmclock.directboot.DirectBootNormalProjectionResult.FingerprintMismatch -> {
                            lastDirectBootReconcileResult = null
                            Log.i("AlarmScheduler", "DirectBoot projection blocked during $reason: $result")
                            DirectBootProjectionHookOutcome.Blocked(result)
                        }
                        is com.sysadmindoc.alarmclock.directboot.DirectBootNormalProjectionResult.DeWriteRejected,
                        is com.sysadmindoc.alarmclock.directboot.DirectBootNormalProjectionResult.PreWriteFailure,
                        is com.sysadmindoc.alarmclock.directboot.DirectBootNormalProjectionResult.DeCommitFailed,
                        is com.sysadmindoc.alarmclock.directboot.DirectBootNormalProjectionResult.CancellationAfterWrite,
                        is com.sysadmindoc.alarmclock.directboot.DirectBootNormalProjectionResult.AmbiguousOutcome -> {
                            lastDirectBootReconcileResult = null
                            Log.e("AlarmScheduler", "DirectBoot projection failed during $reason: $result")
                            DirectBootProjectionHookOutcome.Failed(result)
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    lastDirectBootReconcileResult = null
                    Log.e("AlarmScheduler", "DirectBoot projection threw during $reason", e)
                    DirectBootProjectionHookOutcome.Failed(result = null, cause = e)
                }
            }
        }
        lastDirectBootProjectionOutcome = outcome
        onDirectBootProjectionOutcome?.invoke(outcome)
        return outcome
    }

    companion object {
        const val EXTRA_ALARM_ID = "alarm_id"
        const val EXTRA_ALARM_FIRE_ID = "alarm_fire_id"
        const val EXTRA_SCHEDULED_AT = "scheduled_at"
        const val EXTRA_ISSUE_TOKEN = "issue_token"
        const val EXTRA_RESTORE_EPOCH = "restore_epoch"
        const val EXTRA_QUICK_ALARM = "EXTRA_QUICK_ALARM"

        private const val REQUEST_CODE_ISSUED_OCCURRENCE = 80001
        const val LEGACY_V1_CANCEL_TOKEN_PREFIX = "legacy-v1-cancel:"

        private val SNOW_ICE_CODES = setOf(56, 57, 66, 67, 71, 73, 75, 77, 85, 86)
        fun isSnowOrIceCode(code: Int): Boolean = code in SNOW_ICE_CODES

        suspend fun persistPendingLegacyV1CancelWitness(
            repository: AlarmRepository,
            alarm: Alarm,
            expectedScheduledAt: Long
        ): String {
            if (alarm.id <= 0L || alarm.isQuickAlarm || alarm.generation <= 0) {
                throw IllegalStateException("Invalid or non-versioned alarm for Legacy V1 cancel witness: id=${alarm.id}, gen=${alarm.generation}, quick=${alarm.isQuickAlarm}")
            }
            return repository.database.withTransaction {
                val dao = repository.durableOperationIntentDao()
                val unresolved = dao.getUnresolvedIntentsByAlarmId(alarm.id)
                val nonLegacy = unresolved.filter { !it.operationToken.startsWith(LEGACY_V1_CANCEL_TOKEN_PREFIX) }
                if (nonLegacy.isNotEmpty()) {
                    throw IllegalStateException(
                        "Unresolved non-Legacy intent(s) block Legacy V1 cancel witness for alarm ${alarm.id}: ${nonLegacy.map { it.operationToken }}"
                    )
                }
                unresolved.forEach { prior ->
                    val abortedRows = dao.updateIntentPhase(
                        prior.operationToken,
                        com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent.PHASE_ABORTED
                    )
                    if (abortedRows != 1) {
                        throw IllegalStateException(
                            "Failed to abort prior Legacy V1 cancel witness ${prior.operationToken} (rows=$abortedRows)"
                        )
                    }
                }
                val expectedRestoreEpoch = repository.database.scheduleAuthorityDao()
                    .getMetaById(1)?.restoreEpoch?.takeIf { it > 0L }
                val authority = repository.getAuthorityByAlarmId(alarm.id)
                val token = "$LEGACY_V1_CANCEL_TOKEN_PREFIX${alarm.id}:${alarm.generation}:${java.util.UUID.randomUUID()}"
                val now = System.currentTimeMillis().coerceAtLeast(1L)
                val safeScheduledAt = expectedScheduledAt.coerceAtLeast(0L)
                val fallbackFireId = "legacy-cancel-" + alarm.id + "-" + alarm.generation
                val fireId = AlarmIncidentEvent.fireIdFor(alarm.id, safeScheduledAt)
                    .ifBlank { fallbackFireId }
                val intent = com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent(
                    operationToken = token,
                    alarmId = alarm.id,
                    operationEpoch = now,
                    incarnationId = fireId,
                    operationKind = "CANCEL",
                    expectedOwnerToken = "gen:${alarm.generation}",
                    expectedScheduleRevision = authority?.scheduleRevision ?: alarm.revision.toLong(),
                    expectedRestoreEpoch = expectedRestoreEpoch,
                    phase = com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent.PHASE_PENDING,
                    intentRevision = 1L,
                    originalFireId = fireId,
                    originalScheduledAt = expectedScheduledAt.takeIf { it > 0L }
                )
                dao.insertValidatedIntent(intent)
                dao.getIntentByToken(token)
                    ?: throw IllegalStateException("Legacy V1 cancel witness readback missing for $token")
                token
            }
        }

        suspend fun completePendingLegacyV1CancelWitness(
            repository: AlarmRepository,
            token: String?
        ): Boolean {
            if (token.isNullOrBlank()) return false
            return try {
                val rows = repository.durableOperationIntentDao().updateIntentPhase(
                    token,
                    com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent.PHASE_COMPLETED
                )
                if (rows != 1) {
                    Log.w("AlarmScheduler", "Failed to complete Legacy V1 cancel witness $token (rows=$rows)")
                    false
                } else {
                    true
                }
            } catch (e: Exception) {
                Log.w("AlarmScheduler", "Exception completing Legacy V1 cancel witness $token", e)
                false
            }
        }

        suspend fun abortPendingLegacyV1CancelWitness(
            repository: AlarmRepository,
            token: String?
        ): Boolean {
            if (token.isNullOrBlank()) return false
            return try {
                val rows = repository.durableOperationIntentDao().updateIntentPhase(
                    token,
                    com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent.PHASE_ABORTED
                )
                if (rows != 1) {
                    Log.w("AlarmScheduler", "Failed to abort Legacy V1 cancel witness $token (rows=$rows)")
                    false
                } else {
                    true
                }
            } catch (e: Exception) {
                Log.w("AlarmScheduler", "Exception aborting Legacy V1 cancel witness $token", e)
                false
            }
        }
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
    private suspend fun <T> withSchedulerRecoveryGate(
        onBlocked: suspend () -> T,
        block: suspend () -> T
    ): T = ScheduleAuthorityOwner.withAuthenticatedRecoveryGate(
        context = context,
        onBlocked = onBlocked,
        block = block
    )

    private suspend fun <T> withMainSlotLock(
        alarmId: Long,
        block: suspend () -> T
    ): T {
        val owner = scheduleAuthorityOwnerProvider?.get()
        val slotMutex = owner?.let { ScheduleAuthorityOwner.sharedSlotMutexForOrNull(it, alarmId) }
            ?: mainSlotMutexMap.computeIfAbsent(alarmId) { Mutex() }
        val activeLocks = coroutineContext[ScheduleAuthorityOwner.ActiveMainSlotLockContext]
        if (activeLocks != null && activeLocks.isHeld(slotMutex)) {
            return block()
        }
        return slotMutex.withLock {
            val updatedLocks = (activeLocks ?: ScheduleAuthorityOwner.ActiveMainSlotLockContext.empty()).plus(slotMutex)
            kotlinx.coroutines.withContext(updatedLocks) {
                block()
            }
        }
    }

    suspend fun schedule(
        alarm: Alarm,
        requestWidgetUpdate: Boolean = true,
        notBeforeMillis: Long = 0L
    ): ScheduleOutcome {
        val sanitizedPrecheck = alarm.sanitized()
        val preFetchedWeather = if (sanitizedPrecheck.isEnabled &&
            sanitizedPrecheck.weatherEarlyMinutes > 0 &&
            canScheduleExactAlarms()
        ) {
            prefetchWeatherOutsideRecoveryLock()
        } else {
            null
        }
        return withSchedulerRecoveryGate(
            onBlocked = { ScheduleOutcome.NoArm(alarm.id, "RESTORE_PREPARED_FENCE") }
        ) {
            scheduleAssumingRecoveryLock(
                alarm = alarm,
                requestWidgetUpdate = requestWidgetUpdate,
                notBeforeMillis = notBeforeMillis,
                preFetchedWeather = preFetchedWeather
            )
        }
    }

    private suspend fun resolveOriginAuthorityForScheduling(
        alarm: Alarm
    ): com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority? {
        val existing = repository.getAuthorityByAlarmId(alarm.id)
        if (existing != null || !routeAuthority.isOwnerRouteActive() || alarm.isQuickAlarm) {
            return existing
        }
        val localReader = testLocalIncarnationEvidenceReaderProvider?.invoke()
            ?: com.sysadmindoc.alarmclock.data.local.LocalIncarnationEvidenceReader(
                com.sysadmindoc.alarmclock.data.local.LocalIncarnationMarkerStore(context)
            )
        val localRead = localReader.readLocalEvidence()
        val localId = (localRead as? com.sysadmindoc.alarmclock.data.local.LocalEvidenceReadResult.Success)?.localId
        if (localId.isNullOrBlank()) {
            Log.w("AlarmScheduler", "Cannot bootstrap ScheduleAuthority for alarm ${alarm.id}: local incarnation evidence unavailable")
            return null
        }
        val bootstrapAdapter = testBootstrapAdapterProvider?.invoke()
            ?: ScheduleAuthorityBootstrapAdapter(
                database = repository.database,
                authorityDao = repository.database.scheduleAuthorityDao(),
                alarmRepository = repository,
                calculator = calculator,
                localReader = localReader,
                preferencesManager = preferencesManager,
                holidayRepository = holidayRepository
            )
        return when (bootstrapAdapter.bootstrapAuthorityForAlarm(alarm.id, localId)) {
            BootstrapDecision.RECONCILE,
            BootstrapDecision.ADOPT -> repository.getAuthorityByAlarmId(alarm.id)
            BootstrapDecision.QUARANTINE -> {
                Log.w("AlarmScheduler", "ScheduleAuthority bootstrap quarantined alarm ${alarm.id}")
                null
            }
        }
    }

    private suspend fun scheduleAssumingRecoveryLock(
        alarm: Alarm,
        requestWidgetUpdate: Boolean = true,
        notBeforeMillis: Long = 0L,
        preFetchedWeather: com.sysadmindoc.alarmclock.data.remote.WeatherResponse? = null,
        projectToDirectBoot: Boolean = true
    ): ScheduleOutcome {
        val sanitizedAlarm = alarm.sanitized()
        val expectedGen = sanitizedAlarm.generation.takeIf { !sanitizedAlarm.isQuickAlarm && it > 0 }
        val originAuthority = resolveOriginAuthorityForScheduling(sanitizedAlarm)
        val originRevision = originAuthority?.scheduleRevision
        val originInstanceToken = originAuthority?.instanceToken
        val originRestoreEpoch = originAuthority?.restoreEpoch
        val originEpochFence = currentRestoreEpochOrNull()?.takeIf { it > 0L }
        val originDefinitionRevision = originAuthority?.definitionRevision

        if (routeAuthority.isOwnerRouteActive() && originAuthority == null) {
            Log.w("AlarmScheduler", "Aborting schedule() for alarm ${sanitizedAlarm.id}: Owner route active but authority record absent")
            return ScheduleOutcome.RegistrationFailed(sanitizedAlarm.id, IllegalStateException("Owner route active but authority record absent"))
        }

        if (!sanitizedAlarm.isEnabled) {
            val cancelled = cancelScheduledEntriesAssumingRecoveryLock(
                sanitizedAlarm.id,
                expectedRevision = originRevision,
                expectedGeneration = expectedGen,
                expectedRestoreEpoch = originEpochFence
            )
            if (!cancelled) {
                return ScheduleOutcome.RegistrationFailed(
                    sanitizedAlarm.id,
                    IllegalStateException("OS cancel failed")
                )
            }
            if (projectToDirectBoot) runDirectBootProjectionHook("SCHEDULE_DISABLED")
            return ScheduleOutcome.NoArm(sanitizedAlarm.id, "DISABLED")
        }

        if (!canScheduleExactAlarms()) {
            val cancelled = cancelScheduledEntriesAssumingRecoveryLock(
                sanitizedAlarm.id,
                expectedRevision = originRevision,
                expectedGeneration = expectedGen,
                expectedRestoreEpoch = originEpochFence
            )
            if (!cancelled) {
                return ScheduleOutcome.RegistrationFailed(
                    sanitizedAlarm.id,
                    IllegalStateException("OS cancel failed")
                )
            }
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
            if (projectToDirectBoot) runDirectBootProjectionHook("SCHEDULE_PERMISSION_BLOCKED")
            return ScheduleOutcome.PermissionBlocked(sanitizedAlarm.id, "EXACT_ALARM_PERMISSION_REVOKED")
        }

        val currentSettings = preferencesManager.getCurrentSettings()
        if (currentSettings.isPaused()) {
            val cancelled = cancelScheduledEntriesAssumingRecoveryLock(
                sanitizedAlarm.id,
                expectedRevision = originRevision,
                expectedGeneration = expectedGen,
                expectedRestoreEpoch = originEpochFence
            )
            if (!cancelled) {
                return ScheduleOutcome.RegistrationFailed(
                    sanitizedAlarm.id,
                    IllegalStateException("OS cancel failed")
                )
            }
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
            if (projectToDirectBoot) runDirectBootProjectionHook("SCHEDULE_PAUSED")
            return ScheduleOutcome.NoArm(sanitizedAlarm.id, "PAUSED")
        }

        val isRepeatingNormalAlarm = !sanitizedAlarm.isQuickAlarm &&
            sanitizedAlarm.scheduleKind == Alarm.SCHEDULE_KIND_REPEATING

        val exceptions = if (isRepeatingNormalAlarm) {
            try {
                repository.database.scheduleExceptionDao().getExceptionsForAlarmGeneration(
                    sanitizedAlarm.id,
                    sanitizedAlarm.generation
                )
            } catch (e: Exception) {
                Log.e("AlarmScheduler", "Failed to load schedule exceptions for alarm ${sanitizedAlarm.id}; aborting schedule to fail-closed", e)
                return ScheduleOutcome.RegistrationFailed(sanitizedAlarm.id, e)
            }
        } else {
            emptyList()
        }

        val requiresHolidayOrExceptionEvaluation = isRepeatingNormalAlarm && (
            sanitizedAlarm.canonicalHolidayPolicy() != HolidayPolicy.FOLLOW_BASE ||
            exceptions.isNotEmpty()
        )

        var triggerTime = if (requiresHolidayOrExceptionEvaluation) {
            val schedulingZone = sanitizedAlarm.schedulingZone(ZoneId.systemDefault())
            val notBefore = if (notBeforeMillis > System.currentTimeMillis()) notBeforeMillis else System.currentTimeMillis()
            val notBeforeDate = Instant.ofEpochMilli(notBefore).atZone(schedulingZone).toLocalDate()
            val authorityWindow = try {
                holidayRepository.captureAuthorityWindow(
                    startDate = notBeforeDate,
                    endDateInclusive = notBeforeDate.plusDays(MAX_HOLIDAY_EVALUATION_WINDOW_DAYS - 1L)
                )
            } catch (e: Exception) {
                Log.e("AlarmScheduler", "Failed to capture holiday authority window for alarm ${sanitizedAlarm.id}; aborting schedule to fail-closed", e)
                return ScheduleOutcome.RegistrationFailed(sanitizedAlarm.id, e)
            }
            val classifier = authorityWindow.asLocalHolidayClassifier()
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
            val cancelled = cancelScheduledEntriesAssumingRecoveryLock(
                sanitizedAlarm.id,
                expectedRevision = originRevision,
                expectedGeneration = expectedGen,
                expectedRestoreEpoch = originEpochFence
            )
            if (!cancelled) {
                return ScheduleOutcome.RegistrationFailed(
                    sanitizedAlarm.id,
                    IllegalStateException("OS cancel failed")
                )
            }
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
            if (projectToDirectBoot) runDirectBootProjectionHook("SCHEDULE_NO_NEXT_OCCURRENCE")
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
                val cancelled = cancelScheduledEntriesAssumingRecoveryLock(
                    sanitizedAlarm.id,
                    expectedRevision = originRevision,
                    expectedGeneration = expectedGen,
                    expectedRestoreEpoch = originEpochFence
                )
                if (!cancelled) {
                    return ScheduleOutcome.RegistrationFailed(
                        sanitizedAlarm.id,
                        IllegalStateException("OS cancel failed")
                    )
                }
                repository.updateNextTrigger(sanitizedAlarm.id, 0)
                requestWidgetUpdateIfNeeded(requestWidgetUpdate)
                if (projectToDirectBoot) runDirectBootProjectionHook("SCHEDULE_HOLIDAY_SKIPPED")
                return ScheduleOutcome.NoArm(sanitizedAlarm.id, "HOLIDAY_SKIPPED")
            }
        }

        if (sanitizedAlarm.weatherEarlyMinutes > 0 && triggerTime > 0) {
            val weatherSettings = currentSettings
            val lat = weatherSettings.lastKnownLatitude
            val lng = weatherSettings.lastKnownLongitude
            val haveLocation = lat != 0.0 || lng != 0.0
            val weather = preFetchedWeather ?: weatherRepository.getCachedWeather(
                latitude = lat.takeIf { haveLocation },
                longitude = lng.takeIf { haveLocation }
            )
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

        return scheduleAtAssumingRecoveryLock(
            sanitizedAlarm,
            triggerTime,
            requestWidgetUpdate = requestWidgetUpdate,
            expectedRevision = originRevision,
            expectedInstanceToken = originInstanceToken,
            expectedRestoreEpoch = originRestoreEpoch,
            expectedDefinitionRevision = originDefinitionRevision,
            expectedMetaRestoreEpoch = originEpochFence,
            projectToDirectBoot = projectToDirectBoot
        )
    }

    private suspend fun prefetchWeatherOutsideRecoveryLock(): com.sysadmindoc.alarmclock.data.remote.WeatherResponse? {
        if (coroutineContext[PreparedRestoreRecoveryContext] != null) return null
        val disk = RestoreJournalCoordinator.readVerifiedDiskState(context)
        val allowed = when (disk) {
            is JournalDiskState.Absent -> true
            is JournalDiskState.Verified -> disk.phase == RestoreJournalCoordinator.PHASE_COMMITTED
            is JournalDiskState.InvalidOrBlocked -> false
        }
        if (!allowed) return null

        val weatherSettings = preferencesManager.getCurrentSettings()
        if (weatherSettings.isPaused()) return null
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
        return weather
    }

    suspend fun cancelSuspending(alarmId: Long): Boolean =
        withSchedulerRecoveryGate(
            onBlocked = { false }
        ) {
            val originAuthority = repository.getAuthorityByAlarmId(alarmId)
            val originRevision = originAuthority?.scheduleRevision
            val originEpochFence = currentRestoreEpochOrNull()?.takeIf { it > 0L }
            val cancelled = cancelScheduledEntriesAssumingRecoveryLock(
                alarmId = alarmId,
                includeFollowUpWorkers = true,
                expectedRevision = originRevision,
                expectedRestoreEpoch = originEpochFence
            )
            if (cancelled) {
                AlarmRuntimeState.clear(context, alarmId)
                WidgetUpdater.requestUpdate(context)
                runDirectBootProjectionHook("CANCEL_SUSPENDING")
            }
            cancelled
        }

    fun cancel(alarmId: Long) {
        ScheduleAuthorityOwner.withAuthenticatedSyncRecoveryGate(
            context = context,
            onBlocked = { Unit }
        ) {
            val originAuthority = repository.getAuthorityByAlarmId(alarmId)
            val originRevision = originAuthority?.scheduleRevision
            val originEpochFence = currentRestoreEpochOrNull()?.takeIf { it > 0L }
            val cancelled = cancelScheduledEntriesAssumingRecoveryLock(
                alarmId = alarmId,
                includeFollowUpWorkers = true,
                expectedRevision = originRevision,
                expectedRestoreEpoch = originEpochFence
            )
            if (cancelled) {
                AlarmRuntimeState.clear(context, alarmId)
                WidgetUpdater.requestUpdate(context)
                runDirectBootProjectionHook("CANCEL")
            }
        }
    }

    suspend fun scheduleSnooze(
        alarm: Alarm,
        customMinutes: Int? = null,
        expectedRevision: Long? = null,
        expectedInstanceToken: String? = null,
        expectedRestoreEpoch: Long? = null,
        expectedDefinitionRevision: Long? = null
    ): ScheduleOutcome =
        withSchedulerRecoveryGate(
            onBlocked = { ScheduleOutcome.NoArm(alarm.id, "RESTORE_PREPARED_FENCE") }
        ) {
            val sanitizedAlarm = alarm.sanitized()
            val originAuthority = if (!sanitizedAlarm.isQuickAlarm) repository.getAuthorityByAlarmId(sanitizedAlarm.id) else null
            val originEpochFence = currentRestoreEpochOrNull()?.takeIf { it > 0L }
            val minutes = customMinutes ?: alarm.snoozeDurationMinutes
            val snoozeTime = System.currentTimeMillis() + (minutes * 60 * 1000L)
            if (!canScheduleExactAlarms()) {
                val fireId = AlarmIncidentEvent.fireIdFor(sanitizedAlarm.id, snoozeTime)
                recordScheduleIncident(
                    alarmId = sanitizedAlarm.id,
                    fireId = fireId,
                    triggerTime = snoozeTime,
                    status = AlarmIncidentEvent.STATUS_FAILED,
                    reasonCode = "SNOOZE_EXACT_PERMISSION_REVOKED"
                )
                return@withSchedulerRecoveryGate ScheduleOutcome.PermissionBlocked(sanitizedAlarm.id, "SNOOZE_EXACT_PERMISSION_REVOKED")
            }
            scheduleAtAssumingRecoveryLock(
                alarm = alarm,
                triggerTime = snoozeTime,
                expectedRevision = expectedRevision ?: originAuthority?.scheduleRevision,
                expectedInstanceToken = expectedInstanceToken ?: originAuthority?.instanceToken,
                expectedRestoreEpoch = expectedRestoreEpoch ?: originAuthority?.restoreEpoch,
                expectedDefinitionRevision = expectedDefinitionRevision ?: originAuthority?.definitionRevision,
                expectedMetaRestoreEpoch = expectedRestoreEpoch?.takeIf { it > 0L } ?: originEpochFence
            )
        }

    internal suspend fun scheduleSnoozeAt(
        alarm: Alarm,
        snoozeAtMillis: Long,
        targetGen: com.sysadmindoc.alarmclock.service.AlarmGeneration? = null,
        expectedRevision: Long? = null,
        expectedInstanceToken: String? = null,
        expectedRestoreEpoch: Long? = null,
        expectedDefinitionRevision: Long? = null
    ): ScheduleOutcome = withSchedulerRecoveryGate(
        onBlocked = { ScheduleOutcome.NoArm(alarm.id, "RESTORE_PREPARED_FENCE") }
    ) {
        val sanitizedAlarm = alarm.sanitized()
        val originAuthority = if (!sanitizedAlarm.isQuickAlarm) repository.getAuthorityByAlarmId(sanitizedAlarm.id) else null
        val originEpochFence = currentRestoreEpochOrNull()?.takeIf { it > 0L }
        if (snoozeAtMillis <= System.currentTimeMillis()) {
            val fireId = AlarmIncidentEvent.fireIdFor(sanitizedAlarm.id, snoozeAtMillis)
            recordScheduleIncident(
                alarmId = sanitizedAlarm.id,
                fireId = fireId,
                triggerTime = snoozeAtMillis,
                status = AlarmIncidentEvent.STATUS_FAILED,
                reasonCode = "SNOOZE_AT_INVALID_PAST_TARGET"
            )
            return@withSchedulerRecoveryGate ScheduleOutcome.NoArm(sanitizedAlarm.id, "SNOOZE_AT_INVALID_PAST_TARGET")
        }
        if (!canScheduleExactAlarms()) {
            val fireId = AlarmIncidentEvent.fireIdFor(sanitizedAlarm.id, snoozeAtMillis)
            recordScheduleIncident(
                alarmId = sanitizedAlarm.id,
                fireId = fireId,
                triggerTime = snoozeAtMillis,
                status = AlarmIncidentEvent.STATUS_FAILED,
                reasonCode = "SNOOZE_AT_EXACT_PERMISSION_REVOKED"
            )
            return@withSchedulerRecoveryGate ScheduleOutcome.PermissionBlocked(sanitizedAlarm.id, "SNOOZE_AT_EXACT_PERMISSION_REVOKED")
        }
        scheduleAtAssumingRecoveryLock(
            alarm = alarm,
            triggerTime = snoozeAtMillis,
            targetGen = targetGen,
            expectedRevision = expectedRevision ?: originAuthority?.scheduleRevision,
            expectedInstanceToken = expectedInstanceToken ?: originAuthority?.instanceToken,
            expectedRestoreEpoch = expectedRestoreEpoch ?: originAuthority?.restoreEpoch,
            expectedDefinitionRevision = expectedDefinitionRevision ?: originAuthority?.definitionRevision,
            expectedMetaRestoreEpoch = expectedRestoreEpoch?.takeIf { it > 0L } ?: originEpochFence
        )
    }

    suspend fun reconcileRegistration(
        alarm: Alarm,
        targetTrigger: Long,
        isOsConfirmed: Boolean = false,
        expectedRevision: Long? = null,
        expectedInstanceToken: String? = null,
        expectedRestoreEpoch: Long? = null,
        expectedDefinitionRevision: Long? = null
    ): ScheduleOutcome = withSchedulerRecoveryGate(
        onBlocked = { ScheduleOutcome.NoArm(alarm.id, "RESTORE_PREPARED_FENCE") }
    ) {
        val sanitizedAlarm = alarm.sanitized()
        val originAuthority = if (!sanitizedAlarm.isQuickAlarm) repository.getAuthorityByAlarmId(sanitizedAlarm.id) else null
        val originEpochFence = currentRestoreEpochOrNull()?.takeIf { it > 0L }
        if (isOsConfirmed && sanitizedAlarm.nextTriggerTime == targetTrigger) {
            return@withSchedulerRecoveryGate ScheduleOutcome.Armed(sanitizedAlarm.id, targetTrigger)
        }
        scheduleAtAssumingRecoveryLock(
            alarm = sanitizedAlarm,
            triggerTime = targetTrigger,
            expectedRevision = expectedRevision ?: originAuthority?.scheduleRevision,
            expectedInstanceToken = expectedInstanceToken ?: originAuthority?.instanceToken,
            expectedRestoreEpoch = expectedRestoreEpoch ?: originAuthority?.restoreEpoch,
            expectedDefinitionRevision = expectedDefinitionRevision ?: originAuthority?.definitionRevision,
            expectedMetaRestoreEpoch = expectedRestoreEpoch?.takeIf { it > 0L } ?: originEpochFence
        )
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
    ): ScheduleOutcome = withSchedulerRecoveryGate(
        onBlocked = { ScheduleOutcome.NoArm(alarm.id, "RESTORE_PREPARED_FENCE") }
    ) {
        scheduleAtAssumingRecoveryLock(
            alarm = alarm,
            triggerTime = triggerTime,
            requestWidgetUpdate = requestWidgetUpdate,
            targetGen = targetGen,
            expectedRevision = expectedRevision,
            expectedInstanceToken = expectedInstanceToken,
            expectedRestoreEpoch = expectedRestoreEpoch,
            expectedDefinitionRevision = expectedDefinitionRevision,
            expectedMetaRestoreEpoch = expectedRestoreEpoch?.takeIf { it > 0L }
        )
    }

    private suspend fun scheduleAtAssumingRecoveryLock(
        alarm: Alarm,
        triggerTime: Long,
        requestWidgetUpdate: Boolean = true,
        targetGen: com.sysadmindoc.alarmclock.service.AlarmGeneration? = null,
        expectedRevision: Long? = null,
        expectedInstanceToken: String? = null,
        expectedRestoreEpoch: Long? = null,
        expectedDefinitionRevision: Long? = null,
        expectedMetaRestoreEpoch: Long? = null,
        requireDbPresence: Boolean = false,
        projectToDirectBoot: Boolean = true
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

        val effectiveMetaRestoreEpoch = (expectedMetaRestoreEpoch
            ?: expectedRestoreEpoch?.takeIf { currentRestoreEpochOrNull() != null }
            ?: currentRestoreEpochOrNull())?.takeIf { it > 0L }

        val slotOutcome = withMainSlotLock(sanitizedAlarm.id) {
            val currentDbAlarm = repository.getById(sanitizedAlarm.id)
            if (currentDbAlarm == null && requireDbPresence && !sanitizedAlarm.isQuickAlarm) {
                Log.i("AlarmScheduler", "Aborting stale scheduleAt for alarm ${sanitizedAlarm.id}: alarm missing from DB")
                return@withMainSlotLock ScheduleOutcome.NoArm(sanitizedAlarm.id, "MISSING_ALARM")
            }
            if (currentDbAlarm != null && !sanitizedAlarm.isQuickAlarm) {
                if (!currentDbAlarm.isEnabled) {
                    Log.i("AlarmScheduler", "Aborting stale scheduleAt for alarm ${sanitizedAlarm.id}: alarm is disabled or deleted in DB")
                    return@withMainSlotLock ScheduleOutcome.NoArm(sanitizedAlarm.id, "DISABLED")
                }
                if (currentDbAlarm.generation > sanitizedAlarm.generation) {
                    Log.i("AlarmScheduler", "Aborting stale scheduleAt for alarm ${sanitizedAlarm.id}: current DB generation ${currentDbAlarm.generation} > requested generation ${sanitizedAlarm.generation}")
                    return@withMainSlotLock ScheduleOutcome.RegistrationFailed(sanitizedAlarm.id, IllegalStateException("Stale scheduleAt: generation mismatch"))
                }
                if (currentDbAlarm.generation == sanitizedAlarm.generation && currentDbAlarm.revision > sanitizedAlarm.revision) {
                    Log.i("AlarmScheduler", "Aborting stale scheduleAt for alarm ${sanitizedAlarm.id}: current DB revision ${currentDbAlarm.revision} > requested revision ${sanitizedAlarm.revision}")
                    return@withMainSlotLock ScheduleOutcome.RegistrationFailed(sanitizedAlarm.id, IllegalStateException("Stale scheduleAt: revision mismatch"))
                }
            }
            if (effectiveMetaRestoreEpoch != null) {
                val currentMetaEpoch = currentRestoreEpochOrNull()
                if (currentMetaEpoch == null || currentMetaEpoch != effectiveMetaRestoreEpoch) {
                    Log.i("AlarmScheduler", "Aborting stale scheduleAt for alarm ${sanitizedAlarm.id}: restoreEpoch $currentMetaEpoch != expected $effectiveMetaRestoreEpoch")
                    return@withMainSlotLock ScheduleOutcome.RegistrationFailed(sanitizedAlarm.id, IllegalStateException("Stale scheduleAt: restoreEpoch mismatch"))
                }
            }
            validateCallerAuthorityExpectationsIfLegacy(
                alarm = sanitizedAlarm,
                expectedRevision = expectedRevision,
                expectedInstanceToken = expectedInstanceToken,
                expectedRestoreEpoch = expectedRestoreEpoch,
                expectedDefinitionRevision = expectedDefinitionRevision
            )?.let { return@withMainSlotLock it }

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
                        return@withMainSlotLock ScheduleOutcome.RegistrationFailed(sanitizedAlarm.id, IllegalStateException("Stale scheduleAt: targetGen older than current DB trigger"))
                    }
                    if (currentDbTrigger == targetGen.scheduledAt) {
                        val currentFireId = AlarmIncidentEvent.fireIdFor(sanitizedAlarm.id, currentDbTrigger)
                        if (targetGen.fireId != currentFireId) {
                            Log.i("AlarmScheduler", "Aborting stale scheduleAt for alarm ${sanitizedAlarm.id}: targetGen fireId ${targetGen.fireId} does not match current DB fireId $currentFireId")
                            return@withMainSlotLock ScheduleOutcome.RegistrationFailed(sanitizedAlarm.id, IllegalStateException("Stale scheduleAt: fireId mismatch"))
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
                    return@withMainSlotLock ScheduleOutcome.RegistrationFailed(sanitizedAlarm.id, IllegalStateException("Authority fingerprint incomplete"))
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
                    return@withMainSlotLock ScheduleOutcome.RegistrationFailed(sanitizedAlarm.id, IllegalStateException("Authority fingerprint mismatch"))
                }
            } else {
                repository.updateNextTrigger(sanitizedAlarm.id, triggerTime)
            }

            val currentDbTrigger = repository.getById(sanitizedAlarm.id)?.nextTriggerTime
            if (currentDbTrigger != null && currentDbTrigger != triggerTime) {
                Log.i("AlarmScheduler", "Aborting OS registration for alarm ${sanitizedAlarm.id}: nextTriggerTime updated to $currentDbTrigger while operation for $triggerTime was in-flight")
                repository.casUpdateNextTrigger(id = sanitizedAlarm.id, expectedTrigger = triggerTime, newTrigger = previousTrigger)
                return@withMainSlotLock ScheduleOutcome.RegistrationFailed(sanitizedAlarm.id, IllegalStateException("nextTriggerTime updated while operation in-flight"))
            }

            testOnPreOsRegistrationBarrier?.invoke(sanitizedAlarm.id, triggerTime)

            val postBarrierAlarm = repository.getById(sanitizedAlarm.id)
            if (postBarrierAlarm == null && requireDbPresence && !sanitizedAlarm.isQuickAlarm) {
                Log.i("AlarmScheduler", "Aborting OS registration for alarm ${sanitizedAlarm.id}: DB alarm missing while operation was in-flight")
                return@withMainSlotLock ScheduleOutcome.NoArm(sanitizedAlarm.id, "MISSING_ALARM")
            }
            if (postBarrierAlarm != null && !sanitizedAlarm.isQuickAlarm) {
                if (!postBarrierAlarm.isEnabled) {
                    Log.i("AlarmScheduler", "Aborting OS registration for alarm ${sanitizedAlarm.id}: DB alarm became disabled while operation was in-flight")
                    return@withMainSlotLock ScheduleOutcome.RegistrationFailed(sanitizedAlarm.id, IllegalStateException("DB alarm disabled while operation in-flight"))
                }
                if (postBarrierAlarm.generation > sanitizedAlarm.generation) {
                    Log.i("AlarmScheduler", "Aborting OS registration for alarm ${sanitizedAlarm.id}: DB generation advanced to ${postBarrierAlarm.generation} while operation was in-flight")
                    return@withMainSlotLock ScheduleOutcome.RegistrationFailed(sanitizedAlarm.id, IllegalStateException("DB generation advanced while operation in-flight"))
                }
                if (postBarrierAlarm.generation == sanitizedAlarm.generation && postBarrierAlarm.revision > sanitizedAlarm.revision) {
                    Log.i("AlarmScheduler", "Aborting OS registration for alarm ${sanitizedAlarm.id}: DB revision advanced to ${postBarrierAlarm.revision} while operation was in-flight")
                    return@withMainSlotLock ScheduleOutcome.RegistrationFailed(sanitizedAlarm.id, IllegalStateException("DB revision advanced while operation in-flight"))
                }
            }
            if (effectiveMetaRestoreEpoch != null) {
                val postBarrierEpoch = currentRestoreEpochOrNull()
                if (postBarrierEpoch == null || postBarrierEpoch != effectiveMetaRestoreEpoch) {
                    Log.i("AlarmScheduler", "Aborting OS registration for alarm ${sanitizedAlarm.id}: restoreEpoch advanced from $effectiveMetaRestoreEpoch to $postBarrierEpoch")
                    if (!routeAuthority.isOwnerRouteActive()) {
                        repository.casUpdateNextTrigger(id = sanitizedAlarm.id, expectedTrigger = triggerTime, newTrigger = previousTrigger)
                    }
                    return@withMainSlotLock ScheduleOutcome.RegistrationFailed(sanitizedAlarm.id, IllegalStateException("restoreEpoch advanced while operation in-flight"))
                }
            }
            validateCallerAuthorityExpectationsIfLegacy(
                alarm = sanitizedAlarm,
                expectedRevision = expectedRevision,
                expectedInstanceToken = expectedInstanceToken,
                expectedRestoreEpoch = expectedRestoreEpoch,
                expectedDefinitionRevision = expectedDefinitionRevision
            )?.let { rejection ->
                if (!routeAuthority.isOwnerRouteActive()) {
                    repository.casUpdateNextTrigger(id = sanitizedAlarm.id, expectedTrigger = triggerTime, newTrigger = previousTrigger)
                }
                return@withMainSlotLock rejection
            }

            var registrationCause: Throwable? = null
            val success = try {
                scheduleAlarmClock(
                    alarmId = sanitizedAlarm.id,
                    triggerTime = triggerTime,
                    expectedRevision = expectedRevision,
                    expectedGeneration = sanitizedAlarm.generation,
                    expectedAlarmRevision = sanitizedAlarm.revision,
                    expectedRestoreEpoch = effectiveMetaRestoreEpoch
                )
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
                } else if (!routeAuthority.isOwnerRouteActive()) {
                    val rowsUpdated = repository.casUpdateNextTrigger(
                        id = sanitizedAlarm.id,
                        expectedTrigger = triggerTime,
                        newTrigger = previousTrigger
                    )
                    if (rowsUpdated > 0) {
                        requestWidgetUpdateIfNeeded(requestWidgetUpdate)
                        try { syncBedtimeDndRule() } catch (e: Exception) { Log.w("AlarmScheduler", "Failed syncBedtimeDndRule for alarm ${sanitizedAlarm.id}", e) }
                    } else {
                        Log.i("AlarmScheduler", "LEGACY_ONLY CAS rollback rejected for alarm ${sanitizedAlarm.id}: successor modified nextTriggerTime")
                    }
                } else {
                    Log.i("AlarmScheduler", "Incomplete authority fingerprint for alarm ${sanitizedAlarm.id}; suppressing destructive compensation")
                }
                return@withMainSlotLock if (registrationCause is SecurityException) {
                    ScheduleOutcome.PermissionBlocked(sanitizedAlarm.id, "EXACT_ALARM_PERMISSION_REVOKED")
                } else {
                    ScheduleOutcome.RegistrationFailed(
                        sanitizedAlarm.id,
                        registrationCause ?: IllegalStateException("OS AlarmManager registration unfulfilled")
                    )
                }
            }
            val resolvedEpoch = effectiveMetaRestoreEpoch ?: (currentRestoreEpochOrNull() ?: 0L)
            val postOsAlarm = repository.getById(sanitizedAlarm.id)
            val stillValidForSupportingEffects = (postOsAlarm == null && !requireDbPresence) ||
                sanitizedAlarm.isQuickAlarm ||
                (postOsAlarm != null &&
                    postOsAlarm.isEnabled &&
                    postOsAlarm.generation == sanitizedAlarm.generation &&
                    postOsAlarm.revision == sanitizedAlarm.revision)
            if (stillValidForSupportingEffects) {
                DirectBootAlarmCache.saveIfEarlier(context, sanitizedAlarm, triggerTime)
                try {
                    scheduleSupportingWork(
                        alarm = sanitizedAlarm,
                        triggerTime = triggerTime,
                        restoreEpoch = resolvedEpoch,
                        expectedRevision = expectedRevision
                    )
                } catch (e: Exception) {
                    Log.w("AlarmScheduler", "Failed scheduleSupportingWork for alarm ${sanitizedAlarm.id}", e)
                }
            } else {
                Log.i("AlarmScheduler", "Withholding supporting work & DirectBoot save for alarm ${sanitizedAlarm.id}: DB changed post-OS")
            }
            requestWidgetUpdateIfNeeded(requestWidgetUpdate)
            try { syncBedtimeDndRule() } catch (e: Exception) { Log.w("AlarmScheduler", "Failed syncBedtimeDndRule for alarm ${sanitizedAlarm.id}", e) }
            ScheduleOutcome.Armed(sanitizedAlarm.id, triggerTime)
        }
        if (projectToDirectBoot && slotOutcome is ScheduleOutcome.Armed) {
            runDirectBootProjectionHook("SCHEDULE_AT")
        }
        return slotOutcome
    }

    private suspend fun validateCallerAuthorityExpectationsIfLegacy(
        alarm: Alarm,
        expectedRevision: Long?,
        expectedInstanceToken: String?,
        expectedRestoreEpoch: Long?,
        expectedDefinitionRevision: Long?
    ): ScheduleOutcome.RegistrationFailed? {
        if (routeAuthority.isOwnerRouteActive() || alarm.isQuickAlarm) return null
        val hasAnyExpectation = expectedRevision != null ||
            expectedInstanceToken != null ||
            expectedRestoreEpoch != null ||
            expectedDefinitionRevision != null
        if (!hasAnyExpectation) return null

        val currentAuth = repository.getAuthorityByAlarmId(alarm.id)
        if (currentAuth == null) {
            if (expectedInstanceToken != null || expectedRevision != null || expectedDefinitionRevision != null) {
                Log.i("AlarmScheduler", "Aborting stale scheduleAt for alarm ${alarm.id}: ScheduleAuthority missing while authority expectations were supplied")
                return ScheduleOutcome.RegistrationFailed(alarm.id, IllegalStateException("Stale scheduleAt: authority missing"))
            }
            return null
        }
        if (expectedInstanceToken != null && currentAuth.instanceToken != expectedInstanceToken) {
            Log.i("AlarmScheduler", "Aborting stale scheduleAt for alarm ${alarm.id}: instanceToken mismatch")
            return ScheduleOutcome.RegistrationFailed(alarm.id, IllegalStateException("Stale scheduleAt: authority instanceToken mismatch"))
        }
        if (expectedRestoreEpoch != null && expectedRestoreEpoch > 0L && currentAuth.restoreEpoch != expectedRestoreEpoch) {
            Log.i("AlarmScheduler", "Aborting stale scheduleAt for alarm ${alarm.id}: authority restoreEpoch ${currentAuth.restoreEpoch} != expected $expectedRestoreEpoch")
            return ScheduleOutcome.RegistrationFailed(alarm.id, IllegalStateException("Stale scheduleAt: authority restoreEpoch mismatch"))
        }
        if (expectedRevision != null && currentAuth.scheduleRevision != expectedRevision) {
            Log.i("AlarmScheduler", "Aborting stale scheduleAt for alarm ${alarm.id}: scheduleRevision ${currentAuth.scheduleRevision} != expected $expectedRevision")
            return ScheduleOutcome.RegistrationFailed(alarm.id, IllegalStateException("Stale scheduleAt: authority revision mismatch"))
        }
        if (expectedDefinitionRevision != null && currentAuth.definitionRevision != expectedDefinitionRevision) {
            Log.i("AlarmScheduler", "Aborting stale scheduleAt for alarm ${alarm.id}: definitionRevision ${currentAuth.definitionRevision} != expected $expectedDefinitionRevision")
            return ScheduleOutcome.RegistrationFailed(alarm.id, IllegalStateException("Stale scheduleAt: authority definitionRevision mismatch"))
        }
        return null
    }

    suspend fun rescheduleAll(forceRecalculate: Boolean = false) {
        rescheduleAllInBatches(forceRecalculate = forceRecalculate)
    }

    suspend fun rescheduleAllInBatches(
        forceRecalculate: Boolean = false,
        batchSize: Int = 25
    ): ScheduleOutcome.BatchResult {
        val preFetchedWeather = if (coroutineContext[PreparedRestoreRecoveryContext] == null &&
            canScheduleExactAlarms()
        ) {
            val disk = RestoreJournalCoordinator.readVerifiedDiskState(context)
            val allowed = when (disk) {
                is JournalDiskState.Absent -> true
                is JournalDiskState.Verified -> disk.phase == RestoreJournalCoordinator.PHASE_COMMITTED
                is JournalDiskState.InvalidOrBlocked -> false
            }
            if (allowed && repository.getEnabledNormalAlarms().any { it.isEnabled && it.weatherEarlyMinutes > 0 }) {
                prefetchWeatherOutsideRecoveryLock()
            } else {
                null
            }
        } else {
            null
        }
        return withSchedulerRecoveryGate(
            onBlocked = {
                ScheduleOutcome.BatchResult(
                    armedCount = 0,
                    blockedCount = 1,
                    failedCount = 0,
                    noArmCount = 0,
                    outcomes = emptyMap()
                )
            }
        ) {
            rescheduleAllInBatchesAssumingRecoveryLock(
                forceRecalculate = forceRecalculate,
                batchSize = batchSize,
                preFetchedWeather = preFetchedWeather
            )
        }
    }

    private suspend fun rescheduleAllInBatchesAssumingRecoveryLock(
        forceRecalculate: Boolean = false,
        batchSize: Int = 25,
        preFetchedWeather: com.sysadmindoc.alarmclock.data.remote.WeatherResponse? = null
    ): ScheduleOutcome.BatchResult {
        val now = System.currentTimeMillis()
        val enabledAlarms = repository.getEnabledNormalAlarms()
        val safeBatchSize = batchSize.coerceAtLeast(1)
        val outcomesMap = mutableMapOf<Long, ScheduleOutcome>()
        var armed = 0
        var blocked = 0
        var failed = 0
        var noArm = 0

        val preBatchSnapshot = DirectBootAlarmCache.read(context)
        val enabledIds = enabledAlarms.map { it.id }.toSet()
        if (enabledAlarms.isEmpty() || (preBatchSnapshot != null && preBatchSnapshot.alarmId !in enabledIds)) {
            DirectBootAlarmCache.clear(context)
        }
        enabledAlarms.chunked(safeBatchSize).forEach { batch ->
            batch.forEach { alarm ->
                if (DirectBootAlarmCache.consumeFiredOneShotMarker(context, alarm, now)) {
                    repository.setEnabled(alarm.id, enabled = false, nextTrigger = 0)
                    cancelScheduledEntriesAssumingRecoveryLock(alarm.id)
                    val outcome = ScheduleOutcome.NoArm(alarm.id, "ONE_SHOT_CONSUMED")
                    outcomesMap[alarm.id] = outcome
                    noArm++
                    return@forEach
                }
                val outcome = if (!forceRecalculate && alarm.nextTriggerTime > now) {
                    scheduleExistingTriggerAssumingRecoveryLock(
                        alarm = alarm,
                        triggerTime = alarm.nextTriggerTime,
                        requestWidgetUpdate = false,
                        projectToDirectBoot = false
                    )
                } else if (forceRecalculate && alarm.nextTriggerTime > now &&
                    alarm.nextTriggerTime < calculator.calculate(alarm.sanitized())
                ) {
                    scheduleExistingTriggerAssumingRecoveryLock(
                        alarm = alarm,
                        triggerTime = alarm.nextTriggerTime,
                        requestWidgetUpdate = false,
                        projectToDirectBoot = false
                    )
                } else {
                    scheduleAssumingRecoveryLock(
                        alarm,
                        requestWidgetUpdate = false,
                        preFetchedWeather = preFetchedWeather,
                        projectToDirectBoot = false
                    )
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

        val issuedDao = repository.issuedOccurrenceDao()
        val activeDbAlarmIds = issuedDao.getAlarmIdsWithActiveOccurrences()
        activeDbAlarmIds.filter { it !in enabledIds }.forEach { disabledAlarmId ->
            val v2CancelOutcome = cancelTokenizedScheduledAlarmWithOutcome(disabledAlarmId)
            when (v2CancelOutcome) {
                TokenizedCancelOutcome.CANCELLED_AND_RETIRED -> {
                    // Targeted active occurrence & IssueSlot already retired atomically inside withMainV2SlotLock.
                }
                TokenizedCancelOutcome.NO_ACTIVE_SLOT_TOKEN -> {
                    // Slot has no active currentIssueToken and no non-legacy unresolved CANCEL intent;
                    // do not blanket-revoke uncancelled PREPARED/ADMITTED occurrences without OS cancel.
                }
                TokenizedCancelOutcome.OWNER_UNAVAILABLE,
                TokenizedCancelOutcome.ISOLATED_NON_LEGACY,
                TokenizedCancelOutcome.OS_OUTCOME_UNKNOWN -> {
                    Log.w(
                        "AlarmScheduler",
                        "Preserving V2 witnesses for disabled alarm $disabledAlarmId (v2CancelOutcome=$v2CancelOutcome)"
                    )
                }
            }
            // Under LEGACY_ONLY, cancelScheduledEntries cancels only disjoint Legacy V1 PI (data=null).
            cancelScheduledEntriesAssumingRecoveryLock(disabledAlarmId)
        }

        try {
            val durableIntentDao = repository.durableOperationIntentDao()
            val unresolvedCancelIntents = durableIntentDao.getUnresolvedCancellationIntents()
            unresolvedCancelIntents.forEach { cancelIntent ->
                when (com.sysadmindoc.alarmclock.domain.evaluator.DurableCancelIntentValidator.classifyCancelIntentRevision(cancelIntent)) {
                    com.sysadmindoc.alarmclock.domain.evaluator.DurableCancelIntentValidator.CancelIntentRevisionClass.LEGACY_REV1 -> {
                        if (cancelIntent.operationToken.startsWith(LEGACY_V1_CANCEL_TOKEN_PREFIX)) {
                            val expectedGeneration = cancelIntent.expectedOwnerToken
                                ?.removePrefix("gen:")
                                ?.toIntOrNull()
                            if (expectedGeneration == null || expectedGeneration <= 0) {
                                failed++
                                return@forEach
                            }
                            val currentAlarm = repository.getById(cancelIntent.alarmId)
                            if (currentAlarm == null) {
                                Log.w(
                                    "AlarmScheduler",
                                    "Fail-closed on Legacy V1 cancel witness ${cancelIntent.operationToken}: missing DB alarm ${cancelIntent.alarmId}"
                                )
                                failed++
                                return@forEach
                            }
                            if (cancelIntent.expectedRestoreEpoch != null) {
                                val currentEpoch = currentRestoreEpochOrNull()
                                if (currentEpoch == null || currentEpoch <= 0L) {
                                    failed++
                                    return@forEach
                                }
                                if (currentEpoch != cancelIntent.expectedRestoreEpoch) {
                                    if (!settleLegacyV1CancelWitnessPhase(
                                            durableIntentDao = durableIntentDao,
                                            operationToken = cancelIntent.operationToken,
                                            targetPhase = com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent.PHASE_EXPIRED
                                        )
                                    ) {
                                        failed++
                                    }
                                    return@forEach
                                }
                            }
                            if (currentAlarm.isQuickAlarm) {
                                failed++
                                return@forEach
                            }
                            if (currentAlarm.generation != expectedGeneration || currentAlarm.isEnabled) {
                                if (!settleLegacyV1CancelWitnessPhase(
                                        durableIntentDao = durableIntentDao,
                                        operationToken = cancelIntent.operationToken,
                                        targetPhase = com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent.PHASE_ABORTED
                                    )
                                ) {
                                    failed++
                                }
                                return@forEach
                            }
                            val retrySuccess = cancelScheduledEntriesAssumingRecoveryLock(
                                alarmId = cancelIntent.alarmId,
                                includeFollowUpWorkers = true,
                                expectedGeneration = expectedGeneration,
                                expectedRestoreEpoch = cancelIntent.expectedRestoreEpoch
                            )
                            if (retrySuccess) {
                                AlarmRuntimeState.clear(context, cancelIntent.alarmId)
                                WidgetUpdater.requestUpdate(context)
                                if (!settleLegacyV1CancelWitnessPhase(
                                        durableIntentDao = durableIntentDao,
                                        operationToken = cancelIntent.operationToken,
                                        targetPhase = com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent.PHASE_COMPLETED
                                    )
                                ) {
                                    failed++
                                }
                            } else {
                                failed++
                            }
                        } else if (cancelIntent.originalScheduledAt != null) {
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
                    com.sysadmindoc.alarmclock.domain.evaluator.DurableCancelIntentValidator.CancelIntentRevisionClass.REV2_B_SPECIFIC -> {
                        Log.d(
                            "AlarmScheduler",
                            "Isolating Rev2 B-specific CANCEL intent ${cancelIntent.operationToken} from legacy rescheduleAllInBatches"
                        )
                    }
                    com.sysadmindoc.alarmclock.domain.evaluator.DurableCancelIntentValidator.CancelIntentRevisionClass.UNKNOWN_FAIL_CLOSED,
                    null -> {
                        Log.w(
                            "AlarmScheduler",
                            "Fail-closed on non-Rev1 CANCEL intent revision=${cancelIntent.intentRevision} kind=${cancelIntent.operationKind} token=${cancelIntent.operationToken}"
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("AlarmScheduler", "Failed to reconcile unresolved cancellation intents during rescheduleAllInBatches", e)
            failed++
        }

        runCatching { reconcileOrphanPreparedOccurrences() }
        runDirectBootProjectionHook("RESCHEDULE_BATCH")

        return ScheduleOutcome.BatchResult(
            armedCount = armed,
            blockedCount = blocked,
            failedCount = failed,
            noArmCount = noArm,
            outcomes = outcomesMap
        )
    }

    /**
     * Reconciles orphan PREPARED occurrences (KNO-75 Step 6 D5 Option 1 Sweep).
     * Cancels orphan PendingIntents in OS AlarmManager for occurrences older than [thresholdMs]
     * whose issueToken does not match the active IssueSlot.currentIssueToken.
     */
    internal suspend fun reconcileOrphanPreparedOccurrences(thresholdMs: Long = 60_000L): Int {
        val issuedDao = repository.issuedOccurrenceDao()
        val owner = scheduleAuthorityOwnerProvider?.get() ?: run {
            Log.w("AlarmScheduler", "Skipping reconcileOrphanPreparedOccurrences: ScheduleAuthorityOwner unavailable")
            return 0
        }

        val cutoffTime = System.currentTimeMillis() - thresholdMs
        val orphanCandidates = issuedDao.getExpiredOrphanOccurrences(cutoffTime)
        var cleanedCount = 0

        orphanCandidates.forEach { candidate ->
            owner.withMainV2SlotLock(candidate.alarmId) {
                val freshOcc = issuedDao.getOccurrence(candidate.issueToken) ?: return@withMainV2SlotLock
                val slot = issuedDao.getSlot(candidate.alarmId)

                if ((freshOcc.status == com.sysadmindoc.alarmclock.data.local.entity.IssuedOccurrence.STATUS_PREPARED ||
                     freshOcc.status == com.sysadmindoc.alarmclock.data.local.entity.IssuedOccurrence.STATUS_SET_FAILED) &&
                    slot?.currentIssueToken != candidate.issueToken
                ) {
                    val piOrphan = lookupTokenizedPendingIntentForCancel(candidate.alarmId, candidate.issueToken)
                    val cancelSuccess = try {
                        if (piOrphan != null) {
                            alarmManager.cancel(piOrphan)
                            piOrphan.cancel()
                        }
                        true
                    } catch (e: Exception) {
                        Log.w("AlarmScheduler", "OS cancel exception for orphan token ${candidate.issueToken} in slot ${candidate.alarmId}", e)
                        false
                    }

                    if (cancelSuccess) {
                        val casUpdated = issuedDao.casUpdateOccurrenceStatus(
                            issueToken = candidate.issueToken,
                            expectedStatus = freshOcc.status,
                            newStatus = com.sysadmindoc.alarmclock.data.local.entity.IssuedOccurrence.STATUS_REVOKED,
                            updatedAt = System.currentTimeMillis()
                        )
                        if (casUpdated == 1) {
                            cleanedCount++
                        }
                    }
                }
            }
        }

        return cleanedCount
    }

    private suspend fun settleLegacyV1CancelWitnessPhase(
        durableIntentDao: com.sysadmindoc.alarmclock.data.local.DurableOperationIntentDao,
        operationToken: String,
        targetPhase: String
    ): Boolean {
        return try {
            val rows = durableIntentDao.updateIntentPhase(operationToken, targetPhase)
            if (rows != 1) {
                Log.w(
                    "AlarmScheduler",
                    "Failed to settle Legacy V1 cancel witness $operationToken to $targetPhase (rows=$rows)"
                )
                false
            } else {
                true
            }
        } catch (e: Exception) {
            Log.w(
                "AlarmScheduler",
                "Exception settling Legacy V1 cancel witness $operationToken to $targetPhase",
                e
            )
            false
        }
    }

    /**
     * Internal helper to schedule with AlarmManager at a specific time without recalculating.
     */
    private suspend fun scheduleExistingTriggerAssumingRecoveryLock(
        alarm: Alarm,
        triggerTime: Long,
        requestWidgetUpdate: Boolean = true,
        projectToDirectBoot: Boolean = true
    ): ScheduleOutcome {
        val sanitizedAlarm = alarm.sanitized()
        val originAuthority = resolveOriginAuthorityForScheduling(sanitizedAlarm)
        val originRevision = originAuthority?.scheduleRevision
        val originInstanceToken = originAuthority?.instanceToken
        val originRestoreEpoch = originAuthority?.restoreEpoch
        val originEpochFence = currentRestoreEpochOrNull()?.takeIf { it > 0L }
        val originDefinitionRevision = originAuthority?.definitionRevision
        val expectedGen = sanitizedAlarm.generation.takeIf { !sanitizedAlarm.isQuickAlarm && it > 0 }

        if (routeAuthority.isOwnerRouteActive() && originAuthority == null) {
            Log.w("AlarmScheduler", "Aborting scheduleExistingTrigger() for alarm ${sanitizedAlarm.id}: Owner route active but authority record absent")
            return ScheduleOutcome.RegistrationFailed(sanitizedAlarm.id, IllegalStateException("Owner route active but authority record absent"))
        }

        if (!canScheduleExactAlarms()) {
            val cancelled = cancelScheduledEntriesAssumingRecoveryLock(
                sanitizedAlarm.id,
                expectedRevision = originRevision,
                expectedGeneration = expectedGen,
                expectedRestoreEpoch = originEpochFence
            )
            if (!cancelled) {
                return ScheduleOutcome.RegistrationFailed(
                    sanitizedAlarm.id,
                    IllegalStateException("OS cancel failed")
                )
            }
            repository.updateNextTrigger(sanitizedAlarm.id, 0)
            requestWidgetUpdateIfNeeded(requestWidgetUpdate)
            if (projectToDirectBoot) runDirectBootProjectionHook("SCHEDULE_EXISTING_PERMISSION_BLOCKED")
            return ScheduleOutcome.PermissionBlocked(sanitizedAlarm.id, "EXACT_ALARM_PERMISSION_REVOKED")
        }

        val settings = preferencesManager.getCurrentSettings()
        val vacationAdjustment = VacationAlarmPolicy.adjustTrigger(
            alarm = sanitizedAlarm,
            initialTriggerTime = triggerTime,
            settings = settings,
            calculateFrom = calculator::calculate
        )
        val adjustedTrigger = vacationAdjustment.triggerTime

        return scheduleAtAssumingRecoveryLock(
            alarm = sanitizedAlarm,
            triggerTime = adjustedTrigger,
            requestWidgetUpdate = requestWidgetUpdate,
            expectedRevision = originRevision,
            expectedInstanceToken = originInstanceToken,
            expectedRestoreEpoch = originRestoreEpoch,
            expectedDefinitionRevision = originDefinitionRevision,
            expectedMetaRestoreEpoch = originEpochFence,
            requireDbPresence = true,
            projectToDirectBoot = projectToDirectBoot
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
        val preFetchedWeather = if (coroutineContext[PreparedRestoreRecoveryContext] == null &&
            canScheduleExactAlarms()
        ) {
            val disk = RestoreJournalCoordinator.readVerifiedDiskState(context)
            val allowed = when (disk) {
                is JournalDiskState.Absent -> true
                is JournalDiskState.Verified -> disk.phase == RestoreJournalCoordinator.PHASE_COMMITTED
                is JournalDiskState.InvalidOrBlocked -> false
            }
            val preAlarm = if (allowed) repository.getById(alarmId)?.sanitized() else null
            if (preAlarm != null && preAlarm.isRecurringSchedule && preAlarm.isEnabled && preAlarm.weatherEarlyMinutes > 0) {
                prefetchWeatherOutsideRecoveryLock()
            } else {
                null
            }
        } else {
            null
        }
        return withSchedulerRecoveryGate(
            onBlocked = { ScheduleOutcome.NoArm(alarmId, "RESTORE_PREPARED_FENCE") }
        ) {
            val alarm = repository.getById(alarmId) ?: return@withSchedulerRecoveryGate ScheduleOutcome.NoArm(alarmId, "MISSING_ALARM")

            if (!alarm.isRecurringSchedule) {
                // One-shot alarm: set enabled = false in Room DB first so process death cannot leave an enabled unarmed definition.
                repository.setEnabled(alarmId, enabled = false, nextTrigger = 0)
                cancelMainScheduledAlarmAssumingRecoveryLock(alarmId)
                syncBedtimeDndRule()
                runDirectBootProjectionHook("HANDLE_FIRED_ONE_SHOT")
                ScheduleOutcome.NoArm(alarmId, "ONE_SHOT_DISABLED")
            } else {
                // Repeating alarm: schedule next occurrence (FLAG_UPDATE_CURRENT
                // replaces any stale early-fire PendingIntent with the same requestCode).
                val outcome = scheduleAssumingRecoveryLock(
                    alarm,
                    notBeforeMillis = if (firedScheduledAt > 0L) firedScheduledAt + 60_000L else 0L,
                    preFetchedWeather = preFetchedWeather
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
    suspend fun cancelMainScheduledAlarm(
        alarmId: Long,
        expectedTriggerTime: Long = 0L,
        expectedInstanceToken: String? = null,
        expectedRestoreEpoch: Long? = null,
        expectedScheduleRevision: Long? = null,
        expectedIssueToken: String? = null,
        expectedGeneration: Int? = null
    ): Boolean = withSchedulerRecoveryGate(
        onBlocked = { false }
    ) {
        cancelMainScheduledAlarmAssumingRecoveryLock(
            alarmId = alarmId,
            expectedTriggerTime = expectedTriggerTime,
            expectedInstanceToken = expectedInstanceToken,
            expectedRestoreEpoch = expectedRestoreEpoch,
            expectedScheduleRevision = expectedScheduleRevision,
            expectedIssueToken = expectedIssueToken,
            expectedGeneration = expectedGeneration
        )
    }

    private suspend fun cancelMainScheduledAlarmAssumingRecoveryLock(
        alarmId: Long,
        expectedTriggerTime: Long = 0L,
        expectedInstanceToken: String? = null,
        expectedRestoreEpoch: Long? = null,
        expectedScheduleRevision: Long? = null,
        expectedIssueToken: String? = null,
        expectedGeneration: Int? = null
    ): Boolean {
        return withMainSlotLock(alarmId) {
            val alarm = repository.getById(alarmId)?.sanitized()
                ?: run {
                    Log.w("AlarmScheduler", "Alarm $alarmId missing; rejecting SmartWake cancellation")
                    return@withMainSlotLock false
                }

            if (expectedGeneration != null && !alarm.isQuickAlarm && alarm.generation > expectedGeneration) {
                Log.w("AlarmScheduler", "Stale SmartWake cancellation ignored for alarm $alarmId (generation mismatch: current ${alarm.generation} > expected $expectedGeneration)")
                return@withMainSlotLock false
            }

            val authority = repository.getAuthorityByAlarmId(alarmId)
            val activeSlotToken = repository.issuedOccurrenceDao().getSlotCurrentToken(alarmId)

            // If alarm has migrated to tokenized authority (activeSlotToken is present in issue_slots):
            // Cancellation requires an expectedIssueToken matching activeSlotToken.
            // A missing, blank, or mismatched expectedIssueToken fails closed to protect the active alarm.
            if (!activeSlotToken.isNullOrBlank()) {
                if (expectedIssueToken.isNullOrBlank() || expectedIssueToken != activeSlotToken) {
                    Log.w("AlarmScheduler", "SmartWake cancellation rejected for alarm $alarmId: active tokenized authority '$activeSlotToken' requires matching token (got '$expectedIssueToken')")
                    return@withMainSlotLock false
                }
            }

            // Generation Fence Verification
            if (expectedInstanceToken != null && authority != null && authority.instanceToken != expectedInstanceToken) {
                Log.w("AlarmScheduler", "Stale SmartWake cancellation ignored for alarm $alarmId (instanceToken mismatch)")
                return@withMainSlotLock false
            }

            if (expectedRestoreEpoch != null) {
                val currentEpoch = authority?.restoreEpoch ?: currentRestoreEpochOrNull()
                if (currentEpoch == null || currentEpoch <= 0L || currentEpoch != expectedRestoreEpoch) {
                    Log.w("AlarmScheduler", "Stale SmartWake cancellation ignored for alarm $alarmId (restoreEpoch mismatch: current=$currentEpoch expected=$expectedRestoreEpoch)")
                    return@withMainSlotLock false
                }
            }

            if (expectedScheduleRevision != null && authority != null && authority.scheduleRevision != expectedScheduleRevision) {
                Log.w("AlarmScheduler", "Stale SmartWake cancellation ignored for alarm $alarmId (scheduleRevision mismatch)")
                return@withMainSlotLock false
            }

            if (expectedTriggerTime > 0L) {
                if (alarm.nextTriggerTime != expectedTriggerTime ||
                    (authority != null && authority.desiredTriggerMillis != null && authority.desiredTriggerMillis != expectedTriggerTime)) {
                    Log.w("AlarmScheduler", "Stale SmartWake cancellation ignored for alarm $alarmId (current ${alarm.nextTriggerTime} != expected $expectedTriggerTime)")
                    return@withMainSlotLock false
                }
            }

            val pendingIntent = if (!activeSlotToken.isNullOrBlank()) {
                lookupTokenizedPendingIntentForCancel(alarmId, activeSlotToken)
                    ?: lookupPendingIntentForCancel(alarmId, expectedTriggerTime, "")
            } else {
                lookupPendingIntentForCancel(alarmId, expectedTriggerTime, "")
            }
            if (pendingIntent != null) {
                try {
                    alarmManager.cancel(pendingIntent)
                    pendingIntent.cancel()
                    true
                } catch (e: Exception) {
                    Log.e("AlarmScheduler", "cancelMainScheduledAlarm OS cancel failed for alarm $alarmId", e)
                    false
                }
            } else {
                true
            }
        }
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
    private suspend fun currentRestoreEpochOrNull(): Long? = try {
        repository.database.scheduleAuthorityDao().getMetaById(1)?.restoreEpoch?.takeIf { it > 0L }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        Log.w("AlarmScheduler", "Failed reading ScheduleAuthorityMeta restoreEpoch", e)
        null
    }

    internal fun applyRawOsSet(alarmId: Long, triggerTime: Long): Boolean {
        val restoreEpoch = runBlocking { currentRestoreEpochOrNull() ?: 0L }
        return applyRawOsSetWithEpoch(alarmId, triggerTime, restoreEpoch)
    }

    private fun applyRawOsSetWithEpoch(
        alarmId: Long,
        triggerTime: Long,
        restoreEpoch: Long
    ): Boolean {
        if (!canScheduleExactAlarms()) return false
        val fireId = AlarmIncidentEvent.fireIdFor(alarmId, triggerTime)
        val showAlarmClockIcon = preferencesManager.getCachedSettings().showAlarmClockIcon
        val pendingIntent = createPendingIntentWithEpoch(alarmId, triggerTime, fireId, restoreEpoch)
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
     * DORMANT helper for P2B4 / inactive MAIN_V2 tokenized scheduling integration.
     */
    @androidx.annotation.VisibleForTesting
    internal suspend fun applyTokenizedOsSet(
        alarmId: Long,
        triggerTime: Long,
        issueToken: String = java.util.UUID.randomUUID().toString()
    ): Boolean {
        if (alarmId <= 0L || !routeAuthority.isOwnerRouteActive()) return false
        val owner = scheduleAuthorityOwnerProvider?.get() ?: run {
            Log.w(
                "AlarmScheduler",
                "Fail-closed applyTokenizedOsSet for alarm $alarmId: ScheduleAuthorityOwner unavailable"
            )
            return false
        }
        return owner.withMainV2SlotLock(alarmId) {
            applyTokenizedOsSetLocked(alarmId, triggerTime, issueToken)
        }
    }

    private suspend fun applyTokenizedOsSetLocked(
        alarmId: Long,
        triggerTime: Long,
        issueToken: String
    ): Boolean {
        if (!canScheduleExactAlarms()) return false
        if (alarmId <= 0L || triggerTime <= 0L || !isValidDormantMainV2IssueToken(issueToken)) return false
        if (!verifyMainV2UriParity(alarmId, issueToken)) return false

        val fireId = AlarmIncidentEvent.fireIdFor(alarmId, triggerTime)
        val issuedDao = repository.issuedOccurrenceDao()

        // Capture previous active slot token prior to reserving new occurrence
        val oldToken = issuedDao.getSlotCurrentToken(alarmId)

        // 1. Reserve occurrence in PREPARED status
        val occurrence = issuedDao.reserveIssuedOccurrence(
            issueToken = issueToken,
            alarmId = alarmId,
            scheduledAt = triggerTime,
            fireId = fireId
        ) ?: return false

        // 2. Create tokenized PendingIntent (MAIN_V2 only; zero Legacy V1 raw OS side effects)
        val restoreEpoch = currentRestoreEpochOrNull() ?: 0L
        val pendingIntent = createTokenizedPendingIntent(alarmId, triggerTime, fireId, issueToken, restoreEpoch)
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
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

        // 4. On OS success, atomically activate replacement occurrence C and persist B cancel intent in single DB transaction
        val durableDao = repository.durableOperationIntentDao()
        var cancelIntentCreated = false

        val handoffTransactionOk = try {
            repository.database.withTransaction {
                val activated = issuedDao.activateIssuedOccurrence(issueToken)
                if (!activated) {
                    throw IllegalStateException("Slot activation returned false for token $issueToken in alarm $alarmId")
                }

                if (!oldToken.isNullOrBlank() && oldToken != issueToken) {
                    val oldOccurrence = issuedDao.getOccurrence(oldToken)
                    val cancelIntent = com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent(
                        operationToken = oldToken,
                        alarmId = alarmId,
                        operationEpoch = System.currentTimeMillis(),
                        incarnationId = oldOccurrence?.fireId ?: fireId,
                        operationKind = "CANCEL",
                        expectedOwnerToken = null,
                        expectedScheduleRevision = null,
                        expectedRestoreEpoch = restoreEpoch,
                        phase = com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent.PHASE_PENDING,
                        intentRevision = 1L,
                        originalFireId = oldOccurrence?.fireId ?: fireId,
                        originalScheduledAt = oldOccurrence?.scheduledAt ?: triggerTime
                    )
                    durableDao.insertValidatedIntent(cancelIntent)
                    cancelIntentCreated = true
                }
                true
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("AlarmScheduler", "Atomic handoff transaction failed for alarm $alarmId (token $issueToken)", e)
            false
        }

        if (!handoffTransactionOk) {
            // Clean up orphan C PendingIntent in OS AlarmManager without touching old B's PendingIntent
            try {
                alarmManager.cancel(pendingIntent)
                pendingIntent.cancel()
            } catch (e: Exception) {
                Log.w("AlarmScheduler", "Failed to cancel orphan PendingIntent for failed token $issueToken in alarm $alarmId", e)
            }
            issuedDao.casUpdateOccurrenceStatus(
                issueToken = issueToken,
                expectedStatus = com.sysadmindoc.alarmclock.data.local.entity.IssuedOccurrence.STATUS_PREPARED,
                newStatus = com.sysadmindoc.alarmclock.data.local.entity.IssuedOccurrence.STATUS_SET_FAILED,
                updatedAt = System.currentTimeMillis()
            )
            return false
        }

        // 5. After DB atomic handoff transaction succeeds, attempt physical OS cancellation for oldToken B
        if (!oldToken.isNullOrBlank() && oldToken != issueToken && cancelIntentCreated) {
            val oldPi = lookupTokenizedPendingIntentForCancel(alarmId, oldToken)
            if (oldPi != null) {
                try {
                    alarmManager.cancel(oldPi)
                    oldPi.cancel()
                    runCatching { durableDao.updateIntentPhase(oldToken, com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent.PHASE_COMPLETED) }
                } catch (e: Exception) {
                    Log.w("AlarmScheduler", "Non-fatal OS cancel exception for replaced old token $oldToken in slot $alarmId", e)
                }
            } else {
                runCatching { durableDao.updateIntentPhase(oldToken, com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent.PHASE_COMPLETED) }
            }
        }

        return true
    }

    private enum class TokenizedCancelOutcome {
        CANCELLED_AND_RETIRED,
        NO_ACTIVE_SLOT_TOKEN,
        OWNER_UNAVAILABLE,
        ISOLATED_NON_LEGACY,
        OS_OUTCOME_UNKNOWN
    }

    /**
     * Cancels a tokenized OS registration for [alarmId] and revokes ONLY the targeted occurrence
     * AFTER OS cancellation succeeds (never blanket-revoking unrelated PREPARED B/C occurrences
     * and never mutating Legacy V1 data=null PendingIntents via applyRawOsCancel).
     * DORMANT helper for P2B4 / inactive MAIN_V2 safety.
     */
    @androidx.annotation.VisibleForTesting
    internal suspend fun cancelTokenizedScheduledAlarm(
        alarmId: Long,
        expectedIssueToken: String? = null
    ): Boolean {
        return cancelTokenizedScheduledAlarmWithOutcome(alarmId, expectedIssueToken) ==
            TokenizedCancelOutcome.CANCELLED_AND_RETIRED
    }

    private suspend fun cancelTokenizedScheduledAlarmWithOutcome(
        alarmId: Long,
        expectedIssueToken: String? = null
    ): TokenizedCancelOutcome {
        if (alarmId <= 0L) return TokenizedCancelOutcome.OS_OUTCOME_UNKNOWN
        if (!routeAuthority.isOwnerRouteActive()) return TokenizedCancelOutcome.OWNER_UNAVAILABLE
        val owner = scheduleAuthorityOwnerProvider?.get() ?: run {
            Log.w(
                "AlarmScheduler",
                "Fail-closed cancelTokenizedScheduledAlarm for alarm $alarmId: ScheduleAuthorityOwner unavailable"
            )
            return TokenizedCancelOutcome.OWNER_UNAVAILABLE
        }
        return owner.withMainV2SlotLock(alarmId) {
            cancelTokenizedScheduledAlarmLocked(alarmId, expectedIssueToken)
        }
    }

    private suspend fun cancelTokenizedScheduledAlarmLocked(
        alarmId: Long,
        expectedIssueToken: String?
    ): TokenizedCancelOutcome {
        if (alarmId <= 0L) return TokenizedCancelOutcome.OS_OUTCOME_UNKNOWN
        val issuedDao = repository.issuedOccurrenceDao()
        val durableIntentDao = repository.durableOperationIntentDao()

        // Fail-closed isolation: NEVER let legacy tokenized cancel touch or settle an alarm
        // that has any unresolved Rev2 or Unknown CANCEL intent.
        val unresolvedForAlarm = durableIntentDao.getUnresolvedIntentsByAlarmId(alarmId)
        val nonLegacyUnresolved = unresolvedForAlarm.firstOrNull {
            com.sysadmindoc.alarmclock.domain.evaluator.DurableCancelIntentValidator.isNonLegacyCancelIntent(it)
        }
        if (nonLegacyUnresolved != null) {
            Log.w(
                "AlarmScheduler",
                "Isolating non-legacy CANCEL intent ${nonLegacyUnresolved.operationToken} (rev=${nonLegacyUnresolved.intentRevision}) from legacy cancelTokenizedScheduledAlarm"
            )
            return TokenizedCancelOutcome.ISOLATED_NON_LEGACY
        }

        val currentToken = issuedDao.getSlotCurrentToken(alarmId)
        if (currentToken.isNullOrBlank()) {
            return TokenizedCancelOutcome.NO_ACTIVE_SLOT_TOKEN
        }
        if (expectedIssueToken != null && currentToken != expectedIssueToken) {
            Log.w(
                "AlarmScheduler",
                "Target token mismatch in cancelTokenizedScheduledAlarm for alarm $alarmId: expected=$expectedIssueToken current=$currentToken"
            )
            return TokenizedCancelOutcome.OS_OUTCOME_UNKNOWN
        }

        val existingIntent = durableIntentDao.getIntentByToken(currentToken)
        if (existingIntent != null &&
            com.sysadmindoc.alarmclock.domain.evaluator.DurableCancelIntentValidator.isNonLegacyCancelIntent(existingIntent)
        ) {
            Log.w(
                "AlarmScheduler",
                "Isolating non-legacy CANCEL intent $currentToken (rev=${existingIntent.intentRevision}) from legacy cancelTokenizedScheduledAlarm"
            )
            return TokenizedCancelOutcome.ISOLATED_NON_LEGACY
        }

        val occurrence = issuedDao.getOccurrence(currentToken) ?: return TokenizedCancelOutcome.OS_OUTCOME_UNKNOWN

        val now = System.currentTimeMillis()
        if (existingIntent == null) {
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
        }

        val pendingIntent = lookupTokenizedPendingIntentForCancel(alarmId, currentToken)
        val osCancelSuccess = try {
            if (pendingIntent != null) {
                alarmManager.cancel(pendingIntent)
                pendingIntent.cancel()
            }
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("AlarmScheduler", "cancelTokenizedScheduledAlarm failed for alarm $alarmId", e)
            false
        }

        if (!osCancelSuccess) {
            // Preserve occurrence, slot, and intent when OS cancel outcome is uncertain/failed
            return TokenizedCancelOutcome.OS_OUTCOME_UNKNOWN
        }

        val latestIntent = durableIntentDao.getIntentByToken(currentToken)
        if (latestIntent != null &&
            com.sysadmindoc.alarmclock.domain.evaluator.DurableCancelIntentValidator.isLegacyRev1CancelIntent(latestIntent)
        ) {
            try {
                durableIntentDao.updateIntentPhase(
                    currentToken,
                    com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent.PHASE_COMPLETED
                )
            } catch (_: Exception) {}
        }

        val slot = issuedDao.getSlot(alarmId)
        if (slot != null && slot.currentIssueToken == currentToken) {
            issuedDao.casIncrementSlotGeneration(
                alarmId = alarmId,
                expectedGenId = slot.generationId,
                expectedToken = currentToken,
                newToken = null
            )
        }
        val revokedStatus = occurrence.status
        if (revokedStatus == com.sysadmindoc.alarmclock.data.local.entity.IssuedOccurrence.STATUS_ISSUED ||
            revokedStatus == com.sysadmindoc.alarmclock.data.local.entity.IssuedOccurrence.STATUS_PREPARED ||
            revokedStatus == com.sysadmindoc.alarmclock.data.local.entity.IssuedOccurrence.STATUS_ADMITTED
        ) {
            issuedDao.casUpdateOccurrenceStatus(
                issueToken = currentToken,
                expectedStatus = revokedStatus,
                newStatus = com.sysadmindoc.alarmclock.data.local.entity.IssuedOccurrence.STATUS_REVOKED,
                updatedAt = System.currentTimeMillis()
            )
        }
        return TokenizedCancelOutcome.CANCELLED_AND_RETIRED
    }

    internal data class PreRestoreOccurrenceCleanupResult(
        val cancelledCount: Int = 0,
        val preservedUnknownCount: Int = 0,
        val isolatedCount: Int = 0,
        val failedCount: Int = 0
    )

    internal suspend fun cleanupStalePreRestoreOccurrences(
        currentRestoreEpoch: Long
    ): PreRestoreOccurrenceCleanupResult {
        if (currentRestoreEpoch <= 0L) {
            return PreRestoreOccurrenceCleanupResult(failedCount = 1)
        }
        val issuedDao = repository.issuedOccurrenceDao()
        val staleOccurrences = issuedDao.getActiveStaleRestoreOccurrences(currentRestoreEpoch)
        if (staleOccurrences.isEmpty()) {
            return PreRestoreOccurrenceCleanupResult()
        }
        val owner = scheduleAuthorityOwnerProvider?.get() ?: run {
            Log.w(
                "AlarmScheduler",
                "Fail-closed cleanupStalePreRestoreOccurrences: ScheduleAuthorityOwner unavailable for ${staleOccurrences.size} stale occurrences"
            )
            return PreRestoreOccurrenceCleanupResult(failedCount = staleOccurrences.size)
        }

        val durableIntentDao = repository.durableOperationIntentDao()
        var cancelledCount = 0
        var preservedUnknownCount = 0
        var isolatedCount = 0
        var failedCount = 0

        val grouped = staleOccurrences.groupBy { it.alarmId }
        for ((alarmId, occurrences) in grouped) {
            if (alarmId <= 0L) {
                failedCount += occurrences.size
                continue
            }
            owner.withMainV2SlotLock(alarmId) {
                val unresolvedForAlarm = durableIntentDao.getUnresolvedIntentsByAlarmId(alarmId)
                val alarmIsolated = unresolvedForAlarm.any {
                    it.phase == com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent.PHASE_IN_FLIGHT ||
                        com.sysadmindoc.alarmclock.domain.evaluator.DurableCancelIntentValidator.isNonLegacyCancelIntent(it)
                }
                for (occ in occurrences) {
                    val fresh = issuedDao.getOccurrence(occ.issueToken) ?: continue
                    if (fresh.restoreEpoch >= currentRestoreEpoch) continue
                    val isActiveStatus = fresh.status == com.sysadmindoc.alarmclock.data.local.entity.IssuedOccurrence.STATUS_PREPARED ||
                        fresh.status == com.sysadmindoc.alarmclock.data.local.entity.IssuedOccurrence.STATUS_ISSUED ||
                        fresh.status == com.sysadmindoc.alarmclock.data.local.entity.IssuedOccurrence.STATUS_ADMITTED
                    if (!isActiveStatus) continue

                    val occCancelMatch = unresolvedForAlarm.any {
                        it.operationKind == "CANCEL" && it.operationToken == fresh.issueToken
                    }
                    if (alarmIsolated || occCancelMatch) {
                        Log.w(
                            "AlarmScheduler",
                            "Holding pre-restore stale occurrence ${fresh.issueToken} for alarm $alarmId under Rev2/IN_FLIGHT isolation"
                        )
                        isolatedCount++
                        continue
                    }

                    val pendingIntent = lookupTokenizedPendingIntentForCancel(alarmId, fresh.issueToken)
                    if (pendingIntent == null) {
                        preservedUnknownCount++
                        continue
                    }

                    val osCancelSuccess = try {
                        alarmManager.cancel(pendingIntent)
                        pendingIntent.cancel()
                        true
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(
                            "AlarmScheduler",
                            "cleanupStalePreRestoreOccurrences OS cancel failed for alarm $alarmId token ${fresh.issueToken}",
                            e
                        )
                        false
                    }
                    if (!osCancelSuccess) {
                        failedCount++
                        continue
                    }

                    val updatedRows = issuedDao.casRevokeStalePreRestoreOccurrence(
                        issueToken = fresh.issueToken,
                        expectedStatus = fresh.status,
                        currentRestoreEpoch = currentRestoreEpoch,
                        updatedAt = System.currentTimeMillis()
                    )
                    if (updatedRows == 1) {
                        cancelledCount++
                    } else {
                        failedCount++
                    }
                }
            }
        }

        return PreRestoreOccurrenceCleanupResult(
            cancelledCount = cancelledCount,
            preservedUnknownCount = preservedUnknownCount,
            isolatedCount = isolatedCount,
            failedCount = failedCount
        )
    }

    private suspend fun scheduleAlarmClock(
        alarmId: Long,
        triggerTime: Long,
        expectedRevision: Long? = null,
        expectedGeneration: Int? = null,
        expectedAlarmRevision: Int? = null,
        expectedRestoreEpoch: Long? = null
    ): Boolean {
        testOnImmediatePreOsBarrier?.invoke(alarmId, triggerTime)

        val currentAlarm = repository.getById(alarmId)
        if (currentAlarm != null && !currentAlarm.isQuickAlarm) {
            if (!currentAlarm.isEnabled) {
                Log.i("AlarmScheduler", "Aborting raw OS set for alarm $alarmId: DB alarm became disabled")
                return false
            }
            if (expectedGeneration != null && currentAlarm.generation > expectedGeneration) {
                Log.i("AlarmScheduler", "Aborting raw OS set for alarm $alarmId: DB generation advanced to ${currentAlarm.generation} > expected $expectedGeneration")
                return false
            }
            if (expectedGeneration != null && expectedAlarmRevision != null &&
                currentAlarm.generation == expectedGeneration && currentAlarm.revision > expectedAlarmRevision
            ) {
                Log.i("AlarmScheduler", "Aborting raw OS set for alarm $alarmId: DB revision advanced to ${currentAlarm.revision} > expected $expectedAlarmRevision")
                return false
            }
        }
        if (expectedRestoreEpoch != null && expectedRestoreEpoch > 0L) {
            val liveEpoch = currentRestoreEpochOrNull()
            if (liveEpoch == null || liveEpoch != expectedRestoreEpoch) {
                Log.i("AlarmScheduler", "Aborting raw OS set for alarm $alarmId: restoreEpoch $liveEpoch != expected $expectedRestoreEpoch")
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
            val restoreEpoch = expectedRestoreEpoch ?: (currentRestoreEpochOrNull() ?: 0L)
            val osSuccess = applyRawOsSetWithEpoch(alarmId, triggerTime, restoreEpoch)
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

    private fun scheduleSupportingWork(
        alarm: Alarm,
        triggerTime: Long,
        restoreEpoch: Long = 0L,
        expectedRevision: Long? = null
    ) {
        scheduleSmartAlarmStart(alarm, triggerTime, restoreEpoch = restoreEpoch, expectedRevision = expectedRevision)
        scheduleHueSunrise(alarm, triggerTime, restoreEpoch = restoreEpoch)
        scheduleFireWatchdog(alarm, triggerTime, restoreEpoch = restoreEpoch)
    }

    /**
     * Enqueue a proactive fire-verification check shortly after the scheduled
     * fire. [FireWatchdogWorker] re-fires the alarm if AlarmManager silently
     * failed to deliver it. The "repeat missed alarms" opt-in is checked at run
     * time by the worker, so toggling the setting needs no rescheduling.
     */
    private fun scheduleFireWatchdog(alarm: Alarm, triggerTime: Long, restoreEpoch: Long = 0L) {
        val workManager = WorkManager.getInstance(context)
        val checkAtMs = triggerTime + FireWatchdogPolicy.WATCHDOG_DELAY_MS
        val delayMs = (checkAtMs - System.currentTimeMillis()).coerceAtLeast(0)
        val dataBuilder = Data.Builder()
            .putLong(FireWatchdogWorker.KEY_ALARM_ID, alarm.id)
            .putLong(FireWatchdogWorker.KEY_SCHEDULED_AT, triggerTime)
        if (!alarm.isQuickAlarm && alarm.generation > 0) {
            dataBuilder.putInt(FireWatchdogWorker.KEY_EXPECTED_GENERATION, alarm.generation)
        }
        if (restoreEpoch > 0L) {
            dataBuilder.putLong(FireWatchdogWorker.KEY_EXPECTED_RESTORE_EPOCH, restoreEpoch)
        }
        val workRequest = OneTimeWorkRequestBuilder<FireWatchdogWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(dataBuilder.build())
            .build()
        workManager.enqueueUniqueWork(
            FireWatchdogWorker.uniqueName(alarm.id),
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }

    private fun scheduleSmartAlarmStart(
        alarm: Alarm,
        triggerTime: Long,
        restoreEpoch: Long = 0L,
        expectedRevision: Long? = null
    ) {
        // KNO-75 Step 6 / KNO-76: Personal edition retires Smart early-fire runtime.
        // Retire any pre-existing OS Smart PI for this alarmId without registering
        // new Smart exact alarms or calling startForegroundService(SmartAlarmService),
        // while keeping Main PI, triggerTime, DirectBoot, and FireWatchdog untouched.
        cancelSmartAlarmStart(alarm.id)
    }

    private enum class HueCancelRequestStatus {
        /**
         * WorkManager returned an asynchronous [androidx.work.Operation] for the cancel request.
         * This indicates only that cancellation was requested — NOT that WorkManager finished
         * durable SQLite cancellation. [HueSunriseWorker.doWork] enforces independent entry guard.
         */
        REQUESTED_ASYNC_OPERATION_OBTAINED,
        REQUEST_FAILED
    }

    private fun scheduleHueSunrise(alarm: Alarm, triggerTime: Long, restoreEpoch: Long = 0L) {
        // KNO-75 Step 6 / KNO-76: Personal edition retires Hue Sunrise runtime.
        // Submit a best-effort async WorkManager cancel request for any pre-existing
        // hue_sunrise_<alarmId> work without enqueuing new work or APPEND chains,
        // and without unconditionally cancelling HueSunriseNotifications because
        // notificationId (800_000 + alarmId % 100_000) can collide across alarmIds.
        cancelHueSunrise(alarm.id)
    }

    private fun cancelHueSunrise(alarmId: Long): HueCancelRequestStatus {
        return try {
            val operation: Any? = WorkManager.getInstance(context)
                .cancelUniqueWork(HueSunriseWorker.uniqueName(alarmId))
            if (operation == null) {
                Log.w(
                    "AlarmScheduler",
                    "Hue sunrise cancelUniqueWork returned null Operation for alarm $alarmId; relying on HueSunriseWorker fail-closed entry guard"
                )
                HueCancelRequestStatus.REQUEST_FAILED
            } else {
                HueCancelRequestStatus.REQUESTED_ASYNC_OPERATION_OBTAINED
            }
        } catch (e: Exception) {
            Log.e(
                "AlarmScheduler",
                "Failed to request Hue sunrise work cancel for alarm $alarmId; relying on HueSunriseWorker fail-closed entry guard",
                e
            )
            HueCancelRequestStatus.REQUEST_FAILED
        }
    }

    internal fun cancelScheduledEntries(
        alarmId: Long,
        includeFollowUpWorkers: Boolean = false,
        targetGen: com.sysadmindoc.alarmclock.service.AlarmGeneration? = null,
        expectedRevision: Long? = null,
        expectedGeneration: Int? = null
    ) {
        cancelScheduledEntriesWithResult(
            alarmId = alarmId,
            includeFollowUpWorkers = includeFollowUpWorkers,
            targetGen = targetGen,
            expectedRevision = expectedRevision,
            expectedGeneration = expectedGeneration
        )
    }

    internal suspend fun cancelScheduledEntriesSuspending(
        alarmId: Long,
        includeFollowUpWorkers: Boolean = false,
        targetGen: com.sysadmindoc.alarmclock.service.AlarmGeneration? = null,
        expectedRevision: Long? = null,
        expectedGeneration: Int? = null,
        expectedMaxNextTrigger: Long? = null,
        expectedRestoreEpoch: Long? = null
    ): Boolean = withSchedulerRecoveryGate(
        onBlocked = { false }
    ) {
        cancelScheduledEntriesAssumingRecoveryLock(
            alarmId = alarmId,
            includeFollowUpWorkers = includeFollowUpWorkers,
            targetGen = targetGen,
            expectedRevision = expectedRevision,
            expectedGeneration = expectedGeneration,
            expectedMaxNextTrigger = expectedMaxNextTrigger,
            expectedRestoreEpoch = expectedRestoreEpoch
        )
    }

    internal fun cancelScheduledEntriesWithResult(
        alarmId: Long,
        includeFollowUpWorkers: Boolean = false,
        targetGen: com.sysadmindoc.alarmclock.service.AlarmGeneration? = null,
        expectedRevision: Long? = null,
        expectedGeneration: Int? = null,
        expectedMaxNextTrigger: Long? = null,
        expectedRestoreEpoch: Long? = null
    ): Boolean = ScheduleAuthorityOwner.withAuthenticatedSyncRecoveryGate(
        context = context,
        onBlocked = { false }
    ) {
        cancelScheduledEntriesAssumingRecoveryLock(
            alarmId = alarmId,
            includeFollowUpWorkers = includeFollowUpWorkers,
            targetGen = targetGen,
            expectedRevision = expectedRevision,
            expectedGeneration = expectedGeneration,
            expectedMaxNextTrigger = expectedMaxNextTrigger,
            expectedRestoreEpoch = expectedRestoreEpoch
        )
    }

    private suspend fun cancelScheduledEntriesAssumingRecoveryLock(
        alarmId: Long,
        includeFollowUpWorkers: Boolean = false,
        targetGen: com.sysadmindoc.alarmclock.service.AlarmGeneration? = null,
        expectedRevision: Long? = null,
        expectedGeneration: Int? = null,
        expectedMaxNextTrigger: Long? = null,
        expectedRestoreEpoch: Long? = null
    ): Boolean {
        return withMainSlotLock(alarmId) {
            var isCancelAccepted = false
            var isStaleRejected = false
            var osCancelFailed = false
            if (expectedRestoreEpoch != null) {
                val currentMetaEpoch = currentRestoreEpochOrNull()
                if (currentMetaEpoch == null || currentMetaEpoch <= 0L || currentMetaEpoch != expectedRestoreEpoch) {
                    Log.i(
                        "AlarmScheduler",
                        "Aborting stale cancelScheduledEntries for alarm $alarmId: current restoreEpoch=$currentMetaEpoch != expected=$expectedRestoreEpoch"
                    )
                    isStaleRejected = true
                }
            }
            val currentAlarm = if (!isStaleRejected) repository.getById(alarmId) else null
            if (!isStaleRejected &&
                expectedMaxNextTrigger != null &&
                currentAlarm != null &&
                !currentAlarm.isQuickAlarm &&
                currentAlarm.isEnabled &&
                currentAlarm.nextTriggerTime > expectedMaxNextTrigger
            ) {
                Log.i(
                    "AlarmScheduler",
                    "Aborting stale cancelScheduledEntries for alarm $alarmId: current nextTriggerTime ${currentAlarm.nextTriggerTime} > expectedMaxNextTrigger $expectedMaxNextTrigger"
                )
                isStaleRejected = true
            }
            if (!isStaleRejected &&
                expectedGeneration != null &&
                currentAlarm != null &&
                !currentAlarm.isQuickAlarm &&
                currentAlarm.generation > expectedGeneration
            ) {
                Log.i("AlarmScheduler", "Aborting stale cancelScheduledEntries for alarm $alarmId: current DB generation ${currentAlarm.generation} > expected $expectedGeneration")
                isStaleRejected = true
            }
            if (!isStaleRejected &&
                !routeAuthority.isOwnerRouteActive() &&
                expectedRevision != null &&
                expectedRevision > 0L
            ) {
                val auth = repository.getAuthorityByAlarmId(alarmId)
                if (auth != null && auth.scheduleRevision != expectedRevision) {
                    Log.i(
                        "AlarmScheduler",
                        "Aborting stale cancelScheduledEntries for alarm $alarmId: scheduleRevision ${auth.scheduleRevision} != expected $expectedRevision"
                    )
                    isStaleRejected = true
                }
            }
            if (!isStaleRejected && targetGen != null) {
                if (currentAlarm != null && currentAlarm.nextTriggerTime > targetGen.scheduledAt) {
                    Log.i("AlarmScheduler", "Aborting stale cancelScheduledEntries for alarm $alarmId: targetGen $targetGen is older than current DB trigger ${currentAlarm.nextTriggerTime}")
                    isStaleRejected = true
                }
            }
            if (!isStaleRejected) {
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
                        val osCancelOk = applyRawOsCancel(alarmId)
                        osCancelFailed = !osCancelOk
                        isCancelAccepted = osCancelOk
                    }
                }
            }

            if (isStaleRejected || !isCancelAccepted) {
                when {
                    routeAuthority.isOwnerRouteActive() ->
                        Log.w("AlarmScheduler", "Cancellation unfulfilled or rejected for alarm $alarmId under OWNER_EXCLUSIVE; skipping destructive side effects")
                    isStaleRejected ->
                        Log.w("AlarmScheduler", "Stale cancellation rejected for alarm $alarmId under LEGACY_ONLY; withholding DirectBoot and follow-up cleanup")
                    osCancelFailed ->
                        Log.w("AlarmScheduler", "Main OS cancellation failed (OS state UNKNOWN) for alarm $alarmId under LEGACY_ONLY; withholding DirectBoot and follow-up cleanup")
                    else ->
                        Log.w("AlarmScheduler", "Cancellation not accepted for alarm $alarmId; withholding DirectBoot and follow-up cleanup")
                }
                return@withMainSlotLock false
            }

            DirectBootAlarmCache.removeIfMatches(context, alarmId)

            val smartCancelOk = cancelSmartAlarmStart(alarmId)
            val hueCancelStatus = cancelHueSunrise(alarmId)

            val workManager = WorkManager.getInstance(context)
            workManager.cancelUniqueWork(FireWatchdogWorker.uniqueName(alarmId))
            if (includeFollowUpWorkers) {
                if (alarmId > 0L) {
                    workManager.cancelUniqueWork(
                        OwnerWorkIdentity.legacyUniqueName(alarmId, SupportingWorkType.GUARDIAN)
                    )
                    workManager.cancelAllWorkByTag(
                        com.sysadmindoc.alarmclock.worker.GuardianWorker.alarmTag(alarmId)
                    )
                }
                workManager.cancelUniqueWork("wake_confirm_$alarmId")
                workManager.cancelAllWorkByTag("wake_confirm_$alarmId")
                com.sysadmindoc.alarmclock.worker.WakeConfirmOccurrenceStore.cancelAllForAlarm(context, alarmId)
            }
            smartCancelOk && hueCancelStatus == HueCancelRequestStatus.REQUESTED_ASYNC_OPERATION_OBTAINED
        }
    }

    private fun cancelSmartAlarmStart(alarmId: Long): Boolean {
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
            return try {
                alarmManager.cancel(smartPending)
                smartPending.cancel()
                true
            } catch (e: Exception) {
                Log.e("AlarmScheduler", "Failed to cancel Smart-alarm PI for alarm $alarmId", e)
                false
            }
        }
        return true
    }

    private fun createPendingIntent(
        alarmId: Long,
        triggerTime: Long = 0L,
        fireId: String = ""
    ): PendingIntent = createPendingIntentWithEpoch(
        alarmId = alarmId,
        triggerTime = triggerTime,
        fireId = fireId,
        restoreEpoch = 0L
    )

    private fun createPendingIntentWithEpoch(
        alarmId: Long,
        triggerTime: Long = 0L,
        fireId: String = "",
        restoreEpoch: Long = 0L
    ): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = "com.sysadmindoc.alarmclock.ALARM_FIRE"
            putExtra(EXTRA_ALARM_ID, alarmId)
            putExtra(EXTRA_SCHEDULED_AT, triggerTime)
            putExtra(EXTRA_ALARM_FIRE_ID, fireId.ifBlank { AlarmIncidentEvent.fireIdFor(alarmId, triggerTime) })
            putExtra(EXTRA_QUICK_ALARM, false)
            if (restoreEpoch > 0L) {
                putExtra(EXTRA_RESTORE_EPOCH, restoreEpoch)
            }
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

    @androidx.annotation.VisibleForTesting
    internal fun isValidDormantMainV2IssueToken(issueToken: String): Boolean {
        if (issueToken.isBlank()) return false
        if (issueToken.any { it.isWhitespace() || it.isISOControl() }) return false
        return issueToken.all { c ->
            (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') ||
                c == '-' || c == '_' || c == '.' || c == '~'
        }
    }

    @androidx.annotation.VisibleForTesting
    internal fun isCanonicalMainV2UuidToken(issueToken: String): Boolean {
        if (issueToken.length != 36) return false
        val uuidRegex = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        if (!uuidRegex.matches(issueToken)) return false
        return android.net.Uri.encode(issueToken) == PendingIntentIdentityComparator.encodePercentUtf8(issueToken)
    }

    @androidx.annotation.VisibleForTesting
    internal fun buildTokenizedMainV2UriString(alarmId: Long, issueToken: String): String {
        val uriEncoded = android.net.Uri.encode(issueToken)
        val comparatorEncoded = PendingIntentIdentityComparator.encodePercentUtf8(issueToken)
        require(uriEncoded == comparatorEncoded) {
            "Token URI encoding mismatch between Uri.encode and PendingIntentIdentityComparator"
        }
        val uriString = "alarmclock://alarm/issue?alarmId=$alarmId&token=$uriEncoded"
        val parsed = PendingIntentIdentityComparator.parseAndValidateQueryParams(uriString)
        require(parsed != null && parsed["alarmId"] == alarmId.toString() && parsed["token"] == comparatorEncoded) {
            "Tokenized MAIN_V2 URI failed PendingIntentIdentityComparator validation"
        }
        return uriString
    }

    @androidx.annotation.VisibleForTesting
    internal fun verifyMainV2UriParity(alarmId: Long, issueToken: String): Boolean {
        if (alarmId <= 0L || issueToken.isBlank()) return false
        val uriEncoded = android.net.Uri.encode(issueToken)
        val comparatorEncoded = PendingIntentIdentityComparator.encodePercentUtf8(issueToken)
        if (uriEncoded != comparatorEncoded) return false
        val uriString = "alarmclock://alarm/issue?alarmId=$alarmId&token=$uriEncoded"
        val parsed = PendingIntentIdentityComparator.parseAndValidateQueryParams(uriString) ?: return false
        val decoded = PendingIntentIdentityComparator.decodePercentUtf8(parsed["token"] ?: return false) ?: return false
        return parsed["alarmId"] == alarmId.toString() && decoded == issueToken
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
        issueToken: String,
        restoreEpoch: Long = 0L
    ): PendingIntent {
        require(issueToken.isNotBlank()) { "issueToken MUST NOT be blank for tokenized PendingIntent" }
        require(alarmId > 0L) { "alarmId MUST be positive for tokenized PendingIntent" }
        require(triggerTime > 0L) { "triggerTime MUST be positive for tokenized PendingIntent" }

        val uri = android.net.Uri.parse(buildTokenizedMainV2UriString(alarmId, issueToken))
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = "com.sysadmindoc.alarmclock.ALARM_FIRE"
            data = uri
            putExtra(EXTRA_ALARM_ID, alarmId)
            putExtra(EXTRA_SCHEDULED_AT, triggerTime)
            putExtra(EXTRA_ALARM_FIRE_ID, fireId.ifBlank { AlarmIncidentEvent.fireIdFor(alarmId, triggerTime) })
            putExtra(EXTRA_ISSUE_TOKEN, issueToken)
            putExtra(EXTRA_QUICK_ALARM, false)
            if (restoreEpoch > 0L) {
                putExtra(EXTRA_RESTORE_EPOCH, restoreEpoch)
            }
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
        if (alarmId <= 0L || issueToken.isBlank()) return null
        if (!verifyMainV2UriParity(alarmId, issueToken)) return null
        val uri = android.net.Uri.parse(buildTokenizedMainV2UriString(alarmId, issueToken))
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
