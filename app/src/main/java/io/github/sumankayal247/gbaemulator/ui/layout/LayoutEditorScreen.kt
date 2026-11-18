package io.github.sumankayal247.gbaemulator.ui.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Restore
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import io.github.sumankayal247.gbaemulator.domain.model.ControlAnchors
import io.github.sumankayal247.gbaemulator.domain.model.ControlPlacement
import io.github.sumankayal247.gbaemulator.domain.model.ControlSlot
import io.github.sumankayal247.gbaemulator.domain.model.EmulatorPreferences
import io.github.sumankayal247.gbaemulator.domain.model.LayoutPreset
import io.github.sumankayal247.gbaemulator.domain.model.ShellColors
import io.github.sumankayal247.gbaemulator.domain.model.TouchLayoutProfile
import io.github.sumankayal247.gbaemulator.emulator.TouchInputTracker
import io.github.sumankayal247.gbaemulator.ui.gameplay.HandheldScreenPlaceholder
import io.github.sumankayal247.gbaemulator.ui.gameplay.LandscapeHandheldLayout
import io.github.sumankayal247.gbaemulator.ui.gameplay.PortraitHandheldLayout
import io.github.sumankayal247.gbaemulator.ui.gameplay.ProvideHandheldPalette
import io.github.sumankayal247.gbaemulator.ui.gameplay.handheldPalette
import io.github.sumankayal247.gbaemulator.ui.gameplay.handheldShellBrush
import io.github.sumankayal247.gbaemulator.ui.theme.EmulatorColors
import kotlin.math.roundToInt

/**
 * Virtual handheld layout editor.
 *
 * The workflow is deliberately two steps, because the previous screen exposed a wall of sliders
 * for global nudges and still could not place an individual button:
 *
 *  1. Pick a control from the row of chips.
 *  2. Drag it on the preview, then press **Done** to save.
 *
 * Portrait and landscape keep separate profiles. Everything is clamped by
 * [ControlPlacement], so a control cannot be
 * dragged off the handheld.
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
    var selected by remember { mutableStateOf(ControlSlot.DPAD) }
    var shellDraft by remember(preferences.shellColorHex) { mutableStateOf(preferences.shellColorHex) }

    val hasUnsavedChanges = draft != persisted
    val normalizedShell = ShellColors.normalize(shellDraft)

    fun save() {
        if (editingLandscape) onSaveLandscapeLayout(draft) else onSavePortraitLayout(draft)
    }

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
            text = "Tap a control, drag it where you want it, then press Done.",
            style = MaterialTheme.typography.bodySmall,
            color = EmulatorColors.TextSecondary,
        )

        Spacer(Modifier.height(12.dp))

        // Orientation switcher. Drafts are per-orientation, so switching reloads from prefs.
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OrientationChip("Portrait", !editingLandscape) { editingLandscape = false }
            OrientationChip("Landscape", editingLandscape) { editingLandscape = true }
        }

        Spacer(Modifier.height(10.dp))

        // The draggable preview.
        HandheldLayoutPreview(
            profile = draft,
            landscape = editingLandscape,
            shellColorHex = normalizedShell ?: preferences.shellColorHex,
            selected = selected,
            onSlotMoved = { slot, anchors ->
                draft = draft.withAnchor(slot, anchors)
            },
        )

        Spacer(Modifier.height(10.dp))

        Text(
            text = "Selected: ${selected.label}",
            style = MaterialTheme.typography.labelLarge,
            color = EmulatorColors.AccentCyan,
        )
        Text(
            text = "Drag the highlighted control in the preview above.",
            style = MaterialTheme.typography.bodySmall,
            color = EmulatorColors.TextSecondary,
        )

        Spacer(Modifier.height(8.dp))

        // Control picker.
        ControlSlot.ordered.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { slot ->
                    FilterChip(
                        selected = selected == slot,
                        onClick = { selected = slot },
                        label = { Text(slot.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = EmulatorColors.CardSurface,
                            selectedContainerColor = EmulatorColors.AccentBlue.copy(alpha = 0.25f),
                            selectedLabelColor = EmulatorColors.AccentCyan,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        Spacer(Modifier.height(8.dp))

        // Nudge buttons, an accessible fallback for fine placement without a drag.
        NudgePad(
            onNudge = { dx, dy ->
                val landscape = editingLandscape
                val current = draft.anchorsFor(selected, landscape)
                draft = draft.withAnchor(
                    selected,
                    ControlAnchors(
                        (current.x + dx).coerceIn(0f, 1f),
                        (current.y + dy).coerceIn(0f, 1f),
                    ),
                )
            },
            onCentre = {
                draft = draft.withAnchor(selected, ControlAnchors(0.5f, 0.5f))
            },
            enabled = true,
        )

        Spacer(Modifier.height(12.dp))

        // Size + opacity only. The seven offset sliders are gone: they only moved whole groups
        // and are now expressed by dragging the individual control instead.
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
                    onValueChange = { v -> draft = draft.copy(preset = LayoutPreset.CUSTOM, scale = v) },
                )
                LabeledSlider(
                    label = "Control opacity",
                    valueLabel = "${(draft.opacity * 100).roundToInt()}%",
                    value = draft.opacity,
                    range = TouchLayoutProfile.MIN_OPACITY..TouchLayoutProfile.MAX_OPACITY,
                    onValueChange = { v -> draft = draft.copy(preset = LayoutPreset.CUSTOM, opacity = v) },
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        ShellColorEditor(
            value = shellDraft,
            onValueChange = { shellDraft = it },
            onSave = { normalizedShell?.let(onSaveShellColor) },
            canSave = normalizedShell != null && normalizedShell != preferences.shellColorHex,
        )

        Spacer(Modifier.height(12.dp))

        HapticsRow(preferences.hapticsEnabled, onToggleHaptics)

        Spacer(Modifier.height(12.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedButton(
                onClick = {
                    val defaults = if (editingLandscape) {
                        TouchLayoutProfile.defaultLandscape()
                    } else {
                        TouchLayoutProfile.defaultPortrait()
                    }
                    draft = defaults
                },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("Reset")
            }

            Button(
                onClick = { save() },
                enabled = hasUnsavedChanges,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = EmulatorColors.AccentBlue),
                modifier = Modifier
                    .weight(1.4f)
                    .height(48.dp),
            ) {
                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text(
                    text = if (hasUnsavedChanges) "Done" else "Saved",
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun OrientationChip(label: String, selectedState: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selectedState,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = EmulatorColors.CardSurface,
            selectedContainerColor = EmulatorColors.AccentBlue.copy(alpha = 0.25f),
            selectedLabelColor = EmulatorColors.AccentCyan,
        ),
    )
}

/**
 * Nine-way nudge pad. Dragging is the primary interaction, but a nudge pad is far easier to hit
 * than a drag handle for anyone with limited precision, and it gives a keyboard/switch-access
 * path that a drag gesture does not.
 */
