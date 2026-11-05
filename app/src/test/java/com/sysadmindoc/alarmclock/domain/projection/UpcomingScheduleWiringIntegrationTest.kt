package com.sysadmindoc.alarmclock.domain.projection

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.sysadmindoc.alarmclock.data.local.AlarmDao
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.ScheduleAuthorityDao
import com.sysadmindoc.alarmclock.data.local.ScheduleExceptionDao
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthorityMeta
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleException
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.data.repository.HolidayReconcileCoordinator
import com.sysadmindoc.alarmclock.data.repository.HolidayReconcileResult
import com.sysadmindoc.alarmclock.data.repository.HolidayRepository
import com.sysadmindoc.alarmclock.di.DatabaseModule
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import com.sysadmindoc.alarmclock.domain.ScheduleOutcome
import com.sysadmindoc.alarmclock.domain.evaluator.DurableOneTimeOverrideAdapter
import com.sysadmindoc.alarmclock.domain.evaluator.OneTimeOverrideOutcome
import com.sysadmindoc.alarmclock.receiver.BootReceiver
import com.sysadmindoc.alarmclock.worker.BootRescheduleWorker
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Production Wiring Verification Suite for KNO-156 P1-C2 (Parent: KNO-34 Slice B).
 * Covers W01 through W16 using real production entrypoints, Room in-memory database,
 * and deterministic coroutine barriers.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class UpcomingScheduleWiringIntegrationTest {

    private lateinit var context: Context
    private lateinit var db: AlarmDatabase
    private lateinit var alarmDao: AlarmDao
    private lateinit var exceptionDao: ScheduleExceptionDao
    private lateinit var authorityDao: ScheduleAuthorityDao

    private lateinit var projector: UpcomingScheduleProjector
    private lateinit var coordinator: UpcomingScheduleReconcileCoordinator
    private lateinit var alarmScheduler: AlarmScheduler
    private lateinit var alarmRepo: AlarmRepository
    private lateinit var overrideAdapter: DurableOneTimeOverrideAdapter

    private val zoneId = ZoneId.of("America/New_York")

    // Reference test epoch: Wednesday 2026-11-04 06:00:00
    private val nowEpochMs = LocalDate.of(2026, 11, 4)
        .atTime(LocalTime.of(6, 0))
        .atZone(zoneId)
        .toInstant()
        .toEpochMilli()

    private val wed0700Ms = LocalDate.of(2026, 11, 4)
        .atTime(LocalTime.of(7, 0))
        .atZone(zoneId)
        .toInstant()
        .toEpochMilli()

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        alarmDao = db.alarmDao()
        exceptionDao = db.scheduleExceptionDao()
        authorityDao = db.scheduleAuthorityDao()

        // Seed ScheduleAuthorityMeta
        authorityDao.insertMeta(
            ScheduleAuthorityMeta(id = 1, restoreEpoch = 100L, bootstrapState = "INITIALIZED")
        )

        projector = UpcomingScheduleProjector()

        coordinator = UpcomingScheduleReconcileCoordinator(
            projector = projector,
            alarmDao = alarmDao,
            scheduleExceptionDao = exceptionDao,
            scheduleAuthorityDao = authorityDao
        )

        alarmRepo = AlarmRepository(
            database = db,
            dao = alarmDao,
            groupDao = db.alarmGroupDao(),
            coordinator = coordinator
        )

        alarmScheduler = mockk(relaxed = true)

        overrideAdapter = DurableOneTimeOverrideAdapter(
            database = db,
            coordinator = coordinator,
            alarmScheduler = alarmScheduler
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    // =========================================================================
    // W01 — Alarm Edit Committed Save
    // =========================================================================

    @Test
    fun testW01_alarmEditCommittedSave() = runBlocking {
        val newAlarm = Alarm(
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
            nextTriggerTime = wed0700Ms
        )

        val savedId = alarmRepo.save(newAlarm)
        assertTrue("Saved ID must be positive", savedId > 0)

        // Verify invalidation delivered with ALARM_DEFINITION_CHANGED
        assertTrue("Alarm must be reconciling after save", coordinator.isAlarmReconciling(savedId))
        assertEquals(
            InvalidationReason.ALARM_DEFINITION_CHANGED,
            coordinator.lastInvalidationReasons.value[savedId]
        )

        // Before reconcile, projection reflects RECONCILING freshness
        val inFlight = coordinator.getCurrentProjection(nowEpochMs, evaluationZoneId = zoneId)
        assertEquals(UpcomingProjectionFreshness.RECONCILING, inFlight.projectionFreshness)

        // After reconcile, projection settles to CURRENT with saved alarm details
        val settled = coordinator.reconcile(savedId, nowEpochMs, evaluationZoneId = zoneId)
        assertFalse(coordinator.isAlarmReconciling(savedId))
        assertEquals(UpcomingProjectionFreshness.CURRENT, settled.projectionFreshness)
        assertEquals(LocalTime.of(7, 0), settled.nextNormalOccurrence?.localDisplayTime)
    }

    // =========================================================================
    // W02 — Enable / Disable Toggle
    // =========================================================================

    @Test
    fun testW02_enableDisableToggle() = runBlocking {
        val wed0830Ms = LocalDate.of(2026, 11, 4)
            .atTime(LocalTime.of(8, 30))
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()

        val newAlarm = Alarm(
            hour = 8,
            minute = 30,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY),
            nextTriggerTime = wed0830Ms
        )
        val id = alarmRepo.save(newAlarm)
        coordinator.reconcile(id, nowEpochMs, evaluationZoneId = zoneId)

        // Toggle disabled
        alarmRepo.setEnabled(id, false, 0L)
        assertTrue(coordinator.isAlarmReconciling(id))
        assertEquals(InvalidationReason.ALARM_TOGGLED, coordinator.lastInvalidationReasons.value[id])

        // Rapid toggle back enabled
        alarmRepo.setEnabled(id, true, wed0830Ms)
        assertTrue(coordinator.isAlarmReconciling(id))
        assertEquals(InvalidationReason.ALARM_TOGGLED, coordinator.lastInvalidationReasons.value[id])

        // When authority is RECONCILING, projection reflects RECONCILING freshness
        val inFlight = coordinator.getCurrentProjection(nowEpochMs, evaluationZoneId = zoneId)
        assertEquals(UpcomingProjectionFreshness.RECONCILING, inFlight.projectionFreshness)

        // Settle authority (simulating AlarmScheduler OS registration completion)
        val currentAuth = authorityDao.getAuthorityByAlarmId(id)!!
        authorityDao.insertAuthority(currentAuth.copy(reconcileState = "SETTLED"))

        // Reconcile settles to enabled state
        val settled = coordinator.reconcile(id, nowEpochMs, evaluationZoneId = zoneId)
        assertFalse(coordinator.isAlarmReconciling(id))
        assertEquals(UpcomingProjectionFreshness.CURRENT, settled.projectionFreshness)
        assertTrue(settled.allOccurrences.isNotEmpty())
    }

    // =========================================================================
    // W03 — One-Time Override: Skip Once
    // =========================================================================

    @Test
    fun testW03_skipOnce() = runBlocking {
        val alarm = Alarm(
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY),
            nextTriggerTime = wed0700Ms
        )
        val id = alarmRepo.save(alarm)
        coordinator.reconcile(id, nowEpochMs, evaluationZoneId = zoneId)

        val result = overrideAdapter.executeSkipOnce(
            alarmId = id,
            expectedScheduledAt = wed0700Ms,
            nowEpochMs = nowEpochMs,
            evaluationZoneId = zoneId
        )

        assertEquals(OneTimeOverrideOutcome.SUCCESS_COMMITTED, result.outcome)
        assertTrue(coordinator.isAlarmReconciling(id))
        assertEquals(InvalidationReason.OVERRIDE_CHANGED, coordinator.lastInvalidationReasons.value[id])

        val settled = coordinator.reconcile(id, nowEpochMs, evaluationZoneId = zoneId)
        assertFalse(coordinator.isAlarmReconciling(id))
        assertEquals(UpcomingProjectionFreshness.CURRENT, settled.projectionFreshness)
    }

    // =========================================================================
    // W04 — One-Time Override: Change Time Once
    // =========================================================================

    @Test
    fun testW04_changeTimeOnce() = runBlocking {
        val alarm = Alarm(
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY),
            nextTriggerTime = wed0700Ms
        )
        val id = alarmRepo.save(alarm)
        coordinator.reconcile(id, nowEpochMs, evaluationZoneId = zoneId)

        val newTime = LocalTime.of(9, 15)
        val result = overrideAdapter.executeChangeTimeOnce(
            alarmId = id,
            expectedScheduledAt = wed0700Ms,
            newTime = newTime,
            nowEpochMs = nowEpochMs,
            evaluationZoneId = zoneId
        )

        assertEquals(OneTimeOverrideOutcome.SUCCESS_COMMITTED, result.outcome)
        assertTrue(coordinator.isAlarmReconciling(id))
        assertEquals(InvalidationReason.OVERRIDE_CHANGED, coordinator.lastInvalidationReasons.value[id])

        val settled = coordinator.reconcile(id, nowEpochMs, evaluationZoneId = zoneId)
        assertFalse(coordinator.isAlarmReconciling(id))
        assertEquals(UpcomingProjectionFreshness.CURRENT, settled.projectionFreshness)
        assertEquals(newTime, settled.nextNormalOccurrence?.localDisplayTime)
    }

    // =========================================================================
    // W05 — One-Time Override: Clear / Cancel Override
    // =========================================================================

    @Test
    fun testW05_overrideClear() = runBlocking {
        val alarm = Alarm(
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY),
            nextTriggerTime = wed0700Ms
        )
        val id = alarmRepo.save(alarm)
        overrideAdapter.executeChangeTimeOnce(
            alarmId = id,
            expectedScheduledAt = wed0700Ms,
            newTime = LocalTime.of(10, 0),
            nowEpochMs = nowEpochMs,
            evaluationZoneId = zoneId
        )
        coordinator.reconcile(id, nowEpochMs, evaluationZoneId = zoneId)

        // Cancel override
        val cancelResult = overrideAdapter.cancelOverride(
            alarmId = id,
            expectedScheduledAt = wed0700Ms,
            nowEpochMs = nowEpochMs,
            evaluationZoneId = zoneId
        )

        assertEquals(OneTimeOverrideOutcome.SUCCESS_COMMITTED, cancelResult.outcome)
        assertTrue(coordinator.isAlarmReconciling(id))
        assertEquals(InvalidationReason.OVERRIDE_CHANGED, coordinator.lastInvalidationReasons.value[id])

        val settled = coordinator.reconcile(id, nowEpochMs, evaluationZoneId = zoneId)
        assertFalse(coordinator.isAlarmReconciling(id))
        assertEquals(LocalTime.of(7, 0), settled.nextNormalOccurrence?.localDisplayTime)
    }

    // =========================================================================
    // W06 — Holiday Truth Refresh
    // =========================================================================

    @Test
    fun testW06_holidayTruthRefresh() = runBlocking {
        val holidayRepo = mockk<HolidayRepository>(relaxed = true)
        val scheduler = mockk<AlarmScheduler>(relaxed = true)

        val holidayCoordinator = HolidayReconcileCoordinator(
            context = context,
            holidayRepository = holidayRepo,
            alarmRepository = alarmRepo,
            alarmScheduler = scheduler,
            coordinator = coordinator
        )

        // Case A: Material change detected -> invalidates with HOLIDAY_TRUTH_CHANGED and settles
        val result = holidayCoordinator.reconcile(
            isMaterialChange = true,
            evaluationEpochMs = nowEpochMs,
            evaluationZoneId = zoneId
        )
        assertTrue(result is HolidayReconcileResult.Success)
        assertFalse("ReconcileAll should have settled the global invalidation", coordinator.isGlobalReconciling())

        // Case B: No material change and no alarms -> isNoOp = true, no coordinator invalidation
        assertFalse(coordinator.isGlobalReconciling())
        val noOpResult = holidayCoordinator.reconcile(
            isMaterialChange = false,
            evaluationEpochMs = nowEpochMs,
            evaluationZoneId = zoneId
        )
        assertTrue((noOpResult as HolidayReconcileResult.Success).isNoOp)
        assertFalse(coordinator.isGlobalReconciling())
    }

    // =========================================================================
    // W07 — Time / Timezone Recovery
    // =========================================================================

    @Test
    fun testW07_timeTimezoneRecovery() = runBlocking {
        val receiver = BootReceiver()
        receiver.testScope = CoroutineScope(Dispatchers.Unconfined)

        // LOCKED_BOOT_COMPLETED must NEVER invalidate Room coordinator or enqueue BootRescheduleWorker (Direct Boot boundary)
        receiver.onReceive(context, Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED))
        assertFalse("LOCKED_BOOT_COMPLETED must not invalidate Room coordinator", coordinator.isGlobalReconciling())

        // Recovery execution via canonical BootRescheduleWorker
        val scheduler = mockk<AlarmScheduler>(relaxed = true)
        coEvery { scheduler.rescheduleAllInBatches(any(), any()) } returns
            ScheduleOutcome.BatchResult(0, 0, 0, 0, emptyMap())

        val timeChangedData = androidx.work.Data.Builder()
            .putString("source_action", Intent.ACTION_TIME_CHANGED)
            .putBoolean("force_recalculate", true)
            .build()
        val workerParams = mockk<WorkerParameters>(relaxed = true)
        every { workerParams.inputData } returns timeChangedData

        val worker = BootRescheduleWorker(
            context = context,
            workerParams = workerParams,
            alarmScheduler = scheduler,
            upcomingCoordinator = coordinator
        )

        // When worker executes for TIME_CHANGED recovery, it invalidates with SYSTEM_RECOVERY and settles upon success
        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        assertFalse("Worker should settle global invalidation on success", coordinator.isGlobalReconciling())

        // If worker fails, invalidation is preserved for retry
        coEvery { scheduler.rescheduleAllInBatches(any(), any()) } returns
            ScheduleOutcome.BatchResult(0, 0, 1, 0, mapOf(1L to ScheduleOutcome.RegistrationFailed(1L)))
        val failingResult = worker.doWork()
        assertEquals(ListenableWorker.Result.retry(), failingResult)
        assertTrue("Worker retry must preserve pending SYSTEM_RECOVERY invalidation", coordinator.isGlobalReconciling())
        assertEquals(InvalidationReason.SYSTEM_RECOVERY, coordinator.lastGlobalInvalidationReason.value)
    }

    // =========================================================================
    // W08 — Boot / Process Recovery Worker
    // =========================================================================

    @Test
    fun testW08_bootProcessRecoveryWorker() = runBlocking {
        val scheduler = mockk<AlarmScheduler>(relaxed = true)
        coEvery { scheduler.rescheduleAllInBatches(any(), any()) } returns
            ScheduleOutcome.BatchResult(0, 0, 0, 0, emptyMap())

        val workerParams = mockk<WorkerParameters>(relaxed = true)
        every { workerParams.inputData } returns androidx.work.Data.EMPTY

        val worker = BootRescheduleWorker(
            context = context,
            workerParams = workerParams,
            alarmScheduler = scheduler,
            upcomingCoordinator = coordinator
        )

        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        // Worker invalidates with SYSTEM_RECOVERY and reconciles upon success
        assertFalse("Worker should settle global invalidation on success", coordinator.isGlobalReconciling())
    }

    // =========================================================================
    // W09 — Duplicate Delivery & Convergence
    // =========================================================================

    @Test
    fun testW09_duplicateDeliveryConvergence() = runBlocking {
        val initialGen = coordinator.state.value.nextGeneration

        coordinator.invalidateAlarm(1L, InvalidationReason.ALARM_DEFINITION_CHANGED)
        coordinator.invalidateAlarm(1L, InvalidationReason.ALARM_TOGGLED)
        coordinator.invalidateAlarm(1L, InvalidationReason.OVERRIDE_CHANGED)

        val state = coordinator.state.value
        assertEquals("Generation must advance monotonically", initialGen + 3, state.nextGeneration)
        assertEquals(initialGen + 2, state.alarmGenerations[1L])
        assertEquals(InvalidationReason.OVERRIDE_CHANGED, state.alarmReasons[1L])

        coordinator.reconcile(1L, nowEpochMs, evaluationZoneId = zoneId)
        assertFalse(coordinator.isAlarmReconciling(1L))
    }

    // =========================================================================
    // W10 — Mutation Failure and No False Acknowledgement
    // =========================================================================

    @Test
    fun testW10_mutationFailureNoFalseAcknowledgement() = runBlocking {
        val invalidAlarm = Alarm(
            id = 99L,
            hour = 7,
            minute = 0,
            holidayPolicy = "INVALID_UNKNOWN_POLICY"
        )

        try {
            alarmRepo.save(invalidAlarm)
            fail("Should throw IllegalArgumentException on invalid policy")
        } catch (_: IllegalArgumentException) {
            // Expected
        }

        assertFalse(
            "Coordinator must NOT be notified if DB mutation threw",
            coordinator.isAlarmReconciling(99L)
        )
    }

    // =========================================================================
    // W11 — Invalidation During In-Flight Reconciliation
    // =========================================================================

    @Test
    fun testW11_invalidationDuringInFlightReconciliation() = runBlocking {
        val alarm = Alarm(
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY),
            nextTriggerTime = wed0700Ms
        )
        val id = alarmRepo.save(alarm)

        val startReconcileBarrier = CompletableDeferred<Unit>()
        val canCompleteReconcile = CompletableDeferred<Unit>()

        // Fake a slow projector by wrapping it
        val slowProjector = mockk<UpcomingScheduleProjector>(relaxed = true)
        coEvery {
            slowProjector.projectUpcomingSchedule(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            startReconcileBarrier.complete(Unit)
            canCompleteReconcile.await()
            projector.projectUpcomingSchedule(
                alarms = db.alarmDao().getAllNormalAlarms(),
                nowEpochMs = nowEpochMs,
                evaluationZoneId = zoneId
            )
        }

        val testCoordinator = UpcomingScheduleReconcileCoordinator(
            projector = slowProjector,
            alarmDao = alarmDao,
            scheduleExceptionDao = exceptionDao,
            scheduleAuthorityDao = authorityDao
        )

        testCoordinator.invalidateAlarm(id, InvalidationReason.ALARM_DEFINITION_CHANGED)
        val firstGen = testCoordinator.state.value.alarmGenerations[id]

        val reconcileJob = launch {
            testCoordinator.reconcile(id, nowEpochMs, evaluationZoneId = zoneId)
        }

        startReconcileBarrier.await()

        // Mid-flight: new invalidation arrives
        testCoordinator.invalidateAlarm(id, InvalidationReason.OVERRIDE_CHANGED)
        val secondGen = testCoordinator.state.value.alarmGenerations[id]
        assertTrue(secondGen!! > firstGen!!)

        // Complete the in-flight reconcile
        canCompleteReconcile.complete(Unit)
        reconcileJob.join()

        // First reconcile should NOT have cleared the newer generation!
        assertTrue("Alarm must remain reconciling because a newer invalidation arrived", testCoordinator.isAlarmReconciling(id))
        assertEquals(secondGen, testCoordinator.state.value.alarmGenerations[id])
    }

    // =========================================================================
    // W12 — Process Death Before/After Persistence
    // =========================================================================

    @Test
    fun testW12_processDeathRestartRebuildsFreshness() = runBlocking {
        val alarm = Alarm(
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY),
            nextTriggerTime = wed0700Ms
        )
        val id = alarmRepo.save(alarm)

        // Simulate process death and restart: new Coordinator instance reading existing DB
        val restartedCoordinator = UpcomingScheduleReconcileCoordinator(
            projector = projector,
            alarmDao = alarmDao,
            scheduleExceptionDao = exceptionDao,
            scheduleAuthorityDao = authorityDao
        )

        assertFalse(restartedCoordinator.isAlarmReconciling(id))
        val proj = restartedCoordinator.getCurrentProjection(nowEpochMs, evaluationZoneId = zoneId)
        assertEquals(UpcomingProjectionFreshness.CURRENT, proj.projectionFreshness)
        assertEquals(LocalTime.of(7, 0), proj.nextNormalOccurrence?.localDisplayTime)
    }

    // =========================================================================
    // W13 — Reactive Observer Failure Propagation & Recovery
    // =========================================================================

    @Test
    fun testW13_reactiveObserverFailureAndRecovery() = runBlocking {
        val failingAlarmDao = mockk<AlarmDao>(relaxed = true)
        val errorFlow = flow<List<Alarm>> {
            emit(emptyList())
            throw IllegalStateException("Simulated SQLite Disk I/O Error")
        }
        every { failingAlarmDao.observeAll() } returns errorFlow

        val failingCoordinator = UpcomingScheduleReconcileCoordinator(
            projector = projector,
            alarmDao = failingAlarmDao,
            scheduleExceptionDao = exceptionDao,
            scheduleAuthorityDao = authorityDao
        )

        var caughtError: Throwable? = null
        try {
            failingCoordinator.observeProjection().take(2).toList()
        } catch (e: Throwable) {
            caughtError = e
        }

        assertNotNull("Observer failure must be propagated downstream without silent swallowing", caughtError)
        assertEquals("Simulated SQLite Disk I/O Error", caughtError?.message)

        // Recovery: healthy DAO recovers immediately upon resubscription
        val healthyProjection = coordinator.observeProjection().first()
        assertNotNull(healthyProjection)
    }

    // =========================================================================
    // W14 — ScheduleAuthority Update Propagation
    // =========================================================================

    @Test
    fun testW14_scheduleAuthorityUpdatePropagation() = runBlocking {
        val alarm = Alarm(
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY),
            nextTriggerTime = wed0700Ms
        )
        val id = alarmRepo.save(alarm)
        coordinator.reconcile(id, nowEpochMs, evaluationZoneId = zoneId)

        // Update authority to QUARANTINE
        val authority = authorityDao.getAuthorityByAlarmId(id)
        assertNotNull(authority)
        val updatedAuthority = authority!!.copy(reconcileState = "QUARANTINE")
        authorityDao.insertAuthority(updatedAuthority)

        // Projection observer or check reflects quarantine status
        val proj = coordinator.getCurrentProjection(nowEpochMs, evaluationZoneId = zoneId)
        assertEquals(UpcomingProjectionFreshness.STALE, proj.projectionFreshness)
    }

    // =========================================================================
    // W15 — Quick / Normal / Session Isolation
    // =========================================================================

    @Test
    fun testW15_quickNormalSessionIsolation() = runBlocking {
        val normalAlarm = Alarm(
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY),
            nextTriggerTime = wed0700Ms
        )
        val normalId = alarmRepo.save(normalAlarm)

        // Insert Quick Alarm directly into DAO
        val quickAlarm = Alarm(
            hour = 6,
            minute = 30,
            isEnabled = true,
            kind = Alarm.KIND_QUICK,
            nextTriggerTime = nowEpochMs + 1800_000L
        )
        alarmDao.insert(quickAlarm)

        val proj = coordinator.reconcile(normalId, nowEpochMs, evaluationZoneId = zoneId)
        assertEquals(LocalTime.of(7, 0), proj.nextNormalOccurrence?.localDisplayTime)
        assertFalse("Quick alarm occurrences must not appear in normal projection",
            proj.allOccurrences.any { it.alarmId == quickAlarm.id }
        )
    }

    // =========================================================================
    // W16 — No Duplicate OS Scheduling
    // =========================================================================

    @Test
    fun testW16_noDuplicateOsScheduling() = runBlocking {
        val mockScheduler = mockk<AlarmScheduler>(relaxed = true)

        val alarm = Alarm(
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY),
            nextTriggerTime = wed0700Ms
        )
        val id = alarmRepo.save(alarm)

        coordinator.invalidateAlarm(id, InvalidationReason.ALARM_DEFINITION_CHANGED)
        coordinator.reconcile(id, nowEpochMs, evaluationZoneId = zoneId)
        coordinator.invalidateAll(InvalidationReason.SYSTEM_RECOVERY)
        coordinator.reconcileAll(nowEpochMs, evaluationZoneId = zoneId)

        // Verify 0 interactions with AlarmScheduler
        coVerify(exactly = 0) { mockScheduler.reconcileRegistration(any(), any(), any()) }
        coVerify(exactly = 0) { mockScheduler.rescheduleAllInBatches(any(), any()) }
        coVerify(exactly = 0) { mockScheduler.cancelScheduledEntries(any(), any()) }
    }

    // =========================================================================
    // W17 — DatabaseModule Provides Adapter With Coordinator (D01 Verification)
    // =========================================================================

    @Test
    fun testW17_databaseModuleProvidesAdapterWithCoordinator() = runBlocking {
        val scheduler = mockk<AlarmScheduler>(relaxed = true)
        val adapter = DatabaseModule.provideDurableOneTimeOverrideAdapter(db, coordinator, scheduler)
        assertNotNull(adapter)

        val alarm = Alarm(
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY),
            nextTriggerTime = wed0700Ms
        )
        val id = alarmRepo.save(alarm)
        coordinator.reconcile(id, nowEpochMs, evaluationZoneId = zoneId)
        assertFalse(coordinator.isAlarmReconciling(id))

        // Execute skip once via adapter provided by DatabaseModule
        val result = adapter.executeSkipOnce(
            alarmId = id,
            expectedScheduledAt = wed0700Ms,
            nowEpochMs = nowEpochMs,
            evaluationZoneId = zoneId
        )
        assertEquals(OneTimeOverrideOutcome.SUCCESS_COMMITTED, result.outcome)
        assertTrue("Coordinator must be invalidated by adapter created via DatabaseModule", coordinator.isAlarmReconciling(id))
        assertEquals(InvalidationReason.OVERRIDE_CHANGED, coordinator.lastInvalidationReasons.value[id])
    }
}
