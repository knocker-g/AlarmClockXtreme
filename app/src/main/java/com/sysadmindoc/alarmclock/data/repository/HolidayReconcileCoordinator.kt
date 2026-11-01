package com.sysadmindoc.alarmclock.data.repository

import android.content.Context
import android.util.Log
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import com.sysadmindoc.alarmclock.domain.ScheduleOutcome
import com.sysadmindoc.alarmclock.domain.evaluator.AlarmHolidayEvaluationInput
import com.sysadmindoc.alarmclock.domain.evaluator.HolidayMaterialChangeDetector
import com.sysadmindoc.alarmclock.domain.evaluator.HolidayTruthChangeOutcome
import com.sysadmindoc.alarmclock.domain.evaluator.LocalHolidayClassifier
import com.sysadmindoc.alarmclock.domain.evaluator.OccurrenceDecision
import com.sysadmindoc.alarmclock.domain.evaluator.ScheduleOccurrenceEvaluator
import com.sysadmindoc.alarmclock.domain.evaluator.asLocalHolidayClassifier
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

sealed class HolidayReconcileResult {
    data class Success(val reconciledCount: Int, val isNoOp: Boolean = false) : HolidayReconcileResult()
    data class PendingRetry(val pendingCount: Int, val failureCause: Throwable? = null) : HolidayReconcileResult()
}

/**
 * Coordinates truthful, recoverable Holiday change -> affected Alarm reconciliation (KNO-159 P3).
 *
 * Responsibilities:
 * - Detects material Holiday truth transitions and identifies affected repeating Normal Alarms.
 * - Records durable pending reconcile intent before executing mutations.
 * - Progressively reconciles affected alarms via [AlarmScheduler.reconcileRegistration].
 * - Persists completed alarm IDs across partial failures and crashes.
 * - Handles failed OS registrations with retry semantics without prematurely advancing state.
 * - Treats intentional no-arm suppression as settled without unbounded retries.
 */
