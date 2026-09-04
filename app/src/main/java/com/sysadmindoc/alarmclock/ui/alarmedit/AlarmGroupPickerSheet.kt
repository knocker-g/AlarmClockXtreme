package com.sysadmindoc.alarmclock.ui.alarmedit

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sysadmindoc.alarmclock.R
import com.sysadmindoc.alarmclock.ui.components.*
import com.sysadmindoc.alarmclock.ui.theme.*
import com.sysadmindoc.alarmclock.util.AlarmPublicText
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmGroupPickerSheet(
    currentGroup: String,
    groups: List<String>,
    onSelect: (String) -> Unit,
    onAdd: (String) -> Unit,
    onDelete: (String) -> Unit,
    countAlarms: suspend (String) -> Int,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var newGroupName by remember { mutableStateOf("") }
    var groupToDelete by remember { mutableStateOf<String?>(null) }
    var alarmCountInDeletingGroup by remember { mutableIntStateOf(0) }

    if (groupToDelete != null) {
        val group = groupToDelete!!
        val localizedName = AlarmPublicText.getLocalizedName(group, context)
        AlertDialog(
            onDismissRequest = { groupToDelete = null },
            icon = { Icon(Icons.Default.Delete, null, tint = AccentRed) },
            title = {
                Text(
                    stringResource(R.string.alarm_group_delete_confirm_title, localizedName),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                if (alarmCountInDeletingGroup > 0) {
                    Text(
                        pluralStringResource(
                            R.plurals.alarm_group_delete_confirm_message,
                            alarmCountInDeletingGroup,
                            alarmCountInDeletingGroup
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDelete(group)
                        groupToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(stringResource(R.string.alarm_list_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { groupToDelete = null }) {
                    Text(stringResource(R.string.cancel), color = TextSecondary)
                }
            }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SurfaceMedium,
        dragHandle = { BottomSheetDefaults.DragHandle(color = TextMuted) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            AppSectionTitle(
                title = stringResource(R.string.alarm_edit_alarm_group),
                description = stringResource(R.string.alarm_edit_section_group_description)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = newGroupName,
                    onValueChange = { newGroupName = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.alarm_edit_group_custom_name), color = TextMuted) },
                    singleLine = true,
                    colors = appOutlinedTextFieldColors(),
                    shape = AppInputShape
                )
                Button(
                    onClick = {
                        val trimmed = newGroupName.trim()
                        if (trimmed.isNotBlank()) {
                            onAdd(newGroupName)
                            onDismiss()
                        }
                    },
                    enabled = newGroupName.isNotBlank(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.alarm_edit_add))
                }
            }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                item {
                    GroupRow(
                        name = "",
                        label = stringResource(R.string.alarm_edit_group_none),
                        isSelected = currentGroup.isEmpty(),
                        onSelect = { onSelect(""); onDismiss() },
                        onDelete = {} // Cannot delete "None"
                    )
                }
                items(groups, key = { it }) { group ->
                    GroupRow(
                        name = group,
                        label = AlarmPublicText.getLocalizedName(group, context),
                        isSelected = currentGroup == group,
                        onSelect = { onSelect(group); onDismiss() },
                        onDelete = {
                            scope.launch {
                                val count = countAlarms(group)
                                if (count > 0) {
                                    alarmCountInDeletingGroup = count
                                    groupToDelete = group
                                } else {
                                    onDelete(group)
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupRow(
    name: String,
    label: String,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    val canDelete = name.isNotEmpty()
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
            }
            false // Always return false so the row doesn't disappear; we handle deletion separately
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = canDelete,
        backgroundContent = {
            val willDelete = dismissState.targetValue == SwipeToDismissBoxValue.EndToStart
            val isSwiping = dismissState.currentValue != SwipeToDismissBoxValue.Settled ||
                dismissState.targetValue != SwipeToDismissBoxValue.Settled
            val color by animateColorAsState(
                if (willDelete) AccentRed.copy(alpha = 0.86f) else Color.Transparent,
                label = "delete_bg"
            )
            val scale by animateFloatAsState(
                if (willDelete) 1.05f else 0.86f,
                label = "icon_scale"
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
                    .background(color)
                    .padding(end = 24.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                if (isSwiping) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.alarm_list_delete),
                        tint = Color.White,
                        modifier = Modifier
                            .scale(scale)
                    )
                }
            }
        }
    ) {
        AppSurfaceCard(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onSelect),
            highlighted = isSelected,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else TextPrimary,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                )
                if (isSelected) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}
