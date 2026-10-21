package com.sysadmindoc.alarmclock.worker

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
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

    @Test
    fun `doWork under default LEGACY_ONLY route skips owner reconciler but completes health check`() = runBlocking {
        val worker = AlarmHealthWorker(
            context = context,
            workerParams = workerParams,
            alarmRepository = alarmRepository,
            scheduleAuthorityReconciler = reconciler,
            routeAuthority = ProductionSchedulingRouteAuthority() // Default LEGACY_ONLY
        )

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 0) { reconciler.reconcilePendingAuthorities() }
        coVerify(exactly = 1) { alarmRepository.getEnabled() }
    }
}
