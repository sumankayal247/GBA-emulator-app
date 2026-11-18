package io.github.sumankayal247.gbaemulator

import io.github.sumankayal247.gbaemulator.ui.gameplay.PORTRAIT_CHROME_DP
import io.github.sumankayal247.gbaemulator.ui.gameplay.PORTRAIT_CONTROL_NOMINAL_HEIGHT_DP
import io.github.sumankayal247.gbaemulator.ui.gameplay.PORTRAIT_HORIZONTAL_INSET_DP
import io.github.sumankayal247.gbaemulator.ui.gameplay.portraitBezelHeightDpForWindow
import io.github.sumankayal247.gbaemulator.ui.gameplay.portraitControlScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Portrait layout budget: the screen frame takes the full width, and the controls shrink only as
 * far as they must so nothing overlaps and nothing spills off the bottom of the window.
 *
 * The frame height is itself window-aware now: the reference frame is ~30% taller than the 3:2
 * image inside it, and on a short window it gives that bezel height back to the controls. These
 * tests pin both halves of that trade-off.
 */
class PortraitControlScaleTest {

    @Test
    fun tallWindowKeepsTheUsersScale() {
        // A modern phone has height to spare, so the slider is honoured exactly.
        assertEquals(1f, portraitControlScale(1f, 411f, 891f), 0.0001f)
        assertEquals(1.18f, portraitControlScale(1.18f, 411f, 891f), 0.0001f)
    }

    @Test
    fun shortWindowShrinksTheControlsToProtectTheViewport() {
        // A short window cannot hold the frame and a full-size control cluster, so the cluster
        // has to give height back.
        val scale = portraitControlScale(1f, 360f, 480f)
        assertTrue("expected a squeeze on a short window, got $scale", scale < 1f)
        assertTrue("must not go below the floor", scale >= 0.82f)
    }

    @Test
    fun neverReturnsMoreThanRequested() {
        listOf(0.75f, 0.9f, 1f, 1.18f, 1.3f).forEach { requested ->
            listOf(240f to 400f, 360f to 780f, 411f to 891f, 800f to 1280f).forEach { (w, h) ->
                val scale = portraitControlScale(requested, w, h)
                assertTrue("scale $scale exceeded request $requested at ${w}x$h", scale <= requested + 0.0001f)
                val floor = minOf(0.82f, requested)
                assertTrue("scale $scale below floor at ${w}x$h", scale >= floor - 0.0001f)
            }
        }
    }

    @Test
    fun tallerWindowsNeverShrinkTheControls() {
        var previous = 0f
        listOf(360f, 420f, 480f, 520f, 600f, 780f, 1000f).forEach { height ->
            val scale = portraitControlScale(1f, 382f, height)
            assertTrue(
                "controls shrank when the window grew (h=$height: $scale < $previous)",
                scale >= previous - 0.0001f,
            )
            previous = scale
        }
    }

    @Test
    fun shrinkFloorWinsOverFittingAnImpossiblyShortWindow() {
        // A 200x300 dp window cannot hold the frame plus controls at any usable control size.
        // The floor is deliberate: the controls stay tappable and the window clips rather than
        // becoming unusable. Documented here so the trade-off is a decision, not a bug.
        assertEquals(0.82f, portraitControlScale(1f, 200f, 300f), 0.0001f)
    }

    @Test
    fun degenerateWindowSizesFallBackToTheRequestedScale() {
        assertEquals(1.1f, portraitControlScale(1.1f, 0f, 900f), 0.0001f)
        assertEquals(0.9f, portraitControlScale(0.9f, 400f, 0f), 0.0001f)
        // A zero/negative slider value must not produce a zero-sized control.
        assertEquals(1f, portraitControlScale(0f, 400f, 800f), 0.0001f)
    }

    @Test
    fun frameNeverOverflowsTheWindow() {
        // The frame plus the control space must fit, for every window we claim to support.
        listOf(
            236f to 392f,
            320f to 568f,
            360f to 480f,
            360f to 600f,
            360f to 640f,
            393f to 780f,
            411f to 891f,
            412f to 915f,
            430f to 932f,
        ).forEach { (w, h) ->
            val frame = portraitBezelHeightDpForWindow(w, h)
            val scale = portraitControlScale(1f, w, h)
            val total = frame + PORTRAIT_CONTROL_NOMINAL_HEIGHT_DP * scale + PORTRAIT_CHROME_DP
            val floorWins = scale <= 0.82f + 0.0001f
            assertTrue(
                "frame + controls overflowed at ${w}x$h (total $total, scale $scale)",
                total <= h + 0.01f || floorWins,
            )
        }
    }

    @Test
    fun controlsAreNeverSqueezedBelowWhatTheClusterNeeds() {
        // Regression guard for the overlap the first draft shipped: when the scale is above the
        // floor, the zone must be tall enough for the real control cluster.
        listOf(
            360f to 600f,
            360f to 640f,
            393f to 780f,
            411f to 891f,
            412f to 1015f,
        ).forEach { (w, h) ->
            val frame = portraitBezelHeightDpForWindow(w, h)
            val zone = h - frame - PORTRAIT_CHROME_DP
            val scale = portraitControlScale(1f, w, h)
            val needed = PORTRAIT_CONTROL_NOMINAL_HEIGHT_DP * scale
            assertTrue(
                "controls need ${needed}dp but zone is ${zone}dp at ${w}x$h",
                needed <= zone + 0.01f || scale <= 0.82f + 0.0001f,
            )
        }
    }

    @Test
    fun tallWindowsGetTheFullReferenceBezel() {
        // On a normally-proportioned phone the whole point is the taller reference frame, so the
        // squeeze must not quietly rob it of bezel height.
        listOf(393f to 780f, 411f to 891f, 412f to 915f).forEach { (w, h) ->
            val frame = portraitBezelHeightDpForWindow(w, h)
            val full = (w - 2f * PORTRAIT_HORIZONTAL_INSET_DP) / 1.06f
            assertEquals("lost the reference bezel at ${w}x$h", full, frame, 0.5f)
        }
    }
}