@Composable
private fun NudgePad(
    onNudge: (Float, Float) -> Unit,
    onCentre: () -> Unit,
    enabled: Boolean,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        NudgeButton("↑", enabled) { onNudge(0f, -0.02f) }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NudgeButton("←", enabled) { onNudge(-0.02f, 0f) }
            NudgeButton("◎", enabled, onCentre)
            NudgeButton("→", enabled) { onNudge(0.02f, 0f) }
        }
        NudgeButton("↓", enabled) { onNudge(0f, 0.02f) }
    }
}

@Composable
private fun NudgeButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.size(44.dp),
        contentPadding = PaddingValues(0.dp),
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun HapticsRow(enabled: Boolean, onToggle: (Boolean) -> Unit) {
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
                Text("Haptic feedback", style = MaterialTheme.typography.bodyLarge, color = EmulatorColors.TextPrimary)
                Text(
                    "Subtle vibration when pressing touch controls",
                    style = MaterialTheme.typography.bodySmall,
                    color = EmulatorColors.TextSecondary,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = EmulatorColors.TextPrimary,
                    checkedTrackColor = EmulatorColors.AccentBlue,
                ),
            )
        }
    }
}

/**
 * Live handheld preview that hosts the drag handles.
 *
 * The handheld is measured at real device dp and then scaled down as a single layer, exactly as
 * gameplay measures it, so the preview is a faithful miniature rather than a second
 * implementation. The drag handles live inside that same scaled layer, which is what keeps a
 * handle glued to the control it moves at every zoom level.
 */
@Composable
private fun HandheldLayoutPreview(
    profile: TouchLayoutProfile,
    landscape: Boolean,
    shellColorHex: String,
    selected: ControlSlot,
    onSlotMoved: (ControlSlot, ControlAnchors) -> Unit,
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
        var layerScale by remember { mutableStateOf(1f) }

        Layout(
            modifier = Modifier
                .width(previewWidth)
                .height(previewHeight)
                .clip(RoundedCornerShape(12.dp)),
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
                    // A sibling of the handheld inside the same Box, so the handles share its
                    // scaled coordinate space and stay exactly on top of the controls they edit.
                    LayoutEditorDragHandles(
                        profile = profile,
                        landscape = landscape,
                        selected = selected,
                        layerScale = layerScale,
                        onSlotMoved = onSlotMoved,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            },
        ) { measurables, constraints ->
            val realWidth = deviceWidth.dp.roundToPx().coerceAtLeast(1)
            val realHeight = deviceHeight.dp.roundToPx().coerceAtLeast(1)
            val child = measurables.single().measure(Constraints.fixed(realWidth, realHeight))
            val width = constraints.maxWidth
            val height = constraints.maxHeight
            val scale = minOf(width.toFloat() / realWidth, height.toFloat() / realHeight)
            if (layerScale != scale) layerScale = scale
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
            Text(
                "Applies to both orientations",
                style = MaterialTheme.typography.bodySmall,
                color = EmulatorColors.TextSecondary,
            )
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
                            Text(
                                preset.label,
                                maxLines = 1,
                                style = MaterialTheme.typography.labelSmall,
                                color = EmulatorColors.TextPrimary,
                            )
                        }
                    }
                }
            }
            OutlinedTextField(
                value = value,
                onValueChange = { onValueChange(it.take(9)) },
                label = { Text("Custom colour (RGB hex)") },
                supportingText = {
                    Text(
                        if (normalized == null) "Enter six hexadecimal digits, e.g. 6E00FF"
                        else "Preview updates before saving",
                    )
                },
                isError = normalized == null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = onSave, enabled = canSave, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (normalized == null) "Enter a valid colour"
                    else if (canSave) "Save shell colour" else "Shell colour saved",
                )
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
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
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
