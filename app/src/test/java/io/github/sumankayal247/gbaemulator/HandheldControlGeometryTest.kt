package io.github.sumankayal247.gbaemulator

import io.github.sumankayal247.gbaemulator.ui.gameplay.DpadDirection
import io.github.sumankayal247.gbaemulator.ui.gameplay.HandheldControlGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Tests for the moulded-plastic D-pad outline introduced with the control restyle.
 *
 * Background on the defect being replaced: the old pad built its body from two overlapping
 * rounded rectangles (one vertical bar, one horizontal bar). Where the bars met, both rounded
 * corners curved away from each other, so the union of the two bars had a small notch at each of
 * the four junctions and the arms read as clipped/deformed. On top of that, hard-edged highlight
 * and shadow rectangles were painted per arm and simply stopped part-way, which made the bottom
 * arm look truncated.
 *
 * The replacement is a single traced cross with a real concave fillet at every junction, drawn as
 * one path. These tests pin the properties that construction depends on: the outline stays inside
 * its box, is symmetric left/right and top/bottom, never folds back through itself, keeps a
 * visible rim around the recessed face plate on all four sides, and keeps the directional glyphs
 * inside their arm and clear of the thumb dimple.
 */
class HandheldControlGeometryTest {

    private val edgeSizes = listOf(152f, 146f, 138f, 121f, 96f)

    @Test
    fun outlineStaysInsideItsBoundingBox() {
        edgeSizes.forEach { edge ->
            listOf(0f, edge * HandheldControlGeometry.DPAD_FACE_INSET_FRACTION).forEach { inset ->
                HandheldControlGeometry.crossOutline(composeSize(edge), inset).forEach { point ->
                    assertTrue(
                        "x out of bounds at $edge/$inset: ${point.x}",
                        point.x >= -0.01f && point.x <= edge + 0.01f,
                    )
                    assertTrue(
                        "y out of bounds at $edge/$inset: ${point.y}",
                        point.y >= -0.01f && point.y <= edge + 0.01f,
                    )
                }
            }
        }
    }

    @Test
    fun outlineIsSymmetricLeftRightAndTopBottom() {
        // Sampled on a grid rather than point-to-point: the outline only stores vertices for its
        // straight runs, so comparing raw vertices would report false asymmetry.
        edgeSizes.forEach { edge ->
            listOf(0f, edge * HandheldControlGeometry.DPAD_FACE_INSET_FRACTION).forEach { inset ->
                val points = HandheldControlGeometry.crossOutline(composeSize(edge), inset).toTestPoints()
                val grid = 48
                var mismatches = 0
                for (ix in 0 until grid) {
                    for (iy in 0 until grid) {
                        val x = (ix + 0.5f) / grid * edge
                        val y = (iy + 0.5f) / grid * edge
                        val reference = isInside(points, TestPoint(x, y))
                        if (reference != isInside(points, TestPoint(edge - x, y))) mismatches++
                        if (reference != isInside(points, TestPoint(x, edge - y))) mismatches++
                    }
                }
                assertEquals("outline is not symmetric at $edge/$inset", 0, mismatches)
            }
        }
    }

    /**
     * Each arm must be continuous: a scan down the middle column (and across the middle row) has
     * to be plastic for one unbroken run all the way to the edges of the box. A folded or
     * self-intersecting outline shows up here as a gap, which is the failure mode this outline had
     * while it was being written (the last junction fillet initially turned the wrong way).
     */
    @Test
    fun eachArmIsOneUnbrokenRunAcrossTheMiddle() {
        val edge = 146f
        val points = HandheldControlGeometry.crossOutline(composeSize(edge)).toTestPoints()
        assertUnbrokenScan(points, edge, Axis.VERTICAL)
        assertUnbrokenScan(points, edge, Axis.HORIZONTAL)
    }

    @Test
    fun facePlateIsInsetFromTheBodyOnAllFourSides() {
        val edge = 146f
        val inset = edge * HandheldControlGeometry.DPAD_FACE_INSET_FRACTION
        val body = HandheldControlGeometry.crossOutline(composeSize(edge))
        val plate = HandheldControlGeometry.crossOutline(composeSize(edge), inset)

        // The rim has to be visible on every side: the plate's own extremes must be pulled in from
        // the body's by the inset, not flush on two edges (an early revision shrank left/top but
        // left right/bottom flush, which erased the rim on two sides).
        val plateLeft = plate.minOf { it.x }
        val plateRight = plate.maxOf { it.x }
        val plateTop = plate.minOf { it.y }
        val plateBottom = plate.maxOf { it.y }
        assertEquals("left rim missing", inset, plateLeft, edge * 0.02f)
        assertEquals("right rim missing", inset, edge - plateRight, edge * 0.02f)
        assertEquals("top rim missing", inset, plateTop, edge * 0.02f)
        assertEquals("bottom rim missing", inset, edge - plateBottom, edge * 0.02f)
        assertTrue("body should be the larger outline", body.maxOf { it.x } > plateRight)
    }

