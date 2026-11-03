package com.sysadmindoc.alarmclock.ui.upcoming

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.BeachAccess
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sysadmindoc.alarmclock.R
import com.sysadmindoc.alarmclock.domain.projection.UpcomingOccurrenceOutcome
import com.sysadmindoc.alarmclock.domain.projection.UpcomingOccurrenceReason
import com.sysadmindoc.alarmclock.domain.projection.UpcomingProjectionFreshness
import com.sysadmindoc.alarmclock.ui.alarmlist.components.OneTimeOverrideSheet
import com.sysadmindoc.alarmclock.ui.components.AppSurfaceCard
import com.sysadmindoc.alarmclock.ui.theme.AccentRed
import com.sysadmindoc.alarmclock.ui.theme.SnoozeYellow
import com.sysadmindoc.alarmclock.ui.theme.SurfaceDark
import com.sysadmindoc.alarmclock.ui.theme.SurfaceMedium
import com.sysadmindoc.alarmclock.ui.theme.TextMuted
import com.sysadmindoc.alarmclock.ui.theme.TextPrimary
import com.sysadmindoc.alarmclock.ui.theme.TextSecondary
import com.sysadmindoc.alarmclock.util.AlarmTimeFormatter
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpcomingTimelineScreen(
    onNavigateBack: () -> Unit,
    onEditAlarm: (Long) -> Unit,
    viewModel: UpcomingTimelineViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.userFeedback) {
        state.userFeedback?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearFeedback()
        }
    }

    Scaffold(
        containerColor = SurfaceDark,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.upcoming_timeline_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.upcoming_timeline_back),
                            tint = TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                state.isLoading && state.projection == null -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp)
                        )
                    }
                }

                state.projection != null && state.projection!!.dayGroups.isEmpty() -> {
                    UpcomingEmptyView(
                        noCurrentOccurrenceCount = state.projection!!.noCurrentOccurrenceAlarms.size,
                        disabledAlarmCount = state.projection!!.disabledAlarms.size,
                        freshness = state.projection!!.projectionFreshness
                    )
                }

                state.projection != null -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Truthful Freshness / Transition banner
                        item(key = "status_banner") {
                            UpcomingStatusBanner(freshness = state.projection!!.projectionFreshness)
                        }

                        // Date-grouped chronological timeline items
                        state.projection!!.dayGroups.forEach { dayGroup ->
                            item(key = "header_${dayGroup.date}") {
                                UpcomingDayHeader(
                                    date = dayGroup.date,
                                    count = dayGroup.entries.size
                                )
                            }

                            items(
                                items = dayGroup.entries,
                                key = { occurrence ->
                                    occurrence.occurrenceId?.key
                                        ?: "${occurrence.alarmId}_${occurrence.targetTriggerTimeEpochMs}_${occurrence.reason}"
                                }
                            ) { occurrence ->
                                UpcomingOccurrenceCard(
                                    occurrence = occurrence,
                                    is24Hour = state.is24Hour,
                                    isSubmitting = state.isSubmitting,
                                    onOpenOverride = { viewModel.openOneTimeOverride(occurrence) },
                                    onSkipOnce = { viewModel.applySkipOnce(occurrence) },
                                    onCancelOverride = { viewModel.cancelOneTimeOverride(occurrence) },
                                    onEditAlarm = { onEditAlarm(occurrence.alarmId) }
                                )
                            }
                        }

                        // Summary of no-current-occurrence and disabled alarms at bottom of list
                        if (state.projection!!.noCurrentOccurrenceAlarms.isNotEmpty() ||
                            state.projection!!.disabledAlarms.isNotEmpty()
                        ) {
                            item(key = "footer_summary") {
                                UpcomingSummaryFooter(
                                    noCurrentOccurrenceCount = state.projection!!.noCurrentOccurrenceAlarms.size,
                                    disabledCount = state.projection!!.disabledAlarms.size
                                )
                            }
                        }
                    }
                }
            }

            // Canonical OneTimeOverride Bottom Sheet
            state.overrideTarget?.let { target ->
                OneTimeOverrideSheet(
                    target = target,
                    is24Hour = state.is24Hour,
                    onTimeChange = { h, m -> viewModel.updateOverrideSelectedTime(h, m) },
                    onApplyTime = { h, m -> viewModel.applyChangeTimeOnce(h, m) },
                    onSkipOnce = { viewModel.applySkipOnce() },
                    onCancelOverride = { viewModel.cancelOneTimeOverride() },
                    onDismiss = { viewModel.closeOneTimeOverride() }
                )
            }
        }
    }
}

