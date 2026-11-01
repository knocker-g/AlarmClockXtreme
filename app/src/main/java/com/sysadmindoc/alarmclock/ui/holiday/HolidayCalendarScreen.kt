package com.sysadmindoc.alarmclock.ui.holiday

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sysadmindoc.alarmclock.R
import com.sysadmindoc.alarmclock.data.repository.HolidayClassificationKind
import com.sysadmindoc.alarmclock.data.repository.ManualHolidayOverride
import com.sysadmindoc.alarmclock.ui.theme.AccentRed
import com.sysadmindoc.alarmclock.ui.theme.SurfaceDark
import com.sysadmindoc.alarmclock.ui.theme.TextMuted
import com.sysadmindoc.alarmclock.ui.theme.TextPrimary
import com.sysadmindoc.alarmclock.ui.theme.TextSecondary
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HolidayCalendarScreen(
    onNavigateBack: () -> Unit,
    viewModel: HolidayCalendarViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedDayForAction by remember { mutableStateOf<HolidayDayUiModel?>(null) }

    LaunchedEffect(state.statusMessage) {
        state.statusMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearStatusMessage()
        }
    }

    Scaffold(
        containerColor = SurfaceDark,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.holiday_calendar_title),
                            style = MaterialTheme.typography.titleLarge,
                            color = TextPrimary
                        )
                        if (state.countryCode.isNotBlank()) {
                            Text(
                                text = stringResource(R.string.holiday_calendar_country, state.countryCode),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.holiday_calendar_back),
                            tint = TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            // Corruption Banner
            if (state.isCorrupted) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
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
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.holiday_calendar_corrupted_banner),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        TextButton(onClick = viewModel::openResetConfirmation) {
                            Text(
                                text = stringResource(R.string.holiday_calendar_reset_action),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            // Month Navigation Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = viewModel::previousMonth) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.holiday_calendar_prev_month),
                        tint = TextPrimary
                    )
                }
                Text(
                    text = "${state.displayedMonth.year} / ${state.displayedMonth.monthValue}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                IconButton(onClick = viewModel::nextMonth) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = stringResource(R.string.holiday_calendar_next_month),
                        tint = TextPrimary
                    )
                }
            }

            // Day of Week Header
            val locale = LocalConfiguration.current.locales[0]
            Row(modifier = Modifier.fillMaxWidth()) {
                val daysOfWeek = listOf(
                    DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY,
                    DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY
                )
                for (dow in daysOfWeek) {
                    val name = dow.getDisplayName(TextStyle.SHORT, locale)
                    val isWeekend = dow == DayOfWeek.SUNDAY || dow == DayOfWeek.SATURDAY
                    Text(
                        text = name,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isWeekend) AccentRed else TextMuted
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            // Month Grid
            val firstDayOfWeek = state.displayedMonth.atDay(1).dayOfWeek
            // Sunday is index 0
            val leadingEmptyDays = (firstDayOfWeek.value % 7)

            LazyVerticalGrid(
                columns = GridCells.Fixed(7),
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(leadingEmptyDays) {
                    Spacer(Modifier.height(54.dp))
                }
                items(state.days) { day ->
                    DayCell(
                        day = day,
                        onClick = { selectedDayForAction = day }
                    )
                }
            }
        }
    }

    // Action Dialog for Single Day
    selectedDayForAction?.let { day ->
        AlertDialog(
            onDismissRequest = { selectedDayForAction = null },
            title = { Text(text = "${day.date}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            viewModel.setDateOverride(day.date, ManualHolidayOverride.FORCE_HOLIDAY)
                            selectedDayForAction = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.holiday_calendar_override_force_holiday))
                    }
                    TextButton(
                        onClick = {
                            viewModel.setDateOverride(day.date, ManualHolidayOverride.FORCE_WORKDAY)
                            selectedDayForAction = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.holiday_calendar_override_force_workday))
                    }
                    TextButton(
                        onClick = {
                            viewModel.setDateOverride(day.date, ManualHolidayOverride.AUTO)
                            selectedDayForAction = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.holiday_calendar_override_auto))
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { selectedDayForAction = null }) {
                    Text(stringResource(R.string.holiday_calendar_cancel))
                }
            }
        )
    }

    // Reset Confirmation Dialog
    if (state.showResetConfirmationDialog) {
        AlertDialog(
            onDismissRequest = viewModel::dismissResetConfirmation,
            title = { Text(stringResource(R.string.holiday_calendar_reset_dialog_title)) },
            text = { Text(stringResource(R.string.holiday_calendar_reset_dialog_desc)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmReset) {
                    Text(
                        stringResource(R.string.holiday_calendar_reset_confirm),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissResetConfirmation) {
                    Text(stringResource(R.string.holiday_calendar_cancel))
                }
            }
        )
    }

    // Range Conflict Confirmation Dialog
    state.pendingRangeConflict?.let { conflict ->
        AlertDialog(
            onDismissRequest = viewModel::dismissConflictDialog,
            title = { Text(stringResource(R.string.holiday_calendar_range_conflict_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.holiday_calendar_range_conflict_desc))
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.holiday_calendar_conflicts_prefix, conflict.conflictingDates.joinToString(", ")),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmConflictOverwrite) {
                    Text(
                        stringResource(R.string.holiday_calendar_range_overwrite_confirm),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissConflictDialog) {
                    Text(stringResource(R.string.holiday_calendar_cancel))
                }
            }
        )
    }
}

@Composable
private fun DayCell(
    day: HolidayDayUiModel,
    onClick: () -> Unit
) {
    val borderColor = if (day.isToday) MaterialTheme.colorScheme.primary else Color.Transparent
    val backgroundColor = when (day.manualOverride) {
        ManualHolidayOverride.FORCE_HOLIDAY -> AccentRed.copy(alpha = 0.25f)
        ManualHolidayOverride.FORCE_WORKDAY -> Color.Blue.copy(alpha = 0.15f)
        ManualHolidayOverride.AUTO -> {
            if (day.isHoliday) AccentRed.copy(alpha = 0.12f)
            else Color.Transparent
        }
    }

    Column(
        modifier = Modifier
            .height(54.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = day.date.dayOfMonth.toString(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (day.isToday) FontWeight.Bold else FontWeight.Normal,
            color = if (day.isHoliday) AccentRed else TextPrimary
        )

        val statusText = when (day.manualOverride) {
            ManualHolidayOverride.FORCE_HOLIDAY -> "FORCE"
            ManualHolidayOverride.FORCE_WORKDAY -> "WORK"
            ManualHolidayOverride.AUTO -> {
                when (day.resolvedClassification) {
                    HolidayClassificationKind.HOLIDAY -> "HOL"
                    HolidayClassificationKind.UNKNOWN -> "?"
                    else -> ""
                }
            }
        }

        if (statusText.isNotBlank()) {
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = when (day.manualOverride) {
                    ManualHolidayOverride.FORCE_HOLIDAY -> AccentRed
                    ManualHolidayOverride.FORCE_WORKDAY -> Color.Cyan
                    ManualHolidayOverride.AUTO -> if (day.isHoliday) AccentRed else TextMuted
                }
            )
        } else {
            Spacer(Modifier.height(10.dp))
        }
    }
}
