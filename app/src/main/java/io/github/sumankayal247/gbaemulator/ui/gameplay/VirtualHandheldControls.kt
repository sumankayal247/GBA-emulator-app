package io.github.sumankayal247.gbaemulator.ui.gameplay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sumankayal247.gbaemulator.domain.model.VirtualButton
import io.github.sumankayal247.gbaemulator.emulator.TouchInputTracker
import io.github.sumankayal247.gbaemulator.ui.theme.EmulatorColors

/**
 * Sculpted 8-way multi-touch D-pad supporting diagonals and smooth thumb sliding (ADR-0003).
 * The layered rim, cast shadow, concave dimple and directional triangles are drawn rather than
 * assembled from a labelled Material button, so the control reads as real handheld hardware.
 */
@Composable
fun HandheldDPad(
    tracker: TouchInputTracker,
    pressedButtons: Set<VirtualButton>,
    onInputChanged: () -> Unit,
    sizeDp: Dp = 152.dp,
    opacity: Float = 0.94f,
    interactive: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val palette = LocalHandheldPalette.current
    val anyDirectionPressed = DIRECTION_BUTTONS.any { it in pressedButtons }
    val pressScale = if (anyDirectionPressed) 0.985f else 1f

    Box(
        modifier = modifier
            .size(sizeDp)
            .graphicsLayer(
                scaleX = pressScale,
                scaleY = pressScale,
                translationY = if (anyDirectionPressed) 2f else 0f,
            )
            .alpha(opacity)
            .shadow(
                elevation = if (anyDirectionPressed) 3.dp else 10.dp,
                shape = HandheldDPadShape,
                clip = false,
                ambientColor = palette.castShadow,
                spotColor = palette.castShadow,
            )
            .clip(HandheldDPadShape)
            .background(
                Brush.verticalGradient(
                    listOf(palette.buttonRimLight, palette.buttonRimMid, palette.buttonRimDark),
                ),
                HandheldDPadShape,
            )
            .border(2.dp, palette.buttonOutline, HandheldDPadShape)
            .semantics { contentDescription = "Directional pad" }
            .handheldDpadGestures(interactive, tracker, onInputChanged),
    ) {
        Canvas(
            modifier = Modifier
                .matchParentSize()
                .padding(3.dp)
                .clip(HandheldDPadShape),
        ) {
            val w = size.width
            val h = size.height
            val arm = w * 0.34f
            val crossOffset = (w - arm) / 2f
            val corner = CornerRadius(w * 0.07f, w * 0.07f)
            val center = Offset(w * 0.5f, h * 0.5f)
            val bodyBrush = if (anyDirectionPressed) {
                Brush.verticalGradient(
                    listOf(palette.buttonFace, palette.buttonFacePressed, palette.buttonShadow),
                )
            } else {
                Brush.verticalGradient(
                    listOf(palette.buttonHighlight, palette.buttonFace, palette.buttonShadow),
                )
            }

            // Two intersecting rounded bars form the raised plastic cross.
            drawRoundRect(
                brush = bodyBrush,
                topLeft = Offset(crossOffset, 0f),
                size = Size(arm, h),
                cornerRadius = corner,
            )
            drawRoundRect(
                brush = bodyBrush,
                topLeft = Offset(0f, crossOffset),
                size = Size(w, arm),
                cornerRadius = corner,
            )

            // Long specular highlight and lower bevel sell the rounded 3D face.
            drawRoundRect(
                color = Color.White.copy(alpha = 0.38f),
                topLeft = Offset(crossOffset + 3f, 3f),
                size = Size(arm - 6f, crossOffset - 4f),
                cornerRadius = corner,
            )
            drawRoundRect(
                color = palette.buttonShadow.copy(alpha = 0.24f),
                topLeft = Offset(crossOffset + 3f, crossOffset + arm + 2f),
                size = Size(arm - 6f, crossOffset - 5f),
                cornerRadius = corner,
            )

            val pressedTint = EmulatorColors.AccentBlue.copy(alpha = 0.28f)
            if (VirtualButton.UP in pressedButtons) {
                drawRoundRect(
                    color = pressedTint,
                    topLeft = Offset(crossOffset + 2f, 3f),
                    size = Size(arm - 4f, crossOffset - 3f),
                    cornerRadius = corner,
                )
            }
            if (VirtualButton.DOWN in pressedButtons) {
                drawRoundRect(
                    color = pressedTint,
                    topLeft = Offset(crossOffset + 2f, crossOffset + arm),
                    size = Size(arm - 4f, crossOffset - 3f),
                    cornerRadius = corner,
                )
            }
            if (VirtualButton.LEFT in pressedButtons) {
                drawRoundRect(
                    color = pressedTint,
                    topLeft = Offset(3f, crossOffset + 2f),
                    size = Size(crossOffset - 3f, arm - 4f),
                    cornerRadius = corner,
                )
            }
            if (VirtualButton.RIGHT in pressedButtons) {
                drawRoundRect(
                    color = pressedTint,
                    topLeft = Offset(crossOffset + arm, crossOffset + 2f),
                    size = Size(crossOffset - 3f, arm - 4f),
                    cornerRadius = corner,
                )
            }

            // Concave thumb dimple with a polished inner ring.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(palette.buttonShadow, palette.buttonFace),
                    center = center,
                    radius = arm * 0.48f,
                ),
                radius = arm * 0.32f,
                center = center,
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.48f),
                radius = arm * 0.31f,
                center = center,
                style = Stroke(width = 1.2.dp.toPx()),
            )
            drawCircle(
                color = palette.buttonOutline.copy(alpha = 0.36f),
                radius = arm * 0.34f,
                center = center,
                style = Stroke(width = 1.dp.toPx()),
            )

            fun arrowColor(btn: VirtualButton) =
                if (btn in pressedButtons) EmulatorColors.AccentBlue else palette.buttonLabel

            val arrowSize = arm * 0.28f
            drawPath(
                Path().apply {
                    moveTo(w * 0.5f, h * 0.11f)
                    lineTo(w * 0.5f - arrowSize, h * 0.11f + arrowSize * 1.4f)
                    lineTo(w * 0.5f + arrowSize, h * 0.11f + arrowSize * 1.4f)
                    close()
                },
                color = arrowColor(VirtualButton.UP),
            )
            drawPath(
                Path().apply {
                    moveTo(w * 0.5f, h * 0.89f)
                    lineTo(w * 0.5f - arrowSize, h * 0.89f - arrowSize * 1.4f)
                    lineTo(w * 0.5f + arrowSize, h * 0.89f - arrowSize * 1.4f)
                    close()
                },
                color = arrowColor(VirtualButton.DOWN),
            )
            drawPath(
                Path().apply {
                    moveTo(w * 0.11f, h * 0.5f)
                    lineTo(w * 0.11f + arrowSize * 1.4f, h * 0.5f - arrowSize)
                    lineTo(w * 0.11f + arrowSize * 1.4f, h * 0.5f + arrowSize)
                    close()
                },
                color = arrowColor(VirtualButton.LEFT),
            )
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

