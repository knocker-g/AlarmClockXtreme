package com.sysadmindoc.alarmclock.worker

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.domain.ProductionSchedulingRouteAuthority
import com.sysadmindoc.alarmclock.domain.ScheduleAuthorityReconciler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class AlarmHealthWorkerTest {

    private lateinit var context: Context
    private lateinit var workerParams: WorkerParameters
    private lateinit var alarmRepository: AlarmRepository
    private lateinit var reconciler: ScheduleAuthorityReconciler

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        workerParams = mockk(relaxed = true)
        alarmRepository = mockk(relaxed = true)
        reconciler = mockk(relaxed = true)

        coEvery { alarmRepository.getEnabled() } returns emptyList()
    }

    @After
    fun tearDown() {
        unmockkStatic(WorkManager::class)
    }

    @Test
    fun `doWork under default LEGACY_ONLY route skips owner reconciler but completes health check`() = runBlocking {
        val alarmScheduler = mockk<com.sysadmindoc.alarmclock.domain.AlarmScheduler>(relaxed = true)
        val worker = AlarmHealthWorker(
            context = context,
            workerParams = workerParams,
            alarmRepository = alarmRepository,
            alarmScheduler = alarmScheduler,
            scheduleAuthorityReconciler = reconciler,
            routeAuthority = ProductionSchedulingRouteAuthority() // Default LEGACY_ONLY
        )

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 0) { reconciler.reconcilePendingAuthorities() }
        coVerify(exactly = 1) { alarmRepository.getEnabled() }
    }

    @Test
    fun `C3B-RED-C1 enqueueImmediate skips duplicate rescheduleAllInBatches while periodic default still reschedules`() = runBlocking {
        val wm = mockk<WorkManager>(relaxed = true)
        val requestSlot = slot<OneTimeWorkRequest>()
        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(any<Context>()) } returns wm
        every {
            wm.enqueueUniqueWork(
                AlarmHealthWorker.IMMEDIATE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                capture(requestSlot)
            )
        } returns mockk(relaxed = true)

        AlarmHealthWorker.enqueueImmediate(context)
        val immediateInput = requestSlot.captured.workSpec.input

        val enabledAlarm = Alarm(id = 11L, hour = 7, minute = 0, isEnabled = true, nextTriggerTime = System.currentTimeMillis() + 3_600_000L)
        coEvery { alarmRepository.getEnabled() } returns listOf(enabledAlarm)

        val alarmScheduler = mockk<com.sysadmindoc.alarmclock.domain.AlarmScheduler>(relaxed = true)

        // Immediate boot path must NOT call duplicate rescheduleAllInBatches (BootRescheduleWorker owns boot reschedule)
        val immediateParams = mockk<WorkerParameters>(relaxed = true) {
            every { inputData } returns immediateInput
        }
        val immediateRes = AlarmHealthWorker(
            context = context,
            workerParams = immediateParams,
            alarmRepository = alarmRepository,
            alarmScheduler = alarmScheduler,
            scheduleAuthorityReconciler = reconciler
        ).doWork()
        assertEquals(ListenableWorker.Result.success(), immediateRes)
        coVerify(exactly = 0) { alarmScheduler.rescheduleAllInBatches(any(), any()) }

        // Periodic default (Data.EMPTY) still runs self-heal rescheduleAllInBatches
        val periodicParams = mockk<WorkerParameters>(relaxed = true) {
            every { inputData } returns Data.EMPTY
        }
        val periodicRes = AlarmHealthWorker(
            context = context,
            workerParams = periodicParams,
            alarmRepository = alarmRepository,
            alarmScheduler = alarmScheduler,
            scheduleAuthorityReconciler = reconciler
        ).doWork()
        assertEquals(ListenableWorker.Result.success(), periodicRes)
        coVerify(exactly = 1) { alarmScheduler.rescheduleAllInBatches(forceRecalculate = false, batchSize = any()) }
    }
}
