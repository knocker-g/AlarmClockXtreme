package com.sysadmindoc.alarmclock.worker

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import com.sysadmindoc.alarmclock.domain.ScheduleAuthorityReconciler
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Re-registers enabled alarms after boot, package replacement, or clock
 * changes. Runs outside [android.content.BroadcastReceiver] so large alarm
 * libraries are not constrained by the broadcast ANR window.
 */
@HiltWorker
class BootRescheduleWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val alarmScheduler: AlarmScheduler,
    private val scheduleAuthorityReconciler: ScheduleAuthorityReconciler? = null,
    private val routeAuthority: com.sysadmindoc.alarmclock.domain.ProductionSchedulingRouteAuthority = com.sysadmindoc.alarmclock.domain.ProductionSchedulingRouteAuthority(),
    private val quickAlarmScheduler: com.sysadmindoc.alarmclock.domain.QuickAlarmScheduler? = null,
    private val holidayReconcileCoordinator: com.sysadmindoc.alarmclock.data.repository.HolidayReconcileCoordinator? = null,
    private val upcomingCoordinator: com.sysadmindoc.alarmclock.domain.projection.UpcomingScheduleReconcileCoordinator? = null,
    private val alarmRepository: AlarmRepository? = null,
    private val directBootNormalProjectorProvider: javax.inject.Provider<com.sysadmindoc.alarmclock.directboot.DirectBootNormalProjector>? = null
) : CoroutineWorker(context, workerParams) {

    @androidx.annotation.VisibleForTesting
    internal var lastDirectBootProjectionOutcome: AlarmScheduler.DirectBootProjectionHookOutcome? = null

    @androidx.annotation.VisibleForTesting
    internal var onDirectBootProjectionOutcome: ((AlarmScheduler.DirectBootProjectionHookOutcome) -> Unit)? = null

    @androidx.annotation.VisibleForTesting
    internal var v2RegistrationCapableProvider: () -> Boolean = { false }

    @androidx.annotation.VisibleForTesting
    internal var testDirectBootReconcilerHook: ((Context) -> com.sysadmindoc.alarmclock.directboot.ReconcilerResult)? = null

    @androidx.annotation.VisibleForTesting
    internal var lastDirectBootReconcileResult: com.sysadmindoc.alarmclock.directboot.ReconcilerResult? = null

    private suspend fun runDirectBootProjectionHook(
        sourceAction: String
    ): AlarmScheduler.DirectBootProjectionHookOutcome {
        val reason = if (sourceAction.isBlank()) "BOOT_RESCHEDULE" else "BOOT_RESCHEDULE_$sourceAction"
        val outcome: AlarmScheduler.DirectBootProjectionHookOutcome = if (!routeAuthority.isOwnerRouteActive()) {
            lastDirectBootReconcileResult = null
            AlarmScheduler.DirectBootProjectionHookOutcome.SkippedLegacyOnly
        } else {
            val projector = directBootNormalProjectorProvider?.get()
            if (projector == null) {
                Log.w(TAG, "DirectBootNormalProjector unavailable during $reason under active owner route")
                lastDirectBootReconcileResult = null
                AlarmScheduler.DirectBootProjectionHookOutcome.ProjectorUnavailable
            } else {
                try {
                    when (val result = projector.project()) {
                        is com.sysadmindoc.alarmclock.directboot.DirectBootNormalProjectionResult.Success -> {
                            if (v2RegistrationCapableProvider()) {
                                val recResult = testDirectBootReconcilerHook?.invoke(applicationContext)
                                    ?: com.sysadmindoc.alarmclock.directboot.DirectBootRegistrationReconciler
                                        .getInstance(applicationContext)
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
                            AlarmScheduler.DirectBootProjectionHookOutcome.Succeeded(result)
                        }
                        is com.sysadmindoc.alarmclock.directboot.DirectBootNormalProjectionResult.CoordinatorBlocked,
                        is com.sysadmindoc.alarmclock.directboot.DirectBootNormalProjectionResult.FingerprintMismatch -> {
                            lastDirectBootReconcileResult = null
                            Log.i(TAG, "DirectBoot projection blocked during $reason: $result")
                            AlarmScheduler.DirectBootProjectionHookOutcome.Blocked(result)
                        }
                        is com.sysadmindoc.alarmclock.directboot.DirectBootNormalProjectionResult.DeWriteRejected,
                        is com.sysadmindoc.alarmclock.directboot.DirectBootNormalProjectionResult.PreWriteFailure,
                        is com.sysadmindoc.alarmclock.directboot.DirectBootNormalProjectionResult.DeCommitFailed,
                        is com.sysadmindoc.alarmclock.directboot.DirectBootNormalProjectionResult.CancellationAfterWrite,
                        is com.sysadmindoc.alarmclock.directboot.DirectBootNormalProjectionResult.AmbiguousOutcome -> {
                            lastDirectBootReconcileResult = null
                            Log.e(TAG, "DirectBoot projection failed during $reason: $result")
                            AlarmScheduler.DirectBootProjectionHookOutcome.Failed(result)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    lastDirectBootReconcileResult = null
                    Log.e(TAG, "DirectBoot projection threw during $reason", e)
                    AlarmScheduler.DirectBootProjectionHookOutcome.Failed(result = null, cause = e)
                }
            }
        }
        lastDirectBootProjectionOutcome = outcome
        onDirectBootProjectionOutcome?.invoke(outcome)
        return outcome
    }

    override suspend fun doWork(): Result {
        val sourceAction = inputData.getString(KEY_SOURCE_ACTION).orEmpty()
        val forceRecalculate = inputData.getBoolean(KEY_FORCE_RECALCULATE, false)

        return try {
            upcomingCoordinator?.invalidateAll(com.sysadmindoc.alarmclock.domain.projection.InvalidationReason.SYSTEM_RECOVERY)
            quickAlarmScheduler?.reconcileQuickAlarms(
                entrySource = com.sysadmindoc.alarmclock.domain.ReconcileEntrySource.SYSTEM_RECOVERY
            )
            val batchResult = if (routeAuthority.isOwnerRouteActive() && scheduleAuthorityReconciler != null) {
                val reconciledCount = scheduleAuthorityReconciler.reconcileBootAuthorities()
                Log.i(TAG, "Owner route single-pass boot reconciliation completed ($reconciledCount authorities)")
                com.sysadmindoc.alarmclock.domain.ScheduleOutcome.BatchResult(
                    armedCount = reconciledCount,
                    blockedCount = 0,
                    failedCount = 0,
                    noArmCount = 0,
                    outcomes = emptyMap()
                )
            } else {
                alarmScheduler.rescheduleAllInBatches(
                    forceRecalculate = forceRecalculate,
                    batchSize = RESCHEDULE_BATCH_SIZE
                ).also {
                    Log.i(
                        TAG,
                        "Rescheduled ${it.totalProcessed} enabled alarms after $sourceAction " +
                            "(armed: ${it.armedCount}, blocked: ${it.blockedCount}, failed: ${it.failedCount}, noArm: ${it.noArmCount})"
                    )
                }
            }

            runDirectBootProjectionHook(sourceAction)

            try {
                if (androidx.core.os.UserManagerCompat.isUserUnlocked(applicationContext)) {
                    val deContext = applicationContext.createDeviceProtectedStorageContext()
                    if (com.sysadmindoc.alarmclock.directboot.DirectBootUnlockReceiver.hasPendingTakeoverRecords(deContext, applicationContext)) {
                        val recoveryRes = com.sysadmindoc.alarmclock.directboot.DirectBootUnlockReceiver.runUnlockedRecovery(applicationContext)
                        Log.i(TAG, "BootRescheduleWorker executed DirectBoot takeover recovery: $recoveryRes")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "DirectBoot takeover recovery failed in BootRescheduleWorker", e)
            }

            val reconcileResult = holidayReconcileCoordinator?.reconcile(isMaterialChange = false)

            if (alarmRepository != null) {
                val trigger = when (sourceAction) {
                    Intent.ACTION_USER_UNLOCKED -> WakeConfirmOccurrenceStore.RecoveryTrigger.UNLOCK
                    Intent.ACTION_BOOT_COMPLETED,
                    Intent.ACTION_MY_PACKAGE_REPLACED,
                    Intent.ACTION_TIME_CHANGED,
                    Intent.ACTION_TIMEZONE_CHANGED -> WakeConfirmOccurrenceStore.RecoveryTrigger.REBOOT
                    else -> WakeConfirmOccurrenceStore.RecoveryTrigger.REBOOT
                }
                WakeConfirmOccurrenceStore.recoverPendingEnqueues(
                    context = applicationContext,
                    alarmRepository = alarmRepository,
                    trigger = trigger
                )
            }

            if (reconcileResult is com.sysadmindoc.alarmclock.data.repository.HolidayReconcileResult.PendingRetry) {
                if (runAttemptCount < MAX_ATTEMPTS) {
                    Log.w(TAG, "Holiday reconciliation pending; requesting retry (attempt ${runAttemptCount + 1}/$MAX_ATTEMPTS)")
                    Result.retry()
                } else {
                    Log.e(TAG, "Holiday reconciliation exhausted max attempts ($MAX_ATTEMPTS) with pending work; returning failure")
                    Result.failure()
                }
            } else if (batchResult.failedCount > 0) {
                if (runAttemptCount < MAX_ATTEMPTS) {
                    Log.w(TAG, "Reschedule completed with ${batchResult.failedCount} registration failures; requesting retry (attempt ${runAttemptCount + 1}/$MAX_ATTEMPTS)")
                    Result.retry()
                } else {
                    Log.e(TAG, "Reschedule exhausted max attempts ($MAX_ATTEMPTS) with ${batchResult.failedCount} registration failures; returning failure")
                    Result.failure()
                }
            } else {
                upcomingCoordinator?.reconcileAll()
                Result.success()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reschedule alarms after $sourceAction", e)
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val WORK_NAME = "boot_reschedule_alarms"

        private const val TAG = "BootRescheduleWorker"
        private const val KEY_FORCE_RECALCULATE = "force_recalculate"
        private const val KEY_SOURCE_ACTION = "source_action"
        private const val MAX_ATTEMPTS = 3
        private const val RESCHEDULE_BATCH_SIZE = 25

        fun enqueue(context: Context, sourceAction: String, forceRecalculate: Boolean) {
            val data = Data.Builder()
                .putString(KEY_SOURCE_ACTION, sourceAction)
                .putBoolean(KEY_FORCE_RECALCULATE, forceRecalculate)
                .build()

            val request = OneTimeWorkRequestBuilder<BootRescheduleWorker>()
                .setInputData(data)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
