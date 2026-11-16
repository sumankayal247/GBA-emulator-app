package io.github.sumankayal247.gbaemulator.ui.gameplay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sumankayal247.gbaemulator.domain.model.VirtualButton
import io.github.sumankayal247.gbaemulator.emulator.TouchInputTracker
import io.github.sumankayal247.gbaemulator.ui.theme.EmulatorColors

/**
 * Sculpted 8-way multi-touch D-pad supporting diagonals and smooth thumb sliding (ADR-0003).
 */
@Composable
fun HandheldDPad(
    tracker: TouchInputTracker,
    pressedButtons: Set<VirtualButton>,
    onInputChanged: () -> Unit,
    sizeDp: Dp = 152.dp,
    opacity: Float = 0.94f,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(sizeDp)
            .alpha(opacity)
            .semantics { contentDescription = "Directional pad" }
            .pointerInput(tracker) {
                awaitPointerEventScope {
                    val activePointers = HashSet<Long>()
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val cx = size.width / 2f
                            val cy = size.height / 2f
                            val radius = minOf(cx, cy).coerceAtLeast(1f)
                            var changed = false

                            for (change in event.changes) {
                                val id = change.id.value
                                if (change.pressed) {
                                    activePointers.add(id)
                                    val dx = ((change.position.x - cx) / radius).coerceIn(-1f, 1f)
                                    val dy = ((change.position.y - cy) / radius).coerceIn(-1f, 1f)
                                    tracker.updateDpadPointer(id, dx, dy)
                                    change.consume()
                                    changed = true
                                } else if (activePointers.remove(id)) {
                                    tracker.releasePointer(id)
                                    change.consume()
                                    changed = true
                                }
                            }
                            if (changed) {
                                onInputChanged()
                            }
                        }
                    } finally {
                        if (activePointers.isNotEmpty()) {
                            for (id in activePointers) {
                                tracker.releasePointer(id)
                            }
                            activePointers.clear()
                            onInputChanged()
                        }
                    }
                }
            },
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            val arm = w * 0.34f
            val offset = (w - arm) / 2f
            val corner = CornerRadius(w * 0.08f, w * 0.08f)

            // Cross outline shadow
            drawRoundRect(
                color = EmulatorColors.ButtonOutline,
                topLeft = Offset(offset - 3f, 2f),
                size = Size(arm + 6f, h - 2f),
                cornerRadius = corner,
            )
            drawRoundRect(
                color = EmulatorColors.ButtonOutline,
                topLeft = Offset(2f, offset - 3f),
                size = Size(w - 2f, arm + 6f),
                cornerRadius = corner,
            )

            // Main sculpted cross body
            val bodyBrush = Brush.verticalGradient(
                listOf(EmulatorColors.ButtonHighlight, EmulatorColors.ButtonFace, EmulatorColors.ButtonShadow),
            )
            drawRoundRect(
                brush = bodyBrush,
                topLeft = Offset(offset, 4f),
                size = Size(arm, h - 8f),
                cornerRadius = corner,
            )
            drawRoundRect(
                brush = bodyBrush,
                topLeft = Offset(4f, offset),
                size = Size(w - 8f, arm),
                cornerRadius = corner,
            )

            // Active directional press highlights
            val pressedTint = EmulatorColors.AccentBlue.copy(alpha = 0.28f)
            if (VirtualButton.UP in pressedButtons) {
                drawRoundRect(
                    color = pressedTint,
                    topLeft = Offset(offset + 2f, 6f),
                    size = Size(arm - 4f, offset),
                    cornerRadius = corner,
                )
            }
            if (VirtualButton.DOWN in pressedButtons) {
                drawRoundRect(
                    color = pressedTint,
                    topLeft = Offset(offset + 2f, offset + arm - 2f),
                    size = Size(arm - 4f, offset),
                    cornerRadius = corner,
                )
            }
            if (VirtualButton.LEFT in pressedButtons) {
                drawRoundRect(
                    color = pressedTint,
                    topLeft = Offset(6f, offset + 2f),
                    size = Size(offset, arm - 4f),
                    cornerRadius = corner,
                )
            }
            if (VirtualButton.RIGHT in pressedButtons) {
                drawRoundRect(
                    color = pressedTint,
                    topLeft = Offset(offset + arm - 2f, offset + 2f),
                    size = Size(offset, arm - 4f),
                    cornerRadius = corner,
                )
            }

            // Center concave thumb dimple
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(EmulatorColors.ButtonShadow, EmulatorColors.ButtonFace),
                    center = Offset(w * 0.5f, h * 0.5f),
                    radius = arm * 0.36f,
                ),
                radius = arm * 0.32f,
                center = Offset(w * 0.5f, h * 0.5f),
            )

            // Crisp directional arrows
            fun arrowColor(btn: VirtualButton) =
                if (btn in pressedButtons) EmulatorColors.AccentBlue else EmulatorColors.ButtonLabel

            val arrowSize = arm * 0.28f
            // Up triangle
            drawPath(
                Path().apply {
                    moveTo(w * 0.5f, h * 0.11f)
                    lineTo(w * 0.5f - arrowSize, h * 0.11f + arrowSize * 1.4f)
                    lineTo(w * 0.5f + arrowSize, h * 0.11f + arrowSize * 1.4f)
                    close()
                },
                color = arrowColor(VirtualButton.UP),
            )
            // Down triangle
            drawPath(
                Path().apply {
                    moveTo(w * 0.5f, h * 0.89f)
                    lineTo(w * 0.5f - arrowSize, h * 0.89f - arrowSize * 1.4f)
                    lineTo(w * 0.5f + arrowSize, h * 0.89f - arrowSize * 1.4f)
                    close()
                },
                color = arrowColor(VirtualButton.DOWN),
            )
            // Left triangle
            drawPath(
                Path().apply {
                    moveTo(w * 0.11f, h * 0.5f)
                    lineTo(w * 0.11f + arrowSize * 1.4f, h * 0.5f - arrowSize)
                    lineTo(w * 0.11f + arrowSize * 1.4f, h * 0.5f + arrowSize)
                    close()
                },
                color = arrowColor(VirtualButton.LEFT),
            )
            // Right triangle
            drawPath(
                Path().apply {
                    moveTo(w * 0.89f, h * 0.5f)
                    lineTo(w * 0.89f - arrowSize * 1.4f, h * 0.5f - arrowSize)
                    lineTo(w * 0.89f - arrowSize * 1.4f, h * 0.5f + arrowSize)
                    close()
                },
                color = arrowColor(VirtualButton.RIGHT),
            )
        }
    }
}

