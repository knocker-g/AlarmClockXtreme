package com.sysadmindoc.alarmclock.data.holiday

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.AlarmDao
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.data.remote.HolidayApi
import com.sysadmindoc.alarmclock.data.repository.AlarmIncidentRepository
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.data.repository.FileHolidayReconcileStateStore
import com.sysadmindoc.alarmclock.data.repository.HolidayReconcileCoordinator
import com.sysadmindoc.alarmclock.data.repository.HolidayReconcileResult
import com.sysadmindoc.alarmclock.data.repository.HolidayReconcileState
import com.sysadmindoc.alarmclock.data.repository.HolidayReconcileStateStore
import com.sysadmindoc.alarmclock.data.repository.HolidayRepository
import com.sysadmindoc.alarmclock.data.repository.ManualHolidayOverride
import com.sysadmindoc.alarmclock.data.repository.WeatherRepository
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import com.sysadmindoc.alarmclock.domain.NextAlarmCalculator
import com.sysadmindoc.alarmclock.domain.ScheduleOutcome
import com.sysadmindoc.alarmclock.domain.evaluator.ScheduleOccurrenceEvaluator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ManualHolidayReconcileIntegrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var db: AlarmDatabase
    private lateinit var alarmDao: AlarmDao
    private lateinit var alarmRepo: AlarmRepository
    private lateinit var prefs: PreferencesManager
    private lateinit var holidayApi: HolidayApi
    private lateinit var calculator: NextAlarmCalculator
    private lateinit var incidentRepo: AlarmIncidentRepository
    private lateinit var weatherRepo: WeatherRepository

    private val d1 = LocalDate.of(2026, 12, 23)
    private val d2 = LocalDate.of(2026, 12, 24)
    private val d3 = LocalDate.of(2026, 12, 25)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ShadowAlarmManager.setCanScheduleExactAlarms(true)

        db = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        alarmDao = db.alarmDao()
        alarmRepo = AlarmRepository(db, alarmDao, db.alarmGroupDao())

        prefs = mockk(relaxed = true)
        coEvery { prefs.getCurrentSettings() } returns AppSettings(holidayCountryCode = "JP")
        holidayApi = mockk(relaxed = true)
        calculator = mockk(relaxed = true)
        incidentRepo = mockk(relaxed = true)
        weatherRepo = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun createMockCoordinator(): HolidayReconcileCoordinator {
        val mock = mockk<HolidayReconcileCoordinator>()
        every { mock.clockMillis } returns { System.currentTimeMillis() }
        coEvery { mock.reconcile(any(), any(), any(), any()) } returns
            HolidayReconcileResult.Success(reconciledCount = 1, isNoOp = false)
        return mock
    }

    @Test
    fun test1_single_mutation_commits_and_triggers_material_reconcile() = runTest {
        val file = File(tempFolder.root, "test1_overrides.json")
        val mockCoordinator = createMockCoordinator()

        val store = FileManualHolidayOverrideStore(
            file = file,
            reconcileCoordinatorProvider = { mockCoordinator }
        )

        store.setOverride(d3, ManualHolidayOverride.FORCE_HOLIDAY)

        assertEquals(ManualHolidayOverride.FORCE_HOLIDAY, store.overrideFor(d3))
        assertTrue(file.exists())
        assertTrue(file.length() > 0)

        // Verify reconcile was called with isMaterialChange = true
        coVerify(exactly = 1) {
            mockCoordinator.reconcile(
                isMaterialChange = eq(true),
                previousClassifier = any(),
                evaluationEpochMs = any(),
                evaluationZoneId = any()
            )
        }
    }

    @Test
    fun test2_range_mutation_commits_and_triggers_single_material_reconcile() = runTest {
        val file = File(tempFolder.root, "test2_overrides.json")
        val mockCoordinator = createMockCoordinator()

        val store = FileManualHolidayOverrideStore(
            file = file,
            reconcileCoordinatorProvider = { mockCoordinator }
        )

        val result = store.setRange(d1, d3, ManualHolidayOverride.FORCE_HOLIDAY, overwriteExisting = true)
        assertTrue(result is RangeMutationResult.Applied)
        assertEquals(3, (result as RangeMutationResult.Applied).affectedDatesCount)

        // Verify only 1 batch reconcile call occurred for the entire 3-date range
        coVerify(exactly = 1) {
            mockCoordinator.reconcile(
                isMaterialChange = eq(true),
                previousClassifier = any(),
                evaluationEpochMs = any(),
                evaluationZoneId = any()
            )
        }
    }

    @Test
    fun test3_noop_single_mutation_does_not_trigger_reconcile() = runTest {
        val file = File(tempFolder.root, "test3_overrides.json")
        val mockCoordinator = createMockCoordinator()

        val store = FileManualHolidayOverrideStore(
            file = file,
            reconcileCoordinatorProvider = { mockCoordinator }
        )

        store.setOverride(d1, ManualHolidayOverride.FORCE_HOLIDAY)
        coVerify(exactly = 1) {
            mockCoordinator.reconcile(
                isMaterialChange = eq(true),
                previousClassifier = any(),
                evaluationEpochMs = any(),
                evaluationZoneId = any()
            )
        }

        // Setting same value again is a no-op
        store.setOverride(d1, ManualHolidayOverride.FORCE_HOLIDAY)
        // Count must still be 1
        coVerify(exactly = 1) {
            mockCoordinator.reconcile(
                isMaterialChange = eq(true),
                previousClassifier = any(),
                evaluationEpochMs = any(),
                evaluationZoneId = any()
            )
        }
    }

    @Test
    fun test4_noop_range_mutation_does_not_trigger_reconcile() = runTest {
        val file = File(tempFolder.root, "test4_overrides.json")
        val mockCoordinator = createMockCoordinator()

        val store = FileManualHolidayOverrideStore(
            file = file,
            reconcileCoordinatorProvider = { mockCoordinator }
        )

        store.setOverride(d1, ManualHolidayOverride.FORCE_HOLIDAY)
        coVerify(exactly = 1) {
            mockCoordinator.reconcile(
                isMaterialChange = eq(true),
                previousClassifier = any(),
                evaluationEpochMs = any(),
                evaluationZoneId = any()
            )
        }

        // Range where all dates already have target value returns NoOp
        val noOpResult = store.setRange(d1, d1, ManualHolidayOverride.FORCE_HOLIDAY, false)
        assertTrue(noOpResult is RangeMutationResult.NoOp)
        coVerify(exactly = 1) {
            mockCoordinator.reconcile(
                isMaterialChange = eq(true),
                previousClassifier = any(),
                evaluationEpochMs = any(),
                evaluationZoneId = any()
            )
        }

        // Range with conflict and overwriteExisting=false returns Conflict
        val conflictResult = store.setRange(d1, d2, ManualHolidayOverride.FORCE_WORKDAY, false)
        assertTrue(conflictResult is RangeMutationResult.Conflict)
        coVerify(exactly = 1) {
            mockCoordinator.reconcile(
                isMaterialChange = eq(true),
                previousClassifier = any(),
                evaluationEpochMs = any(),
                evaluationZoneId = any()
            )
        }
    }

    @Test
    fun test5_failed_file_write_does_not_trigger_reconcile() = runTest {
        val file = File(tempFolder.root, "test5_overrides.json")
        val mockCoordinator = createMockCoordinator()
        val store = FileManualHolidayOverrideStore(
            file = file,
            reconcileCoordinatorProvider = { mockCoordinator }
        )

        // Block write by placing a directory at atomic write destination
        val blocker = File(file.path + ".new")
        assertTrue(blocker.mkdir())

        try {
            var exceptionThrown = false
            try {
                store.setOverride(d1, ManualHolidayOverride.FORCE_HOLIDAY)
            } catch (_: Exception) {
                exceptionThrown = true
            }
            assertTrue(exceptionThrown)

            // Memory snapshot unchanged
            assertEquals(ManualHolidayOverride.AUTO, store.overrideFor(d1))
            // Reconcile was NEVER called
            coVerify(exactly = 0) { mockCoordinator.reconcile(any(), any(), any(), any()) }
        } finally {
            blocker.delete()
        }
    }

    @Test
    fun test6_zero_byte_file_fails_closed_in_AlarmScheduler() = runTest {
        val file = tempFolder.newFile("zero_byte_alarm_scheduler.json")
        assertEquals(0L, file.length())
        assertTrue(file.exists())

        val store = FileManualHolidayOverrideStore(file)
        assertTrue(store.isCorrupted)

        val holidayRepo = HolidayRepository(
            context = context,
            holidayApi = holidayApi,
            preferencesManager = prefs,
            manualOverrideSource = store
        )

        val scheduler = AlarmScheduler(
            context = context,
            repository = alarmRepo,
            calculator = calculator,
            preferencesManager = prefs,
            holidayRepository = holidayRepo,
            alarmIncidentRepository = incidentRepo,
            weatherRepository = weatherRepo
        )

        // Create repeating alarm with HolidayPolicy.EXCLUDE
        val alarm = Alarm(
            id = 101L,
            hour = 8,
            minute = 0,
            repeatDays = DayOfWeek.values().toSet(),
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            holidayPolicy = Alarm.HOLIDAY_POLICY_EXCLUDE,
            generation = 1,
            revision = 1
        )
        alarmDao.insert(alarm)

        val outcome = scheduler.schedule(alarm, requestWidgetUpdate = false)

        // Must fail closed with RegistrationFailed and cause CorruptedManualHolidayAuthorityException
        assertTrue("Outcome must be RegistrationFailed", outcome is ScheduleOutcome.RegistrationFailed)
        val regFailed = outcome as ScheduleOutcome.RegistrationFailed
        assertEquals(101L, regFailed.alarmId)
        assertNotNull(regFailed.cause)
        assertTrue(regFailed.cause is CorruptedManualHolidayAuthorityException)
    }

    @Test
    fun test7_zero_byte_file_fails_closed_in_HolidayReconcileCoordinator() = runTest {
        val file = tempFolder.newFile("zero_byte_coordinator.json")
        val store = FileManualHolidayOverrideStore(file)
        assertTrue(store.isCorrupted)

        val holidayRepo = HolidayRepository(
            context = context,
            holidayApi = holidayApi,
            preferencesManager = prefs,
            manualOverrideSource = store
        )

        val mockScheduler = mockk<AlarmScheduler>(relaxed = true)
        val stateStore = mockk<HolidayReconcileStateStore>(relaxed = true)
        coEvery { stateStore.load() } returns HolidayReconcileState()

        val coordinator = HolidayReconcileCoordinator(
            context = context,
            holidayRepository = holidayRepo,
            alarmRepository = alarmRepo,
            alarmScheduler = mockScheduler,
            stateStore = stateStore
        )

        val alarm = Alarm(
            id = 202L,
            hour = 7,
            minute = 30,
            repeatDays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            holidayPolicy = Alarm.HOLIDAY_POLICY_EXCLUDE,
            generation = 1,
            revision = 1
        )
        alarmDao.insert(alarm)

        val result = coordinator.reconcile(isMaterialChange = true)

        // Must return PendingRetry due to unreadable holiday authority
        assertTrue("Result must be PendingRetry", result is HolidayReconcileResult.PendingRetry)
        val pending = result as HolidayReconcileResult.PendingRetry
        assertNotNull(pending.failureCause)
        assertTrue(pending.failureCause is CorruptedManualHolidayAuthorityException)
        // Verify mockScheduler was NEVER called to arm OS alarms
        coVerify(exactly = 0) { mockScheduler.schedule(any(), any(), any()) }
        coVerify(exactly = 0) { mockScheduler.reconcileRegistration(any(), any(), any()) }
    }

    @Test
    fun test8_crash_recovery_between_write_and_reconcile() = runTest {
        // Scenario: Manual override was written to disk by process 1, but process crashed before reconcile could run.
        val file = File(tempFolder.root, "crash_recovery_overrides.json")
        val store1 = FileManualHolidayOverrideStore(file, reconcileCoordinatorProvider = null)
        store1.setOverride(d3, ManualHolidayOverride.FORCE_HOLIDAY)

        // Now process restarts; fresh store loads from disk
        val store2 = FileManualHolidayOverrideStore(file)
        assertEquals(ManualHolidayOverride.FORCE_HOLIDAY, store2.overrideFor(d3))

        val holidayRepo = HolidayRepository(
            context = context,
            holidayApi = holidayApi,
            preferencesManager = prefs,
            manualOverrideSource = store2
        )

        val mockScheduler = mockk<AlarmScheduler>(relaxed = true)
        coEvery { mockScheduler.reconcileRegistration(any(), any(), any()) } returns ScheduleOutcome.Armed(303L, 1000000L)

        val stateStore = FileHolidayReconcileStateStore(context, "reconcile_state_recovery.json")
        // State store has obsolete signature representing prior state without manual override
        stateStore.save(HolidayReconcileState(lastReconciledSignature = "obsolete_sha256_hash"))

        val alarm = Alarm(
            id = 303L,
            hour = 9,
            minute = 0,
            repeatDays = DayOfWeek.values().toSet(),
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            holidayPolicy = Alarm.HOLIDAY_POLICY_EXCLUDE,
            generation = 1,
            revision = 1
        )
        alarmDao.insert(alarm)

        val coordinator = HolidayReconcileCoordinator(
            context = context,
            holidayRepository = holidayRepo,
            alarmRepository = alarmRepo,
            alarmScheduler = mockScheduler,
            stateStore = stateStore
        )

        // Boot / cold start reconcile with isMaterialChange = false
        val result = coordinator.reconcile(isMaterialChange = false)

        // Signature mismatch was detected; eligible alarms reconciled!
        assertTrue("Reconcile should succeed following recovery", result is HolidayReconcileResult.Success)
        val success = result as HolidayReconcileResult.Success
        assertFalse("Should not be no-op because signature changed", success.isNoOp)
        assertEquals(1, success.reconciledCount)

        // Verify alarm was scheduled and new signature persisted
        coVerify(atLeast = 1) { mockScheduler.reconcileRegistration(any(), any(), any()) }
        val updatedState = stateStore.load()
        assertNotNull(updatedState.lastReconciledSignature)
        assertFalse(updatedState.lastReconciledSignature == "obsolete_sha256_hash")
    }

    @Test
    fun test9_restoreOverrides_triggers_reconcile_only_when_changed() = runTest {
        val file = File(tempFolder.root, "test9_overrides.json")
        val mockCoordinator = createMockCoordinator()

        val store = FileManualHolidayOverrideStore(
            file = file,
            reconcileCoordinatorProvider = { mockCoordinator }
        )

        store.restoreOverrides(mapOf(d1 to ManualHolidayOverride.FORCE_HOLIDAY))
        coVerify(exactly = 1) {
            mockCoordinator.reconcile(
                isMaterialChange = eq(true),
                previousClassifier = any(),
                evaluationEpochMs = any(),
                evaluationZoneId = any()
            )
        }

        // Restoring identical map is a no-op
        store.restoreOverrides(mapOf(d1 to ManualHolidayOverride.FORCE_HOLIDAY))
        coVerify(exactly = 1) {
            mockCoordinator.reconcile(
                isMaterialChange = eq(true),
                previousClassifier = any(),
                evaluationEpochMs = any(),
                evaluationZoneId = any()
            )
        }

        // Restoring map with new date triggers reconcile
        store.restoreOverrides(mapOf(
            d1 to ManualHolidayOverride.FORCE_HOLIDAY,
            d2 to ManualHolidayOverride.FORCE_WORKDAY
        ))
        coVerify(exactly = 2) {
            mockCoordinator.reconcile(
                isMaterialChange = eq(true),
                previousClassifier = any(),
                evaluationEpochMs = any(),
                evaluationZoneId = any()
            )
        }
    }

    @Test
    fun test10_concurrent_mutations_serialize_safely() = runTest {
        val file = File(tempFolder.root, "test10_overrides.json")
        val mockCoordinator = createMockCoordinator()

        val store = FileManualHolidayOverrideStore(
            file = file,
            reconcileCoordinatorProvider = { mockCoordinator }
        )

        val count = 15
        val baseDate = LocalDate.of(2026, 6, 1)

        val deferreds = (0 until count).map { i ->
            async(kotlinx.coroutines.Dispatchers.Default) {
                store.setOverride(baseDate.plusDays(i.toLong()), ManualHolidayOverride.FORCE_HOLIDAY)
            }
        }
        deferreds.awaitAll()

        val all = store.getAllOverrides()
        assertEquals(count, all.size)

        val reloaded = FileManualHolidayOverrideStore(file)
        assertEquals(count, reloaded.getAllOverrides().size)
    }

    @Test
    fun test11_reconcile_PendingRetry_propagates_truthful_outcome_and_marks_durable_pending_state() = runTest {
        val file = File(tempFolder.root, "test11_overrides.json")
        val stateFile = File(tempFolder.root, "test11_state.json")
        val stateStore = FileHolidayReconcileStateStore(stateFile)
        val mockCoordinator = createMockCoordinator()

        coEvery {
            mockCoordinator.reconcile(any(), any(), any(), any())
        } returns HolidayReconcileResult.PendingRetry(pendingCount = 2, failureCause = RuntimeException("Registration failed"))

        val store = FileManualHolidayOverrideStore(
            file = file,
            reconcileCoordinatorProvider = { mockCoordinator },
            reconcileStateStore = stateStore
        )

        val outcome = store.setOverride(d1, ManualHolidayOverride.FORCE_HOLIDAY)
        assertTrue("Outcome must be PendingRetry", outcome is HolidayReconcileResult.PendingRetry)
        assertEquals(2, (outcome as HolidayReconcileResult.PendingRetry).pendingCount)
        assertEquals(outcome, store.lastReconcileResult)

        // Durable mutation must be preserved
        assertEquals(ManualHolidayOverride.FORCE_HOLIDAY, store.overrideFor(d1))
        val reloaded = FileManualHolidayOverrideStore(file)
        assertEquals(ManualHolidayOverride.FORCE_HOLIDAY, reloaded.overrideFor(d1))

        // StateStore must have pendingSignature recorded durably
        val savedState = stateStore.load()
        assertEquals("PENDING_MANUAL_RECONCILE", savedState.pendingSignature)
    }

    @Test
    fun test12_reconcile_exception_does_not_swallow_and_marks_durable_pending_state() = runTest {
        val file = File(tempFolder.root, "test12_overrides.json")
        val stateFile = File(tempFolder.root, "test12_state.json")
        val stateStore = FileHolidayReconcileStateStore(stateFile)
        val mockCoordinator = createMockCoordinator()

        coEvery {
            mockCoordinator.reconcile(any(), any(), any(), any())
        } throws IllegalStateException("Coordinator crashed")

        val store = FileManualHolidayOverrideStore(
            file = file,
            reconcileCoordinatorProvider = { mockCoordinator },
            reconcileStateStore = stateStore
        )

        val outcome = store.setOverride(d1, ManualHolidayOverride.FORCE_HOLIDAY)
        assertTrue("Outcome must be PendingRetry on exception", outcome is HolidayReconcileResult.PendingRetry)
        assertEquals("Coordinator crashed", (outcome as HolidayReconcileResult.PendingRetry).failureCause?.message)
        assertEquals(outcome, store.lastReconcileResult)

        // Durable mutation truth preserved
        assertEquals(ManualHolidayOverride.FORCE_HOLIDAY, store.overrideFor(d1))

        // StateStore must have pendingSignature recorded durably
        val savedState = stateStore.load()
        assertEquals("PENDING_MANUAL_RECONCILE", savedState.pendingSignature)
    }

    @Test
    fun test13_null_coordinator_provider_does_not_silently_skip_and_marks_durable_pending() = runTest {
        val file = File(tempFolder.root, "test13_overrides.json")
        val stateFile = File(tempFolder.root, "test13_state.json")
        val stateStore = FileHolidayReconcileStateStore(stateFile)

        val store = FileManualHolidayOverrideStore(
            file = file,
            reconcileCoordinatorProvider = null,
            reconcileStateStore = stateStore
        )

        val outcome = store.setOverride(d1, ManualHolidayOverride.FORCE_HOLIDAY)
        assertTrue("Outcome must be PendingRetry when coordinator is unavailable", outcome is HolidayReconcileResult.PendingRetry)
        assertEquals("HolidayReconcileCoordinator is unavailable", (outcome as HolidayReconcileResult.PendingRetry).failureCause?.message)

        // Durable mutation truth preserved
        assertEquals(ManualHolidayOverride.FORCE_HOLIDAY, store.overrideFor(d1))

        // StateStore must have pendingSignature recorded durably
        val savedState = stateStore.load()
        assertEquals("PENDING_MANUAL_RECONCILE", savedState.pendingSignature)
    }

    @Test
    fun test14_post_crash_reconcile_discovers_pending_state_without_reapplying_mutation() = runTest {
        val file = File(tempFolder.root, "test14_overrides.json")
        val stateFile = File(tempFolder.root, "test14_state.json")
        val stateStore = FileHolidayReconcileStateStore(stateFile)

        // 1. Initial settled state
        val initialSig = "initial_signature_12345"
        stateStore.save(HolidayReconcileState(lastReconciledSignature = initialSig, pendingSignature = null))

        // 2. Mutation occurs, but coordinator was null -> marks PENDING_MANUAL_RECONCILE
        val store1 = FileManualHolidayOverrideStore(
            file = file,
            reconcileCoordinatorProvider = null,
            reconcileStateStore = stateStore
        )
        store1.setOverride(d3, ManualHolidayOverride.FORCE_HOLIDAY)
        assertEquals("PENDING_MANUAL_RECONCILE", stateStore.load().pendingSignature)

        // 3. Process restart: coordinator runs with isMaterialChange = false
        val alarm = Alarm(
            id = 101L,
            hour = 7,
            minute = 0,
            repeatDays = DayOfWeek.values().toSet(),
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            holidayPolicy = Alarm.HOLIDAY_POLICY_EXCLUDE,
            isEnabled = true,
            generation = 1,
            revision = 1
        )
        alarmDao.insert(alarm)

        val holidayRepo = HolidayRepository(
            context = context,
            holidayApi = holidayApi,
            preferencesManager = prefs,
            manualOverrideSource = store1
        )

        val mockScheduler = mockk<AlarmScheduler>(relaxed = true)
        coEvery { mockScheduler.reconcileRegistration(any(), any(), any()) } coAnswers {
            val a = firstArg<Alarm>()
            val t = secondArg<Long>()
            alarmDao.update(a.copy(nextTriggerTime = t))
            ScheduleOutcome.Armed(a.id, t)
        }

        val coordinator = HolidayReconcileCoordinator(
            context = context,
            holidayRepository = holidayRepo,
            alarmRepository = alarmRepo,
            alarmScheduler = mockScheduler,
            stateStore = stateStore
        )
        val result = coordinator.reconcile(isMaterialChange = false)

        assertTrue("Post-crash reconcile must succeed", result is HolidayReconcileResult.Success)
        assertEquals(1, (result as HolidayReconcileResult.Success).reconciledCount)
        val postCrashState = stateStore.load()
        assertNull("Pending signature must be cleared after successful reconciliation", postCrashState.pendingSignature)
        assertNotNull("Last reconciled signature must be set", postCrashState.lastReconciledSignature)

        // 4. Retry / second run must be an idempotent no-op without duplicate OS registrations
        val secondResult = coordinator.reconcile(isMaterialChange = false)
        assertTrue("Second run must be idempotent no-op", secondResult is HolidayReconcileResult.Success && secondResult.isNoOp)
        coVerify(exactly = 1) {
            mockScheduler.reconcileRegistration(any(), any(), any())
        }
    }

    @Test
    fun test15_restore_controlled_mutation_path_prevents_premature_reconcile_before_committed() = runTest {
        val file = File(tempFolder.root, "test15_overrides.json")
        val mockCoordinator = createMockCoordinator()

        val store = FileManualHolidayOverrideStore(
            file = file,
            reconcileCoordinatorProvider = { mockCoordinator }
        )

        // Pre-COMMITTED restore with triggerReconcile = false
        val result = store.restoreOverrides(
            mapOf(d1 to ManualHolidayOverride.FORCE_HOLIDAY),
            triggerReconcile = false
        )
        assertNull("Restoring with triggerReconcile = false must return null", result)
        coVerify(exactly = 0) {
            mockCoordinator.reconcile(any(), any(), any(), any())
        }

        // Durable truth is written
        assertEquals(ManualHolidayOverride.FORCE_HOLIDAY, store.overrideFor(d1))
        val reloaded = FileManualHolidayOverrideStore(file)
        assertEquals(ManualHolidayOverride.FORCE_HOLIDAY, reloaded.overrideFor(d1))

        // Post-COMMITTED explicit reconcile
        val reconcileResult = store.notifyReconciliation()
        assertTrue(reconcileResult is HolidayReconcileResult.Success)
        coVerify(exactly = 1) {
            mockCoordinator.reconcile(eq(true), any(), any(), any())
        }
    }
}
