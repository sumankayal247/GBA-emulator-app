package io.github.sumankayal247.gbaemulator.ui.gameplay

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.sumankayal247.gbaemulator.domain.model.ScalingMode
import io.github.sumankayal247.gbaemulator.ui.theme.EmulatorColors

/**
 * Full working in-game pause menu inspired by `06-game-menu.jpg`.
 * Exposes Save State, Load State, Battery Save (SRAM) flush, Quit, Restart, Mute, Speed up,
 * Lock orientation, Video scaling, dynamic Core Options, and per-game Cheat Codes.
 * Intentionally omits unverified hardware Tilt Sensor controls (Plan §5.H).
 */
@Composable
fun InGameMenuDialog(
    gameTitle: String,
    muted: Boolean,
    fastForward: Boolean,
    orientationLocked: Boolean,
    autoHideTouchWithController: Boolean,
    scalingMode: ScalingMode,
    coreVersion: String,
    onOpenSaveStates: () -> Unit,
    onOpenLoadStates: () -> Unit,
    onOpenCoreOptions: () -> Unit,
    onOpenCheats: () -> Unit,
    onFlushBatterySave: () -> Unit,
    onRestartConfirmed: () -> Unit,
    onQuitConfirmed: () -> Unit,
    onToggleMute: (Boolean) -> Unit,
    onToggleFastForward: (Boolean) -> Unit,
    onToggleOrientationLock: (Boolean) -> Unit,
    onToggleScalingMode: () -> Unit,
    onToggleAutoHideTouch: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmAction by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = EmulatorColors.DialogBackground,
            tonalElevation = 8.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Game menu",
                            style = MaterialTheme.typography.titleLarge,
                            color = EmulatorColors.TextPrimary,
                        )
                        Text(
                            text = gameTitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = EmulatorColors.TextSecondary,
                            maxLines = 1,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Resume game",
                            tint = EmulatorColors.TextPrimary,
                        )
                    }
                }

                HorizontalDivider(color = EmulatorColors.CardBorder)

                MenuActionRow(
                    icon = Icons.Filled.FileDownload,
                    label = "Save",
                    subtitle = "8 save-state slots",
                    onClick = onOpenSaveStates,
                )
                MenuActionRow(
                    icon = Icons.Filled.FileUpload,
                    label = "Load",
                    subtitle = "Restore saved snapshot",
                    onClick = onOpenLoadStates,
                )
                MenuActionRow(
                    icon = Icons.Filled.Save,
                    label = "Save Battery (SRAM)",
                    subtitle = "Flush in-game save to disk now",
                    onClick = onFlushBatterySave,
                )
                MenuActionRow(
                    icon = Icons.Filled.Close,
                    label = "Quit",
                    subtitle = "Save battery progress & return to library",
                    onClick = { confirmAction = "quit" },
                )
                MenuActionRow(
                    icon = Icons.Filled.Refresh,
                    label = "Restart",
                    subtitle = "Reset the emulated console",
                    onClick = { confirmAction = "restart" },
                )

                HorizontalDivider(
                    color = EmulatorColors.CardBorder,
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
                    label = "Speed up",
                    checked = fastForward,
                    onCheckedChange = onToggleFastForward,
                )
                MenuToggleRow(
                    icon = Icons.Filled.ScreenRotation,
                    label = "Lock orientation",
                    checked = orientationLocked,
                    onCheckedChange = onToggleOrientationLock,
                )
                MenuToggleRow(
                    icon = Icons.Filled.SportsEsports,
                    label = "Auto-hide touch controls",
                    checked = autoHideTouchWithController,
                    onCheckedChange = onToggleAutoHideTouch,
                )
                MenuActionRow(
                    icon = Icons.Filled.AspectRatio,
                    label = "Scaling: ${scalingMode.label}",
                    subtitle = "Tap to switch Aspect Fit / Integer Scale",
                    onClick = onToggleScalingMode,
                )

                HorizontalDivider(
                    color = EmulatorColors.CardBorder,
                    modifier = Modifier.padding(vertical = 4.dp),
                )

                MenuActionRow(
                    icon = Icons.Filled.Tune,
                    label = "Core Options",
                    subtitle = "Dynamic mGBA video, audio & system options",
                    onClick = onOpenCoreOptions,
                )
                MenuActionRow(
                    icon = Icons.Filled.BugReport,
                    label = "Cheat",
                    subtitle = "Manage per-game cheat codes",
                    onClick = onOpenCheats,
                )

                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Core: $coreVersion",
                    style = MaterialTheme.typography.labelSmall,
                    color = EmulatorColors.TextMuted,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
        }
    }

    if (confirmAction == "restart") {
        AlertDialog(
            onDismissRequest = { confirmAction = null },
            containerColor = EmulatorColors.DialogBackground,
            title = { Text("Restart game?", color = EmulatorColors.TextPrimary) },
            text = {
                Text(
                    "Any unsaved progress since your last in-game save or save state will be lost. Battery SRAM will be flushed first.",
                    color = EmulatorColors.TextSecondary,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmAction = null
                        onRestartConfirmed()
                    },
                ) {
                    Text("Restart", color = EmulatorColors.AccentCyan)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmAction = null }) {
                    Text("Cancel", color = EmulatorColors.TextSecondary)
                }
            },
        )
    } else if (confirmAction == "quit") {
        AlertDialog(
            onDismissRequest = { confirmAction = null },
            containerColor = EmulatorColors.DialogBackground,
            title = { Text("Quit to library?", color = EmulatorColors.TextPrimary) },
            text = {
                Text(
                    "Your battery save (SRAM) will be flushed safely before shutting down the emulator.",
                    color = EmulatorColors.TextSecondary,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmAction = null
                        onQuitConfirmed()
                    },
                ) {
                    Text("Quit", color = EmulatorColors.ErrorRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmAction = null }) {
                    Text("Cancel", color = EmulatorColors.TextSecondary)
                }
            },
        )
    }
}

@Composable
private fun MenuActionRow(
    icon: ImageVector,
    label: String,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = EmulatorColors.TextPrimary,
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = EmulatorColors.TextPrimary,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = EmulatorColors.TextSecondary,
                )
            }
        }
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
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = EmulatorColors.TextPrimary,
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = EmulatorColors.TextPrimary,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = EmulatorColors.TextPrimary,
                checkedTrackColor = EmulatorColors.AccentBlue,
            ),
        )
    }
}
