package com.sysadmindoc.alarmclock.ui.alarmedit

import android.app.Application
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.AlarmDao
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.ScheduleAuthorityDao
import com.sysadmindoc.alarmclock.data.local.ScheduleExceptionDao
import com.sysadmindoc.alarmclock.data.local.entity.IssueSlot
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleException
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.data.repository.StaleAlarmException
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import com.sysadmindoc.alarmclock.domain.NextAlarmCalculator
import com.sysadmindoc.alarmclock.domain.ScheduleOutcome
import com.sysadmindoc.alarmclock.domain.evaluator.DurableOneTimeOverrideAdapter
import com.sysadmindoc.alarmclock.domain.evaluator.DurableTimeOverrideClaimCoordinator
import com.sysadmindoc.alarmclock.domain.evaluator.HolidayPolicy
import com.sysadmindoc.alarmclock.domain.evaluator.HolidaySnapshot
import com.sysadmindoc.alarmclock.domain.evaluator.HolidayState
import com.sysadmindoc.alarmclock.domain.evaluator.OneTimeOverrideOutcome
import com.sysadmindoc.alarmclock.domain.evaluator.ScheduleOccurrenceEvaluator
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.TimeZone

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class Kno33AlarmEditIntegrationTest {

    private lateinit var context: Context
    private lateinit var db: AlarmDatabase
    private lateinit var alarmDao: AlarmDao
    private lateinit var exceptionDao: ScheduleExceptionDao
    private lateinit var authorityDao: ScheduleAuthorityDao
    private lateinit var repository: AlarmRepository
    private lateinit var scheduler: AlarmScheduler
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var adapter: DurableOneTimeOverrideAdapter
    private lateinit var evaluator: ScheduleOccurrenceEvaluator
    private var originalTimeZone: TimeZone? = null

    private val zoneId = ZoneId.of("America/New_York")
    private val wedDate = LocalDate.of(2026, 11, 4)
    private val friDate = LocalDate.of(2026, 11, 6)

    // Wednesday 06:00 AM (now)
    private val nowEpochMs = wedDate
        .atTime(LocalTime.of(6, 0))
        .atZone(zoneId)
        .toInstant()
        .toEpochMilli()

    // Wednesday 07:00 AM (canonical base trigger time)
    private val wed0700Ms = wedDate
        .atTime(LocalTime.of(7, 0))
        .atZone(zoneId)
        .toInstant()
        .toEpochMilli()

    // Wednesday 08:30 AM (later override)
    private val wed0830Ms = wedDate
        .atTime(LocalTime.of(8, 30))
        .atZone(zoneId)
        .toInstant()
        .toEpochMilli()

    // Next Wednesday 07:00 AM
    private val nextWed0700Ms = wedDate.plusWeeks(1)
        .atTime(LocalTime.of(7, 0))
        .atZone(zoneId)
        .toInstant()
        .toEpochMilli()

    // Friday 07:00 AM
    private val fri0700Ms = friDate
        .atTime(LocalTime.of(7, 0))
        .atZone(zoneId)
        .toInstant()
        .toEpochMilli()

    // Friday 08:30 AM
    private val fri0830Ms = friDate
        .atTime(LocalTime.of(8, 30))
        .atZone(zoneId)
        .toInstant()
        .toEpochMilli()

    private val validAuthority = ScheduleAuthority(
        alarmId = 1L,
        instanceToken = "token_alarm_1_abc",
        definitionRevision = 1L,
        scheduleRevision = 1L,
        lifecycle = "LIVE",
        desiredOperation = "SET",
        desiredTriggerMillis = wed0700Ms,
        reconcileState = "SETTLED",
        restoreEpoch = 1700000000L,
        actionKey = null
    )

    @Before
    fun setUp() {
        originalTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))

        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .addCallback(AlarmDatabase.DATABASE_CALLBACK)
            .allowMainThreadQueries()
            .build()

        alarmDao = db.alarmDao()
        exceptionDao = db.scheduleExceptionDao()
        authorityDao = db.scheduleAuthorityDao()

        repository = AlarmRepository(db, alarmDao, db.alarmGroupDao())
        repository.clockMillis = { nowEpochMs }
        scheduler = mockk(relaxed = true)
        coEvery { scheduler.schedule(any()) } returns ScheduleOutcome.Armed(1L, wed0700Ms)

        preferencesManager = mockk(relaxed = true)
        coEvery { preferencesManager.getCurrentSettings() } returns AppSettings()
        every { preferencesManager.getCachedSettings() } returns AppSettings()
        coEvery { preferencesManager.settings } returns flowOf(AppSettings())

        evaluator = ScheduleOccurrenceEvaluator()
        val coordinator = DurableTimeOverrideClaimCoordinator(database = db)
        adapter = DurableOneTimeOverrideAdapter(
            database = db,
            timeOverrideCoordinator = coordinator,
            evaluator = evaluator,
            alarmScheduler = scheduler
        )
    }

    @After
    fun tearDown() {
        originalTimeZone?.let { TimeZone.setDefault(it) }
        db.close()
        Dispatchers.resetMain()
    }

    private suspend fun insertStandardRepeatingAlarm(
        id: Long = 1L,
        holidayPolicy: String = Alarm.HOLIDAY_POLICY_FOLLOW_BASE,
        repeatDays: Set<DayOfWeek> = setOf(DayOfWeek.WEDNESDAY),
        nextTrigger: Long = wed0700Ms
    ): Alarm {
        val alarm = Alarm(
            id = id,
            hour = 7,
            minute = 0,
            repeatDays = repeatDays,
            isEnabled = true,
            nextTriggerTime = nextTrigger,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            holidayPolicy = holidayPolicy,
            generation = 1,
            revision = 1
        ).sanitized()

        alarmDao.insert(alarm)
        authorityDao.insertAuthority(
            validAuthority.copy(
                alarmId = id,
                desiredTriggerMillis = nextTrigger
            )
        )
        db.issuedOccurrenceDao().insertSlotInitial(
            IssueSlot(alarmId = id, generationId = 1L, currentIssueToken = null)
        )
        return alarm
    }

    // 1. Precedence: Overridden occurrence is skipped -> Skip takes precedence and override is permanently deleted
    @Test
    fun test1_precedence_skipTakesPrecedenceOverTimeOverride_andPermanentlyDeletesIt() = runBlocking {
        insertStandardRepeatingAlarm()

        // 1. Apply Change Time Once: 07:00 -> 08:30
        val changeResult = adapter.executeChangeTimeOnce(
            alarmId = 1L,
            expectedScheduledAt = wed0700Ms,
            expectedBaseTriggerTimeEpochMs = wed0700Ms,
            newTime = LocalTime.of(8, 30),
            nowEpochMs = nowEpochMs,
            evaluationZoneId = zoneId
        )
        assertEquals(OneTimeOverrideOutcome.SUCCESS_COMMITTED, changeResult.outcome)

        // Verify override exception exists
        val overrideEx = exceptionDao.getExceptionForOccurrence(1L, wed0700Ms, 1)
        assertNotNull(overrideEx)
        assertEquals(ScheduleException.TYPE_TIME_OVERRIDE, overrideEx?.exceptionType)
        assertEquals(wed0830Ms, overrideEx?.overrideTriggerTime)

        val alarmAfterChange = alarmDao.getById(1L)!!
        assertEquals(wed0830Ms, alarmAfterChange.nextTriggerTime)

        // 2. Apply Skip Once on the overridden occurrence
        val skipResult = adapter.executeSkipOnce(
            alarmId = 1L,
            expectedScheduledAt = wed0830Ms,
            expectedBaseTriggerTimeEpochMs = wed0700Ms,
            nowEpochMs = nowEpochMs + 1000L,
            evaluationZoneId = zoneId
        )
        assertEquals(OneTimeOverrideOutcome.SUCCESS_COMMITTED, skipResult.outcome)

        // Verify: TYPE_TIME_OVERRIDE is GONE, only TYPE_SKIP exists
        val allExceptions = exceptionDao.getExceptionsForAlarmGeneration(1L, 1)
        assertEquals(1, allExceptions.size)
        assertEquals(ScheduleException.TYPE_SKIP, allExceptions[0].exceptionType)
        assertEquals(wed0700Ms, allExceptions[0].baseTriggerTime)

        val alarmAfterSkip = alarmDao.getById(1L)!!
        assertEquals(nextWed0700Ms, alarmAfterSkip.nextTriggerTime)

        // 3. Cancel Skip override -> Reverts to base schedule (wed0700Ms), NOT the deleted 08:30 override
        val cancelResult = adapter.cancelOverride(
            alarmId = 1L,
            expectedScheduledAt = nextWed0700Ms,
            nowEpochMs = nowEpochMs + 2000L,
            evaluationZoneId = zoneId
        )
        assertEquals(OneTimeOverrideOutcome.SUCCESS_COMMITTED, cancelResult.outcome)

        val exceptionsAfterCancel = exceptionDao.getExceptionsForAlarmGeneration(1L, 1)
        assertTrue("All exceptions must be cleared on cancel", exceptionsAfterCancel.isEmpty())

        val alarmAfterCancel = alarmDao.getById(1L)!!
        assertEquals("Must revert to canonical base time, deleted override must NOT resurrect", wed0700Ms, alarmAfterCancel.nextTriggerTime)
    }

    // 2. Precedence: Overridden occurrence becomes a holiday under EXCLUDE -> Holiday takes precedence and override is permanently deleted
    @Test
    fun test2_precedence_holidayExcludePermanentlyDeletesTimeOverride() = runBlocking {
        insertStandardRepeatingAlarm(holidayPolicy = Alarm.HOLIDAY_POLICY_EXCLUDE)

        // 1. Apply Change Time Once: 07:00 -> 08:30
        val changeResult = adapter.executeChangeTimeOnce(
            alarmId = 1L,
            expectedScheduledAt = wed0700Ms,
            expectedBaseTriggerTimeEpochMs = wed0700Ms,
            newTime = LocalTime.of(8, 30),
            nowEpochMs = nowEpochMs,
            evaluationZoneId = zoneId
        )
        assertEquals(OneTimeOverrideOutcome.SUCCESS_COMMITTED, changeResult.outcome)

        // Verify override exception exists
        assertNotNull(exceptionDao.getExceptionForOccurrence(1L, wed0700Ms, 1))

        // 2. Holiday truth changes: Wednesday becomes a public holiday under EXCLUDE
        val holidaySnapshotProvider: (LocalDate) -> HolidaySnapshot = { date ->
            if (date == wedDate) HolidaySnapshot(state = HolidayState.IS_HOLIDAY)
            else HolidaySnapshot(state = HolidayState.NOT_HOLIDAY)
        }

        val invalidated = adapter.invalidateOverridesIfTargetChanged(
            alarmId = 1L,
            nowEpochMs = nowEpochMs,
            holidaySnapshotProvider = holidaySnapshotProvider,
            evaluationZoneId = zoneId
        )
        assertTrue("Stale override on excluded holiday must be invalidated", invalidated)

        // Verify: override is permanently DELETED from DB
        val exceptions = exceptionDao.getExceptionsForAlarmGeneration(1L, 1)
        assertTrue("Exception must be deleted when occurrence is excluded by holiday", exceptions.isEmpty())

        // 3. Holiday is reverted back to a regular workday
        val workdaySnapshotProvider: (LocalDate) -> HolidaySnapshot = {
            HolidaySnapshot(state = HolidayState.NOT_HOLIDAY)
        }

        val evaluatedAfterHolidayReverted = evaluator.evaluateOccurrence(
            alarm = alarmDao.getById(1L)!!,
            fromEpochMs = nowEpochMs,
            exceptions = exceptionDao.getExceptionsForAlarmGeneration(1L, 1),
            holidaySnapshotProvider = workdaySnapshotProvider,
            evaluationZoneId = zoneId
        )

        assertNotNull(evaluatedAfterHolidayReverted)
        assertEquals("Must fire at base schedule (07:00), deleted override must NOT resurrect", wed0700Ms, evaluatedAfterHolidayReverted?.targetTriggerTimeEpochMs)
    }

    // 3. Alarm Edit: Schedule-affecting edit advances generation and invalidates old-generation exceptions
    @Test
    fun test3_alarmEdit_scheduleAffectingEditAdvancesGenerationAndInvalidatesOverride() = runBlocking {
        val initial = insertStandardRepeatingAlarm()

        // Apply Change Time Once
        adapter.executeChangeTimeOnce(
            alarmId = 1L,
            expectedScheduledAt = wed0700Ms,
            expectedBaseTriggerTimeEpochMs = wed0700Ms,
            newTime = LocalTime.of(8, 30),
            nowEpochMs = nowEpochMs,
            evaluationZoneId = zoneId
        )
        assertEquals(1, exceptionDao.getExceptionsForAlarmGeneration(1L, 1).size)

        // Edit base time from 07:00 to 07:30 (Schedule-affecting)
        val edited = initial.copy(minute = 30)
        repository.update(edited)

        val updated = alarmDao.getById(1L)!!
        assertEquals("Generation must advance", 2, updated.generation)
        assertEquals("Revision must reset to 1", 1, updated.revision)

        val exceptions = exceptionDao.getExceptionsForAlarm(1L)
        assertTrue("Old generation exceptions must be permanently deleted", exceptions.isEmpty())
    }

    // 4. Alarm Edit: Non-schedule edit preserves generation and retains existing valid exception
    @Test
    fun test4_alarmEdit_nonScheduleEditPreservesGenerationAndRetainsValidOverride() = runBlocking {
        val initial = insertStandardRepeatingAlarm()

        // Apply Change Time Once
        adapter.executeChangeTimeOnce(
            alarmId = 1L,
            expectedScheduledAt = wed0700Ms,
            expectedBaseTriggerTimeEpochMs = wed0700Ms,
            newTime = LocalTime.of(8, 30),
            nowEpochMs = nowEpochMs,
            evaluationZoneId = zoneId
        )
        assertEquals(1, exceptionDao.getExceptionsForAlarmGeneration(1L, 1).size)

        // Edit non-schedule field (label and volume)
        val edited = initial.copy(label = "Important Meeting", volume = 80)
        repository.update(edited)

        val updated = alarmDao.getById(1L)!!
        assertEquals("Generation must be preserved", 1, updated.generation)
        assertEquals("Revision must increment", 2, updated.revision)

        val exceptions = exceptionDao.getExceptionsForAlarmGeneration(1L, 1)
        assertEquals("Valid exception on current-next must be retained", 1, exceptions.size)
        assertEquals(wed0830Ms, exceptions[0].overrideTriggerTime)
    }

    // 5. Alarm Edit / Toggle: Disable permanently deletes exceptions; re-enable cannot resurrect
    @Test
    fun test5_alarmDisable_permanentlyDeletesException_reEnableCannotResurrect() = runBlocking {
        insertStandardRepeatingAlarm()

        // Apply Change Time Once
        adapter.executeChangeTimeOnce(
            alarmId = 1L,
            expectedScheduledAt = wed0700Ms,
            expectedBaseTriggerTimeEpochMs = wed0700Ms,
            newTime = LocalTime.of(8, 30),
            nowEpochMs = nowEpochMs,
            evaluationZoneId = zoneId
        )
        assertEquals(1, exceptionDao.getExceptionsForAlarm(1L).size)

        // Disable alarm
        repository.setEnabled(1L, enabled = false, nextTrigger = 0L)

        val exceptionsAfterDisable = exceptionDao.getExceptionsForAlarm(1L)
        assertTrue("Disabling alarm must purge all exceptions", exceptionsAfterDisable.isEmpty())

        // Re-enable alarm
        repository.setEnabled(1L, enabled = true, nextTrigger = wed0700Ms)

        val exceptionsAfterReEnable = exceptionDao.getExceptionsForAlarm(1L)
        assertTrue("Re-enabling alarm must NOT resurrect old exceptions", exceptionsAfterReEnable.isEmpty())
    }

    // 6. Holiday INCLUDE with no weekdays: Valid repeating alarm, participates in One-time Override normally
    @Test
    fun test6_holidayInclude_repeatingAlarmWithZeroWeekdays_participatesInOverrideNormally() = runBlocking {
        val alarm = Alarm(
            id = 2L,
            hour = 7,
            minute = 0,
            repeatDays = emptySet(),
            isEnabled = true,
            nextTriggerTime = fri0700Ms,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            holidayPolicy = Alarm.HOLIDAY_POLICY_INCLUDE,
            generation = 1,
            revision = 1
        ).sanitized()

        assertEquals("INCLUDE policy with zero weekdays must be REPEATING", Alarm.SCHEDULE_KIND_REPEATING, alarm.scheduleKind)

        alarmDao.insert(alarm)
        authorityDao.insertAuthority(
            validAuthority.copy(
                alarmId = 2L,
                desiredTriggerMillis = fri0700Ms
            )
        )
        db.issuedOccurrenceDao().insertSlotInitial(
            IssueSlot(alarmId = 2L, generationId = 1L, currentIssueToken = null)
        )

        val holidaySnapshotProvider: (LocalDate) -> HolidaySnapshot = { date ->
            if (date == friDate) HolidaySnapshot(state = HolidayState.IS_HOLIDAY)
            else HolidaySnapshot(state = HolidayState.NOT_HOLIDAY)
        }

        // Apply Change Time Once to the Friday holiday occurrence
        val result = adapter.executeChangeTimeOnce(
            alarmId = 2L,
            expectedScheduledAt = fri0700Ms,
            expectedBaseTriggerTimeEpochMs = fri0700Ms,
            newTime = LocalTime.of(8, 30),
            nowEpochMs = nowEpochMs,
            holidaySnapshotProvider = holidaySnapshotProvider,
            evaluationZoneId = zoneId
        )

        assertEquals(OneTimeOverrideOutcome.SUCCESS_COMMITTED, result.outcome)

        val exception = exceptionDao.getExceptionForOccurrence(2L, fri0700Ms, 1)
        assertNotNull(exception)
        assertEquals(fri0830Ms, exception?.overrideTriggerTime)
    }

    // 7. OCC: Stale draft update throws StaleAlarmException and rejects save
    @Test
    fun test7_alarmEdit_staleDraftThrowsStaleAlarmExceptionAndRejectsSave() = runBlocking {
        val initial = insertStandardRepeatingAlarm()

        // Background update advances revision to 2
        repository.update(initial.copy(label = "Background Change"))

        // Stale draft holding revision 1 attempts update
        var threwStale = false
        try {
            repository.update(initial.copy(label = "Stale Edit"))
        } catch (e: StaleAlarmException) {
            threwStale = true
        }

        assertTrue("Stale draft must throw StaleAlarmException", threwStale)

        val live = alarmDao.getById(1L)!!
        assertEquals("Background Change", live.label)
        assertEquals(2, live.revision)
    }

    // 8. UI Projection: AlarmEditViewModel loads and exposes active override state
    @Test
    fun test8_alarmEditViewModel_loadsAndProjectsActiveOverride() = runBlocking {
        insertStandardRepeatingAlarm()

        // Apply Change Time Once
        adapter.executeChangeTimeOnce(
            alarmId = 1L,
            expectedScheduledAt = wed0700Ms,
            expectedBaseTriggerTimeEpochMs = wed0700Ms,
            newTime = LocalTime.of(8, 30),
            nowEpochMs = nowEpochMs,
            evaluationZoneId = zoneId
        )

        val savedStateHandle = SavedStateHandle(mapOf("alarmId" to 1L))
        val viewModel = AlarmEditViewModel(
            savedStateHandle = savedStateHandle,
            context = context,
            repository = repository,
            scheduler = scheduler,
            calculator = NextAlarmCalculator(),
            preferencesManager = preferencesManager
        )

        val state = viewModel.uiState.first { it.isEditing }
        assertEquals("TIME_OVERRIDE", state.activeOverrideType)
        assertEquals(wed0830Ms, state.activeOverrideTriggerTime)
        assertEquals(wed0700Ms, state.activeOverrideBaseTriggerTime)
    }
}
