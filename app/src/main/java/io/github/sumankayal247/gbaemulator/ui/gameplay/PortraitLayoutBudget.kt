package io.github.sumankayal247.gbaemulator.ui.gameplay

/**
 * Pure layout arithmetic for the portrait handheld, kept free of Compose so it can be unit-tested
 * on the JVM.
 *
 * These numbers decide how much window height the screen frame gets versus the controls. They are
 * the part of the layout most likely to break on an unusual screen size, so they live here with
 * tests rather than inline in a composable where nothing can reach them.
 */

/** Horizontal padding of the portrait column, in dp. */
internal const val PORTRAIT_HORIZONTAL_INSET_DP = 8f

/** Column padding, the gap under the screen and the fixed-size bottom label row, in dp. */
internal const val PORTRAIT_CHROME_DP = 89f

/** Never shrink the portrait controls past this, however short the window is. */
internal const val PORTRAIT_MIN_CONTROL_SCALE = 0.82f

/**
 * Height the portrait controls occupy at `scale = 1.0`: shoulder row, the D-pad, and the small
 * button row with breathing room between them.
 *
 * This is a real measurement of the layout rather than a tuning knob, so shrinking is driven by
 * whether the controls genuinely fit. It replaced a hand-picked 180 dp slope that let the zone be
 * too short for the controls, which made them overlap on short phones.
 */
internal const val PORTRAIT_CONTROL_NOMINAL_HEIGHT_DP = 236f

/**
 * Aspect of the screen *frame* (the black bezel), not of the game.
 *
 * The reference handheld's frame is 684x635 px, aspect 1.077, with the 3:2 image letterboxed
 * inside it. Matching that frame is what makes the top of the screen read like the reference
 * instead of a letterbox-flush strip. The game is still drawn at [GBA_SCREEN_ASPECT] and is never
 * stretched.
 */
internal const val SCREEN_BEZEL_ASPECT = 1.06f

/**
 * Native GBA aspect ratio (240x160). The game image inside the frame is drawn at exactly this
 * ratio; the surrounding black is bezel, so nothing is squashed or cropped.
 */
internal const val GBA_SCREEN_ASPECT = 1.5f

/** Padding around the landscape control zone. */
internal const val LANDSCAPE_INSET_DP = 10f

/** Smallest control zone we will squeeze the frame down to protect, in dp. */
internal const val PORTRAIT_MIN_ZONE_DP = 210f

/** Full-width frame height at the reference aspect, before any squeeze. */
internal fun portraitBezelHeightDp(windowWidthDp: Float): Float =
    (windowWidthDp - 2f * PORTRAIT_HORIZONTAL_INSET_DP) / SCREEN_BEZEL_ASPECT

/**
 * Frame height for a window, reduced when the tall reference frame would starve the controls.
 *
 * The reference frame is about 30% taller than the 3:2 image inside it, and that extra height is
 * pure bezel. On a short window it would eat the control zone, so the frame gives height back
 * until the zone reaches [PORTRAIT_MIN_ZONE_DP]. The frame never drops below the height the bare
 * 3:2 image needs, so the bezel can collapse to nothing but can never crop the game.
 */
internal fun portraitBezelHeightDpForWindow(windowWidthDp: Float, windowHeightDp: Float): Float {
    val full = portraitBezelHeightDp(windowWidthDp)
    val minimum = (windowWidthDp - 2f * PORTRAIT_HORIZONTAL_INSET_DP) / GBA_SCREEN_ASPECT
    if (windowHeightDp <= 0f) return full
    val roomForFrame = windowHeightDp - PORTRAIT_MIN_ZONE_DP - PORTRAIT_CHROME_DP
    return roomForFrame.coerceIn(minimum, full)
}

/**
 * How large the portrait controls can be before they start squeezing the screen.
 *
 * A request already below [PORTRAIT_MIN_CONTROL_SCALE] is the user's explicit choice and is
 * honoured unchanged; otherwise the result is the largest scale at which the real control cluster
 * fits in the zone that is left, clamped to the floor and to the user's request.
 */
internal fun portraitControlScale(
    requestedScale: Float,
    windowWidthDp: Float,
    windowHeightDp: Float,
): Float {
    if (windowWidthDp <= 0f || windowHeightDp <= 0f) return requestedScale
    if (requestedScale <= 0f) return 1f
    if (requestedScale <= PORTRAIT_MIN_CONTROL_SCALE) return requestedScale
    val frameHeight = portraitBezelHeightDpForWindow(windowWidthDp, windowHeightDp)
    val roomForControls = windowHeightDp - frameHeight - PORTRAIT_CHROME_DP
    val affordable = roomForControls / PORTRAIT_CONTROL_NOMINAL_HEIGHT_DP
    return affordable.coerceIn(PORTRAIT_MIN_CONTROL_SCALE, requestedScale)
}
