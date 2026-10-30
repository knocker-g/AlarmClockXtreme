package com.sysadmindoc.alarmclock.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.sysadmindoc.alarmclock.data.repository.HolidayRefreshOutcome
import com.sysadmindoc.alarmclock.data.repository.HolidayRepository
import com.sysadmindoc.alarmclock.data.repository.HolidayWorkerDecision
import com.sysadmindoc.alarmclock.data.repository.HolidayWorkerResultMapper
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/**
 * F13 / KNO-158: Periodic worker that refreshes public holiday data.
 *
 * Maps typed [HolidayRefreshOutcome] to bounded WorkManager results:
 * - Complete success / Unchanged / Partial success / Not configured -> [Result.success]
 * - Transient / storage failure -> [Result.retry] (up to 3 attempts, then [Result.failure])
 * - Permanent failure -> [Result.failure]
 * - CancellationException is never swallowed.
 */
@HiltWorker
class HolidaySyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val holidayRepository: HolidayRepository
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val outcome = holidayRepository.refreshWithOutcome()
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
