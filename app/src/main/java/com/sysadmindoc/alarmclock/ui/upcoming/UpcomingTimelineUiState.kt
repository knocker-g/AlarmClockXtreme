package com.sysadmindoc.alarmclock.ui.upcoming

import com.sysadmindoc.alarmclock.domain.projection.UpcomingScheduleProjection
import com.sysadmindoc.alarmclock.ui.alarmlist.components.OneTimeOverrideTarget

/**
 * UI State for the Upcoming Timeline Screen (KNO-157 / KNO-34 Slice C).
 *
 * Encapsulates the immutable projection outcome from [UpcomingScheduleReconcileCoordinator],
 * active OneTimeOverride bottom sheet target, loading state, 12/24 hour display preferences,
 * and user-facing feedback messages.
 */
data class UpcomingTimelineUiState(
    val projection: UpcomingScheduleProjection? = null,
    val isLoading: Boolean = true,
    val is24Hour: Boolean = false,
    val overrideTarget: OneTimeOverrideTarget? = null,
    val isSubmitting: Boolean = false,
    val userFeedback: String? = null
)
