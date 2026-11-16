package io.github.sumankayal247.gbaemulator.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.sumankayal247.gbaemulator.R
import io.github.sumankayal247.gbaemulator.domain.model.EmulatorPreferences
import io.github.sumankayal247.gbaemulator.domain.model.OrientationLock
import io.github.sumankayal247.gbaemulator.domain.model.ScalingMode
import io.github.sumankayal247.gbaemulator.ui.theme.EmulatorColors

@Composable
fun SettingsDialog(
    preferences: EmulatorPreferences,
    coreVersion: String,
    onScalingModeChange: (ScalingMode) -> Unit,
    onMutedChange: (Boolean) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onOrientationLockChange: (OrientationLock) -> Unit,
    onControlsOpacityChange: (Float) -> Unit,
    onControlsScaleChange: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            color = EmulatorColors.DarkSurface,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .padding(vertical = 24.dp),
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.action_settings),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = EmulatorColors.TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Close settings",
                            tint = EmulatorColors.TextPrimary,
                        )
                    }
                }

                // Display Scaling
                SectionHeading("Display")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (mode in ScalingMode.entries) {
                        FilterChip(
                            selected = preferences.scalingMode == mode,
                            onClick = { onScalingModeChange(mode) },
                            label = { Text(mode.label) },
                        )
                    }
                }

                // Orientation Lock
                SectionHeading("Gameplay Orientation")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (lock in OrientationLock.entries) {
                        FilterChip(
                            selected = preferences.orientationLock == lock,
                            onClick = { onOrientationLockChange(lock) },
                            label = { Text(lock.label) },
                        )
                    }
                }

                HorizontalDivider(color = EmulatorColors.DarkBorder)

                // Audio
                SectionHeading("Audio")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Mute Audio",
                        style = MaterialTheme.typography.bodyLarge,
                        color = EmulatorColors.TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = preferences.muted,
                        onCheckedChange = onMutedChange,
                    )
                }
                Column {
                    Text(
                        text = "Volume: ${(preferences.audioVolume * 100).toInt()}%",
                        style = MaterialTheme.typography.bodyMedium,
                        color = EmulatorColors.TextSecondary,
                    )
                    Slider(
                        value = preferences.audioVolume,
                        onValueChange = onVolumeChange,
                        valueRange = 0f..1f,
                        enabled = !preferences.muted,
                    )
                }

                HorizontalDivider(color = EmulatorColors.DarkBorder)

                // Virtual Handheld Controls
                SectionHeading("Virtual Controls")
                Column {
                    Text(
                        text = "Button Opacity: ${(preferences.controlsOpacity * 100).toInt()}%",
                        style = MaterialTheme.typography.bodyMedium,
                        color = EmulatorColors.TextSecondary,
                    )
                    Slider(
                        value = preferences.controlsOpacity,
                        onValueChange = onControlsOpacityChange,
                        valueRange = 0.40f..1.0f,
                    )
                }
                Column {
                    Text(
                        text = "Control Size: ${(preferences.controlsScale * 100).toInt()}%",
                        style = MaterialTheme.typography.bodyMedium,
                        color = EmulatorColors.TextSecondary,
                    )
                    Slider(
                        value = preferences.controlsScale,
                        onValueChange = onControlsScaleChange,
                        valueRange = 0.85f..1.20f,
                    )
                }

                HorizontalDivider(color = EmulatorColors.DarkBorder)

                // Firmware / BIOS & Save Storage Transparency
                SectionHeading("Firmware & Save Storage")
                Text(
                    text = stringResource(R.string.bios_status_info),
                    style = MaterialTheme.typography.bodySmall,
                    color = EmulatorColors.TextSecondary,
                )
                Text(
                    text = stringResource(R.string.uninstall_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = EmulatorColors.TextSecondary,
                )

                HorizontalDivider(color = EmulatorColors.DarkBorder)

                // Open-Source & MPL-2.0 Compliance Notices
                SectionHeading("Open-Source Notices")
                Text(
                    text = "Core: $coreVersion\n" +
                        "• mGBA Libretro Core (MPL-2.0) — unmodified upstream source pinned at " +
                        "github.com/libretro/mgba commit 7a12d6d4b9acb14c0ae62c9166b6a2f3d08007f6.\n" +
                        "• Libretro API Header (MIT).\n" +
                        "• AndroidX, Jetpack Compose & Kotlin Coroutines (Apache-2.0).\n" +
                        "No commercial ROMs, copyrighted game assets, or proprietary BIOS files are bundled.",
                    style = MaterialTheme.typography.bodySmall,
                    color = EmulatorColors.TextSecondary,
                )

                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = EmulatorColors.AccentBlue,
    )
}
