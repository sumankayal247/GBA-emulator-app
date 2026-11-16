package io.github.sumankayal247.gbaemulator.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.sumankayal247.gbaemulator.BuildConfig
import io.github.sumankayal247.gbaemulator.domain.model.EmulatorPreferences
import io.github.sumankayal247.gbaemulator.domain.model.OrientationLock
import io.github.sumankayal247.gbaemulator.domain.model.SavedFolderRecord
import io.github.sumankayal247.gbaemulator.domain.model.ScalingMode
import io.github.sumankayal247.gbaemulator.ui.theme.EmulatorColors
import kotlin.math.roundToInt

/**
 * Settings tab (P7 / ADR-0002 / ADR-0005):
 * Separates app preferences from dynamic core options, manages saved SAF folders,
 * surfaces explicit uninstall/data-loss & offline privacy notices, and includes
 * required MPL-2.0 / Apache-2.0 third-party open-source notices.
 */
@Composable
fun SettingsScreen(
    preferences: EmulatorPreferences,
    savedFolders: List<SavedFolderRecord>,
    onUpdateAudioVolume: (Float) -> Unit,
    onUpdateMuted: (Boolean) -> Unit,
    onUpdateScalingMode: (ScalingMode) -> Unit,
    onUpdateOrientationLock: (OrientationLock) -> Unit,
    onUpdateReducedMotion: (Boolean) -> Unit,
    onRemoveSavedFolder: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(EmulatorColors.DarkBackground)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineMedium,
            color = EmulatorColors.AccentBlue,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "App preferences, video/audio defaults, storage privacy, and open-source licences.",
            style = MaterialTheme.typography.bodySmall,
            color = EmulatorColors.TextSecondary,
        )

        Spacer(Modifier.height(16.dp))

        // Audio & Video section
        SectionCard(title = "Audio & Display") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Mute audio", style = MaterialTheme.typography.bodyLarge, color = EmulatorColors.TextPrimary)
                Switch(
                    checked = preferences.muted,
                    onCheckedChange = onUpdateMuted,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = EmulatorColors.TextPrimary,
                        checkedTrackColor = EmulatorColors.AccentBlue,
                    ),
                )
            }

            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Audio volume", style = MaterialTheme.typography.bodyMedium, color = EmulatorColors.TextPrimary)
                Text(
                    "${(preferences.audioVolume * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = EmulatorColors.AccentCyan,
                )
            }
            Slider(
                value = preferences.audioVolume,
                onValueChange = onUpdateAudioVolume,
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(
                    thumbColor = EmulatorColors.AccentCyan,
                    activeTrackColor = EmulatorColors.AccentBlue,
                ),
            )

            Spacer(Modifier.height(8.dp))
            Text("Video scaling", style = MaterialTheme.typography.bodyMedium, color = EmulatorColors.TextPrimary)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScalingMode.entries.forEach { mode ->
                    FilterChip(
                        selected = preferences.scalingMode == mode,
                        onClick = { onUpdateScalingMode(mode) },
                        label = { Text(mode.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = EmulatorColors.AccentBlue.copy(alpha = 0.25f),
                            selectedLabelColor = EmulatorColors.AccentCyan,
                        ),
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Text("Gameplay orientation", style = MaterialTheme.typography.bodyMedium, color = EmulatorColors.TextPrimary)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OrientationLock.entries.forEach { lock ->
                    FilterChip(
                        selected = preferences.orientationLock == lock,
                        onClick = { onUpdateOrientationLock(lock) },
                        label = { Text(lock.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = EmulatorColors.AccentBlue.copy(alpha = 0.25f),
                            selectedLabelColor = EmulatorColors.AccentCyan,
                        ),
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Reduced motion", style = MaterialTheme.typography.bodyMedium, color = EmulatorColors.TextPrimary)
                    Text(
                        "Minimize UI animations for accessibility",
                        style = MaterialTheme.typography.bodySmall,
                        color = EmulatorColors.TextSecondary,
                    )
                }
                Switch(
                    checked = preferences.reducedMotion,
                    onCheckedChange = onUpdateReducedMotion,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = EmulatorColors.TextPrimary,
                        checkedTrackColor = EmulatorColors.AccentBlue,
                    ),
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // Saved SAF Folders section
        SectionCard(title = "Indexed Game Folders (SAF)") {
            if (savedFolders.isEmpty()) {
                Text(
                    text = "No folders added yet. Select a folder in the Home tab to scan ROMs automatically.",
                    style = MaterialTheme.typography.bodySmall,
                    color = EmulatorColors.TextSecondary,
                )
            } else {
                savedFolders.forEachIndexed { idx, folder ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = folder.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = EmulatorColors.TextPrimary,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = "${folder.lastScanGameCount} ROM(s) found" +
                                    (folder.lastScanError?.let { " • $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = EmulatorColors.TextSecondary,
                            )
                        }
                        IconButton(onClick = { onRemoveSavedFolder(folder.treeUri) }) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = "Remove folder ${folder.displayName}",
                                tint = EmulatorColors.TextSecondary,
                            )
                        }
                    }
                    if (idx < savedFolders.lastIndex) {
                        HorizontalDivider(color = EmulatorColors.CardBorder.copy(alpha = 0.5f))
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // Privacy & Data Safety section
        SectionCard(title = "Privacy & Save Backup Notice") {
            Text(
                text = "• 100% Offline: This app declares no INTERNET permission, collects no analytics, and requires no account.",
                style = MaterialTheme.typography.bodySmall,
                color = EmulatorColors.TextSecondary,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "• Scoped Storage (SAF): Only files and folders you explicitly choose are accessed; no broad storage permissions are requested.",
                style = MaterialTheme.typography.bodySmall,
                color = EmulatorColors.TextSecondary,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "• Uninstall warning: Battery saves (SRAM) and save states are stored in app-private storage and will be deleted by Android if you uninstall this app. Use Export Battery Save (.sav) from any game's menu in Home to keep external backups.",
                style = MaterialTheme.typography.bodySmall,
                color = EmulatorColors.WarningAmber,
            )
        }

        Spacer(Modifier.height(14.dp))

        // Open-Source Notices section (MPL-2.0 compliance)
        SectionCard(title = "About & Open-Source Notices") {
            Text(
                text = "GBA Emulator ${BuildConfig.VERSION_NAME} (arm64-v8a)",
                style = MaterialTheme.typography.bodyMedium,
                color = EmulatorColors.TextPrimary,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "• mGBA Libretro Core — Mozilla Public License 2.0 (MPL-2.0). Unmodified pinned source: github.com/libretro/mgba @ 7a12d6d4b9acb14c0ae62c9166b6a2f3d08007f6. Complete source code is available in this repository's third_party/mgba submodule and upstream.",
                style = MaterialTheme.typography.bodySmall,
                color = EmulatorColors.TextSecondary,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "• Jetpack Compose, AndroidX Lifecycle, Room, DataStore, and Kotlin Coroutines — Apache License 2.0.",
                style = MaterialTheme.typography.bodySmall,
                color = EmulatorColors.TextSecondary,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "• No commercial ROMs, copyrighted cover art, or proprietary BIOS files are bundled with this application.",
                style = MaterialTheme.typography.bodySmall,
                color = EmulatorColors.TextMuted,
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Surface(
        color = EmulatorColors.CardSurface,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, EmulatorColors.CardBorder, RoundedCornerShape(16.dp)),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = EmulatorColors.AccentCyan,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}
