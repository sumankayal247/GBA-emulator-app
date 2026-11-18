package io.github.sumankayal247.gbaemulator.ui.gameplay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
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

/**
 * Moulded-plastic 8-way multi-touch D-pad supporting diagonals and smooth thumb sliding
 * (ADR-0003).
 *
 * The control is drawn as real hardware rather than a labelled Material button: a soft cast
 * shadow, a raised cross body (the "lip"), a recessed face plate with a bevelled groove, a
 * dished thumb dimple and engraved directional triangles. Every outline comes from
 * [HandheldControlGeometry] so the silhouette can be unit-tested; drawing the body as two
 * overlapping rounded bars is what previously left the arms looking clipped and deformed.
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
    // One Shape drives the drop shadow, so it has to trace the same moulded outline the Canvas
    // fills. Remembered because the outline is rebuilt per draw otherwise.
    val shape: Shape = remember {
        GenericShape { size, _ -> addPath(HandheldControlGeometry.dpadBodyPath(size)) }
    }

    Box(
        modifier = modifier
            .size(sizeDp)
            .graphicsLayer {
                val pressedDepth = if (anyDirectionPressed) 0.988f else 1f
                scaleX = pressedDepth
                scaleY = pressedDepth
                translationY = if (anyDirectionPressed) 1.5f else 0f
            }
            .alpha(opacity)
            .shadow(
                elevation = if (anyDirectionPressed) 3.dp else 9.dp,
                shape = shape,
                clip = false,
                ambientColor = palette.castShadow,
                spotColor = palette.castShadow,
            )
            .semantics { contentDescription = "Directional pad" }
            .handheldDpadGestures(interactive, tracker, onInputChanged),
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            val body = HandheldControlGeometry.dpadBodyPath(size)
            val face = HandheldControlGeometry.dpadFacePath(size)
            val rimPx = minOf(w, h) * HandheldControlGeometry.DPAD_FACE_INSET_FRACTION

            // 1. Raised body. A vertical gradient from a lit top edge to a shadowed bottom edge
            //    is what makes the lip read as a physical step around the face plate.
            drawPath(
                path = body,
                brush = Brush.verticalGradient(
                    0f to palette.controlLipTop,
                    0.55f to palette.controlFaceTop,
                    1f to palette.controlLipBottom,
                ),
            )
            // Darken the very bottom of the lip so the plate sits on a visible shelf.
            drawPath(
                path = body,
                brush = Brush.verticalGradient(
                    0.82f to Color.Transparent,
                    1f to palette.controlEdgeShade,
                ),
            )

            // 2. Recessed face plate, lit from the top-left like the reference hardware.
            val faceBrush = if (anyDirectionPressed) {
                Brush.verticalGradient(
                    0f to palette.controlFacePressedTop,
                    1f to palette.controlFacePressedBottom,
                )
            } else {
                Brush.verticalGradient(
                    0f to palette.controlFaceTop,
                    0.62f to palette.controlFaceBottom,
                    1f to palette.controlFacePressedBottom,
                )
            }
            drawPath(path = face, brush = faceBrush)

            // 3. Bevel: bright along the top/left of the plate, shaded along the bottom/right.
            drawPath(
                path = face,
                color = palette.controlEdgeLight,
                style = Stroke(width = maxOf(1f, rimPx * 0.30f)),
            )
            drawPath(
                path = face,
                brush = Brush.verticalGradient(
                    0.55f to Color.Transparent,
                    1f to palette.controlEdgeShade,
                ),
                style = Stroke(width = maxOf(1f, rimPx * 0.62f)),
            )
            // Hairline groove where the plate meets the lip.
            drawPath(
                path = body,
                color = palette.controlGroove,
                style = Stroke(width = maxOf(1f, rimPx * 0.22f)),
            )

            // 4. Engraved directional triangles. The sunk cut is sold by a lighter fill that
            //    peeks out below the ink, so only the lower edges catch the light (an outline
            //    around the whole glyph would read as a printed sticker instead of a moulding).
            val engraveDrop = maxOf(0.9f, rimPx * 0.20f)
            DpadDirection.entries.forEach { direction ->
                val button = direction.toVirtualButton()
                val arrow = HandheldControlGeometry.arrowPath(size, direction)
                translate(0f, engraveDrop) {
                    drawPath(path = arrow, color = palette.controlEngraveShadow)
                }
                drawPath(
                    path = arrow,
                    color = if (button in pressedButtons) {
                        palette.controlEngravePressed
                    } else {
                        palette.controlEngrave
                    },
                )
            }

            // 5. Dished thumb dimple with a polished inner wall.
            val dimpleRadius = minOf(w, h) * HandheldControlGeometry.DPAD_ARM_FRACTION *
                HandheldControlGeometry.DPAD_DIMPLE_RADIUS_FRACTION
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(palette.controlFacePressedBottom, palette.controlLipBottom),
                    center = Offset(w * 0.5f, h * 0.5f),
                    radius = dimpleRadius,
                ),
                radius = dimpleRadius,
                center = Offset(w * 0.5f, h * 0.5f),
            )
            drawCircle(
                color = palette.controlEdgeLight,
                radius = dimpleRadius,
                center = Offset(w * 0.5f, h * 0.5f),
                style = Stroke(width = maxOf(1f, rimPx * 0.22f)),
            )
            drawCircle(
                color = palette.controlEdgeShade.copy(alpha = 0.30f),
                radius = dimpleRadius * 0.66f,
                center = Offset(w * 0.5f, h * 0.5f + dimpleRadius * 0.10f),
            )
        }
    }
}

private fun DpadDirection.toVirtualButton(): VirtualButton = when (this) {
    DpadDirection.UP -> VirtualButton.UP
    DpadDirection.RIGHT -> VirtualButton.RIGHT
    DpadDirection.DOWN -> VirtualButton.DOWN
    DpadDirection.LEFT -> VirtualButton.LEFT
}

/**
 * Multi-touch circular A / B button: a moulded plastic disc with the letter engraved into it.
 */
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
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .semantics {
                contentDescription = "${button.label} button"
                role = Role.Button
            }
            .handheldButtonGestures(interactive, tracker, button, onInputChanged),
    ) {
        PremiumRoundButtonSurface(
            modifier = Modifier,
            diameter = diameter,
            isPressed = isPressed,
            opacity = opacity,
        )
        // The letter is moulded into the plastic rather than printed on top of it.
        EngravedPlasticLabel(
            text = button.label,
            fontSize = (diameter.value * 0.34f).sp,
            ink = if (isPressed) palette.controlEngravePressed else palette.controlEngrave,
            lip = palette.controlEngraveShadow,
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
        EngravedPlasticLabel(
            text = button.label,
            fontSize = 15.sp,
            ink = if (isPressed) palette.controlEngravePressed else palette.controlEngrave,
            lip = palette.controlEngraveShadow,
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
            // No lettering on the physical button; the label is engraved into the shell.
            PremiumRoundButtonSurface(
                modifier = Modifier,
                diameter = 30.dp,
                isPressed = isPressed,
                opacity = 1f,
            )
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
            )
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = null,
                tint = palette.controlEngrave,
                modifier = Modifier.size(15.dp),
            )
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

/**
 * Shared round control: a matte moulded-plastic button.
 *
 * Built from the same layers as the reference hardware: a shadowed socket, a raised lip, a
 * slightly domed face plate and a bevelled edge. Deliberately no chrome specular ring, because
 * the matte look is what keeps the controls reading as part of the shell.
 *
 * [isPressed] is a plain Boolean so the surface can be previewed and screenshot-tested.
 */
@Composable
private fun PremiumRoundButtonSurface(
    modifier: Modifier,
    diameter: Dp,
    isPressed: Boolean,
    opacity: Float,
) {
    val palette = LocalHandheldPalette.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(diameter)
            .graphicsLayer {
                val pressedDepth = if (isPressed) 0.972f else 1f
                scaleX = pressedDepth
                scaleY = pressedDepth
                translationY = if (isPressed) 1.5f else 0f
            }
            .alpha(opacity)
            .shadow(
                elevation = if (isPressed) 2.dp else 8.dp,
                shape = CircleShape,
                clip = false,
                ambientColor = palette.castShadow,
                spotColor = palette.castShadow,
            ),
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val center = Offset(size.width * 0.5f, size.height * 0.5f)
            val d = minOf(size.width, size.height)
            val rim = d * 0.055f
            val faceRadius = d * 0.5f - rim

            // Shadowed socket the button sits in.
            drawCircle(
                color = palette.controlEdgeShade.copy(alpha = 0.55f),
                radius = d * 0.5f,
                center = center.copy(y = center.y + d * 0.012f),
            )
            // Raised lip.
            drawCircle(
                brush = Brush.verticalGradient(
                    0f to palette.controlLipTop,
                    1f to palette.controlLipBottom,
                ),
                radius = d * 0.5f,
                center = center,
            )
            // Face plate, lit from the top-left.
            drawCircle(
                brush = if (isPressed) {
                    Brush.linearGradient(
                        colors = listOf(palette.controlFacePressedTop, palette.controlFacePressedBottom),
                        start = Offset(center.x - faceRadius, center.y - faceRadius),
                        end = Offset(center.x + faceRadius, center.y + faceRadius),
                    )
                } else {
                    Brush.linearGradient(
                        colors = listOf(
                            palette.controlLipTop,
                            palette.controlFaceTop,
                            palette.controlFaceBottom,
                        ),
                        start = Offset(center.x - faceRadius, center.y - faceRadius),
                        end = Offset(center.x + faceRadius, center.y + faceRadius * 1.1f),
                    )
                },
                radius = faceRadius,
                center = center,
            )
            // Bevelled edge between the lip and the flat face.
            drawCircle(
                color = palette.controlEdgeLight,
                radius = faceRadius,
                center = center,
                style = Stroke(width = maxOf(1f, rim * 0.34f)),
            )
            drawCircle(
                color = palette.controlEdgeShade.copy(alpha = 0.34f),
                radius = d * 0.5f - maxOf(1f, rim * 0.16f),
                center = center,
                style = Stroke(width = maxOf(1f, rim * 0.26f)),
            )
        }
    }
}

/** Engraved (cut-in) plastic lettering: an ink fill with a light lip on its lower edge. */
@Composable
private fun EngravedPlasticLabel(
    text: String,
    fontSize: TextUnit,
    ink: Color,
    lip: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        fontSize = fontSize,
        fontWeight = FontWeight.Medium,
        color = ink,
        style = TextStyle(
            shadow = Shadow(color = lip, offset = Offset(0f, 1.2f), blurRadius = 0.7f),
        ),
        modifier = modifier,
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