@Composable
fun UpcomingStatusBanner(freshness: UpcomingProjectionFreshness) {
    when (freshness) {
        UpcomingProjectionFreshness.RECONCILING -> {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.upcoming_timeline_reconciling),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        UpcomingProjectionFreshness.STALE -> {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                shape = RoundedCornerShape(12.dp),
                color = SnoozeYellow.copy(alpha = 0.2f)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = SnoozeYellow,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.upcoming_timeline_stale),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary
                    )
                }
            }
        }

        UpcomingProjectionFreshness.NEEDS_REPAIR -> {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.errorContainer
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.upcoming_timeline_needs_repair),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        else -> {
            // Fresh settled truth requires no transition banner
        }
    }
}

@Composable
fun UpcomingDayHeader(date: LocalDate, count: Int) {
    val today = remember { LocalDate.now(ZoneId.systemDefault()) }
    val formattedDate = remember(date, today) {
        when (date) {
            today -> "Today · " + date.format(DateTimeFormatter.ofPattern("EEEE, MMM d"))
            today.plusDays(1) -> "Tomorrow · " + date.format(DateTimeFormatter.ofPattern("EEEE, MMM d"))
            else -> date.format(DateTimeFormatter.ofPattern("EEEE, MMM d"))
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 4.dp)
            .semantics { heading() },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = formattedDate,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = if (date == today) MaterialTheme.colorScheme.primary else TextPrimary
        )
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted
        )
    }
}

