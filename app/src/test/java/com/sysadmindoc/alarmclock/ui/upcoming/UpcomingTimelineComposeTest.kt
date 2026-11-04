package com.sysadmindoc.alarmclock.ui.upcoming

import android.content.Context
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.R
import com.sysadmindoc.alarmclock.domain.evaluator.HolidayPolicy
import com.sysadmindoc.alarmclock.domain.evaluator.HolidayState
import com.sysadmindoc.alarmclock.domain.projection.UpcomingOccurrenceOutcome
import com.sysadmindoc.alarmclock.domain.projection.UpcomingOccurrenceReason
import com.sysadmindoc.alarmclock.domain.projection.UpcomingProjectionFreshness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * Compose UI Interaction Tests for Upcoming Timeline Components (KNO-157 / KNO-34 Slice C).
 *
 * Verifies real Compose on-screen element rendering, button visibility, enabled states,
 * and click dispatch callbacks across all canonical occurrence scenarios.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class UpcomingTimelineComposeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun createOutcome(
        alarmId: Long = 1L,
        label: String = "Morning Alarm",
        localDate: LocalDate = LocalDate.of(2026, 11, 4),
        localDisplayTime: LocalTime = LocalTime.of(8, 0),
        reason: UpcomingOccurrenceReason = UpcomingOccurrenceReason.BASE_SCHEDULE,
        willFire: Boolean = true,
        isNextForAlarm: Boolean = true,
        isOneTimeOverrideEligible: Boolean = false,
        isCancelOverrideEligible: Boolean = false,
        baseTriggerTimeEpochMs: Long = 1793786400000L,
        targetTriggerTimeEpochMs: Long = 1793786400000L,
        freshness: UpcomingProjectionFreshness = UpcomingProjectionFreshness.CURRENT,
        policy: HolidayPolicy = HolidayPolicy.FOLLOW_BASE,
        holidayState: HolidayState = HolidayState.NOT_HOLIDAY
    ): UpcomingOccurrenceOutcome {
        return UpcomingOccurrenceOutcome(
            occurrenceId = null,
            alarmId = alarmId,
            alarmLabel = label,
            generation = 1,
            revision = 1,
            scheduledInstant = Instant.ofEpochMilli(targetTriggerTimeEpochMs),
            targetTriggerTimeEpochMs = targetTriggerTimeEpochMs,
            baseTriggerTimeEpochMs = baseTriggerTimeEpochMs,
            localDate = localDate,
            localDisplayTime = localDisplayTime,
            reason = reason,
            willFire = willFire,
            isNextForAlarm = isNextForAlarm,
            isOneTimeOverrideEligible = isOneTimeOverrideEligible,
            isCancelOverrideEligible = isCancelOverrideEligible,
            projectionFreshness = freshness,
            holidayPolicy = policy,
            holidayState = holidayState
        )
    }

    // 1. Active Change Time Once exposes Cancel Override button and dispatches click
    @Test
    fun activeChangeTimeOnce_rendersCancelOverride_andDispatchesClick() {
        var cancelOverrideClicked = false
        var openOverrideClicked = false
        var skipOnceClicked = false

        val outcome = createOutcome(
            reason = UpcomingOccurrenceReason.CHANGE_TIME_ONCE,
            willFire = true,
            isNextForAlarm = true,
            isOneTimeOverrideEligible = false,
            isCancelOverrideEligible = true
        )

        composeTestRule.setContent {
            UpcomingOccurrenceCard(
                occurrence = outcome,
                is24Hour = false,
                isSubmitting = false,
                onOpenOverride = { openOverrideClicked = true },
                onSkipOnce = { skipOnceClicked = true },
                onCancelOverride = { cancelOverrideClicked = true },
                onEditAlarm = {}
            )
        }

        val cancelText = context.getString(R.string.upcoming_timeline_action_cancel_override)
        composeTestRule.onNodeWithText(cancelText)
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()

        assertTrue("Cancel Override click must dispatch callback", cancelOverrideClicked)
        assertFalse("Open Override must not be clicked", openOverrideClicked)
        assertFalse("Skip Once must not be clicked", skipOnceClicked)

        // Change Time Once and Skip Once action buttons must not exist
        val changeTimeText = context.getString(R.string.upcoming_timeline_action_change_time)
        val skipText = context.getString(R.string.upcoming_timeline_action_skip_once)
        composeTestRule.onNodeWithText(changeTimeText).assertDoesNotExist()
        composeTestRule.onNodeWithText(skipText).assertDoesNotExist()
    }

    // 2. Active Skip Once exposes Cancel Override button and dispatches click
    @Test
    fun activeSkipOnce_rendersCancelOverride_andDispatchesClick() {
        var cancelOverrideClicked = false

        val outcome = createOutcome(
            reason = UpcomingOccurrenceReason.SKIP_ONCE,
            willFire = false,
            isNextForAlarm = false,
            isOneTimeOverrideEligible = false,
            isCancelOverrideEligible = true
        )

        composeTestRule.setContent {
            UpcomingOccurrenceCard(
                occurrence = outcome,
                is24Hour = false,
                isSubmitting = false,
                onOpenOverride = {},
                onSkipOnce = {},
                onCancelOverride = { cancelOverrideClicked = true },
                onEditAlarm = {}
            )
        }

        val cancelText = context.getString(R.string.upcoming_timeline_action_cancel_override)
        composeTestRule.onNodeWithText(cancelText)
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()

        assertTrue("Cancel Override on active skip must dispatch callback", cancelOverrideClicked)

        // Verify Skip Once badge is also displayed on the card
        val skipBadgeText = context.getString(R.string.upcoming_timeline_badge_skip_once)
        composeTestRule.onNodeWithText(skipBadgeText).assertIsDisplayed()
    }

    // 3. Normal Repeating Next occurrence renders Change Time and Skip Once buttons
    @Test
    fun repeatingNextOccurrence_rendersChangeTimeAndSkipOnce_andDispatchesClicks() {
        var openOverrideClicked = false
        var skipOnceClicked = false

        val outcome = createOutcome(
            reason = UpcomingOccurrenceReason.BASE_SCHEDULE,
            willFire = true,
            isNextForAlarm = true,
            isOneTimeOverrideEligible = true,
            isCancelOverrideEligible = false
        )

        composeTestRule.setContent {
            UpcomingOccurrenceCard(
                occurrence = outcome,
                is24Hour = false,
                isSubmitting = false,
                onOpenOverride = { openOverrideClicked = true },
                onSkipOnce = { skipOnceClicked = true },
                onCancelOverride = {},
                onEditAlarm = {}
            )
        }

        val changeTimeText = context.getString(R.string.upcoming_timeline_action_change_time)
        val skipText = context.getString(R.string.upcoming_timeline_action_skip_once)
        val cancelText = context.getString(R.string.upcoming_timeline_action_cancel_override)

        composeTestRule.onNodeWithText(changeTimeText)
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        assertTrue("Change Time click must dispatch callback", openOverrideClicked)

        composeTestRule.onNodeWithText(skipText)
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        assertTrue("Skip Once click must dispatch callback", skipOnceClicked)

        composeTestRule.onNodeWithText(cancelText).assertDoesNotExist()
    }

    // 4. Normal ONE_TIME next occurrence renders Edit Alarm button
    @Test
    fun oneTimeNextOccurrence_rendersEditAlarm_andDispatchesClick() {
        var editAlarmClicked = false

        val outcome = createOutcome(
            reason = UpcomingOccurrenceReason.BASE_SCHEDULE,
            willFire = true,
            isNextForAlarm = true,
            isOneTimeOverrideEligible = false,
            isCancelOverrideEligible = false
        )

        composeTestRule.setContent {
            UpcomingOccurrenceCard(
                occurrence = outcome,
                is24Hour = false,
                isSubmitting = false,
                onOpenOverride = {},
                onSkipOnce = {},
                onCancelOverride = {},
                onEditAlarm = { editAlarmClicked = true }
            )
        }

        val editText = context.getString(R.string.upcoming_timeline_action_edit)
        val cancelText = context.getString(R.string.upcoming_timeline_action_cancel_override)
        val changeTimeText = context.getString(R.string.upcoming_timeline_action_change_time)

        composeTestRule.onNodeWithText(editText)
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()

        assertTrue("Edit Alarm click must dispatch callback", editAlarmClicked)
        composeTestRule.onNodeWithText(cancelText).assertDoesNotExist()
        composeTestRule.onNodeWithText(changeTimeText).assertDoesNotExist()
    }

    // 5. Farther-future occurrence renders read-only label and no action buttons
    @Test
    fun fartherFutureOccurrence_rendersReadOnlyLabel() {
        val outcome = createOutcome(
            reason = UpcomingOccurrenceReason.BASE_SCHEDULE,
            willFire = true,
            isNextForAlarm = false,
            isOneTimeOverrideEligible = false,
            isCancelOverrideEligible = false
        )

        composeTestRule.setContent {
            UpcomingOccurrenceCard(
                occurrence = outcome,
                is24Hour = false,
                isSubmitting = false,
                onOpenOverride = {},
                onSkipOnce = {},
                onCancelOverride = {},
                onEditAlarm = {}
            )
        }

        val readonlyText = context.getString(R.string.upcoming_timeline_future_readonly)
        composeTestRule.onNodeWithText(readonlyText).assertIsDisplayed()

        val cancelText = context.getString(R.string.upcoming_timeline_action_cancel_override)
        val changeTimeText = context.getString(R.string.upcoming_timeline_action_change_time)
        val skipText = context.getString(R.string.upcoming_timeline_action_skip_once)
        composeTestRule.onNodeWithText(cancelText).assertDoesNotExist()
        composeTestRule.onNodeWithText(changeTimeText).assertDoesNotExist()
        composeTestRule.onNodeWithText(skipText).assertDoesNotExist()
    }

    // 6. Submitting state disables buttons
    @Test
    fun submittingState_disablesButtons() {
        val outcome = createOutcome(
            reason = UpcomingOccurrenceReason.BASE_SCHEDULE,
            willFire = true,
            isNextForAlarm = true,
            isOneTimeOverrideEligible = true,
            isCancelOverrideEligible = false
        )

        composeTestRule.setContent {
            UpcomingOccurrenceCard(
                occurrence = outcome,
                is24Hour = false,
                isSubmitting = true,
                onOpenOverride = {},
                onSkipOnce = {},
                onCancelOverride = {},
                onEditAlarm = {}
            )
        }

        val changeTimeText = context.getString(R.string.upcoming_timeline_action_change_time)
        val skipText = context.getString(R.string.upcoming_timeline_action_skip_once)

        composeTestRule.onNodeWithText(changeTimeText).assertIsNotEnabled()
        composeTestRule.onNodeWithText(skipText).assertIsNotEnabled()
    }

    // 7. Holiday Exclude Skip renders holiday skip badge and read-only text, no Cancel Override
    @Test
    fun holidayExcludeSkip_rendersBadgeAndReadOnly_noCancelOverride() {
        val outcome = createOutcome(
            reason = UpcomingOccurrenceReason.SKIPPED_BY_HOLIDAY,
            willFire = false,
            isNextForAlarm = false,
            isOneTimeOverrideEligible = false,
            isCancelOverrideEligible = false,
            policy = HolidayPolicy.EXCLUDE,
            holidayState = HolidayState.IS_HOLIDAY
        )

        composeTestRule.setContent {
            UpcomingOccurrenceCard(
                occurrence = outcome,
                is24Hour = false,
                isSubmitting = false,
                onOpenOverride = {},
                onSkipOnce = {},
                onCancelOverride = {},
                onEditAlarm = {}
            )
        }

        val badgeText = context.getString(R.string.upcoming_timeline_badge_holiday_skip)
        val readonlyText = context.getString(R.string.upcoming_timeline_future_readonly)
        val cancelText = context.getString(R.string.upcoming_timeline_action_cancel_override)

        composeTestRule.onNodeWithText(badgeText).assertIsDisplayed()
        composeTestRule.onNodeWithText(readonlyText).assertIsDisplayed()
        composeTestRule.onNodeWithText(cancelText).assertDoesNotExist()
    }

    // 8. Submitting state disables Cancel Override button when active override exists
    @Test
    fun submittingState_disablesCancelOverrideButton() {
        val outcome = createOutcome(
            reason = UpcomingOccurrenceReason.CHANGE_TIME_ONCE,
            willFire = true,
            isNextForAlarm = true,
            isOneTimeOverrideEligible = false,
            isCancelOverrideEligible = true
        )

        composeTestRule.setContent {
            UpcomingOccurrenceCard(
                occurrence = outcome,
                is24Hour = false,
                isSubmitting = true,
                onOpenOverride = {},
                onSkipOnce = {},
                onCancelOverride = {},
                onEditAlarm = {}
            )
        }

        val cancelText = context.getString(R.string.upcoming_timeline_action_cancel_override)
        composeTestRule.onNodeWithText(cancelText).assertIsNotEnabled()
    }
}