/** Multi-touch circular A / B handheld button with a layered chrome rim and inset face. */
@Composable
fun HandheldActionButton(
    button: VirtualButton,
    tracker: TouchInputTracker,
    isPressed: Boolean,
    onInputChanged: () -> Unit,
    diameter: Dp = 68.dp,
    opacity: Float = 0.94f,
    interactive: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val palette = LocalHandheldPalette.current
    PremiumRoundButtonSurface(
        modifier = modifier
            .semantics {
                contentDescription = "${button.label} button"
                role = Role.Button
            }
            .handheldButtonGestures(interactive, tracker, button, onInputChanged),
        diameter = diameter,
        isPressed = isPressed,
        opacity = opacity,
    ) {
        EmbossedButtonLabel(
            text = button.label,
            fontSize = 22.sp,
            color = if (isPressed) EmulatorColors.AccentBlue else palette.buttonLabel,
            highlight = palette.buttonLabelHighlight,
        )
    }
}

/** Angled handheld L / R shoulder button matching references 01 & 02. */
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
    interactive: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val palette = LocalHandheldPalette.current
    val shape = GenericShape { size, _ ->
        val slant = size.width * 0.16f
        if (isLeftShoulder) {
            moveTo(size.height * 0.35f, 0f)
            lineTo(size.width, 0f)
            lineTo(size.width - slant, size.height)
            lineTo(size.height * 0.35f, size.height)
            quadraticTo(0f, size.height * 0.5f, size.height * 0.35f, 0f)
            close()
        } else {
            moveTo(0f, 0f)
            lineTo(size.width - size.height * 0.35f, 0f)
            quadraticTo(size.width, size.height * 0.5f, size.width - size.height * 0.35f, size.height)
            lineTo(slant, size.height)
            close()
        }
    }
    val bodyBrush = if (isPressed) {
        Brush.verticalGradient(
            listOf(palette.buttonFace, palette.buttonFacePressed, palette.buttonShadow),
        )
    } else {
        Brush.verticalGradient(
            listOf(palette.buttonHighlight, palette.buttonFace, palette.buttonShadow),
        )
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .width(widthDp)
            .height(heightDp)
            .graphicsLayer(
                scaleX = if (isPressed) 0.975f else 1f,
                scaleY = if (isPressed) 0.975f else 1f,
                translationY = if (isPressed) 2f else 0f,
            )
            .alpha(opacity)
            .shadow(
                elevation = if (isPressed) 2.dp else 8.dp,
                shape = shape,
                clip = false,
                ambientColor = palette.castShadow,
                spotColor = palette.castShadow,
            )
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(palette.buttonRimLight, palette.buttonRimMid, palette.buttonRimDark),
                ),
                shape,
            )
            .border(1.8.dp, palette.buttonOutline, shape)
            .semantics {
                contentDescription = "${button.label} shoulder button"
                role = Role.Button
            }
            .handheldButtonGestures(interactive, tracker, button, onInputChanged),
    ) {
        Canvas(
            modifier = Modifier
                .matchParentSize()
                .padding(2.5.dp)
                .clip(shape),
        ) {
            drawRect(brush = bodyBrush, topLeft = Offset.Zero, size = size)
            drawLine(
                color = Color.White.copy(alpha = 0.62f),
                start = Offset(size.height * 0.45f, 3f),
                end = Offset(size.width - size.height * 0.45f, 3f),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawLine(
                color = palette.buttonShadow.copy(alpha = 0.52f),
                start = Offset(size.height * 0.45f, size.height - 3f),
                end = Offset(size.width - size.height * 0.45f, size.height - 3f),
                strokeWidth = 1.8.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        EmbossedButtonLabel(
            text = button.label,
            fontSize = 15.sp,
            color = if (isPressed) EmulatorColors.AccentBlue else palette.buttonLabel,
            highlight = palette.buttonLabelHighlight,
        )
    }
}

/** Multi-touch Select / Start round handheld button with an engraved shell label. */
@Composable
fun HandheldSmallTouchButton(
    button: VirtualButton,
    tracker: TouchInputTracker,
    isPressed: Boolean,
    onInputChanged: () -> Unit,
    opacity: Float = 0.94f,
    interactive: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .alpha(opacity)
            .semantics {
                contentDescription = "${button.label} button"
                role = Role.Button
            }
            .handheldButtonGestures(interactive, tracker, button, onInputChanged),
    ) {
        // 48dp accessible hit envelope wrapping the visual handheld button.
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp)) {
            PremiumRoundButtonSurface(
                modifier = Modifier,
                diameter = 30.dp,
                isPressed = isPressed,
                opacity = 1f,
            ) {
                // No lettering on the physical button; the label is engraved into the shell.
            }
        }
        EngravedShellLabel(text = button.label.uppercase())
    }
}

