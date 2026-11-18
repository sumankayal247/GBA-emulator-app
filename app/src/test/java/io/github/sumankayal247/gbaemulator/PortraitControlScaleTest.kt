package io.github.sumankayal247.gbaemulator

import io.github.sumankayal247.gbaemulator.ui.gameplay.GBA_SCREEN_ASPECT
import io.github.sumankayal247.gbaemulator.ui.gameplay.PORTRAIT_CHROME_DP
import io.github.sumankayal247.gbaemulator.ui.gameplay.PORTRAIT_CONTROL_BAND_SLOPE_DP
import io.github.sumankayal247.gbaemulator.ui.gameplay.PORTRAIT_HORIZONTAL_INSET_DP
import io.github.sumankayal247.gbaemulator.ui.gameplay.portraitControlScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Portrait layout budget: the viewport keeps the full 3:2 width, and the control band shrinks
 * only as far as it must so nothing overlaps the screen or spills off the bottom of the window.
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
        // 360x480 dp: a 3:2 viewport at full width needs 229 dp, leaving too little for a
        // full-size control band, so the band has to give height back.
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
                // Shrinking stops at the floor, unless the user asked for less than the floor.
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
        // A 200x300 dp window cannot hold a 3:2 viewport plus controls at any usable control
        // size. The floor is deliberate: the controls stay tappable and the window clips rather
        // than becoming unusable. Documented here so the trade-off is a decision, not a bug.
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
    fun screenAndControlsAlwaysFitTheWindow() {
        // The whole point of the squeeze: even on a window as short as the annotated
        // 471x783 @2x screenshot, the 3:2 viewport at full width and the control band together
        // stay inside the window.
        // Below roughly this height the floor stops the controls shrinking any further, so these
        // are the smallest windows the layout is expected to hold together on.
        listOf(
            236f to 392f,
            360f to 480f,
            360f to 500f,
            360f to 600f,
            411f to 891f,
        ).forEach { (w, h) ->
            val scale = portraitControlScale(1f, w, h)
            // Derived from the real constants, not copies: a hardcoded duplicate of the chrome
            // budget is exactly what let this assertion drift out of step with the layout.
            val viewportHeight =
                (w - 2f * PORTRAIT_HORIZONTAL_INSET_DP) / GBA_SCREEN_ASPECT
            val total =
                viewportHeight + PORTRAIT_CONTROL_BAND_SLOPE_DP * scale + PORTRAIT_CHROME_DP
            assertTrue("viewport + controls overflowed at ${w}x$h (total $total)", total <= h + 0.01f)
        }
    }
}
