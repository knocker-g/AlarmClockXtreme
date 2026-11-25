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

    @Test
    fun `doWork under OWNER_EXCLUSIVE route runs single-pass reconcileBootAuthorities without duplicate rescheduleAllInBatches`() = runBlocking {
        val ownerRouteAuthority = mockk<com.sysadmindoc.alarmclock.domain.ProductionSchedulingRouteAuthority>()
        io.mockk.every { ownerRouteAuthority.isOwnerRouteActive() } returns true
        coEvery { reconciler.reconcileBootAuthorities() } returns 3

        val worker = BootRescheduleWorker(
            context = context,
            workerParams = workerParams,
            alarmScheduler = alarmScheduler,
            scheduleAuthorityReconciler = reconciler,
            routeAuthority = ownerRouteAuthority
        )

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 1) { reconciler.reconcileBootAuthorities() }
        coVerify(exactly = 0) { alarmScheduler.rescheduleAllInBatches(any(), any()) }
    }

    @Test
    fun `P1A doWork under LEGACY_ONLY skips DE projection and preserves V1 rescheduling`() = runBlocking {
        val dePrefs = context.createDeviceProtectedStorageContext()
            .getSharedPreferences("direct_boot_next_alarm", Context.MODE_PRIVATE)
        dePrefs.edit().clear().commit()
        val mockProjector = mockk<com.sysadmindoc.alarmclock.directboot.DirectBootNormalProjector>()

        val worker = BootRescheduleWorker(
            context = context,
            workerParams = workerParams,
            alarmScheduler = alarmScheduler,
            scheduleAuthorityReconciler = reconciler,
            routeAuthority = ProductionSchedulingRouteAuthority(), // LEGACY_ONLY
            directBootNormalProjectorProvider = javax.inject.Provider { mockProjector }
        )

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(
            AlarmScheduler.DirectBootProjectionHookOutcome.SkippedLegacyOnly,
            worker.lastDirectBootProjectionOutcome
        )
        coVerify(exactly = 0) { mockProjector.project(any<Long>()) }
        coVerify(exactly = 1) { alarmScheduler.rescheduleAllInBatches(forceRecalculate = false, batchSize = 25) }
    }

    @Test
    fun `P1A doWork under OWNER_EXCLUSIVE ENFORCED fixture runs DirectBootNormalProjector after boot reconcile`() = runBlocking {
        val now = System.currentTimeMillis()
        val triggerTime = now + 600_000L
        com.sysadmindoc.alarmclock.directboot.DirectBootAlarmCache.clear(context)
        com.sysadmindoc.alarmclock.directboot.DirectBootAlarmCache.clearQueue(context)
        val dePrefs = context.createDeviceProtectedStorageContext()
            .getSharedPreferences("direct_boot_next_alarm", Context.MODE_PRIVATE)
        dePrefs.edit().clear().commit()

        val roomDb = androidx.room.Room.inMemoryDatabaseBuilder(
            context,
            com.sysadmindoc.alarmclock.data.local.AlarmDatabase::class.java
        ).allowMainThreadQueries().build()

        try {
            roomDb.scheduleAuthorityDao().insertMeta(
                com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthorityMeta(
                    id = 1,
                    restoreEpoch = 1L,
                    bootstrapState = "ENFORCED"
                )
            )
            val alarm = com.sysadmindoc.alarmclock.data.model.Alarm(
                id = 701L,
                hour = 7,
                minute = 0,
                isEnabled = true,
                kind = com.sysadmindoc.alarmclock.data.model.Alarm.KIND_NORMAL,
                nextTriggerTime = triggerTime,
                generation = 1,
                revision = 1
            )
            roomDb.alarmDao().insert(alarm)
            roomDb.scheduleAuthorityDao().insertAuthority(
                com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority(
                    alarmId = 701L,
                    instanceToken = "tok-boot-701",
                    definitionRevision = com.sysadmindoc.alarmclock.domain.ScheduleAuthorityBootstrapAdapter.computeDefinitionRevision(alarm),
                    scheduleRevision = 1L,
                    lifecycle = "LIVE",
                    desiredOperation = "SET",
                    desiredTriggerMillis = triggerTime,
                    reconcileState = "SETTLED",
                    restoreEpoch = 1L,
                    actionKey = "act-boot-701"
                )
            )

            val ownerRouteAuthority = mockk<ProductionSchedulingRouteAuthority>()
            io.mockk.every { ownerRouteAuthority.isOwnerRouteActive() } returns true
            val events = mutableListOf<String>()
            coEvery { reconciler.reconcileBootAuthorities() } coAnswers {
                events.add("BOOT_RECONCILE")
                1
            }

            val snapshotReader = com.sysadmindoc.alarmclock.directboot.DirectBootNormalSnapshotReader(roomDb) { true }
            val coordinator = com.sysadmindoc.alarmclock.directboot.DirectBootNormalProjectionCoordinator(snapshotReader)
            val projector = com.sysadmindoc.alarmclock.directboot.DirectBootNormalProjector(
                context = context,
                database = roomDb,
                coordinator = coordinator,
                preWriteHook = { events.add("PROJECTOR") }
            )

            val worker = BootRescheduleWorker(
                context = context,
                workerParams = workerParams,
                alarmScheduler = alarmScheduler,
                scheduleAuthorityReconciler = reconciler,
                routeAuthority = ownerRouteAuthority,
                directBootNormalProjectorProvider = javax.inject.Provider { projector }
            )

            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            assertEquals(listOf("BOOT_RECONCILE", "PROJECTOR"), events)
            org.junit.Assert.assertTrue(worker.lastDirectBootProjectionOutcome is AlarmScheduler.DirectBootProjectionHookOutcome.Succeeded)
            assertEquals(
                listOf(701L),
                com.sysadmindoc.alarmclock.directboot.DirectBootAlarmCache.readQueue(context, now = now).map { it.alarmId }
            )
            assertEquals(null, worker.lastDirectBootReconcileResult)
        } finally {
            com.sysadmindoc.alarmclock.directboot.DirectBootAlarmCache.clear(context)
            com.sysadmindoc.alarmclock.directboot.DirectBootAlarmCache.clearQueue(context)
            dePrefs.edit().clear().commit()
            roomDb.close()
        }
    }

    @Test
    fun `P1A doWork surfaces projection failure without failing V1 Result success`() = runBlocking {
        val ownerRouteAuthority = mockk<ProductionSchedulingRouteAuthority>()
        io.mockk.every { ownerRouteAuthority.isOwnerRouteActive() } returns true
        coEvery { reconciler.reconcileBootAuthorities() } returns 1

        val mockProjector = mockk<com.sysadmindoc.alarmclock.directboot.DirectBootNormalProjector>()
        coEvery { mockProjector.project(any<Long>()) } returns
            com.sysadmindoc.alarmclock.directboot.DirectBootNormalProjectionResult.DeCommitFailed("Simulated commit failure")

        val worker = BootRescheduleWorker(
            context = context,
            workerParams = workerParams,
            alarmScheduler = alarmScheduler,
            scheduleAuthorityReconciler = reconciler,
            routeAuthority = ownerRouteAuthority,
            directBootNormalProjectorProvider = javax.inject.Provider { mockProjector }
        )

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        val outcome = worker.lastDirectBootProjectionOutcome
        org.junit.Assert.assertTrue(
            "Projection failure must be surfaced as Failed, was $outcome",
            outcome is AlarmScheduler.DirectBootProjectionHookOutcome.Failed
        )
    }

    @Test
    fun `P1C doWork enforces DEFAULT_DENY and orders Projector before optional reconciler when isolated seam enabled`() = runBlocking {
        val ownerRouteAuthority = mockk<ProductionSchedulingRouteAuthority>()
        io.mockk.every { ownerRouteAuthority.isOwnerRouteActive() } returns true
        coEvery { reconciler.reconcileBootAuthorities() } returns 1

        val events = mutableListOf<String>()
        val mockProjector = mockk<com.sysadmindoc.alarmclock.directboot.DirectBootNormalProjector>()
        coEvery { mockProjector.project(any<Long>()) } coAnswers {
            events.add("PROJECTOR")
            com.sysadmindoc.alarmclock.directboot.DirectBootNormalProjectionResult.Success(
                enqueuedNormalCandidates = emptyList(),
                completeQueue = emptyList(),
                fingerprint = com.sysadmindoc.alarmclock.directboot.CeSnapshotFingerprint.create(
                    emptyList(),
                    emptyList(),
                    emptyList()
                )
            )
        }

        // Part 1: Default DEFAULT_DENY must NOT call reconciler
        val denyWorker = BootRescheduleWorker(
            context = context,
            workerParams = workerParams,
            alarmScheduler = alarmScheduler,
            scheduleAuthorityReconciler = reconciler,
            routeAuthority = ownerRouteAuthority,
            directBootNormalProjectorProvider = javax.inject.Provider { mockProjector }
        )
        var reconcilerCalled = 0
        denyWorker.testDirectBootReconcilerHook = {
            reconcilerCalled++
            com.sysadmindoc.alarmclock.directboot.ReconcilerResult.PlanningHalted(
                com.sysadmindoc.alarmclock.directboot.DirectBootRegistrationPlan.NoOp("test")
            )
        }
        assertEquals(ListenableWorker.Result.success(), denyWorker.doWork())
        assertEquals(0, reconcilerCalled)
        assertEquals(listOf("PROJECTOR"), events)

        // Part 2: Isolated opt-in capability verifies Projector -> Reconciler ordering
        events.clear()
        val orderedWorker = BootRescheduleWorker(
            context = context,
            workerParams = workerParams,
            alarmScheduler = alarmScheduler,
            scheduleAuthorityReconciler = reconciler,
            routeAuthority = ownerRouteAuthority,
            directBootNormalProjectorProvider = javax.inject.Provider { mockProjector }
        )
        orderedWorker.v2RegistrationCapableProvider = { true }
        orderedWorker.testDirectBootReconcilerHook = {
            events.add("RECONCILER")
            com.sysadmindoc.alarmclock.directboot.ReconcilerResult.PlanningHalted(
                com.sysadmindoc.alarmclock.directboot.DirectBootRegistrationPlan.NoOp("test")
            )
        }
        assertEquals(ListenableWorker.Result.success(), orderedWorker.doWork())
        assertEquals(listOf("PROJECTOR", "RECONCILER"), events)
    }
}
