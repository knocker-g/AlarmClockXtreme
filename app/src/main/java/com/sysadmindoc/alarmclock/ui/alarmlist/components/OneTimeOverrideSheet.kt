package com.sysadmindoc.alarmclock.ui.alarmlist.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sysadmindoc.alarmclock.R
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleException
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.ui.components.AppSectionTitle
import com.sysadmindoc.alarmclock.ui.components.AppSurfaceCard
import com.sysadmindoc.alarmclock.ui.components.WheelTimePicker
import com.sysadmindoc.alarmclock.ui.theme.AccentRed
import com.sysadmindoc.alarmclock.ui.theme.SnoozeYellow
import com.sysadmindoc.alarmclock.ui.theme.TextMuted
import com.sysadmindoc.alarmclock.ui.theme.TextPrimary
import com.sysadmindoc.alarmclock.ui.theme.TextSecondary
import com.sysadmindoc.alarmclock.util.AlarmTimeFormatter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Captured occurrence identity and UI state for One-time Override (KNO-32).
 */
data class OneTimeOverrideTarget(
    val alarmId: Long,
    val expectedScheduledAt: Long,
    val expectedBaseTriggerTime: Long,
    val expectedGeneration: Int,
    val expectedRevision: Int,
    val alarmLabel: String,
    val baseHour: Int,
    val baseMinute: Int,
    val repeatSummary: String,
    val targetDate: LocalDate,
    val selectedHour: Int,
    val selectedMinute: Int,
    val activeException: ScheduleException? = null,
    val isSubmitting: Boolean = false
)

fun Alarm.isEligibleForOneTimeOverride(isActiveSession: Boolean): Boolean {
    return isEnabled &&
           !isQuickAlarm &&
           scheduleKind == Alarm.SCHEDULE_KIND_REPEATING &&
           isRecurringSchedule &&
           nextTriggerTime > 0L &&
           !isActiveSession
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OneTimeOverrideSheet(
    target: OneTimeOverrideTarget,
    is24Hour: Boolean,
    onTimeChange: (hour: Int, minute: Int) -> Unit,
    onApplyTime: (hour: Int, minute: Int) -> Unit,
    onSkipOnce: () -> Unit,
    onCancelOverride: () -> Unit,
    onDismiss: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val datePattern = stringResource(R.string.alarmlist_date_pattern)
    val nextFormat = stringResource(R.string.alarmlist_next_occurrence_format)

    var currentHour by remember(target.selectedHour) { mutableIntStateOf(target.selectedHour) }
    var currentMinute by remember(target.selectedMinute) { mutableIntStateOf(target.selectedMinute) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.EditCalendar,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Column {
                        Text(
                            text = stringResource(R.string.one_time_override_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        if (target.alarmLabel.isNotBlank()) {
                            Text(
                                text = target.alarmLabel,
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        }
                    }
                }
                IconButton(onClick = onDismiss, enabled = !target.isSubmitting) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.settings_close),
                        tint = TextPrimary
                    )
                }
            }

            // Schedule Info Card (Cover C: Base and effective time displayed separately)
            AppSurfaceCard(
                modifier = Modifier.fillMaxWidth(),
                highlighted = false
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // 1. Original Base Schedule
                    val baseTimeFormatted = AlarmTimeFormatter.format(
                        target.baseHour,
                        target.baseMinute,
                        is24Hour,
                        locale = locale
                    )
                    val baseScheduleText = if (target.repeatSummary.isNotBlank()) {
                        "${target.repeatSummary} $baseTimeFormatted"
                    } else {
                        baseTimeFormatted
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = stringResource(R.string.one_time_override_base_time_label, baseScheduleText),
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary
                        )
                    }

                    // 2. Effective Next Execution Date/Time
                    val nextDateFormatted = Instant.ofEpochMilli(target.expectedScheduledAt)
                        .atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern(datePattern, locale))
                    val nextTimeFormatted = AlarmTimeFormatter.format(
                        target.expectedScheduledAt,
                        is24Hour,
                        locale = locale
                    )
                    val effectiveOccurrenceFormatted = nextFormat.format(nextDateFormatted, nextTimeFormatted)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Alarm,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = stringResource(R.string.one_time_override_effective_time_label, effectiveOccurrenceFormatted),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                    }

                    // 3. Current Temporary Override State
                    val currentStatusText = when (target.activeException?.exceptionType) {
                        ScheduleException.TYPE_TIME_OVERRIDE -> {
                            val overrideTimeFormatted = AlarmTimeFormatter.format(
                                target.activeException.overrideTriggerTime,
                                is24Hour,
                                locale = locale
                            )
                            stringResource(R.string.one_time_override_badge_time, overrideTimeFormatted)
                        }
                        ScheduleException.TYPE_SKIP -> {
                            stringResource(R.string.one_time_override_badge_skipped)
                        }
                        else -> {
                            stringResource(R.string.one_time_override_status_none)
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = if (target.activeException != null) SnoozeYellow else TextMuted,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = stringResource(R.string.one_time_override_current_status_label, currentStatusText),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (target.activeException != null) SnoozeYellow else TextSecondary
                        )
                    }
                }
            }

            // Action 1: Change Time Once
            AppSectionTitle(title = stringResource(R.string.one_time_override_action_change_time))

            WheelTimePicker(
                initialHour = currentHour,
                initialMinute = currentMinute,
                is24Hour = is24Hour,
                onTimeChange = { h, m ->
                    currentHour = h
                    currentMinute = m
                    onTimeChange(h, m)
                }
            )

            Button(
                onClick = { onApplyTime(currentHour, currentMinute) },
                enabled = !target.isSubmitting,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (target.isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(stringResource(R.string.one_time_override_button_apply_time))
            }

            // Action 2: Skip Once
            OutlinedButton(
                onClick = onSkipOnce,
                enabled = !target.isSubmitting,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.one_time_override_action_skip))
            }

            // Action 3: Cancel Override (visible only when active override is present)
            if (target.activeException != null) {
                OutlinedButton(
                    onClick = onCancelOverride,
                    enabled = !target.isSubmitting,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentRed)
                ) {
                    Icon(
                        imageVector = Icons.Default.Restore,
                        contentDescription = null,
                        tint = AccentRed,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.one_time_override_action_cancel),
                        color = AccentRed
                    )
                }
            }
        }
    }
}
