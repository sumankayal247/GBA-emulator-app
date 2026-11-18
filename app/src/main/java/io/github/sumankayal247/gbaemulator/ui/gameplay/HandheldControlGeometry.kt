package io.github.sumankayal247.gbaemulator.ui.gameplay

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import kotlin.math.max
import kotlin.math.min

/**
 * Geometry for the moulded-plastic touch controls.
 *
 * The outlines live here, away from the composables, for two reasons:
 *
 * 1. A single traced cross with real concave fillets at the four junctions replaces the old
 *    "draw one bar, then draw the other" body. Because every layer is now clipped to this one
 *    outline, nothing can spill outside the silhouette; the previous hard-edged highlight and
 *    shadow rectangles were what left the bottom arm looking clipped and deformed.
 * 2. The outline is a list of points ([crossOutline]), so the exact shape the finger sees can be
 *    asserted from a plain JVM unit test without touching android.graphics.
 *
 * All measurements are fractions of the control's bounding box, matching the matte D-pad
 * reference in `docs/reference-images/09-control-art-matte-dpad.jpg` (a two-plate moulded cross:
 * raised arms plus a slightly recessed face plate, i.e. a rim of even thickness on all sides).
 */
internal object HandheldControlGeometry {

    /** Cross arm thickness as a fraction of the D-pad's edge length (reference ≈ 0.315). */
    const val DPAD_ARM_FRACTION = 0.315f

    /** Fillet radius at the outer tip of each arm. */
    const val DPAD_TIP_RADIUS_FRACTION = 0.095f

    /** Concave fillet where an arm meets its neighbours (the "crotch" of the cross). */
    const val DPAD_JUNCTION_RADIUS_FRACTION = 0.165f

    /** Depth of the recessed face plate, as a fraction of the edge length. */
    const val DPAD_FACE_INSET_FRACTION = 0.030f

    /** Length of a directional arrow, as a fraction of the arm thickness. */
    const val DPAD_ARROW_HEIGHT_FRACTION = 0.48f

    /** Half-width of a directional arrow, as a fraction of the arm thickness. */
    const val DPAD_ARROW_HALF_WIDTH_FRACTION = 0.325f

    /** Distance from the D-pad centre to the near edge of each arrow, in arm thicknesses. */
    const val DPAD_ARROW_INSET_FRACTION = 0.58f

    /** Radius of the thumb dimple, as a fraction of the arm thickness. */
    const val DPAD_DIMPLE_RADIUS_FRACTION = 0.185f

    /** Steps used to flatten each quadratic fillet. 14 lies well within a pixel at 150 dp. */
    const val FILLET_STEPS = 14