@Composable
fun UpcomingOccurrenceCard(
    occurrence: UpcomingOccurrenceOutcome,
    is24Hour: Boolean,
    isSubmitting: Boolean = false,
    onOpenOverride: () -> Unit,
    onSkipOnce: () -> Unit,
    onCancelOverride: () -> Unit,
    onEditAlarm: () -> Unit
) {
    val formattedTime = remember(occurrence.localDisplayTime, is24Hour) {
        AlarmTimeFormatter.format(
            occurrence.localDisplayTime.hour,
            occurrence.localDisplayTime.minute,
            is24Hour
        )
    }

    val isOverridden = occurrence.isOverridden
    val isSkipped = occurrence.isSkipped

    AppSurfaceCard(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = buildString {
                    append(occurrence.alarmLabel)
                    append(" at ")
                    append(formattedTime)
                    if (isSkipped) append(", skipped")
                    if (isOverridden) append(", one-time changed")
                    if (occurrence.isNextForAlarm) append(", next occurrence")
                }
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Top Row: Time & Provenance Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = formattedTime,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isSkipped) TextMuted else TextPrimary,
                        textDecoration = if (isSkipped) TextDecoration.LineThrough else TextDecoration.None
                    )
                    if (occurrence.isNextForAlarm) {
                        Spacer(Modifier.width(8.dp))
                        UpcomingBadge(
                            text = stringResource(R.string.upcoming_timeline_badge_next_ring),
                            color = MaterialTheme.colorScheme.primary,
                            icon = Icons.Default.Alarm
                        )
                    }
                }

                // Provenance badge
                when (occurrence.reason) {
                    UpcomingOccurrenceReason.CHANGE_TIME_ONCE -> {
                        UpcomingBadge(
                            text = stringResource(R.string.upcoming_timeline_badge_change_time),
                            color = SnoozeYellow,
                            icon = Icons.Default.EditCalendar
                        )
                    }
                    UpcomingOccurrenceReason.SKIP_ONCE -> {
                        UpcomingBadge(
                            text = stringResource(R.string.upcoming_timeline_badge_skip_once),
                            color = AccentRed,
                            icon = Icons.Default.SkipNext
                        )
                    }
                    UpcomingOccurrenceReason.SKIPPED_BY_HOLIDAY -> {
                        UpcomingBadge(
                            text = stringResource(R.string.upcoming_timeline_badge_holiday_skip),
                            color = TextMuted,
                            icon = Icons.Default.BeachAccess
                        )
                    }
                    UpcomingOccurrenceReason.HOLIDAY_INCLUDE -> {
                        UpcomingBadge(
                            text = stringResource(R.string.upcoming_timeline_badge_holiday_include),
                            color = Color(0xFF4CAF50),
                            icon = Icons.Default.Celebration
                        )
                    }
                    else -> {
                        // Regular base schedule
                    }
                }
            }

            // Middle Row: Alarm Label and Base-Time Context
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = occurrence.alarmLabel,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )

                if (isOverridden && occurrence.baseTriggerTimeEpochMs > 0L) {
                    val baseInstant = java.time.Instant.ofEpochMilli(occurrence.baseTriggerTimeEpochMs)
                    val baseTime = baseInstant.atZone(ZoneId.systemDefault()).toLocalTime()
                    val baseFormatted = AlarmTimeFormatter.format(baseTime.hour, baseTime.minute, is24Hour)
                    Text(
                        text = stringResource(R.string.upcoming_timeline_base_time_format, baseFormatted),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }

            // Bottom Row: Canonical Actions
            when {
                // Active override on this occurrence: provide Cancel Override entrypoint
                occurrence.isCancelOverrideEligible -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onCancelOverride,
                            enabled = !isSubmitting,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Restore,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.upcoming_timeline_action_cancel_override))
                        }
                    }
                }

                // Next repeating occurrence: eligible for Skip Once / Change Time Once
                occurrence.isOneTimeOverrideEligible -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onOpenOverride,
                            enabled = !isSubmitting,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.EditCalendar,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.upcoming_timeline_action_change_time))
                        }

                        OutlinedButton(
                            onClick = onSkipOnce,
                            enabled = !isSubmitting,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.SkipNext,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.upcoming_timeline_action_skip_once))
                        }
                    }
                }

                // Normal ONE_TIME next occurrence: routes to Alarm Edit
                occurrence.isNextForAlarm && !occurrence.isOneTimeOverrideEligible -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        OutlinedButton(
                            onClick = onEditAlarm,
                            enabled = !isSubmitting
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.upcoming_timeline_action_edit))
                        }
                    }
                }

                // Farther-future or non-eligible occurrences: read-only presentation (Section 4)
                else -> {
                    Text(
                        text = stringResource(R.string.upcoming_timeline_future_readonly),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun UpcomingBadge(text: String, color: Color, icon: ImageVector? = null) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.16f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(12.dp)
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = color,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun UpcomingEmptyView(
    noCurrentOccurrenceCount: Int,
    disabledAlarmCount: Int,
    freshness: UpcomingProjectionFreshness
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        UpcomingStatusBanner(freshness = freshness)

        Spacer(Modifier.height(32.dp))
        Icon(
            imageVector = Icons.Default.EventBusy,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = TextMuted
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.upcoming_timeline_empty_title),
            style = MaterialTheme.typography.titleLarge,
            color = TextPrimary,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.upcoming_timeline_empty_description),
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )

        if (noCurrentOccurrenceCount > 0 || disabledAlarmCount > 0) {
            Spacer(Modifier.height(24.dp))
            UpcomingSummaryFooter(
                noCurrentOccurrenceCount = noCurrentOccurrenceCount,
                disabledCount = disabledAlarmCount
            )
        }
    }
}

@Composable
fun UpcomingSummaryFooter(noCurrentOccurrenceCount: Int, disabledCount: Int) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = SurfaceMedium.copy(alpha = 0.5f)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (noCurrentOccurrenceCount > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.upcoming_timeline_no_current_alarms, noCurrentOccurrenceCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                }
            }
            if (disabledCount > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Alarm,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.upcoming_timeline_disabled_alarms, disabledCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                }
            }
        }
    }
}
