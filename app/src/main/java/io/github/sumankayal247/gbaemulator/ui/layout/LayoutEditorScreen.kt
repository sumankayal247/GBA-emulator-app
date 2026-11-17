package io.github.sumankayal247.gbaemulator.ui.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.sumankayal247.gbaemulator.domain.model.EmulatorPreferences
import io.github.sumankayal247.gbaemulator.domain.model.LayoutPreset
import io.github.sumankayal247.gbaemulator.domain.model.ShellColors
import io.github.sumankayal247.gbaemulator.domain.model.TouchLayoutProfile
import io.github.sumankayal247.gbaemulator.ui.theme.EmulatorColors
import io.github.sumankayal247.gbaemulator.emulator.TouchInputTracker
import io.github.sumankayal247.gbaemulator.ui.gameplay.HandheldScreenPlaceholder
import io.github.sumankayal247.gbaemulator.ui.gameplay.LandscapeHandheldLayout
import io.github.sumankayal247.gbaemulator.ui.gameplay.PortraitHandheldLayout
import io.github.sumankayal247.gbaemulator.ui.gameplay.ProvideHandheldPalette
import io.github.sumankayal247.gbaemulator.ui.gameplay.handheldPalette
import io.github.sumankayal247.gbaemulator.ui.gameplay.handheldShellBrush
import kotlin.math.roundToInt

/**
 * Separate Portrait & Landscape virtual handheld layout editor (P4 / ADR-0005).
 * Provides presets (Default / Compact / Large), clamped safe bounds so controls never move
 * off-screen, live handheld preview, Undo, Reset, and haptic feedback toggle.
 */