/** In-game Menu button matching references 01 & 02. */
@Composable
fun HandheldMenuButton(
    onClick: () -> Unit,
    opacity: Float = 0.94f,
    interactive: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val palette = LocalHandheldPalette.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .alpha(opacity)
            .clickable(enabled = interactive, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Open game menu" },
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp)) {
            PremiumRoundButtonSurface(
                modifier = Modifier,
                diameter = 30.dp,
                isPressed = false,
                opacity = 1f,
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = null,
                    tint = palette.buttonLabel,
                    modifier = Modifier.size(17.dp),
                )
            }
        }
        EngravedShellLabel(text = "MENU")
    }
}

/** Decorative angled handheld speaker slits inspired by references 01 & 02. */
@Composable
fun HandheldSpeakerGrille(modifier: Modifier = Modifier) {
    val palette = LocalHandheldPalette.current
    Canvas(modifier = Modifier.size(width = 64.dp, height = 44.dp).then(modifier)) {
        val count = 5
        val spacing = size.height / (count + 1)
        for (i in 0 until count) {
            val y = spacing * (i + 1)
            drawLine(
                color = palette.shellLabelHighlight.copy(alpha = 0.55f),
                start = Offset(4f, y + 10f),
                end = Offset(size.width - 4f, y - 2f),
                strokeWidth = 1.6f,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = palette.shellOutline,
                start = Offset(4f, y + 7f),
                end = Offset(size.width - 4f, y - 5f),
                strokeWidth = 5f,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = Color.Black.copy(alpha = 0.58f),
                start = Offset(4f, y + 8.5f),
                end = Offset(size.width - 4f, y - 3.5f),
                strokeWidth = 2.2f,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** Shared circular button chrome: cast shadow, metal rim, inset bevel and specular ring. */
@Composable
private fun PremiumRoundButtonSurface(
    modifier: Modifier,
    diameter: Dp,
    isPressed: Boolean,
    opacity: Float,
    content: @Composable () -> Unit,
) {
    val palette = LocalHandheldPalette.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(diameter)
            .graphicsLayer(
                scaleX = if (isPressed) 0.965f else 1f,
                scaleY = if (isPressed) 0.965f else 1f,
                translationY = if (isPressed) 2f else 0f,
            )
            .alpha(opacity)
            .shadow(
                elevation = if (isPressed) 2.dp else 9.dp,
                shape = CircleShape,
                clip = false,
                ambientColor = palette.castShadow,
                spotColor = palette.castShadow,
            )
            .clip(CircleShape)
            .background(
                Brush.verticalGradient(
                    listOf(palette.buttonRimLight, palette.buttonRimMid, palette.buttonRimDark),
                ),
                CircleShape,
            )
            .border(1.6.dp, palette.buttonOutline, CircleShape),
    ) {
        Canvas(
            modifier = Modifier
                .matchParentSize()
                .padding(3.dp)
                .clip(CircleShape),
        ) {
            val center = Offset(size.width * 0.5f, size.height * 0.5f)
            val diameterPx = minOf(size.width, size.height)
            val faceBrush = if (isPressed) {
                Brush.radialGradient(
                    colors = listOf(palette.buttonFacePressed, palette.buttonFace, palette.buttonShadow),
                    center = Offset(size.width * 0.58f, size.height * 0.62f),
                    radius = diameterPx * 0.9f,
                )
            } else {
                Brush.radialGradient(
                    colors = listOf(palette.buttonHighlight, palette.buttonFace, palette.buttonShadow),
                    center = Offset(size.width * 0.32f, size.height * 0.22f),
                    radius = diameterPx * 0.95f,
                )
            }
            drawCircle(brush = faceBrush, radius = diameterPx * 0.5f, center = center)
            drawCircle(
                color = Color.White.copy(alpha = if (isPressed) 0.22f else 0.48f),
                radius = diameterPx * 0.42f,
                center = center,
                style = Stroke(width = 1.8.dp.toPx()),
            )
            drawCircle(
                color = palette.buttonShadow.copy(alpha = 0.48f),
                radius = diameterPx * 0.47f,
                center = center,
                style = Stroke(width = 1.dp.toPx()),
            )
        }
        content()
    }
}

@Composable
private fun EmbossedButtonLabel(
    text: String,
    fontSize: TextUnit,
    color: Color,
    highlight: Color,
) {
    Text(
        text = text,
        fontSize = fontSize,
        fontWeight = FontWeight.Bold,
        color = color,
        style = TextStyle(
            shadow = Shadow(
                color = highlight,
                offset = Offset(0f, 1.6f),
                blurRadius = 0.8f,
            ),
        ),
    )
}

@Composable
private fun EngravedShellLabel(text: String) {
    val palette = LocalHandheldPalette.current
    Text(
        text = text,
        color = palette.shellLabel,
        style = MaterialTheme.typography.labelSmall.copy(
            fontWeight = FontWeight.Bold,
            shadow = Shadow(
                color = palette.shellLabelHighlight,
                offset = Offset(0f, 1f),
                blurRadius = 0.6f,
            ),
        ),
    )
}

private fun Modifier.handheldDpadGestures(
    enabled: Boolean,
    tracker: TouchInputTracker,
    onInputChanged: () -> Unit,
): Modifier = if (!enabled) {
    this
} else {
    pointerInput(tracker) {
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
    }
}

private fun Modifier.handheldButtonGestures(
    enabled: Boolean,
    tracker: TouchInputTracker,
    button: VirtualButton,
    onInputChanged: () -> Unit,
): Modifier = if (!enabled) {
    this
} else {
    pointerInput(tracker, button) {
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
    }
}

private val DIRECTION_BUTTONS = setOf(
    VirtualButton.UP,
    VirtualButton.DOWN,
    VirtualButton.LEFT,
    VirtualButton.RIGHT,
)

private val HandheldDPadShape = GenericShape { size, _ ->
    val arm = size.width * 0.34f
    val left = (size.width - arm) / 2f
    val right = left + arm
    val top = (size.height - arm) / 2f
    val bottom = top + arm
    val radius = minOf(size.width, size.height) * 0.075f

    moveTo(left + radius, 0f)
    lineTo(right - radius, 0f)
    quadraticTo(right, 0f, right, radius)
    lineTo(right, top - radius)
    quadraticTo(right, top, right + radius, top)
    lineTo(size.width - radius, top)
    quadraticTo(size.width, top, size.width, top + radius)
    lineTo(size.width, bottom - radius)
    quadraticTo(size.width, bottom, size.width - radius, bottom)
    lineTo(right + radius, bottom)
    quadraticTo(right, bottom, right, bottom + radius)
    lineTo(right, size.height - radius)
    quadraticTo(right, size.height, right - radius, size.height)
    lineTo(left + radius, size.height)
    quadraticTo(left, size.height, left, size.height - radius)
    lineTo(left, bottom + radius)
    quadraticTo(left, bottom, left - radius, bottom)
    lineTo(radius, bottom)
    quadraticTo(0f, bottom, 0f, bottom - radius)
    lineTo(0f, top + radius)
    quadraticTo(0f, top, radius, top)
    lineTo(left - radius, top)
    quadraticTo(left, top, left, top + radius)
    lineTo(left, radius)
    quadraticTo(left, 0f, left + radius, 0f)
    close()
}