    @Test
    fun arrowsPointOutwardAndStayInsideTheArm() {
        val edge = 146f
        val centre = TestPoint(edge / 2f, edge / 2f)
        val arm = edge * HandheldControlGeometry.DPAD_ARM_FRACTION

        DpadDirection.entries.forEach { direction ->
            val points = HandheldControlGeometry.arrowOutline(composeSize(edge), direction).toTestPoints()
            assertEquals("arrow must be a triangle: $direction", 3, points.size)

            val centroid = TestPoint(
                points.sumOf { it.x.toDouble() }.toFloat() / 3f,
                points.sumOf { it.y.toDouble() }.toFloat() / 3f,
            )
            val dx = centroid.x - centre.x
            val dy = centroid.y - centre.y
            val expected = when (direction) {
                DpadDirection.UP -> 0 to -1
                DpadDirection.DOWN -> 0 to 1
                DpadDirection.LEFT -> -1 to 0
                DpadDirection.RIGHT -> 1 to 0
            }
            assertTrue(
                "arrow $direction sits on the wrong side of the dimple",
                dx * expected.first + dy * expected.second > 0f,
            )
            points.forEach { point ->
                assertTrue(
                    "arrow $direction escapes the pad",
                    point.x in 0f..edge && point.y in 0f..edge,
                )
                assertTrue(
                    "arrow $direction overlaps the thumb dimple",
                    hypot(point.x - centre.x, point.y - centre.y) > arm * 0.20f,
                )
                when (direction) {
                    DpadDirection.UP, DpadDirection.DOWN ->
                        assertTrue("arrow $direction wider than its arm", abs(point.x - centre.x) <= arm / 2f)
                    DpadDirection.LEFT, DpadDirection.RIGHT ->
                        assertTrue("arrow $direction taller than its arm", abs(point.y - centre.y) <= arm / 2f)
                }
                assertTrue(
                    "arrow $direction runs into the arm tip",
                    hypot(point.x - centre.x, point.y - centre.y) <= edge / 2f - 0.5f,
                )
            }
        }
    }

    @Test
    fun arrowGeometryScalesWithThePad() {
        val small = HandheldControlGeometry.arrowOutline(composeSize(100f), DpadDirection.UP)
        val large = HandheldControlGeometry.arrowOutline(composeSize(200f), DpadDirection.UP)
        val smallHeight = abs(small[0].y - small[1].y)
        val largeHeight = abs(large[0].y - large[1].y)
        assertEquals("arrows should scale linearly", 2f, largeHeight / smallHeight, 0.01f)
    }

    private enum class Axis { HORIZONTAL, VERTICAL }

    private fun assertUnbrokenScan(points: List<TestPoint>, edge: Float, axis: Axis) {
        val samples = 500
        var insideCount = 0
        var transitions = 0
        var previous = false
        for (i in 1 until samples) {
            val t = i.toFloat() / samples
            val inside = when (axis) {
                Axis.VERTICAL -> isInside(points, TestPoint(edge / 2f, t * edge))
                Axis.HORIZONTAL -> isInside(points, TestPoint(t * edge, edge / 2f))
            }
            if (inside) insideCount++
            if (i > 1 && inside != previous) {
                transitions++
                // Only the two ends of the scan may sit outside the plastic.
                assertTrue("$axis scan re-enters the arm at t=$t", t < 0.02f || t > 0.98f)
            }
            previous = inside
        }
        assertTrue(
            "$axis scan does not cover the whole arm ($insideCount/$samples)",
            insideCount >= samples - 8,
        )
        assertTrue("$axis scan is fragmented", transitions <= 2)
    }

    /** Even-odd ray cast; enough for a simple closed polygon sampled on a regular grid. */
    private fun isInside(points: List<TestPoint>, point: TestPoint): Boolean {
        var inside = false
        var j = points.lastIndex
        for (i in points.indices) {
            val a = points[i]
            val b = points[j]
            if ((a.y > point.y) != (b.y > point.y) &&
                point.x < (b.x - a.x) * (point.y - a.y) / (b.y - a.y) + a.x
            ) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    private fun composeSize(edge: Float) = androidx.compose.ui.geometry.Size(edge, edge)

    private fun List<androidx.compose.ui.geometry.Offset>.toTestPoints(): List<TestPoint> =
        map { TestPoint(it.x, it.y) }

    /** Plain float pair so the geometry is exercised without any Android runtime class. */
    private data class TestPoint(val x: Float, val y: Float)
}
