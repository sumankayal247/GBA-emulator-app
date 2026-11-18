package io.github.sumankayal247.gbaemulator.ui.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.sumankayal247.gbaemulator.domain.model.ControlAnchors
import io.github.sumankayal247.gbaemulator.domain.model.ControlAnchorsDefaults
import io.github.sumankayal247.gbaemulator.domain.model.ControlPlacement
import io.github.sumankayal247.gbaemulator.domain.model.ControlSlot
import io.github.sumankayal247.gbaemulator.domain.model.ControlZone
import io.github.sumankayal247.gbaemulator.domain.model.TouchLayoutProfile
import io.github.sumankayal247.gbaemulator.ui.gameplay.landscapeControlZone
import io.github.sumankayal247.gbaemulator.ui.gameplay.portraitControlZone
import io.github.sumankayal247.gbaemulator.ui.theme.EmulatorColors

/**
 * Drag handles for the layout editor, laid out **inside** the control zone.
 *
 * Deliberately not an overlay drawn on top of the preview: the handles resolve their positions
 * with the very same [ControlPlacement] call and the same [ControlZone] the real controls use, at
 * real device dp. That removes the whole class of bug where a handle drifts away from the control
 * it is meant to move, because there is only one set of coordinates and one zoom applied to both.
 *
 * The zone is measured at real device dp and the whole handheld (preview + handles) is scaled down
 * as a single layer by the caller, so a drag delta in screen px is divided by [layerScale] to get
 * device dp.
 */
@Composable
fun LayoutEditorDragHandles(
    profile: TouchLayoutProfile,
    landscape: Boolean,
    selected: ControlSlot,
    layerScale: Float,
    onSlotMoved: (ControlSlot, ControlAnchors) -> Unit,
    modifier: Modifier = Modifier,
) {
    val configuration = LocalConfiguration.current
    val shortSide = minOf(configuration.screenWidthDp, configuration.screenHeightDp).coerceAtLeast(1)
    val longSide = maxOf(configuration.screenWidthDp, configuration.screenHeightDp).coerceAtLeast(1)
    val deviceWidth = (if (landscape) longSide else shortSide).toFloat()
    val deviceHeight = (if (landscape) shortSide else longSide).toFloat()

    val zone: ControlZone = if (landscape) {
        landscapeControlZone(deviceWidth, deviceHeight)
    } else {
        portraitControlZone(deviceWidth, deviceHeight)
    }
    val scale = profile.scale
    val safeScale = if (layerScale > 0f) layerScale else 1f
    val resolved = ControlPlacement.resolveAll(
        zone = zone,
        anchors = profile.anchors,
        defaults = ControlAnchorsDefaults.forOrientation(landscape),
        scale = scale,
    )

    Box(modifier = modifier) {
        for (slot in ControlSlot.ordered) {
            val r = resolved.getValue(slot)
            val isSelected = slot == selected
            val handleDp = if (isSelected) 44.dp else 26.dp

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    // Top-left of the handle box, in device dp, matching SlotBox's convention.
                    .offset(
                        x = (r.centerXDp - handleDp.value / 2f).dp,
                        y = (r.centerYDp - handleDp.value / 2f).dp,
                    )
                    .size(handleDp)
                    .clip(RoundedCornerShape(if (isSelected) 10.dp else 6.dp))
                    .background(
                        if (isSelected) EmulatorColors.AccentCyan.copy(alpha = 0.30f)
                        else Color.White.copy(alpha = 0.08f),
                    )
                    .border(
                        width = if (isSelected) 2.dp else 1.dp,
                        color = if (isSelected) {
                            EmulatorColors.AccentCyan
                        } else {
                            Color.White.copy(alpha = 0.30f)
                        },
                        shape = RoundedCornerShape(if (isSelected) 10.dp else 6.dp),
                    )
                    .semantics { contentDescription = "Move ${slot.label}" }
                    .pointerInput(landscape, zone, scale, slot, safeScale) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            val current = ControlPlacement.resolve(
                                zone = zone,
                                slot = slot,
                                anchors = profile.anchorsFor(slot, landscape),
                                scale = scale,
                            )
                            // Screen px -> device dp, then straight back to fractions. Clamping
                            // happens inside anchorsFor, so the handle stops at the zone edge.
                            onSlotMoved(
                                slot,
                                ControlPlacement.anchorsFor(
                                    zone = zone,
                                    slot = slot,
                                    centerXDp = current.centerXDp + dragAmount.x / safeScale,
                                    centerYDp = current.centerYDp + dragAmount.y / safeScale,
                                    scale = scale,
                                ),
                            )
                        }
                    },
            ) {
                if (isSelected) {
                    Text(
                        text = slot.label,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}
