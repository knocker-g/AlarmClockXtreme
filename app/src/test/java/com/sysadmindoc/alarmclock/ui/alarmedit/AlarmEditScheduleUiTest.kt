package com.sysadmindoc.alarmclock.ui.alarmedit

import android.content.Context
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.R
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleException
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import com.sysadmindoc.alarmclock.domain.NextAlarmCalculator
import com.sysadmindoc.alarmclock.domain.ScheduleOutcome
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.ZoneId
import java.util.TimeZone

/**
 * Focused UI and Integration Tests for KNO-35 Schedule & Smart Wake UI Cleanup.
 *
 * Verifies:
 * 1. Smart Alarm section is absent from the Personal Schedule page.
 * 2. Smart Alarm overview summary is absent from Alarm Overview.
 * 3. Schedule page title and subtitle are correctly localized.
 * 4. Upcoming button invokes real navigation callback to Upcoming Timeline.
 * 5. Holiday Calendar button invokes real navigation callback to Holiday Calendar.
 * 6. Holiday policy choices remain selectable.
 * 7. Unsaved Alarm Edit draft is protected from silent loss.
 * 8. Legacy 7-entry raw forecast is not rendered as canonical truth.
 * 9. One-time Override remains a passive status hint, not a competing writer.
 * 10. Existing permanent Alarm Edit Save preserves stored smart alarm flag.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en")
class AlarmEditScheduleUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var scheduler: AlarmScheduler
    private lateinit var preferencesManager: PreferencesManager
    private var originalTimeZone: TimeZone? = null
    private var originalLocale: java.util.Locale? = null

    private val zoneId = ZoneId.of("America/New_York")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try { org.robolectric.shadows.ShadowLooper.idleMainLooper() } catch (_: Exception) {}
        originalTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(zoneId))

        originalLocale = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale.US)
        val config = android.content.res.Configuration(context.resources.configuration)
        config.setLocale(java.util.Locale.US)
        context.resources.updateConfiguration(config, context.resources.displayMetrics)

        scheduler = mockk(relaxed = true)
        coEvery { scheduler.schedule(any()) } returns ScheduleOutcome.Armed(1L, 1793786400000L)

        preferencesManager = mockk(relaxed = true)
        coEvery { preferencesManager.getCurrentSettings() } returns AppSettings()
        every { preferencesManager.getCachedSettings() } returns AppSettings()
        coEvery { preferencesManager.settings } returns flowOf(AppSettings())
    }

    @After
    fun tearDown() {
        try { composeTestRule.waitForIdle() } catch (_: Exception) {}
        try { org.robolectric.shadows.ShadowLooper.idleMainLooper() } catch (_: Exception) {}
        originalTimeZone?.let { TimeZone.setDefault(it) }
        originalLocale?.let {
            java.util.Locale.setDefault(it)
            val config = android.content.res.Configuration(context.resources.configuration)
            config.setLocale(it)
            context.resources.updateConfiguration(config, context.resources.displayMetrics)
        }
        Dispatchers.resetMain()
    }

    // 1. Smart Alarm section absent from Personal Schedule
    @Test
    fun test1_smartAlarmSection_isAbsentFromPersonalSchedule() {
        val state = AlarmEditUiState(
            smartAlarmEnabled = true,
            smartAlarmWindowMinutes = 30
        )
        val mockViewModel = mockk<AlarmEditViewModel>(relaxed = true)

        composeTestRule.setContent {
            LazyColumn {
                alarmEditScheduleSections(
                    editorPage = AlarmEditorPage.SCHEDULE,
                    state = state,
                    viewModel = mockViewModel,
                    onOpenUpcomingTimeline = {},
                    onOpenHolidayCalendar = {}
                )
            }
        }

        composeTestRule.onNodeWithText(context.getString(R.string.alarm_edit_light_sleep)).assertDoesNotExist()
        composeTestRule.onNodeWithText(context.getString(R.string.alarm_edit_detection_window)).assertDoesNotExist()
        composeTestRule.onNodeWithText(context.getString(R.string.alarm_edit_smart_alarm_hint)).assertDoesNotExist()
    }

    // 2. Smart Alarm overview summary absent
    @Test
    fun test2_smartAlarmOverviewSummary_isAbsentFromOverview() {
        val state = AlarmEditUiState(
            smartAlarmEnabled = true,
            smartAlarmWindowMinutes = 45,
            repeatDays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY)
        )

        composeTestRule.setContent {
            AlarmEditorCategoryOverview(
                state = state,
                onSelect = {}
            )
        }

        // Must not contain "Smart 45 min window"
        val smartPattern = context.getString(R.string.alarm_edit_schedule_smart, "Mon", 45)
        composeTestRule.onNodeWithText(smartPattern).assertDoesNotExist()

        // Displays standard schedule category title
        composeTestRule.onNodeWithText(context.getString(R.string.alarm_edit_page_schedule)).assertIsDisplayed()
    }

    // 3. Schedule page title and subtitle correctly localized
    @Test
    fun test3_schedulePageTitleAndSubtitle_areCorrectlyLocalized() {
        assertEquals("Schedule", context.getString(R.string.alarm_edit_page_schedule))
        assertEquals("Upcoming rings and holiday policy.", context.getString(R.string.alarm_edit_page_schedule_subtitle))
    }

    // 4. Upcoming button invokes real navigation callback
    @Test
    fun test4_upcomingButton_invokesNavigationCallback() {
        var upcomingTimelineInvoked = false
        val state = AlarmEditUiState()
        val mockViewModel = mockk<AlarmEditViewModel>(relaxed = true)

        composeTestRule.setContent {
            LazyColumn {
                alarmEditScheduleSections(
                    editorPage = AlarmEditorPage.SCHEDULE,
                    state = state,
                    viewModel = mockViewModel,
                    onOpenUpcomingTimeline = { upcomingTimelineInvoked = true },
                    onOpenHolidayCalendar = {}
                )
            }
        }

        val buttonText = context.getString(R.string.alarm_edit_view_upcoming_timeline)
        composeTestRule.onNodeWithText(buttonText).assertIsDisplayed().performClick()
        assertTrue("Upcoming timeline callback should be invoked", upcomingTimelineInvoked)
    }

    // 5. Holiday Calendar button invokes real navigation callback
    @Test
    fun test5_holidayCalendarButton_invokesNavigationCallback() {
        var holidayCalendarInvoked = false
        val state = AlarmEditUiState()
        val mockViewModel = mockk<AlarmEditViewModel>(relaxed = true)

        composeTestRule.setContent {
            LazyColumn {
                alarmEditScheduleSections(
                    editorPage = AlarmEditorPage.SCHEDULE,
                    state = state,
                    viewModel = mockViewModel,
                    onOpenUpcomingTimeline = {},
                    onOpenHolidayCalendar = { holidayCalendarInvoked = true }
                )
            }
        }

        val buttonText = context.getString(R.string.alarm_edit_view_holiday_calendar)
        composeTestRule.onNodeWithText(buttonText).performScrollTo().assertIsDisplayed().performClick()
        assertTrue("Holiday calendar callback should be invoked", holidayCalendarInvoked)
    }

    // 6. Holiday policy choices remain selectable
    @Test
    fun test6_holidayPolicyChoices_remainSelectable() {
        val state = AlarmEditUiState(holidayPolicy = Alarm.HOLIDAY_POLICY_FOLLOW_BASE)
        val mockViewModel = mockk<AlarmEditViewModel>(relaxed = true)

        composeTestRule.setContent {
            LazyColumn {
                alarmEditScheduleSections(
                    editorPage = AlarmEditorPage.SCHEDULE,
                    state = state,
                    viewModel = mockViewModel,
                    onOpenUpcomingTimeline = {},
                    onOpenHolidayCalendar = {}
                )
            }
        }

        val excludeText = context.getString(R.string.holiday_policy_exclude)
        composeTestRule.onNodeWithText(excludeText).performScrollTo().assertIsDisplayed().performClick()
        verify(exactly = 1) { mockViewModel.updateHolidayPolicy(Alarm.HOLIDAY_POLICY_EXCLUDE) }
    }

    // 7. Unsaved Alarm Edit draft is not silently lost
    @Test
    fun test7_unsavedDraftProtection_preventsSilentDiscard() {
        // While on SCHEDULE page, exit decision goes to OVERVIEW, never silent NAVIGATE
        assertEquals(
            AlarmEditorExitDecision.SHOW_OVERVIEW,
            alarmEditorExitDecision(hasUnsavedChanges = true, isSaving = false, page = AlarmEditorPage.SCHEDULE)
        )
        // From OVERVIEW, unsaved changes require confirmation dialog
        assertEquals(
            AlarmEditorExitDecision.CONFIRM_DISCARD,
            alarmEditorExitDecision(hasUnsavedChanges = true, isSaving = false, page = AlarmEditorPage.OVERVIEW)
        )
        // Only clean draft silently navigates
        assertEquals(
            AlarmEditorExitDecision.NAVIGATE,
            alarmEditorExitDecision(hasUnsavedChanges = false, isSaving = false, page = AlarmEditorPage.OVERVIEW)
        )
    }

    // 8. Legacy forecast is not rendered as canonical schedule truth
    @Test
    fun test8_legacyForecast_isNotRenderedInScheduleSections() {
        val state = AlarmEditUiState(
            forecastDates = listOf(
                ForecastEntry(1793786400000L, skippedByVacation = false),
                ForecastEntry(1793872800000L, skippedByVacation = true)
            )
        )
        val mockViewModel = mockk<AlarmEditViewModel>(relaxed = true)

        composeTestRule.setContent {
            LazyColumn {
                alarmEditScheduleSections(
                    editorPage = AlarmEditorPage.SCHEDULE,
                    state = state,
                    viewModel = mockViewModel,
                    onOpenUpcomingTimeline = {},
                    onOpenHolidayCalendar = {}
                )
            }
        }

        // "Will not ring" hint is not rendered
        composeTestRule.onNodeWithText(context.getString(R.string.alarm_edit_will_not_ring)).assertDoesNotExist()
        // Raw forecast date strings are not rendered
        composeTestRule.onNodeWithText("Vacation skip", substring = true).assertDoesNotExist()
    }

    // 9. One-time Override remains a passive status/linked canonical operation, not a new writer
    @Test
    fun test9_oneTimeOverride_isRenderedAsPassiveStatusHint() {
        val state = AlarmEditUiState(
            activeOverrideType = ScheduleException.TYPE_TIME_OVERRIDE,
            activeOverrideTriggerTime = 1793786400000L,
            activeOverrideBaseTriggerTime = 1793782800000L,
            is24HourFormat = true
        )
        val mockViewModel = mockk<AlarmEditViewModel>(relaxed = true)

        composeTestRule.setContent {
            LazyColumn {
                alarmEditScheduleSections(
                    editorPage = AlarmEditorPage.SCHEDULE,
                    state = state,
                    viewModel = mockViewModel,
                    onOpenUpcomingTimeline = {},
                    onOpenHolidayCalendar = {}
                )
            }
        }

        // Passive hint for one-time override status is displayed
        val overridePrefix = context.getString(R.string.one_time_override_current_status_label, "").trim()
        composeTestRule.onNodeWithText(overridePrefix, substring = true).assertIsDisplayed()

        // Mutation actions belong to Upcoming Timeline; not duplicated here
        composeTestRule.onNodeWithText(context.getString(R.string.upcoming_timeline_action_change_time)).assertDoesNotExist()
        composeTestRule.onNodeWithText(context.getString(R.string.upcoming_timeline_action_skip_once)).assertDoesNotExist()
    }

    // 10. Existing permanent Alarm Edit Save behavior is unaffected
    @Test
    fun test10_existingPermanentSave_preservesStoredSmartAlarmValue() = runBlocking {
        val initialAlarm = Alarm(
            id = 1L,
            hour = 7,
            minute = 15,
            repeatDays = setOf(DayOfWeek.MONDAY),
            isEnabled = true,
            smartAlarmEnabled = true,
            smartAlarmWindowMinutes = 20
        )
        val mockRepo = mockk<AlarmRepository>(relaxed = true)
        val mockDb = mockk<com.sysadmindoc.alarmclock.data.local.AlarmDatabase>(relaxed = true)
        val mockExceptionDao = mockk<com.sysadmindoc.alarmclock.data.local.ScheduleExceptionDao>(relaxed = true)
        every { mockRepo.database } returns mockDb
        every { mockDb.scheduleExceptionDao() } returns mockExceptionDao
        coEvery { mockExceptionDao.getExceptionsForAlarmGeneration(any(), any()) } returns emptyList()

        val savedSlot = slot<Alarm>()
        coEvery { mockRepo.getById(1L) } returns initialAlarm
        coEvery { mockRepo.update(capture(savedSlot)) } returns Unit
        every { mockRepo.observeGroups() } returns flowOf(emptyList())

        val savedStateHandle = SavedStateHandle(mapOf("alarmId" to 1L))
        val viewModel = AlarmEditViewModel(
            savedStateHandle = savedStateHandle,
            context = context,
            repository = mockRepo,
            scheduler = scheduler,
            calculator = NextAlarmCalculator(),
            preferencesManager = preferencesManager
        )

        val loadedState = viewModel.uiState.first { it.isEditing }
        assertTrue(loadedState.smartAlarmEnabled)
        assertEquals(20, loadedState.smartAlarmWindowMinutes)

        // Edit label and time
        viewModel.updateLabel("Updated Work Alarm")
        viewModel.updateTime(7, 45)

        val savedDeferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
        viewModel.save(onComplete = { savedDeferred.complete(true) })
        assertTrue("Save callback should complete", savedDeferred.await())

        // Verify repository.update received preserved smartAlarmEnabled = true
        coVerify(exactly = 1) { mockRepo.update(any()) }
        assertTrue(savedSlot.isCaptured)
        val savedAlarm = savedSlot.captured
        assertEquals("Updated Work Alarm", savedAlarm.label)
        assertEquals(7, savedAlarm.hour)
        assertEquals(45, savedAlarm.minute)
        assertTrue("Persisted smartAlarmEnabled must be preserved", savedAlarm.smartAlarmEnabled)
        assertEquals(20, savedAlarm.smartAlarmWindowMinutes)

        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    // 11. Dirty draft + Upcoming navigation triggers discard confirmation, Cancel preserves draft, Confirm proceeds
    @Test
    fun test11_dirtyDraft_upcomingTimelineNavigation_triggersDiscardConfirmation_andCancelPreservesDraft() {
        val uiStateFlow = kotlinx.coroutines.flow.MutableStateFlow(
            AlarmEditUiState(
                isEditing = true,
                hasUnsavedChanges = true,
                hour = 7,
                minute = 30,
                label = "Original Label"
            )
        )
        val mockViewModel = mockk<AlarmEditViewModel>(relaxed = true)
        every { mockViewModel.uiState } returns uiStateFlow

        var upcomingNavigated = false
        composeTestRule.setContent {
            AlarmEditScreen(
                onNavigateBack = {},
                onOpenUpcomingTimeline = { upcomingNavigated = true },
                onOpenHolidayCalendar = {},
                viewModel = mockViewModel,
                initialPage = AlarmEditorPage.SCHEDULE
            )
        }

        // Click "View upcoming timeline" on SCHEDULE page
        val upcomingButtonText = context.getString(R.string.alarm_edit_view_upcoming_timeline)
        composeTestRule.onNodeWithText(upcomingButtonText)
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()

        // Confirmation dialog is displayed
        val discardTitle = context.getString(R.string.alarm_edit_discard_title)
        composeTestRule.onNodeWithText(discardTitle).assertIsDisplayed()
        assertFalse("Navigation callback must NOT be invoked before confirmation", upcomingNavigated)

        // Click "Keep editing" (Cancel)
        val keepEditingText = context.getString(R.string.alarm_edit_keep_editing)
        composeTestRule.onNodeWithText(keepEditingText).performClick()

        // Dialog dismissed, still in editor, navigation was not invoked
        composeTestRule.onNodeWithText(discardTitle).assertDoesNotExist()
        assertFalse("Navigation callback must remain uninvoked after cancel", upcomingNavigated)
        composeTestRule.onNodeWithText(upcomingButtonText).assertIsDisplayed()

        // Click "View upcoming timeline" again, then click "Discard changes"
        composeTestRule.onNodeWithText(upcomingButtonText).performScrollTo().performClick()
        composeTestRule.onNodeWithText(discardTitle).assertIsDisplayed()
        val discardActionText = context.getString(R.string.alarm_edit_discard_action)
        composeTestRule.onNodeWithText(discardActionText).performClick()

        assertTrue("Navigation callback must be invoked after confirmed discard", upcomingNavigated)
    }

    // 12. Dirty draft + Holiday Calendar navigation triggers discard confirmation, Cancel preserves draft, Confirm proceeds
    @Test
    fun test12_dirtyDraft_holidayCalendarNavigation_triggersDiscardConfirmation_andCancelPreservesDraft() {
        val uiStateFlow = kotlinx.coroutines.flow.MutableStateFlow(
            AlarmEditUiState(
                isEditing = true,
                hasUnsavedChanges = true,
                hour = 7,
                minute = 30
            )
        )
        val mockViewModel = mockk<AlarmEditViewModel>(relaxed = true)
        every { mockViewModel.uiState } returns uiStateFlow

        var holidayNavigated = false
        composeTestRule.setContent {
            AlarmEditScreen(
                onNavigateBack = {},
                onOpenUpcomingTimeline = {},
                onOpenHolidayCalendar = { holidayNavigated = true },
                viewModel = mockViewModel,
                initialPage = AlarmEditorPage.SCHEDULE
            )
        }

        // Click "View holiday calendar" on SCHEDULE page
        val holidayButtonText = context.getString(R.string.alarm_edit_view_holiday_calendar)
        composeTestRule.onNodeWithText(holidayButtonText).performScrollTo().performClick()

        // Confirmation dialog displayed
        val discardTitle = context.getString(R.string.alarm_edit_discard_title)
        composeTestRule.onNodeWithText(discardTitle).assertIsDisplayed()
        assertFalse("Holiday navigation callback must NOT be invoked before confirmation", holidayNavigated)

        // Click "Keep editing" (Cancel)
        val keepEditingText = context.getString(R.string.alarm_edit_keep_editing)
        composeTestRule.onNodeWithText(keepEditingText).performClick()

        // Dialog dismissed
        composeTestRule.onNodeWithText(discardTitle).assertDoesNotExist()
        assertFalse("Holiday navigation must remain uninvoked after cancel", holidayNavigated)

        // Click "View holiday calendar" again, then click "Discard changes"
        composeTestRule.onNodeWithText(holidayButtonText).performScrollTo().performClick()
        composeTestRule.onNodeWithText(discardTitle).assertIsDisplayed()
        val discardActionText = context.getString(R.string.alarm_edit_discard_action)
        composeTestRule.onNodeWithText(discardActionText).performClick()

        assertTrue("Holiday navigation must be invoked after confirmed discard", holidayNavigated)
    }

    // 13. Clean draft navigation triggers callback directly without dialog
    @Test
    fun test13_cleanDraft_navigation_triggersCallbackDirectlyWithoutDialog() {
        val uiStateFlow = kotlinx.coroutines.flow.MutableStateFlow(
            AlarmEditUiState(
                isEditing = true,
                hasUnsavedChanges = false,
                hour = 7,
                minute = 30
            )
        )
        val mockViewModel = mockk<AlarmEditViewModel>(relaxed = true)
        every { mockViewModel.uiState } returns uiStateFlow

        var upcomingNavigated = false
        composeTestRule.setContent {
            AlarmEditScreen(
                onNavigateBack = {},
                onOpenUpcomingTimeline = { upcomingNavigated = true },
                onOpenHolidayCalendar = {},
                viewModel = mockViewModel,
                initialPage = AlarmEditorPage.SCHEDULE
            )
        }

        // Click "View upcoming timeline" on SCHEDULE page
        val upcomingButtonText = context.getString(R.string.alarm_edit_view_upcoming_timeline)
        composeTestRule.onNodeWithText(upcomingButtonText).performScrollTo().performClick()

        // Discard dialog is NOT displayed
        val discardTitle = context.getString(R.string.alarm_edit_discard_title)
        composeTestRule.onNodeWithText(discardTitle).assertDoesNotExist()
        assertTrue("Clean draft must navigate directly to upcoming timeline", upcomingNavigated)
    }
}
