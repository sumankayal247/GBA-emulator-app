package io.github.sumankayal247.gbaemulator.ui.controllers

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.sumankayal247.gbaemulator.domain.model.ConnectedControllerInfo
import io.github.sumankayal247.gbaemulator.domain.model.VirtualButton
import io.github.sumankayal247.gbaemulator.emulator.ControllerManager
import io.github.sumankayal247.gbaemulator.ui.theme.EmulatorColors
import kotlin.math.roundToInt

/**
 * External Bluetooth HID & USB Gamepad screen matching `03-controllers-screen.jpg` (P6 / ADR-0005).
 * Includes:
 * - Clean empty state + How to connect guide (launching Android system Bluetooth settings)
 * - Live connected & previously recognized saved controller list
 * - Live interactive input test panel showing logical GBA buttons in real time
 * - Per-device button remapping editor + analog deadzone + Reset to Default
 * - Auto-hide touch controls preference
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ControllersScreen(
    controllers: List<ConnectedControllerInfo>,
    pressedButtons: Set<VirtualButton>,
    capturingBindingFor: Pair<String, VirtualButton>?,
    autoHideTouchWithController: Boolean,
    onStartCapturingBinding: (descriptor: String, button: VirtualButton) -> Unit,
    onCancelCapturingBinding: () -> Unit,
    onUpdateDeadzone: (descriptor: String, deadzone: Float) -> Unit,
    onResetProfile: (descriptor: String) -> Unit,
    onRemoveSavedProfile: (descriptor: String) -> Unit,
    onToggleAutoHideTouch: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var expandedDescriptor by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(EmulatorColors.DarkBackground),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
        Text(
            text = "Controllers",
            style = MaterialTheme.typography.headlineMedium,
            color = EmulatorColors.AccentBlue,
            fontWeight = FontWeight.Bold,
        )

        Spacer(Modifier.height(16.dp))

        if (controllers.isEmpty()) {
            // Exact empty state card inspired by 03-controllers-screen.jpg
            Surface(
                color = EmulatorColors.CardSurface,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, EmulatorColors.CardBorder, RoundedCornerShape(18.dp)),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 32.dp),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(EmulatorColors.AccentBlue.copy(alpha = 0.16f)),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.SportsEsports,
                            contentDescription = null,
                            tint = EmulatorColors.AccentBlue,
                            modifier = Modifier.size(36.dp),
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "No controllers yet",
                        style = MaterialTheme.typography.titleLarge,
                        color = EmulatorColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "No connected or saved controllers. Connect one to start playing your way.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = EmulatorColors.TextSecondary,
                    )
                }
            }
        } else {
            // Live Input Test Panel
            Surface(
                color = EmulatorColors.CardSurface,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, EmulatorColors.CardBorder, RoundedCornerShape(16.dp)),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Live Input Tester",
                        style = MaterialTheme.typography.titleSmall,
                        color = EmulatorColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Press buttons or move the stick on your connected controller to verify input.",
                        style = MaterialTheme.typography.bodySmall,
                        color = EmulatorColors.TextSecondary,
                    )
                    Spacer(Modifier.height(10.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        VirtualButton.entries.forEach { btn ->
                            val active = btn in pressedButtons
                            Surface(
                                color = if (active) EmulatorColors.AccentBlue else EmulatorColors.DarkSurface,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.border(
                                    1.dp,
                                    if (active) EmulatorColors.AccentCyan else EmulatorColors.CardBorder,
                                    RoundedCornerShape(10.dp),
                                ),
                            ) {
                                Text(
                                    text = btn.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                    color = if (active) Color.White else EmulatorColors.TextSecondary,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // Connected & saved controllers list
            controllers.forEach { ctrl ->
                val isExpanded = expandedDescriptor == ctrl.descriptor
                ControllerProfileCard(
                    info = ctrl,
                    expanded = isExpanded,
                    onToggleExpand = {
                        expandedDescriptor = if (isExpanded) null else ctrl.descriptor
                    },
                    onStartRemap = { btn -> onStartCapturingBinding(ctrl.descriptor, btn) },
                    onUpdateDeadzone = { dz -> onUpdateDeadzone(ctrl.descriptor, dz) },
                    onResetDefault = { onResetProfile(ctrl.descriptor) },
                    onRemoveProfile = if (!ctrl.isConnected) {
                        { onRemoveSavedProfile(ctrl.descriptor) }
                    } else {
                        null
                    },
                )
                Spacer(Modifier.height(10.dp))
            }
        }

        Spacer(Modifier.height(16.dp))

        // Auto-hide touch overlay toggle
        Surface(
            color = EmulatorColors.CardSurface,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, EmulatorColors.CardBorder, RoundedCornerShape(14.dp)),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Auto-hide touch controls",
                        style = MaterialTheme.typography.bodyLarge,
                        color = EmulatorColors.TextPrimary,
                    )
                    Text(
                        text = "Hide on-screen buttons in landscape after you use a physical controller (Menu & Controls pills stay visible). Touch controls stay visible until controller input is detected.",
                        style = MaterialTheme.typography.bodySmall,
                        color = EmulatorColors.TextSecondary,
                    )
                }
                Switch(
                    checked = autoHideTouchWithController,
                    onCheckedChange = onToggleAutoHideTouch,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = EmulatorColors.TextPrimary,
                        checkedTrackColor = EmulatorColors.AccentBlue,
                    ),
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // How to connect section matching 03-controllers-screen.jpg
        Text(
            text = "How to connect",
            style = MaterialTheme.typography.titleMedium,
            color = EmulatorColors.TextPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(10.dp))

        Surface(
            color = EmulatorColors.CardSurface,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, EmulatorColors.CardBorder, RoundedCornerShape(16.dp)),
        ) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                            }
                        }
                        .padding(16.dp),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(EmulatorColors.AccentBlue.copy(alpha = 0.16f)),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Bluetooth,
                            contentDescription = "Open Bluetooth settings",
                            tint = EmulatorColors.AccentBlue,
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Bluetooth",
                            style = MaterialTheme.typography.titleSmall,
                            color = EmulatorColors.TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Put your controller in pairing mode, then tap here to open your device's Bluetooth settings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = EmulatorColors.TextSecondary,
                        )
                    }
                }

                HorizontalDivider(
                    color = EmulatorColors.CardBorder,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(EmulatorColors.AccentBlue.copy(alpha = 0.16f)),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Usb,
                            contentDescription = null,
                            tint = EmulatorColors.AccentBlue,
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "USB",
                            style = MaterialTheme.typography.titleSmall,
                            color = EmulatorColors.TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Plug your controller into your device using a compatible USB cable or OTG adapter.",
                            style = MaterialTheme.typography.bodySmall,
                            color = EmulatorColors.TextSecondary,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            text = "Once connected, your controller will appear here automatically. Disconnecting a controller immediately releases all held buttons.",
            style = MaterialTheme.typography.bodySmall,
            color = EmulatorColors.TextMuted,
        )
        Spacer(Modifier.height(24.dp))
        }

        // In-window button remap capture overlay so hardware gamepad KeyEvents continue routing
        // straight to MainActivity.onKeyDown without a separate Dialog window intercepting focus.
        capturingBindingFor?.let { (_, button) ->
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.72f))
                    .clickable(onClick = onCancelCapturingBinding)
                    .padding(24.dp),
            ) {
                Surface(
                    color = EmulatorColors.DialogBackground,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, EmulatorColors.AccentBlue, RoundedCornerShape(18.dp)),
                ) {
                    Column(modifier = Modifier.padding(22.dp)) {
                        Text(
                            text = "Map '${button.label}' button",
                            style = MaterialTheme.typography.titleLarge,
                            color = EmulatorColors.TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "Press the desired physical button on your connected Bluetooth or USB controller now.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = EmulatorColors.TextSecondary,
                        )
                        Spacer(Modifier.height(16.dp))
                        Row(
                            horizontalArrangement = Arrangement.End,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            TextButton(onClick = onCancelCapturingBinding) {
                                Text("Cancel", color = EmulatorColors.AccentCyan)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ControllerProfileCard(
    info: ConnectedControllerInfo,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onStartRemap: (VirtualButton) -> Unit,
    onUpdateDeadzone: (Float) -> Unit,
    onResetDefault: () -> Unit,
    onRemoveProfile: (() -> Unit)?,
) {
    Surface(
        color = EmulatorColors.CardSurface,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                if (info.isConnected) EmulatorColors.AccentBlue.copy(alpha = 0.55f) else EmulatorColors.CardBorder,
                RoundedCornerShape(16.dp),
            ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpand),
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (info.isConnected) Color(0xFF4CAF50) else EmulatorColors.TextMuted),
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = info.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        color = EmulatorColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = if (info.isConnected) {
                            "Connected • Tap to remap buttons"
                        } else {
                            "Saved profile (offline) • Tap to view mappings"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (info.isConnected) EmulatorColors.AccentCyan else EmulatorColors.TextSecondary,
                    )
                }
                if (onRemoveProfile != null) {
                    IconButton(onClick = onRemoveProfile) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = "Forget saved controller",
                            tint = EmulatorColors.TextSecondary,
                        )
                    }
                }
            }

            if (expanded) {
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = EmulatorColors.CardBorder)
                Spacer(Modifier.height(10.dp))

                VirtualButton.entries.forEach { button ->
                    val boundCode = info.profile.buttonKeyCodes[button]
                        ?: io.github.sumankayal247.gbaemulator.domain.model.ControllerProfile.defaultBindings()
                            .getValue(button)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (info.isConnected) {
                                    Modifier.clickable { onStartRemap(button) }
                                } else {
                                    Modifier
                                },
                            )
                            .padding(vertical = 8.dp),
                    ) {
                        Text(
                            text = button.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = EmulatorColors.TextPrimary,
                        )
                        Surface(
                            color = EmulatorColors.DarkSurface,
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text(
                                text = ControllerManager.formatKeyCodeLabel(boundCode),
                                style = MaterialTheme.typography.labelMedium,
                                color = EmulatorColors.AccentCyan,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "Analog stick deadzone",
                        style = MaterialTheme.typography.bodySmall,
                        color = EmulatorColors.TextSecondary,
                    )
                    Text(
                        text = "${(info.profile.analogDeadzone * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = EmulatorColors.AccentCyan,
                    )
                }
                Slider(
                    value = info.profile.analogDeadzone,
                    onValueChange = onUpdateDeadzone,
                    valueRange = 0.15f..0.70f,
                    colors = SliderDefaults.colors(
                        thumbColor = EmulatorColors.AccentCyan,
                        activeTrackColor = EmulatorColors.AccentBlue,
                    ),
                )

                OutlinedButton(
                    onClick = onResetDefault,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Reset to Default Mappings")
                }
            }
        }
    }
}