@Singleton
class HolidayReconcileCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val holidayRepository: HolidayRepository,
    private val alarmRepository: AlarmRepository,
    private val alarmScheduler: AlarmScheduler
) {
    var clockMillis: () -> Long = { System.currentTimeMillis() }
    private var testEvaluator: ScheduleOccurrenceEvaluator? = null
    private var testDetector: HolidayMaterialChangeDetector? = null
    private var testStateStore: HolidayReconcileStateStore? = null

    internal constructor(
        context: Context,
        holidayRepository: HolidayRepository,
        alarmRepository: AlarmRepository,
        alarmScheduler: AlarmScheduler,
        evaluator: ScheduleOccurrenceEvaluator = ScheduleOccurrenceEvaluator(),
        detector: HolidayMaterialChangeDetector = HolidayMaterialChangeDetector(evaluator),
        stateStore: HolidayReconcileStateStore = FileHolidayReconcileStateStore(context)
    ) : this(context, holidayRepository, alarmRepository, alarmScheduler) {
        this.testEvaluator = evaluator
        this.testDetector = detector
        this.testStateStore = stateStore
    }

    private val evaluator: ScheduleOccurrenceEvaluator
        get() = testEvaluator ?: ScheduleOccurrenceEvaluator()

    private val detector: HolidayMaterialChangeDetector
        get() = testDetector ?: HolidayMaterialChangeDetector(evaluator)

    private val stateStore: HolidayReconcileStateStore
        get() = testStateStore ?: FileHolidayReconcileStateStore(context)

    suspend fun reconcile(
        isMaterialChange: Boolean = false,
        previousClassifier: LocalHolidayClassifier? = null,
        evaluationEpochMs: Long = clockMillis(),
        evaluationZoneId: ZoneId = ZoneId.systemDefault()
    ): HolidayReconcileResult {
        var currentState = try {
            stateStore.load()
        } catch (e: Exception) {
            Log.e("HolidayReconcileCoordinator", "Failed to load durable reconcile state; failing closed with PendingRetry", e)
            return HolidayReconcileResult.PendingRetry(pendingCount = 1, failureCause = e)
        }

        val allNormalAlarms = alarmRepository.getEnabledNormalAlarms()
        val eligibleAlarms = allNormalAlarms.filter {
            !it.isQuickAlarm && it.scheduleKind == Alarm.SCHEDULE_KIND_REPEATING &&
                (it.holidayPolicy == Alarm.HOLIDAY_POLICY_EXCLUDE || it.holidayPolicy == Alarm.HOLIDAY_POLICY_INCLUDE)
        }

        if (eligibleAlarms.isEmpty() && currentState.pendingSignature == null && !isMaterialChange) {
            return HolidayReconcileResult.Success(reconciledCount = 0, isNoOp = true)
        }

        val evaluationRange = HolidayMaterialChangeDetector.resolveEvaluationDateRange(
            evaluationEpochMs = evaluationEpochMs,
            evaluationZoneId = evaluationZoneId,
            alarms = eligibleAlarms
        )
        val authorityWindow = holidayRepository.captureAuthorityWindow(
            startDate = evaluationRange.startDate,
            endDateInclusive = evaluationRange.endDateInclusive
        )
        val currentClassifier = authorityWindow.asLocalHolidayClassifier()
        val currentSignature = detector.computeSignature(
            classifier = currentClassifier,
            startDate = evaluationRange.startDate,
            horizonDays = evaluationRange.dayCount
        )

        // Idempotent no-op check: if already fully reconciled for this exact signature and no pending work
        if (!isMaterialChange &&
            currentState.pendingSignature == null &&
            currentState.lastReconciledSignature == currentSignature.sha256Hex
        ) {
            return HolidayReconcileResult.Success(reconciledCount = 0, isNoOp = true)
        }

        val evaluationInputs = try {
            eligibleAlarms.map { alarm ->
                val exceptions = alarmRepository.database.scheduleExceptionDao().getExceptionsForAlarmGeneration(alarm.id, alarm.generation)
                AlarmHolidayEvaluationInput(
                    alarm = alarm,
                    exceptions = exceptions
                )
            }
        } catch (e: Exception) {
            Log.e("HolidayReconcileCoordinator", "Failed to query schedule exceptions for eligible alarms; aborting reconcile to fail-closed", e)
            return HolidayReconcileResult.PendingRetry(pendingCount = eligibleAlarms.size.coerceAtLeast(1), failureCause = e)
        }

        val isRecovery = (previousClassifier == null || previousClassifier === currentClassifier)
        val effectivePreviousClassifier = previousClassifier ?: currentClassifier

        val decision = detector.detectMaterialChange(
            previousClassifier = effectivePreviousClassifier,
            currentClassifier = currentClassifier,
            evaluationEpochMs = evaluationEpochMs,
            evaluationZoneId = evaluationZoneId,
            alarms = evaluationInputs,
            isRecovery = isRecovery
        )

        if (decision.outcome == HolidayTruthChangeOutcome.NO_MATERIAL_CHANGE &&
            currentState.pendingSignature == null &&
            !isMaterialChange
        ) {
            if (currentState.lastReconciledSignature != currentSignature.sha256Hex) {
                try {
                    stateStore.save(
                        currentState.copy(
                            lastReconciledSignature = currentSignature.sha256Hex,
                            pendingSignature = null,
                            completedAlarmIds = emptySet(),
                            completedGenerations = emptyMap()
                        )
                    )
                } catch (_: Exception) {}
            }
            return HolidayReconcileResult.Success(reconciledCount = 0, isNoOp = true)
        }

        // Invalidate stale pending progress on country / truth signature mismatch
        val isSignatureMismatch = currentState.pendingSignature != null &&
            currentState.pendingSignature.length == 64 &&
            currentState.pendingSignature != currentSignature.sha256Hex
        val (pendingSig, completedIds, completedGens) = if (isSignatureMismatch) {
            Triple(currentSignature.sha256Hex, mutableSetOf<Long>(), mutableMapOf<Long, Int>())
        } else {
            Triple(
                currentState.pendingSignature ?: currentSignature.sha256Hex,
                currentState.completedAlarmIds.toMutableSet(),
                currentState.completedGenerations.toMutableMap()
            )
        }

        // Persist pending signature before executing mutations; abort if write fails
        if (currentState.pendingSignature != pendingSig || isSignatureMismatch) {
            currentState = currentState.copy(
                pendingSignature = pendingSig,
                completedAlarmIds = completedIds,
                completedGenerations = completedGens
            )
            try {
                stateStore.save(currentState)
            } catch (e: Exception) {
                return HolidayReconcileResult.PendingRetry(decision.affectedAlarms.size, e)
            }
        }

        var anyFailed = false
        var failureCause: Throwable? = null
        var reconciledCount = 0

        for (delta in decision.affectedAlarms) {
            val alarmId = delta.alarmId
            val alarm = alarmRepository.getById(alarmId)

            val recordedGen = completedGens[alarmId]
            val isStaleGeneration = alarm != null && recordedGen != null && alarm.generation != recordedGen

            if (completedIds.contains(alarmId) && !isStaleGeneration) {
                continue
            }

            if (alarm == null || !alarm.isEnabled) {
                completedIds.add(alarmId)
                if (alarm != null) completedGens[alarmId] = alarm.generation
                val nextState = currentState.copy(
                    completedAlarmIds = completedIds,
                    completedGenerations = completedGens
                )
                try {
                    stateStore.save(nextState)
                    currentState = nextState
                } catch (e: Exception) {
                    anyFailed = true
                    failureCause = e
                }
                continue
            }

            val targetTrigger = if (
                delta.currentEvaluation != null &&
                (delta.currentEvaluation.decision == OccurrenceDecision.NORMAL_TRIGGER ||
                    delta.currentEvaluation.decision == OccurrenceDecision.TIME_OVERRIDDEN)
            ) {
                delta.currentEvaluation.targetTriggerTimeEpochMs
            } else {
                0L
            }

            val outcome = if (targetTrigger > 0L) {
                alarmScheduler.reconcileRegistration(alarm, targetTrigger, isOsConfirmed = false)
            } else {
                alarmScheduler.cancelScheduledEntries(alarm.id, expectedGeneration = alarm.generation)
                alarmRepository.updateNextTrigger(alarm.id, 0L)
                ScheduleOutcome.NoArm(alarm.id, "HOLIDAY_SUPPRESSED")
            }

            when (outcome) {
                is ScheduleOutcome.Armed, is ScheduleOutcome.NoArm -> {
                    completedIds.add(alarmId)
                    completedGens[alarmId] = alarm.generation
                    val nextState = currentState.copy(
                        completedAlarmIds = completedIds,
                        completedGenerations = completedGens
                    )
                    try {
                        stateStore.save(nextState)
                        currentState = nextState
                        reconciledCount++
                    } catch (e: Exception) {
                        anyFailed = true
                        failureCause = e
                    }
                }
                is ScheduleOutcome.RegistrationFailed -> {
                    anyFailed = true
                    failureCause = outcome.cause
                    Log.w("HolidayReconcileCoordinator", "Registration failed for alarm $alarmId: ${outcome.cause?.message}")
                }
                is ScheduleOutcome.PermissionBlocked -> {
                    anyFailed = true
                    failureCause = IllegalStateException("Permission blocked: ${outcome.reason}")
                    Log.w("HolidayReconcileCoordinator", "Permission blocked for alarm $alarmId: ${outcome.reason}")
                }
                else -> {
                    completedIds.add(alarmId)
                    completedGens[alarmId] = alarm.generation
                    val nextState = currentState.copy(
                        completedAlarmIds = completedIds,
                        completedGenerations = completedGens
                    )
                    try {
                        stateStore.save(nextState)
                        currentState = nextState
                    } catch (e: Exception) {
                        anyFailed = true
                        failureCause = e
                    }
                }
            }
        }

        return if (anyFailed) {
            val pendingRemaining = decision.affectedAlarms.size - completedIds.size
            HolidayReconcileResult.PendingRetry(
                pendingCount = pendingRemaining.coerceAtLeast(1),
                failureCause = failureCause
            )
        } else {
            try {
                stateStore.save(
                    HolidayReconcileState(
                        lastReconciledSignature = pendingSig,
                        pendingSignature = null,
                        completedAlarmIds = emptySet(),
                        completedGenerations = emptyMap()
                    )
                )
                HolidayReconcileResult.Success(reconciledCount = reconciledCount)
            } catch (e: Exception) {
                HolidayReconcileResult.PendingRetry(
                    pendingCount = 1,
                    failureCause = e
                )
            }
        }
    }

    suspend fun reconcileAlarm(
        alarm: Alarm,
        targetTrigger: Long,
        isOsConfirmed: Boolean = false
    ): ScheduleOutcome {
        return alarmScheduler.reconcileRegistration(alarm, targetTrigger, isOsConfirmed = isOsConfirmed)
    }
}
