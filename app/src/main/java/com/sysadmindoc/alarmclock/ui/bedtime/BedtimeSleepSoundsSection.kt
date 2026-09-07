package com.sysadmindoc.alarmclock.ui.bedtime

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Sailing
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import com.sysadmindoc.alarmclock.domain.SleepNoisePreset
import com.sysadmindoc.alarmclock.ui.components.AppFilterChip
import com.sysadmindoc.alarmclock.ui.components.AppSectionTitle
import com.sysadmindoc.alarmclock.ui.components.AppSurfaceCard
import com.sysadmindoc.alarmclock.ui.theme.SurfaceCard
import com.sysadmindoc.alarmclock.ui.theme.TextMuted
import com.sysadmindoc.alarmclock.ui.theme.TextPrimary
import com.sysadmindoc.alarmclock.ui.theme.TextSecondary
import androidx.compose.ui.res.stringResource
import com.sysadmindoc.alarmclock.R

private data class SleepSound(
    val labelRes: Int,
    val icon: ImageVector,
    val preset: SleepNoisePreset
)

private val SLEEP_SOUNDS = listOf(
    SleepSound(R.string.sleep_sound_white_noise, Icons.Default.Waves, SleepNoisePreset.WHITE),
    SleepSound(R.string.sleep_sound_rain, Icons.Default.WaterDrop, SleepNoisePreset.RAIN),
    SleepSound(R.string.sleep_sound_brown_noise, Icons.Default.GraphicEq, SleepNoisePreset.BROWN),
    SleepSound(R.string.sleep_sound_ocean, Icons.Default.Sailing, SleepNoisePreset.OCEAN),
    SleepSound(R.string.sleep_sound_fan, Icons.Default.Air, SleepNoisePreset.FAN),
    SleepSound(R.string.sleep_sound_pink_noise, Icons.Default.GraphicEq, SleepNoisePreset.PINK),
    SleepSound(R.string.sleep_sound_violet_noise, Icons.Default.Waves, SleepNoisePreset.VIOLET),
)

@Composable
internal fun SleepSoundsSection(
    state: BedtimeUiState,
    viewModel: BedtimeViewModel,
    modifier: Modifier = Modifier
) {
    AppSurfaceCard(modifier = modifier) {
        AppSectionTitle(
            title = stringResource(R.string.bedtime_sounds_sleep_sounds),
            description = stringResource(R.string.bedtime_sounds_continuous_procedural_soundscapes_no_looping)
        )

        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(SLEEP_SOUNDS) { _, sound ->
                val isActive = state.activeSoundKey == sound.preset.key

                Card(
                    modifier = Modifier
                        .width(112.dp)
                        .height(132.dp)
                        .clickable(role = Role.Button) {
                            if (isActive) viewModel.stopSound()
                            else viewModel.playSound(sound.preset)
                        },
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isActive) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                        } else {
                            SurfaceCard
                        }
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val label = stringResource(sound.labelRes)
                        Icon(
                            imageVector = sound.icon,
                            contentDescription = label,
                            tint = if (isActive) MaterialTheme.colorScheme.primary else TextSecondary,
                            modifier = Modifier.size(24.dp)
                        )
                        Column(
                            modifier = Modifier.fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = label,
                                color = TextPrimary,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.heightIn(min = 40.dp)
                            )
                            Text(
                                text = if (isActive) stringResource(R.string.bedtime_playing) else stringResource(R.string.sleep_sound_tap_to_preview),
                                color = if (isActive) MaterialTheme.colorScheme.primary else TextMuted,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = TextMuted.copy(alpha = 0.16f))

        Text(
            text = stringResource(R.string.bedtime_sounds_fade_out_after),
            color = TextSecondary,
            style = MaterialTheme.typography.labelLarge
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            listOf(0, 15, 30, 45, 60).forEach { minutes ->
                AppFilterChip(
                    label = if (minutes == 0) stringResource(R.string.settings_never) else stringResource(R.string.alarmlist_min, minutes),
                    selected = state.sleepSoundFadeMinutes == minutes,
                    onClick = { viewModel.setSleepSoundFade(minutes) },
                    selectionSemantics = true,
                )
            }
        }

        // v1.5.0: Final-taper duration. Until this pass the fade was hard-coded
        // to 60s; users with deeper-sleep routines asked for a longer slide.
        Text(
            text = stringResource(R.string.bedtime_sounds_final_taper_length),
            color = TextSecondary,
            style = MaterialTheme.typography.labelLarge
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val tapers = listOf(15, 30, 60, 120, 300, 600)
            tapers.forEach { seconds ->
                AppFilterChip(
                    label = when {
                        seconds < 60 -> "${seconds}s"
                        seconds % 60 == 0 -> stringResource(R.string.alarmlist_min, seconds / 60)
                        else -> "${seconds}s"
                    },
                    selected = state.sleepSoundFadeSeconds == seconds,
                    onClick = { viewModel.setSleepSoundFadeSeconds(seconds) },
                    selectionSemantics = true,
                )
            }
        }

        if (state.activeSoundKey.isNotBlank()) {
            TextButton(
                onClick = viewModel::stopSound,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text(stringResource(R.string.bedtime_sounds_stop_sound), color = TextSecondary)
            }
        }
    }
}
