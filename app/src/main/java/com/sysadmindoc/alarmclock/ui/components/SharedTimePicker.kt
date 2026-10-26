package com.sysadmindoc.alarmclock.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sysadmindoc.alarmclock.ui.theme.SurfaceCard
import com.sysadmindoc.alarmclock.ui.theme.SurfaceMedium
import com.sysadmindoc.alarmclock.ui.theme.TextPrimary
import com.sysadmindoc.alarmclock.ui.theme.TextSecondary

/**
 * Pure value contract for time normalization and 12h/24h conversion.
 */
data class TimePickerValue(val hour: Int, val minute: Int) {
    init {
        require(hour in 0..23) { "Hour must be in 0..23" }
        require(minute in 0..59) { "Minute must be in 0..59" }
    }

    fun displayHour(is24Hour: Boolean): Int = when {
        is24Hour -> hour
        hour % 12 == 0 -> 12
        else -> hour % 12
    }

    val isPm: Boolean get() = hour >= 12

    companion object {
        fun from12Hour(displayHour: Int, minute: Int, isPm: Boolean): TimePickerValue {
            val h = displayHour.coerceIn(1, 12)
            val m = minute.coerceIn(0, 59)
            val hour24 = (h % 12) + if (isPm) 12 else 0
            return TimePickerValue(hour24, m)
        }
    }
}

/**
 * Reusable wheel/drum time picker for 12h/24h absolute time selection.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WheelTimePicker(
    initialHour: Int,
    initialMinute: Int,
    is24Hour: Boolean,
    modifier: Modifier = Modifier,
    onTimeChange: (hour: Int, minute: Int) -> Unit
) {
    val initialValue = remember(initialHour, initialMinute) {
        TimePickerValue(initialHour.coerceIn(0, 23), initialMinute.coerceIn(0, 59))
    }

    val hoursList = remember(is24Hour) {
        if (is24Hour) (0..23).toList() else (1..12).toList()
    }
    val minutesList = remember { (0..59).toList() }

    var selectedDisplayHour by remember(initialValue, is24Hour) {
        mutableIntStateOf(initialValue.displayHour(is24Hour))
    }
    var selectedMinute by remember(initialValue) {
        mutableIntStateOf(initialValue.minute)
    }
    var selectedIsPm by remember(initialValue) {
        mutableStateOf(initialValue.isPm)
    }

    // Trigger time change callback on state update
    LaunchedEffect(selectedDisplayHour, selectedMinute, selectedIsPm, is24Hour) {
        val value = if (is24Hour) {
            TimePickerValue(selectedDisplayHour.coerceIn(0, 23), selectedMinute.coerceIn(0, 59))
        } else {
            TimePickerValue.from12Hour(selectedDisplayHour, selectedMinute, selectedIsPm)
        }
        onTimeChange(value.hour, value.minute)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp)
            .background(SurfaceMedium, RoundedCornerShape(12.dp))
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        // Center selection bar overlay
        Box(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .height(44.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Hours Wheel
            WheelColumn(
                items = hoursList,
                initialItem = selectedDisplayHour,
                formatItem = { if (is24Hour) "%02d".format(it) else "%d".format(it) },
                onItemSelected = { selectedDisplayHour = it }
            )

            Text(
                ":",
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 28.sp, fontWeight = FontWeight.Bold),
                color = TextPrimary,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            // Minutes Wheel
            WheelColumn(
                items = minutesList,
                initialItem = selectedMinute,
                formatItem = { "%02d".format(it) },
                onItemSelected = { selectedMinute = it }
            )

            // AM / PM Selector for 12h format
            if (!is24Hour) {
                Box(modifier = Modifier.width(16.dp))
                AmPmSelector(
                    isPm = selectedIsPm,
                    onAmPmChange = { selectedIsPm = it }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun <T> WheelColumn(
    items: List<T>,
    initialItem: T,
    formatItem: (T) -> String,
    onItemSelected: (T) -> Unit
) {
    val itemHeight = 44.dp
    val initialIndex = remember(items, initialItem) {
        items.indexOf(initialItem).coerceAtLeast(0)
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    val snapFlingBehavior = rememberSnapFlingBehavior(lazyListState = listState)

    LaunchedEffect(initialIndex) {
        if (listState.firstVisibleItemIndex != initialIndex) {
            listState.scrollToItem(initialIndex)
        }
    }

    val selectedIndex by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val visibleItems = layoutInfo.visibleItemsInfo
            if (visibleItems.isEmpty()) initialIndex
            else {
                val centerOffset = layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset / 2
                visibleItems.minByOrNull { kotlin.math.abs((it.offset + it.size / 2) - centerOffset) }?.index
                    ?: initialIndex
            }
        }
    }

    LaunchedEffect(selectedIndex) {
        val safeIndex = selectedIndex.coerceIn(0, items.lastIndex)
        onItemSelected(items[safeIndex])
    }

    LazyColumn(
        state = listState,
        flingBehavior = snapFlingBehavior,
        contentPadding = PaddingValues(vertical = itemHeight),
        modifier = Modifier
            .width(64.dp)
            .height(itemHeight * 3),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        items(items.size) { index ->
            val item = items[index]
            val isSelected = index == selectedIndex
            Box(
                modifier = Modifier
                    .height(itemHeight)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = formatItem(item),
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontSize = if (isSelected) 24.sp else 18.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    ),
                    color = if (isSelected) MaterialTheme.colorScheme.primary else TextSecondary,
                    modifier = Modifier.alpha(if (isSelected) 1f else 0.5f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun AmPmSelector(
    isPm: Boolean,
    onAmPmChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceCard)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        TextButton(
            onClick = { onAmPmChange(false) },
            modifier = Modifier
                .height(36.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (!isPm) MaterialTheme.colorScheme.primary else SurfaceCard)
        ) {
            Text(
                "AM",
                color = if (!isPm) MaterialTheme.colorScheme.onPrimary else TextSecondary,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
            )
        }
        TextButton(
            onClick = { onAmPmChange(true) },
            modifier = Modifier
                .height(36.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (isPm) MaterialTheme.colorScheme.primary else SurfaceCard)
        ) {
            Text(
                "PM",
                color = if (isPm) MaterialTheme.colorScheme.onPrimary else TextSecondary,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
            )
        }
    }
}
