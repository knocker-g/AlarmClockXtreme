package com.sysadmindoc.alarmclock.domain.evaluator

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.Operation
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.data.repository.AlarmIncidentRepository
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.data.repository.CapturedHolidayAuthorityWindow
import com.sysadmindoc.alarmclock.data.repository.FileHolidayReconcileStateStore
import com.sysadmindoc.alarmclock.data.repository.HolidayClassification
import com.sysadmindoc.alarmclock.data.repository.HolidayClassificationKind
import com.sysadmindoc.alarmclock.data.repository.HolidayClassificationSource
import com.sysadmindoc.alarmclock.data.repository.HolidayProviderSnapshot
import com.sysadmindoc.alarmclock.data.repository.NAGER_DATE_PROVIDER_ID
import com.sysadmindoc.alarmclock.data.repository.HolidayReconcileCoordinator
import com.sysadmindoc.alarmclock.data.repository.HolidayReconcileResult
import com.sysadmindoc.alarmclock.data.repository.HolidayReconcileState
import com.sysadmindoc.alarmclock.data.repository.HolidayReconcileStateStore
import com.sysadmindoc.alarmclock.data.repository.HolidayRefreshOutcome
import com.sysadmindoc.alarmclock.data.repository.HolidayRepository
import com.sysadmindoc.alarmclock.data.repository.HolidayYearCoverage
import com.sysadmindoc.alarmclock.data.repository.WeatherRepository
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import com.sysadmindoc.alarmclock.domain.NextAlarmCalculator
import com.sysadmindoc.alarmclock.domain.ProductionSchedulingRoute
import com.sysadmindoc.alarmclock.domain.ProductionSchedulingRouteAuthority
import com.sysadmindoc.alarmclock.domain.ScheduleOutcome
import com.sysadmindoc.alarmclock.service.BedtimeZenRuleManager
import com.sysadmindoc.alarmclock.widget.WidgetUpdater
import com.sysadmindoc.alarmclock.worker.HolidaySyncWorker
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class P3RuntimeIntegrationTest {

    private lateinit var context: Context
    private lateinit var repository: AlarmRepository
    private lateinit var calculator: NextAlarmCalculator
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var holidayRepository: HolidayRepository
    private lateinit var incidentRepository: AlarmIncidentRepository
    private lateinit var weatherRepository: WeatherRepository
    private lateinit var workManager: WorkManager
    private lateinit var scheduler: AlarmScheduler
    private lateinit var mockAlarmManager: AlarmManager

    private val lastScheduledTrigger = AtomicLong(-1L)

    class InMemoryHolidayReconcileStateStore : HolidayReconcileStateStore {
        var state = HolidayReconcileState()
        override fun load(): HolidayReconcileState = state
        override fun save(state: HolidayReconcileState) { this.state = state }
        override fun clear() { state = HolidayReconcileState() }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repository = mockk(relaxed = true)
        calculator = mockk(relaxed = true)
        preferencesManager = mockk()
        holidayRepository = mockk(relaxed = true)
        incidentRepository = mockk(relaxed = true)
        weatherRepository = mockk(relaxed = true)
        workManager = mockk(relaxed = true)
        mockAlarmManager = mockk(relaxed = true)

        lastScheduledTrigger.set(-1L)

        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(any<Context>()) } returns workManager
        every { workManager.cancelUniqueWork(any()) } returns mockk<Operation>(relaxed = true)

        mockkObject(WidgetUpdater)
        every { WidgetUpdater.requestUpdate(any()) } just Runs

        mockkObject(BedtimeZenRuleManager)
        every { BedtimeZenRuleManager.syncRule(any(), any(), any()) } returns mockk(relaxed = true)

        coEvery { preferencesManager.getCurrentSettings() } returns AppSettings(
            showAlarmClockIcon = true,
            holidayCountryCode = "JP"
        )
        every { preferencesManager.getCachedSettings() } returns AppSettings(
            showAlarmClockIcon = true,
            holidayCountryCode = "JP"
        )
        coEvery { holidayRepository.isHoliday(any()) } returns false
        every { weatherRepository.getCachedWeather() } returns null

        val alarmClockInfoSlot = slot<AlarmManager.AlarmClockInfo>()
        every { mockAlarmManager.setAlarmClock(capture(alarmClockInfoSlot), any()) } answers {
            lastScheduledTrigger.set(alarmClockInfoSlot.captured.triggerTime)
        }
        every { mockAlarmManager.setExactAndAllowWhileIdle(any(), any(), any()) } answers {
            lastScheduledTrigger.set(secondArg<Long>())
        }

        scheduler = AlarmScheduler(
            context = context,
            repository = repository,
            calculator = calculator,
            preferencesManager = preferencesManager,
            holidayRepository = holidayRepository,
            alarmIncidentRepository = incidentRepository,
            weatherRepository = weatherRepository
        )
        scheduler.testAlarmManager = mockAlarmManager
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    /**
     * Test A: Generation check immediately before OS call.
     * User edit occurs after final pre-OS generation check, right at the immediate barrier.
     * The stale reconcile OS call must be fenced and aborted.
     */
    @Test
    fun testA_editAfterFinalGenerationCheck_fencesStaleOsRegistration() = runTest {
        val alarmId = 1L
        val staleGen1Alarm = Alarm(
            id = alarmId,
            generation = 1,
            revision = 1,
            hour = 8,
            minute = 0,
            repeatDays = setOf(DayOfWeek.MONDAY),
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            nextTriggerTime = 1000L
        )
        val userEditGen2Alarm = staleGen1Alarm.copy(
            generation = 2,
            revision = 1,
            hour = 9,
            nextTriggerTime = 3000L
        )

        var currentDbAlarm = staleGen1Alarm
        coEvery { repository.getById(alarmId) } answers { currentDbAlarm }
        coEvery { repository.updateNextTrigger(alarmId, any()) } answers {
            currentDbAlarm = currentDbAlarm.copy(nextTriggerTime = secondArg<Long>())
        }

        val barrierReached = CompletableDeferred<Unit>()
        val userEditFinished = CompletableDeferred<Unit>()

        scheduler.testOnImmediatePreOsBarrier = { id, trigger ->
            if (id == alarmId && trigger == 2000L) {
                barrierReached.complete(Unit)
                userEditFinished.await()
            }
        }

        val reconcileJob = launch {
            scheduler.scheduleAt(staleGen1Alarm, 2000L)
        }

        barrierReached.await()

        // User edit commits to DB and sets OS trigger to 3000L
        currentDbAlarm = userEditGen2Alarm
        lastScheduledTrigger.set(3000L)
        userEditFinished.complete(Unit)

        reconcileJob.join()

        // Verification: OS must retain user edit trigger 3000L
        assertEquals(
            "AlarmManager must retain user edit trigger 3000L; stale reconcile must be fenced",
            3000L,
            lastScheduledTrigger.get()
        )
    }

    /**
     * Test B: Retry-only Worker Regression resolved.
     * Worker with coordinator given a material change reconciles the affected alarm,
     * registers it with AlarmScheduler, and returns Result.success() (not retry!).
     */
    @Test
    fun testB_retryOnlyWorkerRegression_reconcilesAndCompletesTruthfully() = runTest {
        val alarmId = 10L
        val zone = ZoneId.systemDefault()
        val nowMs = LocalDate.of(2026, 1, 4).atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
        val holidayDate = LocalDate.of(2026, 1, 5) // Monday
        val targetDate = LocalDate.of(2026, 1, 6) // Tuesday
        val targetTriggerMs = targetDate.atTime(8, 0).atZone(zone).toInstant().toEpochMilli()

        val alarm = Alarm(
            id = alarmId,
            generation = 1,
            revision = 1,
            hour = 8,
            minute = 0,
            repeatDays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY),
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            holidayPolicy = Alarm.HOLIDAY_POLICY_EXCLUDE,
            nextTriggerTime = holidayDate.atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
        )

        var dbAlarm = alarm
        coEvery { repository.getEnabledNormalAlarms() } returns listOf(dbAlarm)
        coEvery { repository.getById(alarmId) } answers { dbAlarm }
        coEvery { repository.updateNextTrigger(alarmId, any()) } answers {
            dbAlarm = dbAlarm.copy(nextTriggerTime = secondArg<Long>())
        }

        val snapshot = HolidayProviderSnapshot(
            providerId = NAGER_DATE_PROVIDER_ID,
            countryCode = "JP",
            generation = 1L,
            years = mapOf(
                2026 to HolidayYearCoverage(2026, nowMs, setOf(holidayDate))
            )
        )
        coEvery { holidayRepository.refreshWithOutcome() } returns HolidayRefreshOutcome.Success(snapshot, materialChange = true)
        coEvery { holidayRepository.captureAuthorityWindow(any(), any()) } returns CapturedHolidayAuthorityWindow(
            startDate = LocalDate.of(2026, 1, 1),
            endDateInclusive = LocalDate.of(2026, 1, 31),
            configuredCountry = "JP",
            providerSnapshot = snapshot,
            capturedAtMillis = nowMs
        )

        val stateStore = InMemoryHolidayReconcileStateStore()
        val coordinator = HolidayReconcileCoordinator(
            context = context,
            holidayRepository = holidayRepository,
            alarmRepository = repository,
            alarmScheduler = scheduler,
            stateStore = stateStore
        )
        coordinator.clockMillis = { nowMs }

        val workerParams = mockk<WorkerParameters>(relaxed = true)
        val worker = HolidaySyncWorker(context, workerParams, holidayRepository, coordinator)

        val result = worker.doWork()

        assertTrue("Worker must complete with Result.success() when reconciliation succeeds", result is ListenableWorker.Result.Success)
        assertEquals("Alarm must be scheduled to Tuesday Jan 6 in OS", targetTriggerMs, lastScheduledTrigger.get())
        assertNull("Pending signature must be cleared after successful reconciliation", stateStore.state.pendingSignature)
    }

    /**
     * Test C: Snapshot replaced before reconciliation.
     * When previous snapshot is absent, coordinator discovers all alarms whose stored projection
     * differs from target and reconciles them.
     */
    @Test
    fun testC_snapshotReplacedBeforeReconciliation_recoversAllUnreconciledAlarms() = runTest {
        val zone = ZoneId.of("UTC")
        val nowMs = LocalDate.of(2026, 1, 4).atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
        val holidayDate = LocalDate.of(2026, 1, 5) // Monday
        val targetDate = LocalDate.of(2026, 1, 6) // Tuesday
        val targetTriggerMs = targetDate.atTime(8, 0).atZone(zone).toInstant().toEpochMilli()

        // 3 alarms: Alarm 1 was already updated to Tuesday, Alarms 2 and 3 still on Monday (unreconciled)
        val alarm1 = Alarm(id = 1L, generation = 1, revision = 1, hour = 8, minute = 0, repeatDays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY), scheduleKind = Alarm.SCHEDULE_KIND_REPEATING, holidayPolicy = Alarm.HOLIDAY_POLICY_EXCLUDE, nextTriggerTime = targetTriggerMs)
        val alarm2 = Alarm(id = 2L, generation = 1, revision = 1, hour = 8, minute = 0, repeatDays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY), scheduleKind = Alarm.SCHEDULE_KIND_REPEATING, holidayPolicy = Alarm.HOLIDAY_POLICY_EXCLUDE, nextTriggerTime = holidayDate.atTime(8, 0).atZone(zone).toInstant().toEpochMilli())
        val alarm3 = Alarm(id = 3L, generation = 1, revision = 1, hour = 8, minute = 0, repeatDays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY), scheduleKind = Alarm.SCHEDULE_KIND_REPEATING, holidayPolicy = Alarm.HOLIDAY_POLICY_EXCLUDE, nextTriggerTime = holidayDate.atTime(8, 0).atZone(zone).toInstant().toEpochMilli())

        var alarmMap = mutableMapOf(1L to alarm1, 2L to alarm2, 3L to alarm3)
        coEvery { repository.getEnabledNormalAlarms() } answers { alarmMap.values.toList() }
        coEvery { repository.getById(any()) } answers { alarmMap[firstArg<Long>()] }
        coEvery { repository.updateNextTrigger(any(), any()) } answers {
            val id = firstArg<Long>()
            val trig = secondArg<Long>()
            alarmMap[id] = alarmMap[id]!!.copy(nextTriggerTime = trig)
        }

        val snapshot = HolidayProviderSnapshot(
            providerId = NAGER_DATE_PROVIDER_ID,
            countryCode = "JP",
            generation = 2L,
            years = mapOf(2026 to HolidayYearCoverage(2026, nowMs, setOf(holidayDate)))
        )
        coEvery { holidayRepository.captureAuthorityWindow(any(), any()) } returns CapturedHolidayAuthorityWindow(
            startDate = LocalDate.of(2026, 1, 1),
            endDateInclusive = LocalDate.of(2026, 1, 31),
            configuredCountry = "JP",
            providerSnapshot = snapshot,
            capturedAtMillis = nowMs
        )

        val stateStore = InMemoryHolidayReconcileStateStore()
        val coordinator = HolidayReconcileCoordinator(
            context = context,
            holidayRepository = holidayRepository,
            alarmRepository = repository,
            alarmScheduler = scheduler,
            stateStore = stateStore
        )

        val result = coordinator.reconcile(
            isMaterialChange = false,
            previousClassifier = null, // No previous snapshot available
            evaluationEpochMs = nowMs,
            evaluationZoneId = zone
        )

        assertTrue(result is HolidayReconcileResult.Success)
        assertEquals("Alarms 2 and 3 must be updated to target trigger", targetTriggerMs, alarmMap[2L]!!.nextTriggerTime)
        assertEquals("Alarms 2 and 3 must be updated to target trigger", targetTriggerMs, alarmMap[3L]!!.nextTriggerTime)
    }

    /**
     * Test D: Crash after partial batch completion.
     * Alarm 1 was completed in earlier batch. Coordinator resumes and reconciles only alarms 2 and 3.
     */
    @Test
    fun testD_crashAfterPartialBatchCompletion_resumesOnlyUnfinishedAlarms() = runTest {
        val zone = ZoneId.of("UTC")
        val nowMs = LocalDate.of(2026, 1, 4).atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
        val holidayDate = LocalDate.of(2026, 1, 5)
        val targetTriggerMs = LocalDate.of(2026, 1, 6).atTime(8, 0).atZone(zone).toInstant().toEpochMilli()

        val alarm1 = Alarm(id = 1L, generation = 1, revision = 1, hour = 8, minute = 0, repeatDays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY), scheduleKind = Alarm.SCHEDULE_KIND_REPEATING, holidayPolicy = Alarm.HOLIDAY_POLICY_EXCLUDE, nextTriggerTime = holidayDate.atTime(8, 0).atZone(zone).toInstant().toEpochMilli())
        val alarm2 = Alarm(id = 2L, generation = 1, revision = 1, hour = 8, minute = 0, repeatDays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY), scheduleKind = Alarm.SCHEDULE_KIND_REPEATING, holidayPolicy = Alarm.HOLIDAY_POLICY_EXCLUDE, nextTriggerTime = holidayDate.atTime(8, 0).atZone(zone).toInstant().toEpochMilli())
        val alarm3 = Alarm(id = 3L, generation = 1, revision = 1, hour = 8, minute = 0, repeatDays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY), scheduleKind = Alarm.SCHEDULE_KIND_REPEATING, holidayPolicy = Alarm.HOLIDAY_POLICY_EXCLUDE, nextTriggerTime = holidayDate.atTime(8, 0).atZone(zone).toInstant().toEpochMilli())

        var alarmMap = mutableMapOf(1L to alarm1, 2L to alarm2, 3L to alarm3)
        val scheduledAlarms = mutableListOf<Long>()
        coEvery { repository.getEnabledNormalAlarms() } answers { alarmMap.values.toList() }
        coEvery { repository.getById(any()) } answers { alarmMap[firstArg<Long>()] }
        coEvery { repository.updateNextTrigger(any(), any()) } answers {
            val id = firstArg<Long>()
            scheduledAlarms.add(id)
            alarmMap[id] = alarmMap[id]!!.copy(nextTriggerTime = secondArg<Long>())
        }

        val snapshot = HolidayProviderSnapshot(
            providerId = NAGER_DATE_PROVIDER_ID,
            countryCode = "JP",
            generation = 1L,
            years = mapOf(2026 to HolidayYearCoverage(2026, nowMs, setOf(holidayDate)))
        )
        coEvery { holidayRepository.captureAuthorityWindow(any(), any()) } returns CapturedHolidayAuthorityWindow(
            startDate = LocalDate.of(2026, 1, 1),
            endDateInclusive = LocalDate.of(2026, 1, 31),
            configuredCountry = "JP",
            providerSnapshot = snapshot,
            capturedAtMillis = nowMs
        )

        // Store reflects partial completion of alarm 1
        val stateStore = InMemoryHolidayReconcileStateStore()
        stateStore.state = HolidayReconcileState(
            pendingSignature = "sig-batch-1",
            completedAlarmIds = setOf(1L)
        )

        val coordinator = HolidayReconcileCoordinator(
            context = context,
            holidayRepository = holidayRepository,
            alarmRepository = repository,
            alarmScheduler = scheduler,
            stateStore = stateStore
        )

        val result = coordinator.reconcile(
            isMaterialChange = true,
            evaluationEpochMs = nowMs,
            evaluationZoneId = zone
        )

        assertTrue(result is HolidayReconcileResult.Success)
        assertFalse("Alarm 1 must NOT be scheduled again (was already completed)", scheduledAlarms.contains(1L))
        assertTrue("Alarm 2 must be scheduled", scheduledAlarms.contains(2L))
        assertTrue("Alarm 3 must be scheduled", scheduledAlarms.contains(3L))
    }

    /**
     * Test E: Stored trigger zero arms newly available holiday occurrence.
     * Holiday-only INCLUDE alarm with stored trigger 0 is armed when holiday becomes available.
     */
    @Test
    fun testE_storedTriggerZero_armsNewlyAvailableHolidayOccurrence() = runTest {
        val zone = ZoneId.of("UTC")
        val nowMs = LocalDate.of(2026, 1, 4).atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
        val holidayDate = LocalDate.of(2026, 1, 5)
        val targetTriggerMs = holidayDate.atTime(8, 0).atZone(zone).toInstant().toEpochMilli()

        val includeAlarm = Alarm(
            id = 50L,
            generation = 1,
            revision = 1,
            hour = 8,
            minute = 0,
            repeatDays = emptySet(),
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            holidayPolicy = Alarm.HOLIDAY_POLICY_INCLUDE,
            nextTriggerTime = 0L // stored trigger is 0
        )

        var dbAlarm = includeAlarm
        coEvery { repository.getEnabledNormalAlarms() } returns listOf(dbAlarm)
        coEvery { repository.getById(50L) } answers { dbAlarm }
        coEvery { repository.updateNextTrigger(50L, any()) } answers {
            dbAlarm = dbAlarm.copy(nextTriggerTime = secondArg<Long>())
        }

        val snapshot = HolidayProviderSnapshot(
            providerId = NAGER_DATE_PROVIDER_ID,
            countryCode = "JP",
            generation = 1L,
            years = mapOf(2026 to HolidayYearCoverage(2026, nowMs, setOf(holidayDate)))
        )
        coEvery { holidayRepository.captureAuthorityWindow(any(), any()) } returns CapturedHolidayAuthorityWindow(
            startDate = LocalDate.of(2026, 1, 1),
            endDateInclusive = LocalDate.of(2026, 1, 31),
            configuredCountry = "JP",
            providerSnapshot = snapshot,
            capturedAtMillis = nowMs
        )

        val coordinator = HolidayReconcileCoordinator(
            context = context,
            holidayRepository = holidayRepository,
            alarmRepository = repository,
            alarmScheduler = scheduler,
            stateStore = InMemoryHolidayReconcileStateStore()
        )

        val result = coordinator.reconcile(
            isMaterialChange = true,
            evaluationEpochMs = nowMs,
            evaluationZoneId = zone
        )

        assertTrue(result is HolidayReconcileResult.Success)
        assertEquals("Include alarm must be armed at holiday occurrence", targetTriggerMs, dbAlarm.nextTriggerTime)
        assertEquals("OS AlarmManager must be armed at holiday occurrence", targetTriggerMs, lastScheduledTrigger.get())
    }

    /**
     * Test F: DB trigger equal without OS registration forces OS arming.
     * Alarm 1 has nextTriggerTime = 2000L in Room matching target trigger, but OS is unconfirmed.
     * Reconcile forces OS arming via AlarmScheduler.
     */
    @Test
    fun testF_dbTriggerEqualWithoutOsRegistration_forcesOsArming() = runTest {
        val alarmId = 1L
        val targetTrigger = 2000L
        val alarm = Alarm(
            id = alarmId,
            generation = 1,
            revision = 1,
            hour = 8,
            minute = 0,
            repeatDays = setOf(DayOfWeek.MONDAY),
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            nextTriggerTime = targetTrigger
        )

        lastScheduledTrigger.set(-1L)
        coEvery { repository.getById(alarmId) } returns alarm

        val coordinator = HolidayReconcileCoordinator(
            context = context,
            holidayRepository = holidayRepository,
            alarmRepository = repository,
            alarmScheduler = scheduler,
            stateStore = InMemoryHolidayReconcileStateStore()
        )

        val outcome = coordinator.reconcileAlarm(alarm, targetTrigger, isOsConfirmed = false)
        assertTrue("Alarm must be armed in OS", outcome is ScheduleOutcome.Armed)
        assertEquals("OS AlarmManager must receive registration", targetTrigger, lastScheduledTrigger.get())
    }

    /**
     * Test G: Equivalent refresh with completed reconcile is idempotent no-op.
     * When lastReconciledSignature matches current signature, reconcile is a no-op.
     */
    @Test
    fun testG_equivalentRefreshWithCompletedReconcile_isIdempotentNoOp() = runTest {
        val zone = ZoneId.of("UTC")
        val nowMs = LocalDate.of(2026, 1, 4).atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
        val holidayDate = LocalDate.of(2026, 1, 5)

        val snapshot = HolidayProviderSnapshot(
            providerId = NAGER_DATE_PROVIDER_ID,
            countryCode = "JP",
            generation = 1L,
            years = mapOf(2026 to HolidayYearCoverage(2026, nowMs, setOf(holidayDate)))
        )
        coEvery { holidayRepository.captureAuthorityWindow(any(), any()) } returns CapturedHolidayAuthorityWindow(
            startDate = LocalDate.of(2026, 1, 1),
            endDateInclusive = LocalDate.of(2026, 1, 31),
            configuredCountry = "JP",
            providerSnapshot = snapshot,
            capturedAtMillis = nowMs
        )

        val detector = HolidayMaterialChangeDetector()
        val classifier = CapturedHolidayAuthorityWindow(
            startDate = LocalDate.of(2026, 1, 1),
            endDateInclusive = LocalDate.of(2026, 1, 31),
            configuredCountry = "JP",
            providerSnapshot = snapshot,
            capturedAtMillis = nowMs
        ).asLocalHolidayClassifier()
        val sig = detector.computeSignature(classifier, LocalDate.of(2026, 1, 1), 31)

        val stateStore = InMemoryHolidayReconcileStateStore()
        stateStore.state = HolidayReconcileState(
            lastReconciledSignature = sig.sha256Hex,
            pendingSignature = null
        )

        val coordinator = HolidayReconcileCoordinator(
            context = context,
            holidayRepository = holidayRepository,
            alarmRepository = repository,
            alarmScheduler = scheduler,
            stateStore = stateStore
        )

        val result = coordinator.reconcile(
            isMaterialChange = false,
            evaluationEpochMs = nowMs,
            evaluationZoneId = zone
        )

        assertTrue("Must be Success with isNoOp = true", result is HolidayReconcileResult.Success && result.isNoOp)
        assertEquals(-1L, lastScheduledTrigger.get())
    }

    /**
     * Test H: Failed OS registration retries until success.
     * When OS registration fails, reconcile returns PendingRetry and does not advance signature.
     */
    @Test
    fun testH_failedOsRegistration_retriesUntilSuccess() = runTest {
        val zone = ZoneId.of("UTC")
        val nowMs = LocalDate.of(2026, 1, 4).atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
        val holidayDate = LocalDate.of(2026, 1, 5)

        val alarm = Alarm(
            id = 77L,
            generation = 1,
            revision = 1,
            hour = 8,
            minute = 0,
            repeatDays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY),
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            holidayPolicy = Alarm.HOLIDAY_POLICY_EXCLUDE,
            nextTriggerTime = holidayDate.atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
        )

        var dbAlarm = alarm
        coEvery { repository.getEnabledNormalAlarms() } returns listOf(dbAlarm)
        coEvery { repository.getById(77L) } answers { dbAlarm }
        coEvery { repository.updateNextTrigger(77L, any()) } answers {
            dbAlarm = dbAlarm.copy(nextTriggerTime = secondArg<Long>())
        }

        val snapshot = HolidayProviderSnapshot(
            providerId = NAGER_DATE_PROVIDER_ID,
            countryCode = "JP",
            generation = 1L,
            years = mapOf(2026 to HolidayYearCoverage(2026, nowMs, setOf(holidayDate)))
        )
        coEvery { holidayRepository.captureAuthorityWindow(any(), any()) } returns CapturedHolidayAuthorityWindow(
            startDate = LocalDate.of(2026, 1, 1),
            endDateInclusive = LocalDate.of(2026, 1, 31),
            configuredCountry = "JP",
            providerSnapshot = snapshot,
            capturedAtMillis = nowMs
        )

        // Simulate OS failure on attempt 1
        var shouldThrow = true
        every { mockAlarmManager.setAlarmClock(any(), any()) } answers {
            if (shouldThrow) {
                throw SecurityException("Exact alarm permission revoked")
            } else {
                lastScheduledTrigger.set(firstArg<AlarmManager.AlarmClockInfo>().triggerTime)
            }
        }

        val stateStore = InMemoryHolidayReconcileStateStore()
        val coordinator = HolidayReconcileCoordinator(
            context = context,
            holidayRepository = holidayRepository,
            alarmRepository = repository,
            alarmScheduler = scheduler,
            stateStore = stateStore
        )

        // Attempt 1: Fails
        val result1 = coordinator.reconcile(isMaterialChange = true, evaluationEpochMs = nowMs, evaluationZoneId = zone)
        assertTrue("Attempt 1 must return PendingRetry", result1 is HolidayReconcileResult.PendingRetry)
        assertNull("Signature must NOT be advanced after failure", stateStore.state.lastReconciledSignature)
        assertEquals("Pending signature must remain recorded", 1, stateStore.state.pendingSignature?.length?.coerceAtMost(1) ?: 0)

        // Attempt 2: Succeeds
        shouldThrow = false
        val result2 = coordinator.reconcile(isMaterialChange = true, evaluationEpochMs = nowMs, evaluationZoneId = zone)
        assertTrue("Attempt 2 must succeed", result2 is HolidayReconcileResult.Success)
        assertTrue("Signature must now be advanced", stateStore.state.lastReconciledSignature != null)
        assertNull("Pending signature must be cleared", stateStore.state.pendingSignature)
    }

    /**
     * Test I: UNKNOWN classification and intentional no-arm semantics.
     * UNKNOWN fails safe to Base schedule (KNO-31).
     * Fully suppressed alarms produce NoArm and are marked completed (no infinite retry).
     */
    @Test
    fun testI_unknownClassificationAndIntentionalNoArm_truthfulSemantics() = runTest {
        val zone = ZoneId.of("UTC")
        val nowMs = LocalDate.of(2026, 1, 4).atTime(8, 0).atZone(zone).toInstant().toEpochMilli()

        // Include alarm with NO base repeat days. When all dates are UNKNOWN (no holidays), next occurrence is 0.
        val includeAlarm = Alarm(
            id = 99L,
            generation = 1,
            revision = 1,
            hour = 8,
            minute = 0,
            repeatDays = emptySet(),
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            holidayPolicy = Alarm.HOLIDAY_POLICY_INCLUDE,
            nextTriggerTime = 12345L
        )

        var dbAlarm = includeAlarm
        coEvery { repository.getEnabledNormalAlarms() } returns listOf(dbAlarm)
        coEvery { repository.getById(99L) } answers { dbAlarm }
        coEvery { repository.updateNextTrigger(99L, any()) } answers {
            dbAlarm = dbAlarm.copy(nextTriggerTime = secondArg<Long>())
        }

        // All dates classify as UNKNOWN when country and provider snapshot are null
        coEvery { holidayRepository.captureAuthorityWindow(any(), any()) } returns CapturedHolidayAuthorityWindow(
            startDate = LocalDate.of(2026, 1, 1),
            endDateInclusive = LocalDate.of(2026, 1, 31),
            configuredCountry = null,
            providerSnapshot = null,
            capturedAtMillis = nowMs
        )

        val stateStore = InMemoryHolidayReconcileStateStore()
        val coordinator = HolidayReconcileCoordinator(
            context = context,
            holidayRepository = holidayRepository,
            alarmRepository = repository,
            alarmScheduler = scheduler,
            stateStore = stateStore
        )

        val result = coordinator.reconcile(
            isMaterialChange = true,
            evaluationEpochMs = nowMs,
            evaluationZoneId = zone
        )

        assertTrue("Must complete with Success (intentional no-arm suppression is settled)", result is HolidayReconcileResult.Success)
        assertEquals("Database trigger must be zeroed for suppressed alarm", 0L, dbAlarm.nextTriggerTime)
        assertNull("Pending signature must be cleared (not stuck in retry loop)", stateStore.state.pendingSignature)
    }

    /**
     * Test J: LEGACY_ONLY single OS writer contract.
     * Verifies that production route is LEGACY_ONLY and direct OS registration executes without owner authority.
     */
    @Test
    fun testJ_legacyOnly_singleOsWriterContract() = runTest {
        val routeAuthority = ProductionSchedulingRouteAuthority()
        assertEquals("Active route must strictly be LEGACY_ONLY", ProductionSchedulingRoute.LEGACY_ONLY, routeAuthority.currentRoute)
        assertFalse("Owner route must NOT be active", routeAuthority.isOwnerRouteActive())

        val alarm = Alarm(
            id = 100L,
            generation = 1,
            revision = 1,
            hour = 8,
            minute = 0,
            repeatDays = setOf(DayOfWeek.MONDAY),
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            nextTriggerTime = 1000L
        )
        var currentDbAlarm = alarm
        coEvery { repository.getById(100L) } answers { currentDbAlarm }
        coEvery { repository.updateNextTrigger(100L, any()) } answers {
            currentDbAlarm = currentDbAlarm.copy(nextTriggerTime = secondArg<Long>())
        }

        val outcome = scheduler.scheduleAt(alarm, 2000L)
        assertTrue("ScheduleAt under LEGACY_ONLY must succeed directly", outcome is ScheduleOutcome.Armed)
        assertEquals(2000L, lastScheduledTrigger.get())
    }

    /**
     * Test K: Canonical scheduler integrates ScheduleOccurrenceEvaluator for repeating normal alarms.
     * Evaluates EXCLUDE holiday policy: base Monday is a holiday, so occurrence advances to next Monday.
     */
    @Test
    fun testK_canonicalScheduler_repeatingNormalAlarm_evaluatesHolidayPolicy() = runTest {
        val zone = ZoneId.systemDefault()
        val now = LocalDate.now(zone)
        val nextMonday = now.with(java.time.temporal.TemporalAdjusters.next(DayOfWeek.MONDAY))
        val followingMonday = nextMonday.plusWeeks(1)
        val nowMs = System.currentTimeMillis()

        val snapshot = HolidayProviderSnapshot(
            providerId = NAGER_DATE_PROVIDER_ID,
            countryCode = "JP",
            generation = 1L,
            years = mapOf(
                nextMonday.year to HolidayYearCoverage(nextMonday.year, nowMs, setOf(nextMonday))
            )
        )
        coEvery { holidayRepository.captureAuthorityWindow(any(), any()) } returns CapturedHolidayAuthorityWindow(
            startDate = now,
            endDateInclusive = now.plusDays(60),
            configuredCountry = "JP",
            providerSnapshot = snapshot,
            capturedAtMillis = nowMs
        )

        val alarm = Alarm(
            id = 101L,
            generation = 1,
            revision = 1,
            hour = 8,
            minute = 0,
            repeatDays = setOf(DayOfWeek.MONDAY),
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            holidayPolicy = Alarm.HOLIDAY_POLICY_EXCLUDE
        )
        var dbAlarm = alarm
        coEvery { repository.getById(101L) } answers { dbAlarm }
        coEvery { repository.updateNextTrigger(101L, any()) } answers {
            dbAlarm = dbAlarm.copy(nextTriggerTime = secondArg<Long>())
        }

        val outcome = scheduler.schedule(alarm)
        assertTrue("Must be armed", outcome is ScheduleOutcome.Armed)
        val expectedTrigger = followingMonday.atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(expectedTrigger, lastScheduledTrigger.get())
        assertEquals(expectedTrigger, dbAlarm.nextTriggerTime)
    }

    /**
     * Test L: Canonical scheduler supports INCLUDE holiday policy with zero weekdays (holiday-only).
     * Synthesizes trigger on the holiday date.
     */
    @Test
    fun testL_canonicalScheduler_includeHolidayOnly_zeroWeekdays_synthesizesTrigger() = runTest {
        val zone = ZoneId.systemDefault()
        val now = LocalDate.now(zone)
        val holidayDate = now.plusDays(2)
        val nowMs = System.currentTimeMillis()

        val snapshot = HolidayProviderSnapshot(
            providerId = NAGER_DATE_PROVIDER_ID,
            countryCode = "JP",
            generation = 1L,
            years = mapOf(
                holidayDate.year to HolidayYearCoverage(holidayDate.year, nowMs, setOf(holidayDate))
            )
        )
        coEvery { holidayRepository.captureAuthorityWindow(any(), any()) } returns CapturedHolidayAuthorityWindow(
            startDate = now,
            endDateInclusive = now.plusDays(60),
            configuredCountry = "JP",
            providerSnapshot = snapshot,
            capturedAtMillis = nowMs
        )

        val holidayOnlyAlarm = Alarm(
            id = 102L,
            generation = 1,
            revision = 1,
            hour = 9,
            minute = 30,
            repeatDays = emptySet(),
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            holidayPolicy = Alarm.HOLIDAY_POLICY_INCLUDE
        )
        var dbAlarm = holidayOnlyAlarm
        coEvery { repository.getById(102L) } answers { dbAlarm }
        coEvery { repository.updateNextTrigger(102L, any()) } answers {
            dbAlarm = dbAlarm.copy(nextTriggerTime = secondArg<Long>())
        }

        val outcome = scheduler.schedule(holidayOnlyAlarm)
        assertTrue("Must arm on the holiday date", outcome is ScheduleOutcome.Armed)
        val expectedTrigger = holidayDate.atTime(9, 30).atZone(zone).toInstant().toEpochMilli()
        assertEquals(expectedTrigger, lastScheduledTrigger.get())
        assertEquals(expectedTrigger, dbAlarm.nextTriggerTime)
    }

    /**
     * Test M: Durable state store invalidates stale pending progress on truth signature mismatch.
     */
    @Test
    fun testM_durableStateStore_signatureMismatch_invalidatesStalePendingProgress() = runTest {
        val zone = ZoneId.of("UTC")
        val nowMs = LocalDate.of(2026, 1, 4).atTime(8, 0).atZone(zone).toInstant().toEpochMilli()

        val alarm = Alarm(
            id = 103L,
            generation = 1,
            revision = 1,
            hour = 8,
            minute = 0,
            repeatDays = setOf(DayOfWeek.MONDAY),
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            holidayPolicy = Alarm.HOLIDAY_POLICY_EXCLUDE
        )
        var dbAlarm = alarm
        coEvery { repository.getEnabledNormalAlarms() } returns listOf(dbAlarm)
        coEvery { repository.getById(103L) } answers { dbAlarm }
        coEvery { repository.updateNextTrigger(103L, any()) } answers {
            dbAlarm = dbAlarm.copy(nextTriggerTime = secondArg<Long>())
        }

        coEvery { holidayRepository.captureAuthorityWindow(any(), any()) } returns CapturedHolidayAuthorityWindow(
            startDate = LocalDate.of(2026, 1, 1),
            endDateInclusive = LocalDate.of(2026, 1, 31),
            configuredCountry = "JP",
            providerSnapshot = null,
            capturedAtMillis = nowMs
        )

        val stateStore = InMemoryHolidayReconcileStateStore()
        stateStore.state = HolidayReconcileState(
            pendingSignature = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            completedAlarmIds = setOf(103L),
            completedGenerations = mapOf(103L to 1)
        )

        val coordinator = HolidayReconcileCoordinator(
            context = context,
            holidayRepository = holidayRepository,
            alarmRepository = repository,
            alarmScheduler = scheduler,
            stateStore = stateStore
        )

        val result = coordinator.reconcile(isMaterialChange = true, evaluationEpochMs = nowMs, evaluationZoneId = zone)
        assertTrue("Reconcile must succeed", result is HolidayReconcileResult.Success)
        val reconciledCount = (result as HolidayReconcileResult.Success).reconciledCount
        assertEquals("Alarm must be evaluated and reconciled despite stale completedIds", 1, reconciledCount)
    }

    /**
     * Test N: AtomicFile write failure in FileHolidayReconcileStateStore throws and causes PendingRetry.
     */
    @Test
    fun testN_durableStateStore_saveFailure_propagatedToCoordinator() = runTest {
        val failingStore = object : HolidayReconcileStateStore {
            override fun load(): HolidayReconcileState = HolidayReconcileState()
            override fun save(state: HolidayReconcileState) {
                throw java.io.IOException("Disk I/O failure simulation")
            }
            override fun clear() {}
        }

        val alarm = Alarm(
            id = 104L,
            generation = 1,
            revision = 1,
            hour = 8,
            minute = 0,
            repeatDays = setOf(DayOfWeek.MONDAY),
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            holidayPolicy = Alarm.HOLIDAY_POLICY_EXCLUDE
        )
        coEvery { repository.getEnabledNormalAlarms() } returns listOf(alarm)
        coEvery { repository.getById(104L) } returns alarm

        val coordinator = HolidayReconcileCoordinator(
            context = context,
            holidayRepository = holidayRepository,
            alarmRepository = repository,
            alarmScheduler = scheduler,
            stateStore = failingStore
        )

        val result = coordinator.reconcile(isMaterialChange = true)
        assertTrue("Save failure must return PendingRetry", result is HolidayReconcileResult.PendingRetry)
        val cause = (result as HolidayReconcileResult.PendingRetry).failureCause
        assertTrue("Cause must be the IOException", cause is java.io.IOException)
    }

    /**
     * Test O: HolidaySyncWorker handles PartialSuccess with materialChange = true.
     */
    @Test
    fun testO_holidaySyncWorker_partialSuccessWithMaterialChange_triggersReconcile() = runTest {
        val workerParams: WorkerParameters = mockk(relaxed = true)
        every { workerParams.runAttemptCount } returns 0

        val snapshot = HolidayProviderSnapshot(
            providerId = NAGER_DATE_PROVIDER_ID,
            countryCode = "JP",
            generation = 1L,
            years = mapOf(
                2026 to HolidayYearCoverage(2026, 0L, emptySet())
            )
        )
        coEvery { holidayRepository.refreshWithOutcome() } returns HolidayRefreshOutcome.PartialSuccess(
            snapshot = snapshot,
            successfulYears = setOf(2026),
            failedYears = setOf(2027),
            materialChange = true
        )

        val coordinator: HolidayReconcileCoordinator = mockk(relaxed = true)
        every { coordinator.clockMillis } returns { System.currentTimeMillis() }
        coEvery { coordinator.reconcile(any(), any(), any(), any()) } returns HolidayReconcileResult.Success(reconciledCount = 1)

        val worker = HolidaySyncWorker(context, workerParams, holidayRepository, coordinator)
        val result = worker.doWork()

        coVerify(exactly = 1) { coordinator.reconcile(isMaterialChange = true, any(), any(), any()) }
        assertEquals("Worker maps PartialSuccess with failed years to retry", ListenableWorker.Result.retry(), result)
    }

    /**
     * Test P: BootRescheduleWorker coordinates pending holiday reconciliation.
     */
    @Test
    fun testP_bootRescheduleWorker_coordinatesPendingHolidayReconciliation() = runTest {
        val workerParams: WorkerParameters = mockk(relaxed = true)
        every { workerParams.runAttemptCount } returns 0

        val coordinator: HolidayReconcileCoordinator = mockk(relaxed = true)
        every { coordinator.clockMillis } returns { System.currentTimeMillis() }
        coEvery { coordinator.reconcile(any(), any(), any(), any()) } returns HolidayReconcileResult.PendingRetry(pendingCount = 1)

        val bootWorker = com.sysadmindoc.alarmclock.worker.BootRescheduleWorker(
            context = context,
            workerParams = workerParams,
            alarmScheduler = scheduler,
            holidayReconcileCoordinator = coordinator
        )

        val result = bootWorker.doWork()
        assertEquals("Worker must retry when coordinator reports pending work", ListenableWorker.Result.retry(), result)
    }

    /**
     * Test Q: AlarmScheduler fails closed when ScheduleException DAO throws.
     * Prevents arming an alarm when exception authority cannot be read.
     */
    @Test
    fun testQ_alarmScheduler_scheduleExceptionDaoFailure_failsClosedWithoutArming() = runTest {
        val zone = ZoneId.systemDefault()
        val now = LocalDate.now(zone)
        val nowMs = System.currentTimeMillis()

        coEvery { holidayRepository.captureAuthorityWindow(any(), any()) } returns CapturedHolidayAuthorityWindow(
            startDate = now,
            endDateInclusive = now.plusDays(104),
            configuredCountry = "JP",
            providerSnapshot = null,
            capturedAtMillis = nowMs
        )

        val exceptionDao = mockk<com.sysadmindoc.alarmclock.data.local.ScheduleExceptionDao>()
        coEvery { repository.database.scheduleExceptionDao() } returns exceptionDao
        coEvery { exceptionDao.getExceptionsForAlarmGeneration(any(), any()) } throws android.database.sqlite.SQLiteDiskIOException("Disk read failure")

        val alarm = Alarm(
            id = 201L,
            generation = 1,
            revision = 1,
            hour = 8,
            minute = 0,
            repeatDays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            holidayPolicy = Alarm.HOLIDAY_POLICY_EXCLUDE
        )
        var dbAlarm = alarm
        coEvery { repository.getById(201L) } answers { dbAlarm }

        val outcome = scheduler.schedule(alarm)

        assertTrue("Outcome must be RegistrationFailed", outcome is ScheduleOutcome.RegistrationFailed)
        assertEquals("Trigger must not be registered with AlarmManager", -1L, lastScheduledTrigger.get())
        assertEquals("DB trigger must remain unmutated", 0L, dbAlarm.nextTriggerTime)
    }

    /**
     * Test R: Durable state store throws on read corruption and clear failure,
     * causing coordinator to fail closed with PendingRetry.
     */
    @Test
    fun testR_durableStateStore_readAndClearFailures_failClosedWithPendingRetry() = runTest {
        val storeFile = java.io.File(context.filesDir, "corrupted_reconcile_state.json")
        storeFile.writeText("THIS IS NOT VALID JSON {[[{")

        val store = FileHolidayReconcileStateStore(context, "corrupted_reconcile_state.json")
        try {
            store.load()
            fail("Expected load() to throw JSONException on corrupted file")
        } catch (e: org.json.JSONException) {
            // Expected fail-closed behavior
        }

        val coordinator = HolidayReconcileCoordinator(
            context = context,
            holidayRepository = holidayRepository,
            alarmRepository = repository,
            alarmScheduler = scheduler,
            stateStore = store
        )

        val result = coordinator.reconcile(isMaterialChange = false)
        assertTrue("Corrupted state store must fail closed to PendingRetry", result is HolidayReconcileResult.PendingRetry)
        val pendingRetry = result as HolidayReconcileResult.PendingRetry
        assertTrue("Cause must be JSONException", pendingRetry.failureCause is org.json.JSONException)

        store.clear()
        assertFalse("File must be deleted after clear()", storeFile.exists())
    }

    /**
     * Test S: BootRescheduleWorker returns Result.failure() upon retry exhaustion
     * while coordinator reconciliation remains PendingRetry (does not claim false success).
     */
    @Test
    fun testS_bootRescheduleWorker_retryExhaustionWithPendingReconcile_returnsFailure() = runTest {
        val workerParams: WorkerParameters = mockk(relaxed = true)
        every { workerParams.runAttemptCount } returns 3 // MAX_ATTEMPTS reached

        val coordinator: HolidayReconcileCoordinator = mockk(relaxed = true)
        every { coordinator.clockMillis } returns { System.currentTimeMillis() }
        coEvery { coordinator.reconcile(any(), any(), any(), any()) } returns HolidayReconcileResult.PendingRetry(pendingCount = 1)

        val bootWorker = com.sysadmindoc.alarmclock.worker.BootRescheduleWorker(
            context = context,
            workerParams = workerParams,
            alarmScheduler = scheduler,
            holidayReconcileCoordinator = coordinator
        )

        val result = bootWorker.doWork()
        assertEquals(
            "Worker must return failure on retry exhaustion when reconciliation is pending",
            ListenableWorker.Result.failure(),
            result
        )
    }

    /**
     * Test T: Holiday evaluation horizon extends to full MAX_HOLIDAY_EVALUATION_WINDOW_DAYS (104 days).
     * Correctly evaluates holidays beyond day 60 without premature UNKNOWN fallback.
     */
    @Test
    fun testT_holidayEvaluationHorizon_extendsToMaxSearchHorizon_evaluatesHolidaysPastDay60() = runTest {
        val zone = ZoneId.systemDefault()
        val now = LocalDate.now(zone)
        val holidayPast60 = now.plusDays(75) // 75 days in the future (past old 60-day limit)
        val nowMs = System.currentTimeMillis()

        val capturedEndDates = mutableListOf<LocalDate>()
        val snapshot = HolidayProviderSnapshot(
            providerId = NAGER_DATE_PROVIDER_ID,
            countryCode = "JP",
            generation = 1L,
            years = mapOf(
                holidayPast60.year to HolidayYearCoverage(holidayPast60.year, nowMs, setOf(holidayPast60))
            )
        )
        coEvery { holidayRepository.captureAuthorityWindow(any(), any()) } answers {
            val start = firstArg<LocalDate>()
            val end = secondArg<LocalDate>()
            capturedEndDates.add(end)
            CapturedHolidayAuthorityWindow(
                startDate = start,
                endDateInclusive = end,
                configuredCountry = "JP",
                providerSnapshot = snapshot,
                capturedAtMillis = nowMs
            )
        }

        val holidayOnlyAlarm = Alarm(
            id = 202L,
            generation = 1,
            revision = 1,
            hour = 10,
            minute = 0,
            repeatDays = emptySet(),
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            holidayPolicy = Alarm.HOLIDAY_POLICY_INCLUDE
        )
        var dbAlarm = holidayOnlyAlarm
        coEvery { repository.getById(202L) } answers { dbAlarm }
        coEvery { repository.updateNextTrigger(202L, any()) } answers {
            dbAlarm = dbAlarm.copy(nextTriggerTime = secondArg<Long>())
        }

        val outcome = scheduler.schedule(holidayOnlyAlarm)

        assertTrue("Scheduler must request authority window extending >= 100 days", capturedEndDates.any { it >= now.plusDays(100) })
        assertTrue("Must arm on holiday falling on day 75", outcome is ScheduleOutcome.Armed)
        val expectedTrigger = holidayPast60.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(expectedTrigger, lastScheduledTrigger.get())
        assertEquals(expectedTrigger, dbAlarm.nextTriggerTime)
    }

    /**
     * Test U: BootRescheduleWorker returns Result.failure() upon retry exhaustion
     * when batch has registration failures, even if Holiday reconciliation is not pending.
     */
    @Test
    fun testU_bootRescheduleWorker_retryExhaustionWithBatchFailure_returnsFailure() = runTest {
        val workerParams: WorkerParameters = mockk(relaxed = true)
        every { workerParams.runAttemptCount } returns 3 // MAX_ATTEMPTS reached

        val mockScheduler: AlarmScheduler = mockk(relaxed = true)
        coEvery { mockScheduler.rescheduleAllInBatches(any(), any()) } returns ScheduleOutcome.BatchResult(
            armedCount = 1,
            blockedCount = 0,
            failedCount = 1,
            noArmCount = 0,
            outcomes = mapOf(
                203L to ScheduleOutcome.RegistrationFailed(203L, IllegalStateException("OS registration failure"))
            )
        )

        val coordinator: HolidayReconcileCoordinator = mockk(relaxed = true)
        every { coordinator.clockMillis } returns { System.currentTimeMillis() }
        coEvery { coordinator.reconcile(any(), any(), any(), any()) } returns HolidayReconcileResult.Success(reconciledCount = 0, isNoOp = true)

        val bootWorker = com.sysadmindoc.alarmclock.worker.BootRescheduleWorker(
            context = context,
            workerParams = workerParams,
            alarmScheduler = mockScheduler,
            holidayReconcileCoordinator = coordinator
        )

        val result = bootWorker.doWork()
        assertEquals(
            "Worker must return failure on retry exhaustion when batch registration fails",
            ListenableWorker.Result.failure(),
            result
        )
    }
}