@Composable
fun LayoutEditorScreen(
    preferences: EmulatorPreferences,
    onSavePortraitLayout: (TouchLayoutProfile) -> Unit,
    onSaveLandscapeLayout: (TouchLayoutProfile) -> Unit,
    onToggleHaptics: (Boolean) -> Unit,
    onSaveShellColor: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editingLandscape by remember { mutableStateOf(false) }
    val persisted = if (editingLandscape) preferences.landscapeLayout else preferences.portraitLayout

    var draft by remember(editingLandscape, persisted) { mutableStateOf(persisted) }
    var previousDraft by remember(editingLandscape) { mutableStateOf<TouchLayoutProfile?>(null) }

    fun updateDraft(mutator: (TouchLayoutProfile) -> TouchLayoutProfile) {
        previousDraft = draft
        draft = mutator(draft).clamped()
    }

    val hasUnsavedChanges = draft != persisted
    var shellDraft by remember(preferences.shellColorHex) { mutableStateOf(preferences.shellColorHex) }
    val normalizedShell = ShellColors.normalize(shellDraft)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(EmulatorColors.DarkBackground)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text = "Layout",
            style = MaterialTheme.typography.headlineMedium,
            color = EmulatorColors.AccentBlue,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Customize portrait and landscape virtual handheld controls separately.",
            style = MaterialTheme.typography.bodySmall,
            color = EmulatorColors.TextSecondary,
        )

        Spacer(Modifier.height(14.dp))

        // Orientation mode switcher
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilterChip(
                selected = !editingLandscape,
                onClick = { editingLandscape = false },
                label = { Text("Portrait") },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = EmulatorColors.CardSurface,
                    selectedContainerColor = EmulatorColors.AccentBlue.copy(alpha = 0.25f),
                    selectedLabelColor = EmulatorColors.AccentCyan,
                ),
            )
            FilterChip(
                selected = editingLandscape,
                onClick = { editingLandscape = true },
                label = { Text("Landscape") },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = EmulatorColors.CardSurface,
                    selectedContainerColor = EmulatorColors.AccentBlue.copy(alpha = 0.25f),
                    selectedLabelColor = EmulatorColors.AccentCyan,
                ),
            )
        }

        Spacer(Modifier.height(12.dp))

        // Live visual handheld preview
        HandheldLayoutPreview(
            profile = draft,
            landscape = editingLandscape,
            shellColorHex = normalizedShell ?: preferences.shellColorHex,
        )
        Text(
            text = "Scaled to this window’s dimensions. Rotated preview is an estimate; system insets can differ in gameplay.",
            style = MaterialTheme.typography.bodySmall,
            color = EmulatorColors.TextSecondary,
        )

        Spacer(Modifier.height(14.dp))
        ShellColorEditor(
            value = shellDraft,
            onValueChange = { shellDraft = it },
            onSave = { normalizedShell?.let(onSaveShellColor) },
            canSave = normalizedShell != null && normalizedShell != preferences.shellColorHex,
        )

        Spacer(Modifier.height(14.dp))

        // Presets row
        Text(
            text = "Presets",
            style = MaterialTheme.typography.labelLarge,
            color = EmulatorColors.TextPrimary,
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(LayoutPreset.DEFAULT, LayoutPreset.COMPACT, LayoutPreset.LARGE).forEach { preset ->
                FilterChip(
                    selected = draft.preset == preset,
                    onClick = {
                        updateDraft { TouchLayoutProfile.forPreset(preset, editingLandscape) }
                    },
                    label = { Text(preset.label) },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = EmulatorColors.CardSurface,
                        selectedContainerColor = EmulatorColors.AccentBlue.copy(alpha = 0.25f),
                        selectedLabelColor = EmulatorColors.AccentCyan,
                    ),
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // Clamped adjustment card
        Surface(
            color = EmulatorColors.CardSurface,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, EmulatorColors.CardBorder, RoundedCornerShape(16.dp)),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                LabeledSlider(
                    label = "Control size",
                    valueLabel = "${(draft.scale * 100).roundToInt()}%",
                    value = draft.scale,
                    range = TouchLayoutProfile.MIN_SCALE..TouchLayoutProfile.MAX_SCALE,
                    onValueChange = { v ->
                        updateDraft { it.copy(preset = LayoutPreset.CUSTOM, scale = v) }
                    },
                )
                LabeledSlider(
                    label = "Control opacity",
                    valueLabel = "${(draft.opacity * 100).roundToInt()}%",
                    value = draft.opacity,
                    range = TouchLayoutProfile.MIN_OPACITY..TouchLayoutProfile.MAX_OPACITY,
                    onValueChange = { v ->
                        updateDraft { it.copy(preset = LayoutPreset.CUSTOM, opacity = v) }
                    },
                )
                LabeledSlider(
                    label = "D-Pad horizontal offset",
                    valueLabel = "${draft.dpadOffsetXdp.roundToInt()} dp",
                    value = draft.dpadOffsetXdp,
                    range = -TouchLayoutProfile.MAX_OFFSET_DP..TouchLayoutProfile.MAX_OFFSET_DP,
                    onValueChange = { v ->
                        updateDraft { it.copy(preset = LayoutPreset.CUSTOM, dpadOffsetXdp = v) }
                    },
                )
                LabeledSlider(
                    label = "D-Pad vertical offset",
                    valueLabel = "${draft.dpadOffsetYdp.roundToInt()} dp",
                    value = draft.dpadOffsetYdp,
                    range = -TouchLayoutProfile.MAX_OFFSET_DP..TouchLayoutProfile.MAX_OFFSET_DP,
                    onValueChange = { v ->
                        updateDraft { it.copy(preset = LayoutPreset.CUSTOM, dpadOffsetYdp = v) }
                    },
                )
                LabeledSlider(
                    label = "A / B buttons horizontal offset",
                    valueLabel = "${draft.buttonsOffsetXdp.roundToInt()} dp",
                    value = draft.buttonsOffsetXdp,
                    range = -TouchLayoutProfile.MAX_OFFSET_DP..TouchLayoutProfile.MAX_OFFSET_DP,
                    onValueChange = { v ->
                        updateDraft { it.copy(preset = LayoutPreset.CUSTOM, buttonsOffsetXdp = v) }
                    },
                )
                LabeledSlider(
                    label = "A / B buttons vertical offset",
                    valueLabel = "${draft.buttonsOffsetYdp.roundToInt()} dp",
                    value = draft.buttonsOffsetYdp,
                    range = -TouchLayoutProfile.MAX_OFFSET_DP..TouchLayoutProfile.MAX_OFFSET_DP,
                    onValueChange = { v ->
                        updateDraft { it.copy(preset = LayoutPreset.CUSTOM, buttonsOffsetYdp = v) }
                    },
                )
                LabeledSlider(
                    label = "L / R shoulders vertical offset",
                    valueLabel = "${draft.shouldersOffsetYdp.roundToInt()} dp",
                    value = draft.shouldersOffsetYdp,
                    range = -TouchLayoutProfile.MAX_SHOULDER_OFFSET_DP..TouchLayoutProfile.MAX_SHOULDER_OFFSET_DP,
                    onValueChange = { v ->
                        updateDraft { it.copy(preset = LayoutPreset.CUSTOM, shouldersOffsetYdp = v) }
                    },
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // Haptic feedback switch
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
                        text = "Haptic feedback",
                        style = MaterialTheme.typography.bodyLarge,
                        color = EmulatorColors.TextPrimary,
                    )
                    Text(
                        text = "Subtle vibration when pressing touch controls",
                        style = MaterialTheme.typography.bodySmall,
                        color = EmulatorColors.TextSecondary,
                    )
                }
                Switch(
                    checked = preferences.hapticsEnabled,
                    onCheckedChange = onToggleHaptics,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = EmulatorColors.TextPrimary,
                        checkedTrackColor = EmulatorColors.AccentBlue,
                    ),
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // Action buttons: Undo, Reset, Save
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedButton(
                onClick = {
                    previousDraft?.let { prev ->
                        draft = prev
                        previousDraft = null
                    }
                },
                enabled = previousDraft != null,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.Undo, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Undo")
            }

            OutlinedButton(
                onClick = {
                    val defaultProfile = if (editingLandscape) {
                        TouchLayoutProfile.defaultLandscape()
                    } else {
                        TouchLayoutProfile.defaultPortrait()
                    }
                    previousDraft = draft
                    draft = defaultProfile
                    if (editingLandscape) onSaveLandscapeLayout(defaultProfile) else onSavePortraitLayout(defaultProfile)
                },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Reset")
            }
        }

        Spacer(Modifier.height(10.dp))

        Button(
            onClick = {
                if (editingLandscape) onSaveLandscapeLayout(draft) else onSavePortraitLayout(draft)
            },
            enabled = hasUnsavedChanges,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = EmulatorColors.AccentBlue),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Text(
                text = if (hasUnsavedChanges) {
                    "Save ${if (editingLandscape) "Landscape" else "Portrait"} Layout"
                } else {
                    "Layout Saved"
                },
                fontWeight = FontWeight.SemiBold,
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun HandheldLayoutPreview(
    profile: TouchLayoutProfile,
    landscape: Boolean,
    shellColorHex: String,
) {
    val configuration = LocalConfiguration.current
    val shortSide = minOf(configuration.screenWidthDp, configuration.screenHeightDp).coerceAtLeast(1)
    val longSide = maxOf(configuration.screenWidthDp, configuration.screenHeightDp).coerceAtLeast(1)
    val deviceWidth = if (landscape) longSide else shortSide
    val deviceHeight = if (landscape) shortSide else longSide
    val tracker = remember { TouchInputTracker() }
    val palette = remember(shellColorHex) { handheldPalette(shellColorHex) }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val previewWidth = minOf(maxWidth.value, 340f * deviceWidth / deviceHeight).dp
        val previewHeight = (previewWidth.value * deviceHeight / deviceWidth).dp
        // Measure exactly as gameplay does at the current window's dp size, then scale the
        // entire result (including text, offsets and shadows) down as a single layer.
        Layout(
            modifier = Modifier.width(previewWidth).height(previewHeight).clip(RoundedCornerShape(12.dp)),
            content = {
                ProvideHandheldPalette(shellColorHex) {
                    Box(Modifier.fillMaxSize().background(handheldShellBrush(palette))) {
                        if (landscape) {
                            LandscapeHandheldLayout(
                                tracker = tracker, pressedButtons = emptySet(), onInputChanged = {},
                                profile = profile, onOpenMenu = {}, interactive = false,
                                screenViewport = { HandheldScreenPlaceholder(Modifier.fillMaxSize()) },
                            )
                        } else {
                            PortraitHandheldLayout(
                                tracker = tracker, pressedButtons = emptySet(), onInputChanged = {},
                                profile = profile, onOpenMenu = {}, interactive = false,
                                screenViewport = { HandheldScreenPlaceholder(Modifier.fillMaxSize()) },
                            )
                        }
                    }
                }
            },
        ) { measurables, constraints ->
            val realWidth = deviceWidth.dp.roundToPx().coerceAtLeast(1)
            val realHeight = deviceHeight.dp.roundToPx().coerceAtLeast(1)
            val child = measurables.single().measure(Constraints.fixed(realWidth, realHeight))
            val width = constraints.maxWidth
            val height = constraints.maxHeight
            val scale = minOf(width.toFloat() / realWidth, height.toFloat() / realHeight)
            layout(width, height) {
                child.placeRelativeWithLayer(0, 0) {
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0f, 0f)
                }
            }
        }
    }
}