/**
 * Multi-touch circular A / B handheld button.
 */
@Composable
fun HandheldActionButton(
    button: VirtualButton,
    tracker: TouchInputTracker,
    isPressed: Boolean,
    onInputChanged: () -> Unit,
    diameter: Dp = 68.dp,
    opacity: Float = 0.94f,
    modifier: Modifier = Modifier,
) {
    val bgBrush = if (isPressed) {
        Brush.verticalGradient(listOf(EmulatorColors.ButtonFacePressed, EmulatorColors.ButtonShadow))
    } else {
        Brush.verticalGradient(listOf(EmulatorColors.ButtonHighlight, EmulatorColors.ButtonFace, EmulatorColors.ButtonShadow))
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(diameter)
            .alpha(opacity)
            .clip(CircleShape)
            .background(bgBrush)
            .border(2.dp, EmulatorColors.ButtonOutline, CircleShape)
            .semantics {
                contentDescription = "${button.label} button"
                role = Role.Button
            }
            .pointerInput(tracker, button) {
                awaitPointerEventScope {
                    val activePointers = HashSet<Long>()
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            var changed = false
                            for (change in event.changes) {
                                val id = change.id.value
                                if (change.pressed) {
                                    if (activePointers.add(id)) {
                                        tracker.updateButtonPointer(id, button, true)
                                        changed = true
                                    }
                                    change.consume()
                                } else if (activePointers.remove(id)) {
                                    tracker.releasePointer(id)
                                    change.consume()
                                    changed = true
                                }
                            }
                            if (changed) onInputChanged()
                        }
                    } finally {
                        if (activePointers.isNotEmpty()) {
                            for (id in activePointers) tracker.releasePointer(id)
                            activePointers.clear()
                            onInputChanged()
                        }
                    }
                }
            },
    ) {
        Text(
            text = button.label,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = if (isPressed) EmulatorColors.AccentBlue else EmulatorColors.ButtonLabel,
        )
    }
}

/**
 * Angled handheld L / R shoulder button matching references 01 & 02.
 */
