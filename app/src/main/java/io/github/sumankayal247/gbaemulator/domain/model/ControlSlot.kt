package io.github.sumankayal247.gbaemulator.domain.model

/**
 * Every draggable thing on the virtual handheld.
 *
 * Positions live in [ControlAnchors] as fractions of the free space in the control zone, so a
 * profile saved on one phone resolves correctly on a phone of any size. The footprint here is the
 * slot's half-extent at `scale = 1.0`, used to keep a control fully inside the zone when it is
 * dragged to an edge.
 */
enum class ControlSlot(
    val id: String,
    val label: String,
    val halfWidthDp: Float,
    val halfHeightDp: Float,
) {
    SHOULDER_L("l", "L", 52f, 17f),
    SHOULDER_R("r", "R", 52f, 17f),
    DPAD("dpad", "D-pad", 74f, 74f),
    BUTTON_A("a", "A", 33f, 33f),
    BUTTON_B("b", "B", 33f, 33f),
    MENU("menu", "Menu", 24f, 24f),
    SELECT("select", "Select", 24f, 24f),
    START("start", "Start", 24f, 24f),
    ;

    companion object {
        fun fromId(id: String): ControlSlot? = entries.firstOrNull { it.id == id }

        /** Serialization order. Stable so saved profiles stay byte-identical between runs. */
        val ordered: List<ControlSlot> = entries.toList()
    }
}

/**
 * A control's position as a fraction of the *free* space in the control zone.
 *
 * `(0, 0)` puts the control flush against the top-left corner, `(1, 1)` flush against the
 * bottom-right. Fractions rather than dp is what lets one saved profile work on a small phone and
 * a tablet without the controls drifting off-screen.
 */
data class ControlAnchors(val x: Float, val y: Float) {
    fun clamped(): ControlAnchors = ControlAnchors(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
}

/**
 * Default placements, expressed in the same fractional space as [ControlAnchors].
 *
 * These are deliberately *not* the old absolute positions. The old layout pinned the thumb
 * controls to the bottom of the window and left a large empty band above them (roughly 28% of the
 * screen height). These defaults spread the cluster over the whole zone the way the reference
 * handheld does, which is what removes that dead space.
 */
object ControlAnchorsDefaults {

    fun portrait(): Map<ControlSlot, ControlAnchors> = mapOf(
        // Shoulders sit just under the screen, at the far left and right.
        ControlSlot.SHOULDER_L to ControlAnchors(0.08f, 0.00f),
        ControlSlot.SHOULDER_R to ControlAnchors(0.92f, 0.00f),
        // Thumb cluster spans the middle of the zone instead of being crammed into the bottom
        // sixth, which is what removes the empty band the owner pointed at.
        ControlSlot.DPAD to ControlAnchors(0.18f, 0.42f),
        ControlSlot.BUTTON_B to ControlAnchors(0.97f, 0.20f),
        ControlSlot.BUTTON_A to ControlAnchors(0.98f, 0.50f),
        // Small buttons spread along the bottom, evenly spaced.
        ControlSlot.MENU to ControlAnchors(0.00f, 0.88f),
        ControlSlot.SELECT to ControlAnchors(0.44f, 0.88f),
        ControlSlot.START to ControlAnchors(0.72f, 0.88f),
    )

    fun landscape(): Map<ControlSlot, ControlAnchors> = mapOf(
        ControlSlot.SHOULDER_L to ControlAnchors(0.02f, 0.04f),
        ControlSlot.SHOULDER_R to ControlAnchors(0.90f, 0.04f),
        ControlSlot.DPAD to ControlAnchors(0.06f, 0.52f),
        ControlSlot.BUTTON_B to ControlAnchors(0.90f, 0.42f),
        ControlSlot.BUTTON_A to ControlAnchors(0.70f, 0.62f),
        ControlSlot.MENU to ControlAnchors(0.02f, 0.90f),
        ControlSlot.SELECT to ControlAnchors(0.72f, 0.90f),
        ControlSlot.START to ControlAnchors(0.90f, 0.90f),
    )

    fun forOrientation(landscape: Boolean): Map<ControlSlot, ControlAnchors> =
        if (landscape) landscape() else portrait()
}