@Composable
private fun ShellColorEditor(
    value: String,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
    canSave: Boolean,
) {
    val normalized = ShellColors.normalize(value)
    Surface(
        color = EmulatorColors.CardSurface,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, EmulatorColors.CardBorder, RoundedCornerShape(16.dp)),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Shell colour", style = MaterialTheme.typography.titleMedium, color = EmulatorColors.TextPrimary)
            Text("Applies to both orientations", style = MaterialTheme.typography.bodySmall, color = EmulatorColors.TextSecondary)
            ShellColors.PRESETS.chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { preset ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.weight(1f)
                                .clickable(role = Role.Button) { onValueChange(preset.hex) }
                                .semantics { contentDescription = "${preset.label} shell colour" }
                                .padding(vertical = 6.dp),
                        ) {
                            Box(
                                Modifier.size(32.dp)
                                    .background(Color(preset.hex.toLong(16) or 0xFF000000), CircleShape)
                                    .border(
                                        if (normalized == preset.hex) 3.dp else 1.dp,
                                        if (normalized == preset.hex) Color.White else EmulatorColors.CardBorder,
                                        CircleShape,
                                    ),
                            )
                            Text(preset.label, maxLines = 1, style = MaterialTheme.typography.labelSmall, color = EmulatorColors.TextPrimary)
                        }
                    }
                }
            }
            OutlinedTextField(
                value = value,
                onValueChange = { onValueChange(it.take(9)) },
                label = { Text("Custom colour (RGB hex)") },
                supportingText = { Text(if (normalized == null) "Enter six hexadecimal digits, e.g. 6E00FF" else "Preview updates before saving") },
                isError = normalized == null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = onSave, enabled = canSave, modifier = Modifier.fillMaxWidth()) {
                Text(if (normalized == null) "Enter a valid colour" else if (canSave) "Save shell colour" else "Shell colour saved")
            }
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = EmulatorColors.TextPrimary)
            Text(valueLabel, style = MaterialTheme.typography.labelMedium, color = EmulatorColors.AccentCyan)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = EmulatorColors.AccentCyan,
                activeTrackColor = EmulatorColors.AccentBlue,
            ),
        )
    }
}
