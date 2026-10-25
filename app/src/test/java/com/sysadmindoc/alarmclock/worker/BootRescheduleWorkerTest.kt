package com.sysadmindoc.alarmclock.worker

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import com.sysadmindoc.alarmclock.domain.ProductionSchedulingRouteAuthority
import com.sysadmindoc.alarmclock.domain.ScheduleAuthorityReconciler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class BootRescheduleWorkerTest {

    private lateinit var context: Context
    private lateinit var workerParams: WorkerParameters
    private lateinit var alarmScheduler: AlarmScheduler
    private lateinit var reconciler: ScheduleAuthorityReconciler

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        workerParams = mockk(relaxed = true)
        alarmScheduler = mockk(relaxed = true)
        reconciler = mockk(relaxed = true)

        coEvery { workerParams.inputData } returns Data.EMPTY
        coEvery { alarmScheduler.rescheduleAllInBatches(any(), any()) } returns com.sysadmindoc.alarmclock.domain.ScheduleOutcome.BatchResult(5, 0, 0, 0, emptyMap())
    }

    @Test
    fun `doWork under default LEGACY_ONLY route skips owner reconciler but executes legacy rescheduling`() = runBlocking {
        val worker = BootRescheduleWorker(
            context = context,
            workerParams = workerParams,
            alarmScheduler = alarmScheduler,
            scheduleAuthorityReconciler = reconciler,
            routeAuthority = ProductionSchedulingRouteAuthority() // Default LEGACY_ONLY
        )

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 0) { reconciler.reconcileBootAuthorities() }
        coVerify(exactly = 1) { alarmScheduler.rescheduleAllInBatches(forceRecalculate = false, batchSize = 25) }
    }
}
