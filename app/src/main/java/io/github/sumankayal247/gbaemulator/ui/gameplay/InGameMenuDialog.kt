package io.github.sumankayal247.gbaemulator.ui.gameplay

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.sumankayal247.gbaemulator.domain.model.OrientationLock
import io.github.sumankayal247.gbaemulator.domain.model.ScalingMode
import io.github.sumankayal247.gbaemulator.ui.theme.EmulatorColors

/**
 * In-game pause modal inspired by reference 06-game-menu.jpg.
 * Exposes ONLY verified, working P1/P2 capabilities — zero non-functional rows (AGENTS.md).
 */
@Composable
fun InGameMenuDialog(
    gameTitle: String,
    muted: Boolean,
    fastForward: Boolean,
    scalingMode: ScalingMode,
    orientationLock: OrientationLock,
    onResume: () -> Unit,
    onSaveSram: () -> Unit,
    onExportSram: () -> Unit,
    onRestartConfirmed: () -> Unit,
    onQuitConfirmed: () -> Unit,
    onToggleMute: (Boolean) -> Unit,
    onToggleFastForward: (Boolean) -> Unit,
    onToggleScalingMode: () -> Unit,
    onCycleOrientationLock: () -> Unit,
) {
    var confirmRestart by remember { mutableStateOf(false) }
    var confirmQuit by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onResume,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            color = EmulatorColors.DarkSurface,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .padding(vertical = 24.dp),
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 12.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Game menu",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = EmulatorColors.TextPrimary,
                        )
                        Text(
                            text = gameTitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = EmulatorColors.TextSecondary,
                        )
                    }
                    IconButton(onClick = onResume) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Resume game",
                            tint = EmulatorColors.TextPrimary,
                        )
                    }
                }

                HorizontalDivider(color = EmulatorColors.DarkBorder)

                MenuActionRow(
                    icon = Icons.Filled.PlayArrow,
                    label = "Resume",
                    onClick = onResume,
                )
                MenuActionRow(
                    icon = Icons.Filled.Save,
                    label = "Save SRAM Now",
                    onClick = onSaveSram,
                )
                MenuActionRow(
                    icon = Icons.Filled.FileDownload,
                    label = "Export Save (.sav)",
                    onClick = onExportSram,
                )
                MenuActionRow(
                    icon = Icons.Filled.Refresh,
                    label = "Restart",
                    onClick = { confirmRestart = true },
                )
                MenuActionRow(
                    icon = Icons.Filled.Close,
                    label = "Quit to Library",
                    onClick = { confirmQuit = true },
                )

                HorizontalDivider(
                    color = EmulatorColors.DarkBorder,
                    modifier = Modifier.padding(vertical = 4.dp),
                )

                MenuToggleRow(
                    icon = Icons.Filled.VolumeOff,
                    label = "Mute",
                    checked = muted,
                    onCheckedChange = onToggleMute,
                )
                MenuToggleRow(
                    icon = Icons.Filled.FastForward,
                    label = "Speed up (2x)",
                    checked = fastForward,
                    onCheckedChange = onToggleFastForward,
                )
                MenuValueRow(
                    icon = Icons.Filled.AspectRatio,
                    label = "Display scaling",
                    value = scalingMode.label,
                    onClick = onToggleScalingMode,
                )
                MenuValueRow(
                    icon = Icons.Filled.ScreenRotation,
                    label = "Orientation",
                    value = orientationLock.label,
                    onClick = onCycleOrientationLock,
                )
            }
        }
    }

    if (confirmRestart) {
        AlertDialog(
            onDismissRequest = { confirmRestart = false },
            containerColor = EmulatorColors.DarkSurfaceElevated,
            title = { Text("Restart game?") },
            text = { Text("Your battery save (SRAM) will be flushed first, but unsaved in-game progress will be reset.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRestart = false
                        onRestartConfirmed()
                    },
                ) {
                    Text("Restart", color = EmulatorColors.DangerRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRestart = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (confirmQuit) {
        AlertDialog(
            onDismissRequest = { confirmQuit = false },
            containerColor = EmulatorColors.DarkSurfaceElevated,
            title = { Text("Quit to Library?") },
            text = { Text("Battery save (SRAM) will be written safely before unloading the emulator.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmQuit = false
                        onQuitConfirmed()
                    },
                ) {
                    Text("Quit", color = EmulatorColors.DangerRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmQuit = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun MenuActionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = EmulatorColors.TextPrimary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = EmulatorColors.TextPrimary,
        )
    }
}

@Composable
private fun MenuToggleRow(
    icon: ImageVector,
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = EmulatorColors.TextPrimary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = EmulatorColors.TextPrimary,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun MenuValueRow(
    icon: ImageVector,
    label: String,
    value: String,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = EmulatorColors.TextPrimary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = EmulatorColors.TextPrimary,
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = EmulatorColors.AccentBlue,
        )
    }
}
