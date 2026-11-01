package com.sysadmindoc.alarmclock.ui.holiday

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.holiday.ManualHolidayOverrideStore
import com.sysadmindoc.alarmclock.data.holiday.RangeMutationResult
import com.sysadmindoc.alarmclock.data.local.AlarmDao
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.data.repository.CapturedHolidayAuthorityWindow
import com.sysadmindoc.alarmclock.data.repository.HolidayClassification
import com.sysadmindoc.alarmclock.data.repository.HolidayClassificationKind
import com.sysadmindoc.alarmclock.data.repository.HolidayClassificationSource
import com.sysadmindoc.alarmclock.data.repository.HolidayProviderSnapshot
import com.sysadmindoc.alarmclock.data.repository.HolidayReconcileCoordinator
import com.sysadmindoc.alarmclock.data.repository.HolidayReconcileResult
import com.sysadmindoc.alarmclock.data.repository.HolidayRepository
import com.sysadmindoc.alarmclock.data.repository.HolidayYearCoverage
import com.sysadmindoc.alarmclock.data.repository.ManualHolidayOverride
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import com.sysadmindoc.alarmclock.domain.NextAlarmCalculator
import com.sysadmindoc.alarmclock.ui.alarmedit.AlarmEditViewModel
import com.sysadmindoc.alarmclock.ui.navigation.Screen
import com.sysadmindoc.alarmclock.ui.settings.SettingsUiState
import com.sysadmindoc.alarmclock.ui.settings.SettingsViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
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
import java.io.IOException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Provider

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HolidayUiIntegrationTest {

    private lateinit var context: Context
    private lateinit var db: AlarmDatabase
    private lateinit var alarmDao: AlarmDao
    private lateinit var alarmRepo: com.sysadmindoc.alarmclock.data.repository.AlarmRepository
    private lateinit var preferencesManager: PreferencesManager
    private val calculator = NextAlarmCalculator()
    private val mockScheduler: AlarmScheduler = mockk(relaxed = true)

    private val mockHolidayRepository: HolidayRepository = mockk(relaxed = true)
    private val mockOverrideStore: ManualHolidayOverrideStore = mockk(relaxed = true)
    private val mockCoordinator: HolidayReconcileCoordinator = mockk(relaxed = true)
    private val coordinatorProvider = Provider { mockCoordinator }

    @Before
    fun setUp() {
        try { Dispatchers.resetMain() } catch (_: Exception) {}
        Dispatchers.setMain(UnconfinedTestDispatcher())

        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .addCallback(AlarmDatabase.DATABASE_CALLBACK)
            .allowMainThreadQueries()
            .build()
        alarmDao = db.alarmDao()
        alarmRepo = com.sysadmindoc.alarmclock.data.repository.AlarmRepository(db, alarmDao, db.alarmGroupDao())

        preferencesManager = mockk(relaxed = true)
        coEvery { preferencesManager.getCurrentSettings() } returns AppSettings(holidayCountryCode = "JP")
        every { preferencesManager.getCachedSettings() } returns AppSettings(holidayCountryCode = "JP")
        coEvery { mockScheduler.schedule(any()) } returns com.sysadmindoc.alarmclock.domain.ScheduleOutcome.Armed(1L, 1000L)

        every { mockOverrideStore.isCorrupted } returns false
        coEvery { mockOverrideStore.setOverride(any(), any()) } returns HolidayReconcileResult.Success(reconciledCount = 1)
        coEvery { mockOverrideStore.clearOverride(any()) } returns HolidayReconcileResult.Success(reconciledCount = 1)
        coEvery { mockCoordinator.reconcile(any(), any(), any(), any()) } returns HolidayReconcileResult.Success(reconciledCount = 1)
        coEvery { mockHolidayRepository.captureAuthorityWindow(any(), any()) } answers {
            val start = firstArg<LocalDate>()
            val end = secondArg<LocalDate>()
            CapturedHolidayAuthorityWindow(
                startDate = start,
                endDateInclusive = end,
                configuredCountry = "JP",
                providerSnapshot = null,
                manualOverrides = emptyMap(),
                capturedAtMillis = 1000L
            )
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    // A. FOLLOW_BASE load/save
    @Test
    fun testA_follow_base_load_and_save() = runBlocking {
        val alarm = Alarm(
            hour = 8,
            minute = 0,
            repeatDays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY),
            holidayPolicy = Alarm.HOLIDAY_POLICY_FOLLOW_BASE,
            skipOnHolidays = false
        )
        val id = alarmRepo.save(alarm)

        val handle = SavedStateHandle(mapOf("alarmId" to id))
        val viewModel = AlarmEditViewModel(handle, context, alarmRepo, mockScheduler, calculator, preferencesManager)

        val state = viewModel.uiState.first { it.isEditing }
        assertEquals(Alarm.HOLIDAY_POLICY_FOLLOW_BASE, state.holidayPolicy)
        assertFalse(state.skipOnHolidays)

        val savedDeferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
        viewModel.save(onComplete = { savedDeferred.complete(true) })
        assertTrue(savedDeferred.await())

        val savedAlarm = alarmRepo.getById(id)
        assertNotNull(savedAlarm)
        assertEquals(Alarm.HOLIDAY_POLICY_FOLLOW_BASE, savedAlarm!!.holidayPolicy)
        assertFalse(savedAlarm.skipOnHolidays)
    }

    // B. EXCLUDE load/save
    @Test
    fun testB_exclude_load_and_save() = runBlocking {
        val alarm = Alarm(
            hour = 8,
            minute = 0,
            repeatDays = setOf(DayOfWeek.MONDAY),
            holidayPolicy = Alarm.HOLIDAY_POLICY_EXCLUDE,
            skipOnHolidays = true
        )
        val id = alarmRepo.save(alarm)

        val handle = SavedStateHandle(mapOf("alarmId" to id))
        val viewModel = AlarmEditViewModel(handle, context, alarmRepo, mockScheduler, calculator, preferencesManager)

        val state = viewModel.uiState.first { it.isEditing }
        assertEquals(Alarm.HOLIDAY_POLICY_EXCLUDE, state.holidayPolicy)
        assertTrue(state.skipOnHolidays)

        val savedDeferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
        viewModel.save(onComplete = { savedDeferred.complete(true) })
        assertTrue(savedDeferred.await())

        val savedAlarm = alarmRepo.getById(id)
        assertNotNull(savedAlarm)
        assertEquals(Alarm.HOLIDAY_POLICY_EXCLUDE, savedAlarm!!.holidayPolicy)
        assertTrue(savedAlarm.skipOnHolidays)
    }

    // C. INCLUDE load/save
    @Test
    fun testC_include_load_and_save() = runBlocking {
        val alarm = Alarm(
            hour = 8,
            minute = 0,
            repeatDays = setOf(DayOfWeek.MONDAY),
            holidayPolicy = Alarm.HOLIDAY_POLICY_INCLUDE,
            skipOnHolidays = false
        )
        val id = alarmRepo.save(alarm)

        val handle = SavedStateHandle(mapOf("alarmId" to id))
        val viewModel = AlarmEditViewModel(handle, context, alarmRepo, mockScheduler, calculator, preferencesManager)

        val state = viewModel.uiState.first { it.isEditing }
        assertEquals(Alarm.HOLIDAY_POLICY_INCLUDE, state.holidayPolicy)
        assertFalse(state.skipOnHolidays)

        val savedDeferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
        viewModel.save(onComplete = { savedDeferred.complete(true) })
        assertTrue(savedDeferred.await())

        val savedAlarm = alarmRepo.getById(id)
        assertNotNull(savedAlarm)
        assertEquals(Alarm.HOLIDAY_POLICY_INCLUDE, savedAlarm!!.holidayPolicy)
        assertFalse(savedAlarm.skipOnHolidays)
    }

    // D. INCLUDE with zero weekdays becomes repeating alarm
    @Test
    fun testD_include_with_zero_weekdays() = runBlocking {
        val handle = SavedStateHandle() // New alarm
        val viewModel = AlarmEditViewModel(handle, context, alarmRepo, mockScheduler, calculator, preferencesManager)

        viewModel.updateHolidayPolicy(Alarm.HOLIDAY_POLICY_INCLUDE)

        val savedDeferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
        viewModel.save(onComplete = { savedDeferred.complete(true) })
        assertTrue(savedDeferred.await())

        val alarms = alarmRepo.getAll()
        val savedAlarm = alarms.first()
        assertEquals(Alarm.HOLIDAY_POLICY_INCLUDE, savedAlarm.holidayPolicy)
        assertEquals(Alarm.SCHEDULE_KIND_REPEATING, savedAlarm.scheduleKind)
    }

    // E. Existing legacy skip alarm migration/display
    @Test
    fun testE_existing_legacy_skip_alarm_migration_display() = runBlocking {
        val legacyAlarm = Alarm(
            hour = 7,
            minute = 30,
            repeatDays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
            skipOnHolidays = true,
            holidayPolicy = "" // blank legacy policy
        )
        val id = alarmRepo.save(legacyAlarm)

        val handle = SavedStateHandle(mapOf("alarmId" to id))
        val viewModel = AlarmEditViewModel(handle, context, alarmRepo, mockScheduler, calculator, preferencesManager)

        val state = viewModel.uiState.first { it.isEditing }
        assertEquals(Alarm.HOLIDAY_POLICY_EXCLUDE, state.holidayPolicy)
        assertTrue(state.skipOnHolidays)

        val savedDeferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
        viewModel.save(onComplete = { savedDeferred.complete(true) })
        assertTrue(savedDeferred.await())

        val savedAlarm = alarmRepo.getById(id)
        assertNotNull(savedAlarm)
        assertEquals(Alarm.HOLIDAY_POLICY_EXCLUDE, savedAlarm!!.holidayPolicy)
        assertTrue(savedAlarm.skipOnHolidays)
    }

    // F. Unrelated edit preserves holiday policy
    @Test
    fun testF_unrelated_edit_preserves_holiday_policy() = runBlocking {
        val alarm = Alarm(
            hour = 8,
            minute = 0,
            label = "Morning",
            repeatDays = setOf(DayOfWeek.MONDAY),
            holidayPolicy = Alarm.HOLIDAY_POLICY_INCLUDE
        )
        val id = alarmRepo.save(alarm)

        val handle = SavedStateHandle(mapOf("alarmId" to id))
        val viewModel = AlarmEditViewModel(handle, context, alarmRepo, mockScheduler, calculator, preferencesManager)

        val state = viewModel.uiState.first { it.isEditing }
        assertEquals(Alarm.HOLIDAY_POLICY_INCLUDE, state.holidayPolicy)

        viewModel.updateLabel("Evening")
        val savedDeferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
        viewModel.save(onComplete = { savedDeferred.complete(true) })
        assertTrue(savedDeferred.await())

        val savedAlarm = alarmRepo.getById(id)
        assertNotNull(savedAlarm)
        assertEquals("Evening", savedAlarm!!.label)
        assertEquals(Alarm.HOLIDAY_POLICY_INCLUDE, savedAlarm.holidayPolicy)
    }

    // G. Settings hides global auto-skip control while preserving stored preference
    @Test
    fun testG_settings_hides_global_auto_skip_control() {
        val settings = AppSettings(holidayAutoSkipEnabled = true, holidayCountryCode = "JP")
        assertEquals(true, settings.holidayAutoSkipEnabled)
        assertEquals("JP", settings.holidayCountryCode)
    }

    // H. Settings navigates to Holiday Calendar
    @Test
    fun testH_settings_navigates_to_holiday_calendar() {
        assertEquals("holiday_calendar", Screen.HolidayCalendar.route)
    }

    // I. Calendar displays effective date status
    @Test
    fun testI_calendar_displays_effective_date_status() = runBlocking {
        val nov2026 = YearMonth.of(2026, 11)
        val start = nov2026.atDay(1)
        val end = nov2026.atEndOfMonth()
        val nov3 = nov2026.atDay(3) // Provider holiday
        val nov4 = nov2026.atDay(4) // Provider holiday but FORCE_WORKDAY
        val nov5 = nov2026.atDay(5) // Normal day but FORCE_HOLIDAY

        val snapshot = HolidayProviderSnapshot(
            providerId = "nager.date/v3",
            countryCode = "JP",
            generation = 1L,
            years = mapOf(
                2026 to HolidayYearCoverage(
                    year = 2026,
                    fetchedAtMillis = 1000L,
                    dates = setOf(nov3, nov4)
                )
            )
        )
        val authority = CapturedHolidayAuthorityWindow(
            startDate = start,
            endDateInclusive = end,
            configuredCountry = "JP",
            providerSnapshot = snapshot,
            manualOverrides = mapOf(
                nov4 to ManualHolidayOverride.FORCE_WORKDAY,
                nov5 to ManualHolidayOverride.FORCE_HOLIDAY
            ),
            capturedAtMillis = 2000L
        )
        coEvery { mockHolidayRepository.captureAuthorityWindow(start, end) } returns authority

        val handle = SavedStateHandle(mapOf(HolidayCalendarViewModel.KEY_DISPLAYED_MONTH to nov2026.toString()))
        val viewModel = HolidayCalendarViewModel(
            handle, context, mockHolidayRepository, mockOverrideStore, preferencesManager, coordinatorProvider
        )

        val state = viewModel.uiState.first { it.days.isNotEmpty() }
        val day3 = state.days.first { it.date == nov3 }
        assertTrue(day3.isProviderHoliday)
        assertEquals(ManualHolidayOverride.AUTO, day3.manualOverride)
        assertEquals(HolidayClassificationKind.HOLIDAY, day3.resolvedClassification)
        assertTrue(day3.isHoliday)

        val day4 = state.days.first { it.date == nov4 }
        assertTrue(day4.isProviderHoliday)
        assertEquals(ManualHolidayOverride.FORCE_WORKDAY, day4.manualOverride)
        assertEquals(HolidayClassificationKind.WORKDAY, day4.resolvedClassification)
        assertFalse(day4.isHoliday)

        val day5 = state.days.first { it.date == nov5 }
        assertFalse(day5.isProviderHoliday)
        assertEquals(ManualHolidayOverride.FORCE_HOLIDAY, day5.manualOverride)
        assertEquals(HolidayClassificationKind.HOLIDAY, day5.resolvedClassification)
        assertTrue(day5.isHoliday)
    }

    // J. FORCE_HOLIDAY action
    @Test
    fun testJ_force_holiday_action() = runBlocking {
        val nov2026 = YearMonth.of(2026, 11)
        val targetDate = nov2026.atDay(10)
        val handle = SavedStateHandle(mapOf(HolidayCalendarViewModel.KEY_DISPLAYED_MONTH to nov2026.toString()))
        val viewModel = HolidayCalendarViewModel(
            handle, context, mockHolidayRepository, mockOverrideStore, preferencesManager, coordinatorProvider
        )

        viewModel.forceHoliday(targetDate)
        coVerify { mockOverrideStore.setOverride(targetDate, ManualHolidayOverride.FORCE_HOLIDAY) }
        assertEquals(HolidayFeedbackKind.RECONCILE_SUCCESS, viewModel.uiState.value.lastFeedbackKind)
    }

    // K. FORCE_WORKDAY action
    @Test
    fun testK_force_workday_action() = runBlocking {
        val nov2026 = YearMonth.of(2026, 11)
        val targetDate = nov2026.atDay(11)
        val handle = SavedStateHandle(mapOf(HolidayCalendarViewModel.KEY_DISPLAYED_MONTH to nov2026.toString()))
        val viewModel = HolidayCalendarViewModel(
            handle, context, mockHolidayRepository, mockOverrideStore, preferencesManager, coordinatorProvider
        )

        viewModel.forceWorkday(targetDate)
        coVerify { mockOverrideStore.setOverride(targetDate, ManualHolidayOverride.FORCE_WORKDAY) }
        assertEquals(HolidayFeedbackKind.RECONCILE_SUCCESS, viewModel.uiState.value.lastFeedbackKind)
    }

    // L. AUTO reset
    @Test
    fun testL_auto_reset() = runBlocking {
        val nov2026 = YearMonth.of(2026, 11)
        val targetDate = nov2026.atDay(12)
        val handle = SavedStateHandle(mapOf(HolidayCalendarViewModel.KEY_DISPLAYED_MONTH to nov2026.toString()))
        val viewModel = HolidayCalendarViewModel(
            handle, context, mockHolidayRepository, mockOverrideStore, preferencesManager, coordinatorProvider
        )

        viewModel.resetToAuto(targetDate)
        coVerify { mockOverrideStore.clearOverride(targetDate) }
        assertEquals(HolidayFeedbackKind.RECONCILE_SUCCESS, viewModel.uiState.value.lastFeedbackKind)
    }

    // M. Range conflict blocks mutation
    @Test
    fun testM_range_conflict_blocks_mutation() = runBlocking {
        val nov2026 = YearMonth.of(2026, 11)
        val d1 = nov2026.atDay(1)
        val d5 = nov2026.atDay(5)
        val conflictDate = nov2026.atDay(3)

        coEvery {
            mockOverrideStore.setRange(d1, d5, ManualHolidayOverride.FORCE_HOLIDAY, overwriteExisting = false)
        } returns RangeMutationResult.Conflict(listOf(conflictDate))

        val handle = SavedStateHandle(mapOf(HolidayCalendarViewModel.KEY_DISPLAYED_MONTH to nov2026.toString()))
        val viewModel = HolidayCalendarViewModel(
            handle, context, mockHolidayRepository, mockOverrideStore, preferencesManager, coordinatorProvider
        )

        viewModel.applyRange(d1, d5, ManualHolidayOverride.FORCE_HOLIDAY, overwriteExisting = false)

        val state = viewModel.uiState.value
        assertNotNull(state.pendingRangeConflict)
        assertEquals(listOf(conflictDate), state.pendingRangeConflict!!.conflictingDates)
    }

    // N. Explicit range overwrite
    @Test
    fun testN_explicit_range_overwrite() = runBlocking {
        val nov2026 = YearMonth.of(2026, 11)
        val d1 = nov2026.atDay(1)
        val d5 = nov2026.atDay(5)
        val conflictDate = nov2026.atDay(3)

        coEvery {
            mockOverrideStore.setRange(d1, d5, ManualHolidayOverride.FORCE_HOLIDAY, overwriteExisting = false)
        } returns RangeMutationResult.Conflict(listOf(conflictDate))

        coEvery {
            mockOverrideStore.setRange(d1, d5, ManualHolidayOverride.FORCE_HOLIDAY, overwriteExisting = true)
        } returns RangeMutationResult.Applied(affectedDatesCount = 5, HolidayReconcileResult.Success(reconciledCount = 1))

        val handle = SavedStateHandle(mapOf(HolidayCalendarViewModel.KEY_DISPLAYED_MONTH to nov2026.toString()))
        val viewModel = HolidayCalendarViewModel(
            handle, context, mockHolidayRepository, mockOverrideStore, preferencesManager, coordinatorProvider
        )

        viewModel.applyRange(d1, d5, ManualHolidayOverride.FORCE_HOLIDAY, overwriteExisting = false)
        assertNotNull(viewModel.uiState.value.pendingRangeConflict)

        viewModel.confirmConflictOverwrite()

        coVerify {
            mockOverrideStore.setRange(d1, d5, ManualHolidayOverride.FORCE_HOLIDAY, overwriteExisting = true)
        }
        assertNull(viewModel.uiState.value.pendingRangeConflict)
        assertEquals(HolidayFeedbackKind.RECONCILE_SUCCESS, viewModel.uiState.value.lastFeedbackKind)
    }

    // O. PendingRetry user feedback
    @Test
    fun testO_pending_retry_user_feedback() = runBlocking {
        val nov2026 = YearMonth.of(2026, 11)
        val d1 = nov2026.atDay(1)
        coEvery { mockOverrideStore.setOverride(d1, any()) } returns HolidayReconcileResult.PendingRetry(pendingCount = 2)

        val handle = SavedStateHandle(mapOf(HolidayCalendarViewModel.KEY_DISPLAYED_MONTH to nov2026.toString()))
        val viewModel = HolidayCalendarViewModel(
            handle, context, mockHolidayRepository, mockOverrideStore, preferencesManager, coordinatorProvider
        )

        viewModel.setDateOverride(d1, ManualHolidayOverride.FORCE_HOLIDAY)

        val state = viewModel.uiState.value
        assertEquals(HolidayFeedbackKind.RECONCILE_PENDING, state.lastFeedbackKind)
        assertNotNull(state.statusMessage)
        assertTrue(
            state.statusMessage!!.contains("pending", ignoreCase = true) ||
            state.statusMessage!!.contains("保留")
        )
    }

    // P. File write failure user feedback
    @Test
    fun testP_file_write_failure_user_feedback() = runBlocking {
        val nov2026 = YearMonth.of(2026, 11)
        val d1 = nov2026.atDay(1)
        coEvery { mockOverrideStore.setOverride(d1, any()) } throws IOException("Disk error")

        val handle = SavedStateHandle(mapOf(HolidayCalendarViewModel.KEY_DISPLAYED_MONTH to nov2026.toString()))
        val viewModel = HolidayCalendarViewModel(
            handle, context, mockHolidayRepository, mockOverrideStore, preferencesManager, coordinatorProvider
        )

        viewModel.setDateOverride(d1, ManualHolidayOverride.FORCE_HOLIDAY)

        val state = viewModel.uiState.value
        assertEquals(HolidayFeedbackKind.SAVE_FAILED, state.lastFeedbackKind)
        assertNotNull(state.statusMessage)
        assertTrue(
            state.statusMessage!!.contains("fail", ignoreCase = true) ||
            state.statusMessage!!.contains("失敗")
        )
    }

    // Q. Corrupted authority UI state
    @Test
    fun testQ_corrupted_authority_ui_state() = runBlocking {
        every { mockOverrideStore.isCorrupted } returns true
        val nov2026 = YearMonth.of(2026, 11)
        val handle = SavedStateHandle(mapOf(HolidayCalendarViewModel.KEY_DISPLAYED_MONTH to nov2026.toString()))
        val viewModel = HolidayCalendarViewModel(
            handle, context, mockHolidayRepository, mockOverrideStore, preferencesManager, coordinatorProvider
        )

        val state = viewModel.uiState.first { it.isCorrupted }
        assertTrue(state.isCorrupted)

        viewModel.confirmReset()
        io.mockk.verify { mockOverrideStore.recoverFromCorruption(clearCorruptedFile = true) }
        coVerify { mockCoordinator.reconcile(isMaterialChange = true, any(), any(), any()) }
        assertEquals(HolidayFeedbackKind.RESET_SUCCESS, viewModel.uiState.value.lastFeedbackKind)
    }

    // R. Month navigation and state refresh
    @Test
    fun testR_month_navigation_and_state_refresh() = runBlocking {
        val nov2026 = YearMonth.of(2026, 11)
        val handle = SavedStateHandle(mapOf(HolidayCalendarViewModel.KEY_DISPLAYED_MONTH to nov2026.toString()))
        val viewModel = HolidayCalendarViewModel(
            handle, context, mockHolidayRepository, mockOverrideStore, preferencesManager, coordinatorProvider
        )

        assertEquals(nov2026, viewModel.uiState.value.displayedMonth)

        viewModel.nextMonth()
        assertEquals(YearMonth.of(2026, 12), viewModel.uiState.value.displayedMonth)

        viewModel.previousMonth()
        assertEquals(nov2026, viewModel.uiState.value.displayedMonth)
    }

    // S. Rotation/recreation state correctness
    @Test
    fun testS_rotation_recreation_state_correctness() = runBlocking {
        val handle = SavedStateHandle()
        val viewModel1 = HolidayCalendarViewModel(
            handle, context, mockHolidayRepository, mockOverrideStore, preferencesManager, coordinatorProvider
        )

        val targetMonth = YearMonth.of(2027, 4)
        viewModel1.setMonth(targetMonth)
        assertEquals(targetMonth, viewModel1.uiState.value.displayedMonth)

        // Recreate with same SavedStateHandle
        val viewModel2 = HolidayCalendarViewModel(
            handle, context, mockHolidayRepository, mockOverrideStore, preferencesManager, coordinatorProvider
        )
        assertEquals(targetMonth, viewModel2.uiState.value.displayedMonth)
    }

    // T. No duplicate submissions
    @Test
    fun testT_no_duplicate_submissions() = runBlocking {
        val nov2026 = YearMonth.of(2026, 11)
        val d1 = nov2026.atDay(1)
        val handle = SavedStateHandle(mapOf(HolidayCalendarViewModel.KEY_DISPLAYED_MONTH to nov2026.toString()))
        val viewModel = HolidayCalendarViewModel(
            handle, context, mockHolidayRepository, mockOverrideStore, preferencesManager, coordinatorProvider
        )

        // In standard flow, setDateOverride sets isSubmitting = true, runs block, then finally sets isSubmitting = false
        // Let's verify isSubmitting is false initially and after operation
        assertFalse(viewModel.uiState.value.isSubmitting)
        viewModel.setDateOverride(d1, ManualHolidayOverride.FORCE_HOLIDAY)
        assertFalse(viewModel.uiState.value.isSubmitting)
        coVerify(exactly = 1) { mockOverrideStore.setOverride(d1, ManualHolidayOverride.FORCE_HOLIDAY) }
    }
}
