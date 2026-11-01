package com.sysadmindoc.alarmclock.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.sysadmindoc.alarmclock.data.repository.HolidayReconcileCoordinator
import com.sysadmindoc.alarmclock.data.repository.HolidayReconcileResult
import com.sysadmindoc.alarmclock.data.repository.HolidayRefreshOutcome
import com.sysadmindoc.alarmclock.data.repository.HolidayRepository
import com.sysadmindoc.alarmclock.data.repository.HolidayWorkerDecision
import com.sysadmindoc.alarmclock.data.repository.HolidayWorkerResultMapper
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/**
 * F13 / KNO-158 / KNO-159 P3: Periodic worker that refreshes public holiday data
 * and reconciles affected repeating Normal Alarms.
 *
 * Maps typed [HolidayRefreshOutcome] to bounded WorkManager results:
 * - When material change or pending reconciliation exists, coordinates execution
 *   and verifies scheduling outcomes before completing.
 * - Transient / storage failure / incomplete reconciliation -> [Result.retry]
 * - Permanent failure -> [Result.failure]
 * - CancellationException is never swallowed.
 */
@HiltWorker
class HolidaySyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val holidayRepository: HolidayRepository,
    private val reconcileCoordinator: HolidayReconcileCoordinator? = null
) : CoroutineWorker(context, workerParams) {

    constructor(
        context: Context,
        workerParams: WorkerParameters,
        holidayRepository: HolidayRepository
    ) : this(context, workerParams, holidayRepository, null)

    override suspend fun doWork(): Result {
        return try {
            val outcome = holidayRepository.refreshWithOutcome()
            val coordinator = reconcileCoordinator

            val isMaterial = (outcome is HolidayRefreshOutcome.Success && outcome.materialChange) ||
                (outcome is HolidayRefreshOutcome.PartialSuccess && outcome.materialChange)

            if (coordinator != null) {
                val reconcileResult = coordinator.reconcile(isMaterialChange = isMaterial)
                if (reconcileResult is HolidayReconcileResult.PendingRetry) {
                    return Result.retry()
                }
            } else if (isMaterial) {
                return Result.retry()
            }

            when (HolidayWorkerResultMapper.map(outcome, runAttemptCount)) {
                HolidayWorkerDecision.SUCCESS -> Result.success()
                HolidayWorkerDecision.RETRY -> Result.retry()
                HolidayWorkerDecision.FAILURE -> Result.failure()
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