@Composable
fun HandheldShoulderButton(
    button: VirtualButton,
    isLeftShoulder: Boolean,
    tracker: TouchInputTracker,
    isPressed: Boolean,
    onInputChanged: () -> Unit,
    widthDp: Dp = 104.dp,
    heightDp: Dp = 36.dp,
    opacity: Float = 0.94f,
    modifier: Modifier = Modifier,
) {
    val shape = GenericShape { size, _ ->
        val slant = size.width * 0.16f
        if (isLeftShoulder) {
            moveTo(size.height * 0.35f, 0f)
            lineTo(size.width, 0f)
            lineTo(size.width - slant, size.height)
            lineTo(size.height * 0.35f, size.height)
            quadraticTo(0f, size.height * 0.5f, size.height * 0.35f, 0f)
        } else {
            moveTo(0f, 0f)
            lineTo(size.width - size.height * 0.35f, 0f)
            quadraticTo(size.width, size.height * 0.5f, size.width - size.height * 0.35f, size.height)
            lineTo(slant, size.height)
            close()
        }
    }

    val bgBrush = if (isPressed) {
        Brush.verticalGradient(listOf(EmulatorColors.ButtonFacePressed, EmulatorColors.ButtonShadow))
    } else {
        Brush.verticalGradient(listOf(EmulatorColors.ButtonHighlight, EmulatorColors.ButtonFace, EmulatorColors.ButtonShadow))
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .width(widthDp)
            .height(heightDp)
            .alpha(opacity)
            .clip(shape)
            .background(bgBrush)
            .border(1.8.dp, EmulatorColors.ButtonOutline, shape)
            .semantics {
                contentDescription = "${button.label} shoulder button"
                role = Role.Button
            }
            .pointerInput(tracker, button) {
                awaitPointerEventScope {
                    val activePointers = HashSet<Long>()
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            var changed = false
                            for (change in event.changes) {
                                val id = change.id.value
                                if (change.pressed) {
                                    if (activePointers.add(id)) {
                                        tracker.updateButtonPointer(id, button, true)
                                        changed = true
                                    }
                                    change.consume()
                                } else if (activePointers.remove(id)) {
                                    tracker.releasePointer(id)
                                    change.consume()
                                    changed = true
                                }
                            }
                            if (changed) onInputChanged()
                        }
                    } finally {
                        if (activePointers.isNotEmpty()) {
                            for (id in activePointers) tracker.releasePointer(id)
                            activePointers.clear()
                            onInputChanged()
                        }
                    }
                }
            },
    ) {
        Text(
            text = button.label,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = if (isPressed) EmulatorColors.AccentBlue else EmulatorColors.ButtonLabel,
        )
    }
}

/**
 * Multi-touch Select / Start round handheld button with engraved shell label.
 */
@Composable
fun HandheldSmallTouchButton(
    button: VirtualButton,
    tracker: TouchInputTracker,
    isPressed: Boolean,
    onInputChanged: () -> Unit,
    opacity: Float = 0.94f,
    modifier: Modifier = Modifier,
) {
    val bgBrush = if (isPressed) {
        Brush.verticalGradient(listOf(EmulatorColors.ButtonFacePressed, EmulatorColors.ButtonShadow))
    } else {
        Brush.verticalGradient(listOf(EmulatorColors.ButtonHighlight, EmulatorColors.ButtonFace, EmulatorColors.ButtonShadow))
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .alpha(opacity)
            .semantics {
                contentDescription = "${button.label} button"
                role = Role.Button
            }
            .pointerInput(tracker, button) {
                awaitPointerEventScope {
                    val activePointers = HashSet<Long>()
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            var changed = false
                            for (change in event.changes) {
                                val id = change.id.value
                                if (change.pressed) {
                                    if (activePointers.add(id)) {
                                        tracker.updateButtonPointer(id, button, true)
                                        changed = true
                                    }
                                    change.consume()
                                } else if (activePointers.remove(id)) {
                                    tracker.releasePointer(id)
                                    change.consume()
                                    changed = true
                                }
                            }
                            if (changed) onInputChanged()
                        }
                    } finally {
                        if (activePointers.isNotEmpty()) {
                            for (id in activePointers) tracker.releasePointer(id)
                            activePointers.clear()
                            onInputChanged()
                        }
                    }
                }
            },
    ) {
        // 48dp accessible hit envelope wrapping the visual handheld button
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(48.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(bgBrush)
                    .border(1.8.dp, EmulatorColors.ButtonOutline, CircleShape),
            )
        }
        Text(
            text = button.label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFD5B8FF),
        )
    }
}

/**
 * In-game Menu button matching references 01 & 02.
 */
@Composable
fun HandheldMenuButton(
    onClick: () -> Unit,
    opacity: Float = 0.94f,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .alpha(opacity)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Open game menu" },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(48.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.verticalGradient(
                            listOf(EmulatorColors.ButtonHighlight, EmulatorColors.ButtonFace, EmulatorColors.ButtonShadow),
                        ),
                    )
                    .border(1.8.dp, EmulatorColors.ButtonOutline, CircleShape),
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = null,
                    tint = EmulatorColors.ButtonLabel,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Text(
            text = "MENU",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFD5B8FF),
        )
    }
}

/**
 * Decorative angled handheld speaker slits inspired by references 01 & 02.
 */
@Composable
fun HandheldSpeakerGrille(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(width = 64.dp, height = 44.dp)) {
        val count = 5
        val spacing = size.height / (count + 1)
        for (i in 0 until count) {
            val y = spacing * (i + 1)
            drawLine(
                color = EmulatorColors.ButtonOutline.copy(alpha = 0.8f),
                start = Offset(4f, y + 6f),
                end = Offset(size.width - 4f, y - 6f),
                strokeWidth = 4.5f,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = EmulatorColors.ShellPurpleLight.copy(alpha = 0.35f),
                start = Offset(4f, y + 9f),
                end = Offset(size.width - 4f, y - 3f),
                strokeWidth = 1.5f,
                cap = StrokeCap.Round,
            )
        }
    }
}
