package com.sysadmindoc.alarmclock.ui.quickalarm

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sysadmindoc.alarmclock.R
import com.sysadmindoc.alarmclock.ui.components.AppFilterChip
import com.sysadmindoc.alarmclock.ui.components.AppStatusChip
import com.sysadmindoc.alarmclock.ui.components.WheelTimePicker
import com.sysadmindoc.alarmclock.ui.theme.SurfaceMedium
import com.sysadmindoc.alarmclock.ui.theme.TextPrimary
import com.sysadmindoc.alarmclock.ui.theme.TextSecondary
import java.time.Instant
import java.time.ZoneId

import androidx.compose.runtime.rememberCoroutineScope
import com.sysadmindoc.alarmclock.domain.QuickCreationResult
import kotlinx.coroutines.launch

import androidx.compose.ui.platform.LocalContext
import com.sysadmindoc.alarmclock.domain.QuickTimeResolver

enum class QuickAlarmInputMode {
    RELATIVE,
    ABSOLUTE
}

/**
 * Dedicated Quick Alarm creation surface supporting RELATIVE-first and ABSOLUTE alternate modes.
 */
@Composable
fun QuickAlarmCreateDialog(
    initialMinutes: Int = 15,
    is24HourFormat: Boolean,
    onDismissRequest: () -> Unit,
    onCreateRelative: suspend (minutes: Int, retryAlarmId: Long?) -> QuickCreationResult,
    onCreateAbsolute: suspend (hour: Int, minute: Int, retryAlarmId: Long?) -> QuickCreationResult
) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(QuickAlarmInputMode.RELATIVE) }

    // Fresh trigger state for each creation instance
    var relativeMinutesInput by remember { mutableStateOf(initialMinutes.coerceIn(1, 180).toString()) }

    val nowLocal = remember {
        Instant.ofEpochMilli(System.currentTimeMillis()).atZone(ZoneId.systemDefault())
    }
    var selectedAbsoluteHour by remember { mutableIntStateOf(nowLocal.hour) }
    var selectedAbsoluteMinute by remember { mutableIntStateOf(nowLocal.minute) }

    val parsedRelativeMinutes = relativeMinutesInput.toIntOrNull()
    val isRelativeValid = parsedRelativeMinutes != null && parsedRelativeMinutes in 1..180

    var isSubmitting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var lastPersistedAlarmId by remember { mutableStateOf<Long?>(null) }
    val scope = rememberCoroutineScope()

    // Reset retry ID on user input modification
    LaunchedEffect(mode, relativeMinutesInput, selectedAbsoluteHour, selectedAbsoluteMinute) {
        lastPersistedAlarmId = null
    }

    AlertDialog(
        onDismissRequest = {
            if (!isSubmitting) onDismissRequest()
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (!isSubmitting) {
                        isSubmitting = true
                        scope.launch {
                            val result = if (mode == QuickAlarmInputMode.RELATIVE) {
                                parsedRelativeMinutes?.let { onCreateRelative(it, lastPersistedAlarmId) }
                                    ?: QuickCreationResult.ValidationRejected
                            } else {
                                onCreateAbsolute(selectedAbsoluteHour, selectedAbsoluteMinute, lastPersistedAlarmId)
                            }

                            when (result) {
                                is QuickCreationResult.CreatedAndArmed -> {
                                    onDismissRequest()
                                }
                                is QuickCreationResult.PermissionBlocked -> {
                                    lastPersistedAlarmId = result.alarmId
                                    errorMessage = context.getString(R.string.quick_alarm_permission_required)
                                    isSubmitting = false
                                }
                                is QuickCreationResult.RegistrationFailed -> {
                                    lastPersistedAlarmId = result.alarmId
                                    errorMessage = context.getString(R.string.quick_alarm_schedule_failed)
                                    isSubmitting = false
                                }
                                is QuickCreationResult.ValidationRejected -> {
                                    errorMessage = context.getString(R.string.quick_alarm_invalid_duration)
                                    isSubmitting = false
                                }
                            }
                        }
                    }
                },
                enabled = (!isSubmitting) && (mode == QuickAlarmInputMode.ABSOLUTE || isRelativeValid)
            ) {
                Text(
                    stringResource(R.string.alarm_edit_save_time),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    if (!isSubmitting) onDismissRequest()
                },
                enabled = !isSubmitting
            ) {
                Text(stringResource(R.string.alarm_edit_keep_current), color = TextSecondary)
            }
        },
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AppStatusChip(
                    label = stringResource(R.string.alarm_list_quick_alarms),
                    icon = Icons.Default.Schedule,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    stringResource(R.string.alarm_list_quick_alarms),
                    color = TextPrimary,
                    style = MaterialTheme.typography.titleLarge
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Mode Selector Segmented Chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AppFilterChip(
                        label = stringResource(R.string.quick_alarm_mode_relative),
                        selected = mode == QuickAlarmInputMode.RELATIVE,
                        onClick = { mode = QuickAlarmInputMode.RELATIVE },
                        modifier = Modifier.weight(1f)
                    )
                    AppFilterChip(
                        label = stringResource(R.string.quick_alarm_mode_absolute),
                        selected = mode == QuickAlarmInputMode.ABSOLUTE,
                        onClick = { mode = QuickAlarmInputMode.ABSOLUTE },
                        modifier = Modifier.weight(1f)
                    )
                }

                if (mode == QuickAlarmInputMode.RELATIVE) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = relativeMinutesInput,
                            onValueChange = { newValue ->
                                if (newValue.length <= 3 && newValue.all { it.isDigit() }) {
                                    relativeMinutesInput = newValue
                                }
                            },
                            label = { Text(stringResource(R.string.quick_alarm_minutes_label)) },
                            singleLine = true,
                            isError = !isRelativeValid && relativeMinutesInput.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth()
                        )

                        // Representative Relative Shortcut Chips (Seed input without creating)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(
                                10 to stringResource(R.string.alarm_list_quick_10min),
                                30 to stringResource(R.string.alarm_list_quick_30min),
                                60 to stringResource(R.string.alarm_list_quick_1h),
                                120 to stringResource(R.string.alarm_list_quick_2h)
                            ).forEach { (mins, label) ->
                                AppFilterChip(
                                    label = label,
                                    selected = parsedRelativeMinutes == mins,
                                    onClick = { relativeMinutesInput = mins.toString() }
                                )
                            }
                        }

                        if (!isRelativeValid && relativeMinutesInput.isNotEmpty()) {
                            Text(
                                stringResource(R.string.quick_alarm_duration_error),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Reuse KNO-19 WheelTimePicker
                        WheelTimePicker(
                            initialHour = selectedAbsoluteHour,
                            initialMinute = selectedAbsoluteMinute,
                            is24Hour = is24HourFormat,
                            onTimeChange = { h, m ->
                                selectedAbsoluteHour = h
                                selectedAbsoluteMinute = m
                            }
                        )

                        // Preview next HH:mm occurrence
                        val todayLabel = stringResource(R.string.quick_alarm_today)
                        val tomorrowLabel = stringResource(R.string.quick_alarm_tomorrow)
                        val previewFormat = stringResource(R.string.quick_alarm_preview_format)
                        val previewText = remember(selectedAbsoluteHour, selectedAbsoluteMinute, todayLabel, tomorrowLabel, previewFormat) {
                            val resolvedMs: Long? = QuickTimeResolver.resolveAbsolute(
                                selectedAbsoluteHour, selectedAbsoluteMinute, System.currentTimeMillis(), ZoneId.systemDefault()
                            )
                            if (resolvedMs == null) {
                                ""
                            } else {
                                val target = Instant.ofEpochMilli(resolvedMs).atZone(ZoneId.systemDefault())
                                val isTomorrow = target.dayOfYear != nowLocal.dayOfYear
                                val dayLabel = if (isTomorrow) tomorrowLabel else todayLabel
                                val diffMins = (resolvedMs - System.currentTimeMillis()) / 60_000L
                                previewFormat.format(dayLabel, target.hour as Any, target.minute as Any, diffMins as Any)
                            }
                        }

                        Text(
                            text = previewText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }

                if (errorMessage != null) {
                    Text(
                        text = errorMessage!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        },
        containerColor = SurfaceMedium,
        shape = RoundedCornerShape(12.dp)
    )
}
