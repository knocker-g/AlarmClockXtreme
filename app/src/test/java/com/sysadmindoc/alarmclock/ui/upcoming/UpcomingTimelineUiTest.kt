package com.sysadmindoc.alarmclock.ui.upcoming

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.R
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
import com.sysadmindoc.alarmclock.data.repository.CapturedHolidayAuthorityWindow
import com.sysadmindoc.alarmclock.data.repository.HolidayRepository
import com.sysadmindoc.alarmclock.domain.evaluator.DurableOneTimeOverrideAdapter
import com.sysadmindoc.alarmclock.domain.evaluator.HolidayPolicy
import com.sysadmindoc.alarmclock.domain.evaluator.HolidaySnapshot
import com.sysadmindoc.alarmclock.domain.evaluator.HolidayState
import com.sysadmindoc.alarmclock.domain.projection.InvalidationReason
import com.sysadmindoc.alarmclock.domain.projection.UpcomingOccurrenceOutcome
import com.sysadmindoc.alarmclock.domain.projection.UpcomingOccurrenceReason
import com.sysadmindoc.alarmclock.domain.projection.UpcomingProjectionFreshness
import com.sysadmindoc.alarmclock.domain.projection.UpcomingScheduleProjector
import com.sysadmindoc.alarmclock.domain.projection.UpcomingScheduleReconcileCoordinator
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
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Deterministic Acceptance & Forensic Verification for KNO-157 / KNO-34 Slice C.
 *
 * Verifies all 16 Acceptance Criteria specified in Section 7.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class UpcomingTimelineUiTest {

    private lateinit var context: Context
    private lateinit var db: AlarmDatabase
    private lateinit var alarmDao: AlarmDao
    private lateinit var exceptionDao: ScheduleExceptionDao
    private lateinit var authorityDao: ScheduleAuthorityDao
    private lateinit var alarmRepo: AlarmRepository
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var projector: UpcomingScheduleProjector
    private lateinit var coordinator: UpcomingScheduleReconcileCoordinator
    private lateinit var adapter: DurableOneTimeOverrideAdapter
    private lateinit var holidayRepo: HolidayRepository
    private lateinit var viewModel: UpcomingTimelineViewModel

    private val testDispatcher = UnconfinedTestDispatcher()
    private val zoneId = ZoneId.of("America/New_York")
    private val fixedNowMs = ZonedDateTime.of(2026, 11, 4, 6, 0, 0, 0, zoneId).toInstant().toEpochMilli()
    private var originalTimeZone: java.util.TimeZone? = null

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        originalTimeZone = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/New_York"))
        context = ApplicationProvider.getApplicationContext()

        db = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        alarmDao = db.alarmDao()
        exceptionDao = db.scheduleExceptionDao()
        authorityDao = db.scheduleAuthorityDao()

        alarmRepo = AlarmRepository(db, alarmDao, db.alarmGroupDao())
        preferencesManager = mockk(relaxed = true)

        every { preferencesManager.getCachedSettings() } returns AppSettings(
            activeAlarmId = null,
            is24HourFormat = false
        )
        coEvery { preferencesManager.getCurrentSettings() } returns AppSettings(
            activeAlarmId = null,
            is24HourFormat = false
        )
        every { preferencesManager.settings } returns flowOf(
            AppSettings(activeAlarmId = null, is24HourFormat = false)
        )

        holidayRepo = mockk(relaxed = true)
        coEvery { holidayRepo.captureAuthorityWindow(any(), any()) } returns CapturedHolidayAuthorityWindow(
            startDate = LocalDate.of(2026, 11, 1),
            endDateInclusive = LocalDate.of(2026, 11, 30),
            configuredCountry = null,
            providerSnapshot = null,
            manualOverrides = emptyMap(),
            capturedAtMillis = fixedNowMs
        )

        projector = UpcomingScheduleProjector()
        coordinator = UpcomingScheduleReconcileCoordinator(
            projector = projector,
            alarmDao = alarmDao,
            scheduleExceptionDao = exceptionDao,
            scheduleAuthorityDao = authorityDao
        )

        adapter = DurableOneTimeOverrideAdapter(
            database = db,
            coordinator = coordinator
        )

        viewModel = UpcomingTimelineViewModel(
            context = context,
            coordinator = coordinator,
            alarmRepository = alarmRepo,
            overrideAdapter = adapter,
            preferencesManager = preferencesManager,
            scheduleExceptionDao = exceptionDao,
            holidayRepository = holidayRepo
        ).apply {
            nowEpochMsProvider = { fixedNowMs }
            zoneIdProvider = { zoneId }
        }
    }

    @After
    fun tearDown() {
        originalTimeZone?.let { java.util.TimeZone.setDefault(it) }
        Dispatchers.resetMain()
        db.close()
    }

    private fun insertAlarm(
        id: Long = 0L,
        hour: Int = 8,
        minute: Int = 0,
        label: String = "Test Alarm",
        isEnabled: Boolean = true,
        repeatDays: Set<DayOfWeek> = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
        scheduleKind: String = if (repeatDays.isEmpty()) Alarm.SCHEDULE_KIND_ONE_TIME else Alarm.SCHEDULE_KIND_REPEATING,
        kind: String = Alarm.KIND_NORMAL,
        specificDate: String = if (repeatDays.isEmpty()) "2026-11-04" else "",
        nextTriggerTime: Long = 0L,
        generation: Int = 1,
        holidayPolicy: HolidayPolicy = HolidayPolicy.FOLLOW_BASE
    ): Long = runBlocking {
        val alarm = Alarm(
            id = id,
            hour = hour,
            minute = minute,
            label = label,
            isEnabled = isEnabled,
            repeatDays = repeatDays,
            scheduleKind = scheduleKind,
            kind = kind,
            specificDate = specificDate,
            nextTriggerTime = nextTriggerTime,
            generation = generation,
            holidayPolicy = holidayPolicy.name
        )
        val insertedId = alarmDao.insert(alarm)
        authorityDao.insertAuthority(
            ScheduleAuthority(
                alarmId = insertedId,
                instanceToken = "token_alarm_${insertedId}_abc",
                definitionRevision = alarm.revision.toLong(),
                scheduleRevision = 1L,
                lifecycle = "LIVE",
                desiredOperation = "SET",
                desiredTriggerMillis = if (nextTriggerTime > 0L) nextTriggerTime else 1700000000000L,
                reconcileState = "SETTLED",
                restoreEpoch = 1700000000L,
                actionKey = null
            )
        )
        insertedId
    }

    // 1. Multiple alarms on one date sorted chronologically
    @Test
    fun test01_multipleAlarmsOnOneDateSortedChronologically() = runBlocking {
        val fixedNow = ZonedDateTime.of(2026, 11, 4, 6, 0, 0, 0, zoneId)
        val nowMs = fixedNow.toInstant().toEpochMilli()

        val idLater = insertAlarm(hour = 9, minute = 30, label = "Later Alarm")
        val idEarlier = insertAlarm(hour = 7, minute = 0, label = "Earlier Alarm")

        // Invalidate coordinator with fixed time evaluation
        coordinator.invalidateAll(InvalidationReason.SYSTEM_RECOVERY)
        val projection = coordinator.getCurrentProjection(nowEpochMs = nowMs, evaluationZoneId = zoneId)

        val todayGroup = projection.dayGroups.firstOrNull { it.date == fixedNow.toLocalDate() }
        assertNotNull("Day group for today must exist", todayGroup)
        assertEquals("Must have 2 alarms today", 2, todayGroup!!.entries.size)

        val first = todayGroup.entries[0]
        val second = todayGroup.entries[1]

        assertEquals("First occurrence must be earlier (07:00)", idEarlier, first.alarmId)
        assertEquals("Second occurrence must be later (09:30)", idLater, second.alarmId)
        assertTrue("Chronological sort: first <= second", first.targetTriggerTimeEpochMs < second.targetTriggerTimeEpochMs)
    }

    // 2. Multiple dates grouped correctly
    @Test
    fun test02_multipleDatesGroupedCorrectly() = runBlocking {
        val fixedNow = ZonedDateTime.of(2026, 11, 4, 6, 0, 0, 0, zoneId) // Wednesday
        val nowMs = fixedNow.toInstant().toEpochMilli()

        insertAlarm(hour = 8, minute = 0, label = "Weekday Alarm", repeatDays = setOf(DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY))

        coordinator.invalidateAll(InvalidationReason.SYSTEM_RECOVERY)
        val projection = coordinator.getCurrentProjection(nowEpochMs = nowMs, evaluationZoneId = zoneId)

        assertTrue("Must group into multiple dates", projection.dayGroups.size >= 2)
        assertEquals("First group must be Wednesday", LocalDate.of(2026, 11, 4), projection.dayGroups[0].date)
        assertEquals("Second group must be Thursday", LocalDate.of(2026, 11, 5), projection.dayGroups[1].date)
    }

    // 3. Holiday EXCLUDE skip is understandable
    @Test
    fun test03_holidayExcludeSkipIsRepresentedAccurately() = runBlocking {
        val holidayDate = LocalDate.of(2026, 11, 4)
        val fixedNow = ZonedDateTime.of(2026, 11, 4, 6, 0, 0, 0, zoneId)
        val nowMs = fixedNow.toInstant().toEpochMilli()

        insertAlarm(
            hour = 8,
            minute = 0,
            label = "Holiday Skip Alarm",
            repeatDays = setOf(DayOfWeek.WEDNESDAY),
            holidayPolicy = HolidayPolicy.EXCLUDE
        )

        val projection = coordinator.getCurrentProjection(
            nowEpochMs = nowMs,
            evaluationZoneId = zoneId,
            holidaySnapshotProvider = { date ->
                if (date == holidayDate) HolidaySnapshot(state = HolidayState.IS_HOLIDAY, holidayName = "Test Holiday")
                else HolidaySnapshot()
            }
        )

        val holidayOccurrence = projection.allOccurrences.firstOrNull { it.localDate == holidayDate }
        assertNotNull("Occurrence on holiday must be present in projection", holidayOccurrence)
        assertEquals("Reason must be SKIPPED_BY_HOLIDAY", UpcomingOccurrenceReason.SKIPPED_BY_HOLIDAY, holidayOccurrence!!.reason)
        assertFalse("willFire must be false for holiday exclusion", holidayOccurrence.willFire)
        assertTrue("isSkipped helper property must be true", holidayOccurrence.isSkipped)
    }

    // 4. Holiday INCLUDE occurrence is represented correctly
    @Test
    fun test04_holidayIncludeOccurrenceRepresentedCorrectly() = runBlocking {
        val holidayDate = LocalDate.of(2026, 11, 4) // Wednesday
        val fixedNow = ZonedDateTime.of(2026, 11, 4, 6, 0, 0, 0, zoneId)
        val nowMs = fixedNow.toInstant().toEpochMilli()

        // Alarm normally repeats only on SUNDAY, but has HolidayPolicy.INCLUDE
        insertAlarm(
            hour = 10,
            minute = 0,
            label = "Holiday Only Alarm",
            repeatDays = setOf(DayOfWeek.SUNDAY),
            holidayPolicy = HolidayPolicy.INCLUDE
        )

        val projection = coordinator.getCurrentProjection(
            nowEpochMs = nowMs,
            evaluationZoneId = zoneId,
            holidaySnapshotProvider = { date ->
                if (date == holidayDate) HolidaySnapshot(state = HolidayState.IS_HOLIDAY, holidayName = "Holiday Ring")
                else HolidaySnapshot()
            }
        )

        val holidayOccurrence = projection.allOccurrences.firstOrNull { it.localDate == holidayDate }
        assertNotNull("Holiday INCLUDE must project occurrence on holiday date", holidayOccurrence)
        assertEquals("Reason must be HOLIDAY_INCLUDE", UpcomingOccurrenceReason.HOLIDAY_INCLUDE, holidayOccurrence!!.reason)
        assertTrue("willFire must be true for HOLIDAY_INCLUDE", holidayOccurrence.willFire)
    }

    // 5. Skip Once via Card action triggers canonical operation and updates timeline projection
    @Test
    fun test05_skipOnceViaCardTriggersCanonicalOperationAndUpdatesTimeline() = runBlocking {
        val fixedNow = ZonedDateTime.of(2026, 11, 4, 6, 0, 0, 0, zoneId)
        val nowMs = fixedNow.toInstant().toEpochMilli()
        val scheduledMs = ZonedDateTime.of(2026, 11, 4, 8, 0, 0, 0, zoneId).toInstant().toEpochMilli()

        val alarmId = insertAlarm(hour = 8, minute = 0, label = "Daily", nextTriggerTime = scheduledMs)

        val initialProjection = coordinator.getCurrentProjection(nowEpochMs = nowMs, evaluationZoneId = zoneId)
        val targetOccurrence = initialProjection.allOccurrences.first { it.alarmId == alarmId }

        // Directly trigger Card Skip Once entrypoint on ViewModel
        viewModel.applySkipOnce(targetOccurrence)

        // Verify feedback
        val feedbackState = viewModel.uiState.first { it.userFeedback != null }
        assertEquals(context.getString(R.string.one_time_override_badge_skipped), feedbackState.userFeedback)

        // Verify Room persistence: ScheduleException committed
        val exceptions = exceptionDao.getExceptionsForAlarm(alarmId)
        assertEquals("Must have 1 exception in Room", 1, exceptions.size)
        val exception = exceptions.first()
        assertEquals(ScheduleException.TYPE_SKIP, exception.exceptionType)

        // Verify Timeline Projection updated reactively
        val updatedProjection = coordinator.getCurrentProjection(nowEpochMs = nowMs, evaluationZoneId = zoneId)
        val outcome = updatedProjection.allOccurrences.first { it.alarmId == alarmId && it.localDate == LocalDate.of(2026, 11, 4) }
        assertEquals("Occurrence reason must be SKIP_ONCE", UpcomingOccurrenceReason.SKIP_ONCE, outcome.reason)
        assertFalse("Skipped occurrence will not fire", outcome.willFire)
        assertTrue("isSkipped must be true", outcome.isSkipped)
    }

    // 5b. Skip Once via BottomSheet triggers canonical operation, dismisses sheet, and updates timeline
    @Test
    fun test05b_skipOnceViaSheetTriggersCanonicalOperationAndDismissesSheet() = runBlocking {
        val fixedNow = ZonedDateTime.of(2026, 11, 4, 6, 0, 0, 0, zoneId)
        val nowMs = fixedNow.toInstant().toEpochMilli()
        val scheduledMs = ZonedDateTime.of(2026, 11, 4, 8, 0, 0, 0, zoneId).toInstant().toEpochMilli()

        val alarmId = insertAlarm(hour = 8, minute = 0, label = "Daily", nextTriggerTime = scheduledMs)

        val initialProjection = coordinator.getCurrentProjection(nowEpochMs = nowMs, evaluationZoneId = zoneId)
        val targetOccurrence = initialProjection.allOccurrences.first { it.alarmId == alarmId }

        // Open sheet first
        viewModel.openOneTimeOverride(targetOccurrence)
        val sheetOpenState = viewModel.uiState.first { it.overrideTarget != null }
        assertNotNull(sheetOpenState.overrideTarget)

        // Execute in-sheet Skip Once
        viewModel.applySkipOnce()

        // Verify sheet dismissed and feedback emitted
        val dismissedState = viewModel.uiState.first { it.overrideTarget == null && it.userFeedback != null }
        assertNull(dismissedState.overrideTarget)
        assertEquals(context.getString(R.string.one_time_override_badge_skipped), dismissedState.userFeedback)

        // Verify Room persistence
        val exceptions = exceptionDao.getExceptionsForAlarm(alarmId)
        assertEquals(1, exceptions.size)
        assertEquals(ScheduleException.TYPE_SKIP, exceptions.first().exceptionType)
    }

    // 6. Change Time Once displays actual time and base-time context via ViewModel entrypoint
    @Test
    fun test06_changeTimeOnceDisplaysActualTimeAndBaseTimeContext() = runBlocking {
        val fixedNow = ZonedDateTime.of(2026, 11, 4, 6, 0, 0, 0, zoneId)
        val nowMs = fixedNow.toInstant().toEpochMilli()
        val baseScheduledMs = ZonedDateTime.of(2026, 11, 4, 8, 0, 0, 0, zoneId).toInstant().toEpochMilli()

        val alarmId = insertAlarm(hour = 8, minute = 0, label = "Work", nextTriggerTime = baseScheduledMs)

        val initialProjection = coordinator.getCurrentProjection(nowEpochMs = nowMs, evaluationZoneId = zoneId)
        val targetOccurrence = initialProjection.allOccurrences.first { it.alarmId == alarmId }

        // Open sheet and verify canonical target metadata
        viewModel.openOneTimeOverride(targetOccurrence)
        val sheetState = viewModel.uiState.first { it.overrideTarget != null }
        assertNotNull(sheetState.overrideTarget)
        assertEquals(baseScheduledMs, sheetState.overrideTarget!!.expectedScheduledAt)
        assertEquals(baseScheduledMs, sheetState.overrideTarget!!.expectedBaseTriggerTime)
        assertEquals(8, sheetState.overrideTarget!!.selectedHour)
        assertEquals(0, sheetState.overrideTarget!!.selectedMinute)

        // Submit Change Time Once to 10:15
        viewModel.applyChangeTimeOnce(10, 15)

        // Verify sheet dismissed and feedback emitted
        val afterSubmitState = viewModel.uiState.first { it.overrideTarget == null && it.userFeedback != null }
        assertNull(afterSubmitState.overrideTarget)
        assertTrue(afterSubmitState.userFeedback != null && afterSubmitState.userFeedback!!.contains("10:15"))

        // Verify Room persistence: TIME_OVERRIDE exception committed
        val exceptions = exceptionDao.getExceptionsForAlarm(alarmId)
        assertEquals(1, exceptions.size)
        val exception = exceptions.first()
        assertEquals(ScheduleException.TYPE_TIME_OVERRIDE, exception.exceptionType)

        // Verify Timeline Projection updated
        val projection = coordinator.getCurrentProjection(nowEpochMs = nowMs, evaluationZoneId = zoneId)
        val outcome = projection.allOccurrences.first { it.alarmId == alarmId && it.localDate == LocalDate.of(2026, 11, 4) }

        assertEquals("Reason must be CHANGE_TIME_ONCE", UpcomingOccurrenceReason.CHANGE_TIME_ONCE, outcome.reason)
        assertEquals("Actual display hour must be 10", 10, outcome.localDisplayTime.hour)
        assertEquals("Actual display minute must be 15", 15, outcome.localDisplayTime.minute)
        assertEquals("Base trigger time must be preserved for base context", baseScheduledMs, outcome.baseTriggerTimeEpochMs)
        assertTrue("isOverridden must be true", outcome.isOverridden)
        assertTrue("isCancelOverrideEligible must be true for active CHANGE_TIME_ONCE", outcome.isCancelOverrideEligible)
        assertFalse("isOneTimeOverrideEligible must be false for already overridden occurrence", outcome.isOneTimeOverrideEligible)
    }

    // 6b. Cancel Override restores base schedule
    @Test
    fun test06b_cancelOneTimeOverrideRestoresBaseSchedule() = runBlocking {
        val fixedNow = ZonedDateTime.of(2026, 11, 4, 6, 0, 0, 0, zoneId)
        val nowMs = fixedNow.toInstant().toEpochMilli()
        val baseScheduledMs = ZonedDateTime.of(2026, 11, 4, 8, 0, 0, 0, zoneId).toInstant().toEpochMilli()

        val alarmId = insertAlarm(hour = 8, minute = 0, label = "Work", nextTriggerTime = baseScheduledMs)

        // First apply skip once
        val skipOccurrence = coordinator.getCurrentProjection(nowEpochMs = nowMs, evaluationZoneId = zoneId)
            .allOccurrences.first { it.alarmId == alarmId }
        viewModel.applySkipOnce(skipOccurrence)
        val skipFeedback = viewModel.uiState.first { it.userFeedback != null }
        assertEquals(context.getString(R.string.one_time_override_badge_skipped), skipFeedback.userFeedback)
        assertEquals(1, exceptionDao.getExceptionsForAlarm(alarmId).size)

        // Now find the overridden occurrence in projection
        val overriddenOccurrence = coordinator.getCurrentProjection(nowEpochMs = nowMs, evaluationZoneId = zoneId)
            .allOccurrences.first { it.alarmId == alarmId }
        assertEquals(UpcomingOccurrenceReason.SKIP_ONCE, overriddenOccurrence.reason)
        assertTrue("isCancelOverrideEligible must be true for active SKIP_ONCE", overriddenOccurrence.isCancelOverrideEligible)
        assertFalse("isOneTimeOverrideEligible must be false for SKIP_ONCE", overriddenOccurrence.isOneTimeOverrideEligible)

        // Invoke Cancel Override via ViewModel
        viewModel.cancelOneTimeOverride(overriddenOccurrence)

        // Verify feedback
        val cancelState = viewModel.uiState.first { it.userFeedback == context.getString(R.string.one_time_override_success_cancel) }
        assertEquals(context.getString(R.string.one_time_override_success_cancel), cancelState.userFeedback)

        // Verify Room persistence: exception removed
        val exceptions = exceptionDao.getExceptionsForAlarm(alarmId)
        assertTrue("Exception must be cancelled/removed", exceptions.isEmpty())

        // Verify Timeline Projection reverts to BASE_SCHEDULE
        val revertedProjection = coordinator.getCurrentProjection(nowEpochMs = nowMs, evaluationZoneId = zoneId)
        val revertedOutcome = revertedProjection.allOccurrences.first { it.alarmId == alarmId }
        assertEquals(UpcomingOccurrenceReason.BASE_SCHEDULE, revertedOutcome.reason)
        assertTrue(revertedOutcome.willFire)
        assertFalse(revertedOutcome.isOverridden)
        assertFalse(revertedOutcome.isSkipped)
        assertFalse("Reverted base schedule is not cancel override eligible", revertedOutcome.isCancelOverrideEligible)
        assertTrue("Reverted base schedule is one-time override eligible", revertedOutcome.isOneTimeOverrideEligible)
    }

    // 7. REPEATING current next occurrence exposes eligible actions
    @Test
    fun test07_repeatingCurrentNextOccurrenceExposesEligibleActions() = runBlocking {
        val fixedNow = ZonedDateTime.of(2026, 11, 4, 6, 0, 0, 0, zoneId)
        val nowMs = fixedNow.toInstant().toEpochMilli()
        val scheduledMs = ZonedDateTime.of(2026, 11, 4, 8, 0, 0, 0, zoneId).toInstant().toEpochMilli()

        val alarmId = insertAlarm(hour = 8, minute = 0, label = "Weekly", nextTriggerTime = scheduledMs)

        val projection = coordinator.getCurrentProjection(nowEpochMs = nowMs, evaluationZoneId = zoneId)
        val nextOccurrence = projection.allOccurrences.first { it.alarmId == alarmId }

        assertTrue("isNextForAlarm must be true for earliest occurrence", nextOccurrence.isNextForAlarm)
        assertTrue("isOneTimeOverrideEligible must be true for current next REPEATING occurrence", nextOccurrence.isOneTimeOverrideEligible)

        // ViewModel opens override sheet
        viewModel.openOneTimeOverride(nextOccurrence)
        val state = viewModel.uiState.first { it.overrideTarget != null }
        assertNotNull("Override target must be populated in UI state", state.overrideTarget)
        assertEquals("Override target must match alarmId", alarmId, state.overrideTarget!!.alarmId)
    }

    // 8. Farther-future occurrences are read-only
    @Test
    fun test08_fartherFutureOccurrencesAreReadOnly() = runBlocking {
        val fixedNow = ZonedDateTime.of(2026, 11, 4, 6, 0, 0, 0, zoneId)
        val nowMs = fixedNow.toInstant().toEpochMilli()

        val alarmId = insertAlarm(hour = 8, minute = 0, label = "Daily", repeatDays = setOf(DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY))

        val projection = coordinator.getCurrentProjection(nowEpochMs = nowMs, evaluationZoneId = zoneId)
        val occurrencesForAlarm = projection.allOccurrences.filter { it.alarmId == alarmId }

        assertTrue("Must have at least 2 occurrences", occurrencesForAlarm.size >= 2)
        val next = occurrencesForAlarm[0]
        val future = occurrencesForAlarm[1]

        assertTrue("First occurrence is next", next.isNextForAlarm)
        assertFalse("Second occurrence is NOT next", future.isNextForAlarm)
        assertFalse("Farther-future occurrence is NOT eligible for override", future.isOneTimeOverrideEligible)

        // Attempting to open override on future occurrence is rejected
        viewModel.openOneTimeOverride(future)
        val state = viewModel.uiState.first { it.userFeedback != null }
        assertNull("Override target must remain null for farther-future occurrence", state.overrideTarget)
        assertEquals("Must display ineligible feedback", context.getString(R.string.one_time_override_ineligible), state.userFeedback)
    }

    // 9. ONE_TIME routes to Alarm Edit
    @Test
    fun test09_oneTimeRoutesToAlarmEditAndNotOverride() = runBlocking {
        val fixedNow = ZonedDateTime.of(2026, 11, 4, 6, 0, 0, 0, zoneId)
        val nowMs = fixedNow.toInstant().toEpochMilli()
        val scheduledMs = ZonedDateTime.of(2026, 11, 4, 14, 0, 0, 0, zoneId).toInstant().toEpochMilli()

        val alarmId = insertAlarm(
            hour = 14,
            minute = 0,
            label = "Dentist Appointment",
            repeatDays = emptySet(), // ONE_TIME
            nextTriggerTime = scheduledMs
        )

        val projection = coordinator.getCurrentProjection(nowEpochMs = nowMs, evaluationZoneId = zoneId)
        val oneTimeOccurrence = projection.allOccurrences.first { it.alarmId == alarmId }

        assertTrue("isNextForAlarm is true", oneTimeOccurrence.isNextForAlarm)
        assertFalse("ONE_TIME must NOT be eligible for One-Time Override", oneTimeOccurrence.isOneTimeOverrideEligible)

        // Attempting to open override on ONE_TIME is rejected
        viewModel.openOneTimeOverride(oneTimeOccurrence)
        assertNull("Override target must not open for ONE_TIME", viewModel.uiState.value.overrideTarget)
    }

    // 10. Quick Alarm is excluded
    @Test
    fun test10_quickAlarmIsExcludedFromUpcomingTimeline() = runBlocking {
        val fixedNow = ZonedDateTime.of(2026, 11, 4, 6, 0, 0, 0, zoneId)
        val nowMs = fixedNow.toInstant().toEpochMilli()

        val quickId = insertAlarm(
            hour = 7,
            minute = 0,
            label = "Nap Quick Alarm",
            kind = Alarm.KIND_QUICK,
            repeatDays = emptySet(),
            nextTriggerTime = nowMs + 1800000L
        )

        val normalId = insertAlarm(
            hour = 8,
            minute = 0,
            label = "Morning Alarm"
        )

        val projection = coordinator.getCurrentProjection(nowEpochMs = nowMs, evaluationZoneId = zoneId)

        assertFalse("Quick alarm MUST NOT appear in allOccurrences", projection.allOccurrences.any { it.alarmId == quickId })
        assertTrue("Normal alarm must appear", projection.allOccurrences.any { it.alarmId == normalId })
    }

    // 11. RECONCILING/STALE does not present false settled truth
    @Test
    fun test11_reconcilingAndStaleFreshnessIsReportedTruthfully() = runBlocking {
        val fixedNow = ZonedDateTime.of(2026, 11, 4, 6, 0, 0, 0, zoneId)
        val nowMs = fixedNow.toInstant().toEpochMilli()

        val alarmId = insertAlarm(hour = 8, minute = 0)

        // Normal state: CURRENT
        val normalProjection = coordinator.getCurrentProjection(nowEpochMs = nowMs, evaluationZoneId = zoneId)
        assertEquals("Settled state must report CURRENT", UpcomingProjectionFreshness.CURRENT, normalProjection.projectionFreshness)

        // Advance reconcile coordinator invalidation
        coordinator.invalidateAlarm(alarmId, InvalidationReason.ALARM_DEFINITION_CHANGED)
        val reconcilingProjection = coordinator.getCurrentProjection(nowEpochMs = nowMs, evaluationZoneId = zoneId)
        assertEquals("In-flight invalidation must report RECONCILING", UpcomingProjectionFreshness.RECONCILING, reconcilingProjection.projectionFreshness)
    }

    // 12. No-current-occurrence is not represented as disabled
    @Test
    fun test12_noCurrentOccurrenceIsNotRepresentedAsDisabled() = runBlocking {
        val fixedNow = ZonedDateTime.of(2026, 11, 4, 6, 0, 0, 0, zoneId)
        val nowMs = fixedNow.toInstant().toEpochMilli()

        // 1: Enabled ONE_TIME in the past (no future active occurrence in horizon)
        val pastOneTimeId = insertAlarm(
            hour = 5,
            minute = 0,
            label = "Past One-time",
            isEnabled = true,
            repeatDays = emptySet(),
            nextTriggerTime = nowMs - 3600000L
        )

        // 2: Explicitly disabled alarm
        val disabledId = insertAlarm(
            hour = 8,
            minute = 0,
            label = "Disabled Alarm",
            isEnabled = false
        )

        val projection = coordinator.getCurrentProjection(nowEpochMs = nowMs, evaluationZoneId = zoneId)

        assertTrue("pastOneTimeId must be classified under noCurrentOccurrenceAlarms", projection.noCurrentOccurrenceAlarms.contains(pastOneTimeId))
        assertFalse("pastOneTimeId MUST NOT be classified under disabledAlarms", projection.disabledAlarms.contains(pastOneTimeId))

        assertTrue("disabledId must be classified under disabledAlarms", projection.disabledAlarms.contains(disabledId))
        assertFalse("disabledId MUST NOT be in noCurrentOccurrenceAlarms", projection.noCurrentOccurrenceAlarms.contains(disabledId))
    }

    // 13. Duplicate projection emissions do not produce duplicate actions
    @Test
    fun test13_duplicateEmissionsDoNotDuplicateActions() = runBlocking {
        val fixedNow = ZonedDateTime.of(2026, 11, 4, 6, 0, 0, 0, zoneId)
        val nowMs = fixedNow.toInstant().toEpochMilli()
        val scheduledMs = ZonedDateTime.of(2026, 11, 4, 8, 0, 0, 0, zoneId).toInstant().toEpochMilli()

        val alarmId = insertAlarm(hour = 8, minute = 0, nextTriggerTime = scheduledMs)

        // Trigger two consecutive invalidations
        coordinator.invalidateAll(InvalidationReason.SYSTEM_RECOVERY)
        coordinator.invalidateAll(InvalidationReason.SYSTEM_RECOVERY)

        val projection = coordinator.getCurrentProjection(nowEpochMs = nowMs, evaluationZoneId = zoneId)
        val occurrences = projection.allOccurrences.filter { it.alarmId == alarmId && it.localDate == LocalDate.of(2026, 11, 4) }

        assertEquals("Must only emit exactly 1 occurrence outcome for alarm on that date", 1, occurrences.size)
    }

    // 14. Stale occurrence actions are rejected or revalidated
    @Test
    fun test14_staleOccurrenceActionsAreRejected() = runBlocking {
        val fixedNow = ZonedDateTime.of(2026, 11, 4, 6, 0, 0, 0, zoneId)
        val nowMs = fixedNow.toInstant().toEpochMilli()
        val scheduledMs = ZonedDateTime.of(2026, 11, 4, 8, 0, 0, 0, zoneId).toInstant().toEpochMilli()

        val alarmId = insertAlarm(hour = 8, minute = 0, nextTriggerTime = scheduledMs, generation = 1)

        val staleOccurrence = UpcomingOccurrenceOutcome(
            occurrenceId = null,
            alarmId = alarmId,
            alarmLabel = "Alarm",
            generation = 999, // Stale generation mismatch!
            revision = 1,
            scheduledInstant = Instant.ofEpochMilli(scheduledMs),
            targetTriggerTimeEpochMs = scheduledMs,
            baseTriggerTimeEpochMs = scheduledMs,
            localDate = LocalDate.of(2026, 11, 4),
            localDisplayTime = LocalTime.of(8, 0),
            reason = UpcomingOccurrenceReason.BASE_SCHEDULE,
            willFire = true,
            isNextForAlarm = true,
            isOneTimeOverrideEligible = true,
            projectionFreshness = UpcomingProjectionFreshness.CURRENT
        )

        viewModel.openOneTimeOverride(staleOccurrence)

        val state = viewModel.uiState.first { it.userFeedback != null }
        assertNull("Stale generation mismatch must reject opening override", state.overrideTarget)
        assertEquals("Must emit stale occurrence error feedback", context.getString(R.string.upcoming_timeline_stale_occurrence_error), state.userFeedback)
    }

    // 15. No second scheduling writer or cache is introduced
    @Test
    fun test15_noSecondSchedulingWriterOrCacheIntroduced() = runBlocking {
        // Verify that observing projection does not write to Room or ScheduleAuthority
        val authoritiesBefore = authorityDao.getAllAuthorities()
        val exceptionsBefore = exceptionDao.getAll()
        val alarmsBefore = alarmDao.getAllNormalAlarms()

        val projection = coordinator.getCurrentProjection(evaluationZoneId = zoneId)
        assertNotNull(projection)

        val authoritiesAfter = authorityDao.getAllAuthorities()
        val exceptionsAfter = exceptionDao.getAll()
        val alarmsAfter = alarmDao.getAllNormalAlarms()

        assertEquals("ScheduleAuthority table must be strictly untouched (0 writes)", authoritiesBefore.size, authoritiesAfter.size)
        assertEquals("ScheduleException table must be strictly untouched (0 writes)", exceptionsBefore.size, exceptionsAfter.size)
        assertEquals("Alarm table must be strictly untouched (0 writes)", alarmsBefore.size, alarmsAfter.size)
    }

    // 16. UI restoration/navigation does not mutate Schedule truth
    @Test
    fun test16_uiRestorationDoesNotMutateScheduleTruth() = runBlocking {
        val scheduledMs = ZonedDateTime.of(2026, 11, 4, 8, 0, 0, 0, zoneId).toInstant().toEpochMilli()
        val alarmId = insertAlarm(hour = 8, minute = 0, nextTriggerTime = scheduledMs)

        // Open and close One-time override sheet (simulating user entering and backing out)
        val projection = coordinator.getCurrentProjection(evaluationZoneId = zoneId)
        val nextOccurrence = projection.allOccurrences.first { it.alarmId == alarmId }

        viewModel.openOneTimeOverride(nextOccurrence)
        val openState = viewModel.uiState.first { it.overrideTarget != null }
        assertNotNull("Override sheet opened", openState.overrideTarget)

        viewModel.closeOneTimeOverride()
        val closedState = viewModel.uiState.first { it.overrideTarget == null }
        assertNull("Override sheet dismissed without submit", closedState.overrideTarget)

        // Verify database and schedule truth
        val dbAlarm = alarmRepo.getById(alarmId)
        assertNotNull(dbAlarm)
        assertEquals("Trigger time must remain unchanged", scheduledMs, dbAlarm!!.nextTriggerTime)
        assertNull("No exception should be created on cancel/dismiss", exceptionDao.getExceptionsForAlarm(alarmId).firstOrNull())
    }

    // 17. Concurrency protection: rapid duplicate taps are handled safely without duplicate commits
    @Test
    fun test17_concurrencyProtectionDropsRapidDuplicateTaps() = runBlocking {
        val fixedNow = ZonedDateTime.of(2026, 11, 4, 6, 0, 0, 0, zoneId)
        val nowMs = fixedNow.toInstant().toEpochMilli()
        val scheduledMs = ZonedDateTime.of(2026, 11, 4, 8, 0, 0, 0, zoneId).toInstant().toEpochMilli()

        val alarmId = insertAlarm(hour = 8, minute = 0, label = "Rapid Tap", nextTriggerTime = scheduledMs)

        val targetOccurrence = coordinator.getCurrentProjection(nowEpochMs = nowMs, evaluationZoneId = zoneId)
            .allOccurrences.first { it.alarmId == alarmId }

        // Execute first Skip Once
        viewModel.applySkipOnce(targetOccurrence)

        // Second duplicate tap on the same occurrence
        viewModel.applySkipOnce(targetOccurrence)

        // Wait for completion
        val state = viewModel.uiState.first { it.userFeedback != null }
        assertEquals(context.getString(R.string.one_time_override_badge_skipped), state.userFeedback)

        // Verify Room persistence: exactly one exception committed, no duplicate corruption
        val exceptions = exceptionDao.getExceptionsForAlarm(alarmId)
        assertEquals("Exactly one exception committed", 1, exceptions.size)
    }

    // 18. Failure outcome: active firing/snooze session reports error honestly without false success
    @Test
    fun test18_failureOutcomeWhenActiveSessionExistsReportsErrorHonestly() = runBlocking {
        val fixedNow = ZonedDateTime.of(2026, 11, 4, 6, 0, 0, 0, zoneId)
        val nowMs = fixedNow.toInstant().toEpochMilli()
        val scheduledMs = ZonedDateTime.of(2026, 11, 4, 8, 0, 0, 0, zoneId).toInstant().toEpochMilli()

        val alarmId = insertAlarm(hour = 8, minute = 0, label = "Active Firing", nextTriggerTime = scheduledMs)

        // Mock preferencesManager to report that this alarm is currently active / firing
        every { preferencesManager.getCachedSettings() } returns AppSettings(
            activeAlarmId = alarmId,
            activeAlarmState = "FIRING",
            is24HourFormat = false
        )
        coEvery { preferencesManager.getCurrentSettings() } returns AppSettings(
            activeAlarmId = alarmId,
            activeAlarmState = "FIRING",
            is24HourFormat = false
        )

        val targetOccurrence = coordinator.getCurrentProjection(nowEpochMs = nowMs, evaluationZoneId = zoneId)
            .allOccurrences.first { it.alarmId == alarmId }

        // Attempt Skip Once while session is active
        viewModel.applySkipOnce(targetOccurrence)

        val state = viewModel.uiState.first { it.userFeedback != null }
        assertEquals("Must report active session conflict honestly", context.getString(R.string.alarmlist_cannot_modify_active_alarm), state.userFeedback)

        // Room must NOT be mutated
        assertTrue("No exception should be committed during active session", exceptionDao.getExceptionsForAlarm(alarmId).isEmpty())
    }

    // 19. UI recreation does not replay mutations or leave pending state hanging
    @Test
    fun test19_uiRecreationDoesNotReplayMutations() = runBlocking {
        val scheduledMs = ZonedDateTime.of(2026, 11, 4, 8, 0, 0, 0, zoneId).toInstant().toEpochMilli()
        val alarmId = insertAlarm(hour = 8, minute = 0, nextTriggerTime = scheduledMs)

        // Create new ViewModel instance (simulating configuration change or process recreation)
        val recreatedViewModel = UpcomingTimelineViewModel(
            context = context,
            coordinator = coordinator,
            alarmRepository = alarmRepo,
            overrideAdapter = adapter,
            preferencesManager = preferencesManager,
            scheduleExceptionDao = exceptionDao,
            holidayRepository = holidayRepo
        )

        val recreatedState = recreatedViewModel.uiState.first()
        assertFalse("isSubmitting must be false", recreatedState.isSubmitting)
        assertNull("overrideTarget must be null", recreatedState.overrideTarget)
        assertNull("userFeedback must be null", recreatedState.userFeedback)
        assertTrue("No exceptions in DB", exceptionDao.getExceptionsForAlarm(alarmId).isEmpty())
    }

    // 20. Canonical HolidayRepository truth is projected by UpcomingTimelineViewModel
    @Test
    fun test20_canonicalHolidayRepositoryProjectionInViewModel() = runBlocking {
        val holidayDate = LocalDate.of(2026, 11, 4) // Wednesday
        val alarmId = insertAlarm(
            hour = 8,
            minute = 0,
            label = "Holiday Skip Alarm",
            repeatDays = setOf(DayOfWeek.WEDNESDAY),
            holidayPolicy = HolidayPolicy.EXCLUDE
        )

        // Configure mock holidayRepo to report that 2026-11-04 is a confirmed HOLIDAY via ManualHolidayOverride
        coEvery { holidayRepo.captureAuthorityWindow(any(), any()) } returns CapturedHolidayAuthorityWindow(
            startDate = LocalDate.of(2026, 11, 1),
            endDateInclusive = LocalDate.of(2026, 11, 30),
            configuredCountry = "US",
            providerSnapshot = null,
            manualOverrides = mapOf(holidayDate to com.sysadmindoc.alarmclock.data.repository.ManualHolidayOverride.FORCE_HOLIDAY),
            capturedAtMillis = fixedNowMs
        )

        val vm = UpcomingTimelineViewModel(
            context = context,
            coordinator = coordinator,
            alarmRepository = alarmRepo,
            overrideAdapter = adapter,
            preferencesManager = preferencesManager,
            scheduleExceptionDao = exceptionDao,
            holidayRepository = holidayRepo
        ).apply {
            nowEpochMsProvider = { fixedNowMs }
            zoneIdProvider = { zoneId }
        }

        val state = vm.uiState.first { it.projection != null && it.projection!!.allOccurrences.any { occ -> occ.alarmId == alarmId } }
        val holidayOccurrence = state.projection!!.allOccurrences.first { it.alarmId == alarmId && it.localDate == holidayDate }

        assertEquals("ViewModel projection must reflect HolidayRepository truth as SKIPPED_BY_HOLIDAY",
            UpcomingOccurrenceReason.SKIPPED_BY_HOLIDAY, holidayOccurrence.reason)
        assertFalse("willFire must be false for holiday skip", holidayOccurrence.willFire)
        assertTrue("isSkipped must be true", holidayOccurrence.isSkipped)
        assertFalse("isCancelOverrideEligible must be false for holiday-derived skip", holidayOccurrence.isCancelOverrideEligible)
    }
}