    /**
     * Outline of the moulded cross body, optionally shrunk by [inset] pixels so the same routine
     * can produce both the raised lip and the recessed face plate. [inset] is subtracted from
     * every edge and every outer fillet, so the two plates stay concentric and the rim keeps an
     * even thickness on all four sides.
     *
     * The points are traced clockwise exactly once, starting just right of the top-left fillet.
     */
    fun crossOutline(size: Size, inset: Float = 0f, steps: Int = FILLET_STEPS): List<Offset> {
        val edge = min(size.width, size.height).coerceAtLeast(1f)
        // Only the face plate is ever drawn with an inset, and it is a small fraction of the
        // edge. Clamping keeps the outline valid if a future caller asks for something extreme.
        val safeInset = inset.coerceIn(0f, edge * 0.12f)
        val arm = (edge * DPAD_ARM_FRACTION - 2f * safeInset)
            .coerceIn(edge * 0.10f, edge * DPAD_ARM_FRACTION)
        // Both fillets must leave at least a sliver of straight edge on either side of them, or
        // the outline degenerates. Never let them exceed half the arm.
        val filletCeiling = (arm / 2f).coerceAtLeast(0f)
        val tip = (edge * DPAD_TIP_RADIUS_FRACTION - safeInset)
            .coerceIn(0f, filletCeiling)
        val junction = (edge * DPAD_JUNCTION_RADIUS_FRACTION + safeInset)
            .coerceIn(0f, filletCeiling)
        val half = edge / 2f
        val left = half - arm / 2f
        val right = half + arm / 2f
        val top = left
        val bottom = right
        // Outer extents pulled in by [safeInset] so the shrunk copy stays concentric.
        val maxX = size.width - safeInset
        val maxY = size.height - safeInset

        val out = ArrayList<Offset>(40 + 8 * steps)
        var cursor = Offset.Zero

        fun lineTo(x: Float, y: Float) {
            cursor = Offset(x, y)
            out.add(cursor)
        }

        fun quadTo(cx: Float, cy: Float, x: Float, y: Float) {
            val sx = cursor.x
            val sy = cursor.y
            for (i in 1..steps) {
                val t = i.toFloat() / steps
                val u = 1f - t
                out.add(
                    Offset(
                        u * u * sx + 2f * u * t * cx + t * t * x,
                        u * u * sy + 2f * u * t * cy + t * t * y,
                    ),
                )
            }
            cursor = Offset(x, y)
        }

        lineTo(left + tip, safeInset)                       // top edge of the top arm
        lineTo(right - tip, safeInset)
        quadTo(right, safeInset, right, safeInset + tip)        // outer tip, top-right
        lineTo(right, top - junction)                   // down the right side of the top arm
        quadTo(right, top, right + junction, top)       // concave fillet into the right arm
        lineTo(maxX - tip, top)                         // top edge of the right arm
        quadTo(maxX, top, maxX, top + tip)
        lineTo(maxX, bottom - tip)
        quadTo(maxX, bottom, maxX - tip, bottom)
        lineTo(right + junction, bottom)                // bottom edge of the right arm
        quadTo(right, bottom, right, bottom + junction)
        lineTo(right, maxY - tip)                       // right side of the bottom arm
        quadTo(right, maxY, right - tip, maxY)
        lineTo(left + tip, maxY)                        // bottom edge of the bottom arm
        quadTo(left, maxY, left, maxY - tip)
        lineTo(left, bottom + junction)
        quadTo(left, bottom, left - junction, bottom)
        lineTo(tip, bottom)                             // bottom edge of the left arm
        quadTo(safeInset, bottom, safeInset, bottom - tip)
        lineTo(safeInset, top + tip)
        quadTo(safeInset, top, safeInset + tip, top)
        lineTo(left - junction, top)                    // top edge of the left arm
        quadTo(left, top, left, top - junction)
        lineTo(left, safeInset + tip)                       // left side of the top arm
        quadTo(left, safeInset, left + tip, safeInset)
        return out
    }

    /** Path for [crossOutline], used by the composables. */
    fun crossPath(size: Size, inset: Float = 0f): Path = pathOf(crossOutline(size, inset))

    /** The raised lip: the full moulded body. */
    fun dpadBodyPath(size: Size): Path = crossPath(size)

    /** The recessed face plate that sits inside the lip. */
    fun dpadFacePath(size: Size): Path =
        crossPath(size, min(size.width, size.height) * DPAD_FACE_INSET_FRACTION)

    /**
     * Isosceles directional arrow pointing along [direction]: the apex sits half an arrow-height
     * beyond [DPAD_ARROW_INSET_FRACTION] and the base trails half a height behind the apex.
     */
    fun arrowOutline(size: Size, direction: DpadDirection): List<Offset> {
        val edge = min(size.width, size.height)
        val arm = edge * DPAD_ARM_FRACTION
        val height = arm * DPAD_ARROW_HEIGHT_FRACTION
        val halfWidth = arm * DPAD_ARROW_HALF_WIDTH_FRACTION
        val near = arm * DPAD_ARROW_INSET_FRACTION + height / 2f
        val cx = size.width / 2f
        val cy = size.height / 2f
        return when (direction) {
            DpadDirection.UP -> listOf(
                Offset(cx, cy - near - height / 2f),
                Offset(cx - halfWidth, cy - near + height / 2f),
                Offset(cx + halfWidth, cy - near + height / 2f),
            )
            DpadDirection.DOWN -> listOf(
                Offset(cx, cy + near + height / 2f),
                Offset(cx - halfWidth, cy + near - height / 2f),
                Offset(cx + halfWidth, cy + near - height / 2f),
            )
            DpadDirection.LEFT -> listOf(
                Offset(cx - near - height / 2f, cy),
                Offset(cx - near + height / 2f, cy - halfWidth),
                Offset(cx - near + height / 2f, cy + halfWidth),
            )
            DpadDirection.RIGHT -> listOf(
                Offset(cx + near + height / 2f, cy),
                Offset(cx + near - height / 2f, cy - halfWidth),
                Offset(cx + near - height / 2f, cy + halfWidth),
            )
        }
    }

    /** Path for [arrowOutline]. */
    fun arrowPath(size: Size, direction: DpadDirection): Path = pathOf(arrowOutline(size, direction))

    private fun pathOf(points: List<Offset>): Path = Path().apply {
        if (points.isEmpty()) return@apply
        moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
        close()
    }
}

/** Direction of one D-pad arm, ordered clockwise starting at the top. */
internal enum class DpadDirection { UP, RIGHT, DOWN, LEFT }
