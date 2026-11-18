package io.github.sumankayal247.gbaemulator.domain.model

/**
 * Size of the draggable region in dp, and how big each control is at `scale = 1.0`.
 *
 * Kept separate from Compose so the placement maths can be unit-tested without a UI: the editor
 * and the gameplay screen both resolve positions through [ControlPlacement.resolve], which means
 * a control cannot land in a different spot in one than the other.
 */
data class ControlZone(
    val widthDp: Float,
    val heightDp: Float,
) {
    init {
        require(widthDp >= 0f && heightDp >= 0f) { "Control zone must be non-negative" }
    }
}

/** A fully resolved control position: the centre, in dp from the top-left of the control zone. */
data class ResolvedControl(
    val slot: ControlSlot,
    val centerXDp: Float,
    val centerYDp: Float,
    val halfWidthDp: Float,
    val halfHeightDp: Float,
) {
    val leftDp: Float get() = centerXDp - halfWidthDp
    val topDp: Float get() = centerYDp - halfHeightDp
    val rightDp: Float get() = centerXDp + halfWidthDp
    val bottomDp: Float get() = centerYDp + halfHeightDp
}

/**
 * Turns fractional [ControlAnchors] into dp centres, and does it *twice*: once when saving and
 * once when drawing, always through this object.
 *
 * The important property is that [resolve] clamps the *result*, not the input fraction. A slot is
 * kept fully inside the zone even if its half-extent is larger than the space available, so a
 * control scaled up to 130% still cannot hang off the edge of the screen. Dragging therefore
 * saturates at the border instead of silently storing an unreachable position.
 */
object ControlPlacement {

    /** Free travel available to a slot's centre on each axis, in dp. Never negative. */
    fun travel(
        zone: ControlZone,
        slot: ControlSlot,
        scale: Float,
    ): Pair<Float, Float> {
        val hw = slot.halfWidthDp * scale
        val hh = slot.halfHeightDp * scale
        val tx = (zone.widthDp - 2f * hw).coerceAtLeast(0f)
        val ty = (zone.heightDp - 2f * hh).coerceAtLeast(0f)
        return tx to ty
    }

    /**
     * Centre in dp for [slot], clamped so the control stays wholly inside [zone].
     *
     * When the zone is smaller than the control itself (a very short window, or a control scaled
     * right up) travel collapses to zero and the control is centred on that axis rather than
     * being pushed to a corner.
     */
    fun resolve(
        zone: ControlZone,
        slot: ControlSlot,
        anchors: ControlAnchors,
        scale: Float,
    ): ResolvedControl {
        val safeScale = if (scale > 0f) scale else 1f
        val hw = slot.halfWidthDp * safeScale
        val hh = slot.halfHeightDp * safeScale
        val (tx, ty) = travel(zone, slot, safeScale)
        val a = anchors.clamped()
        val cx = if (tx <= 0f) zone.widthDp * 0.5f else hw + a.x * tx
        val cy = if (ty <= 0f) zone.heightDp * 0.5f else hh + a.y * ty
        return ResolvedControl(
            slot = slot,
            centerXDp = clampCentre(cx, hw, zone.widthDp),
            centerYDp = clampCentre(cy, hh, zone.heightDp),
            halfWidthDp = hw,
            halfHeightDp = hh,
        )
    }

    /**
     * Clamp a centre so the control stays inside `0..extent`, or centre it when the control is
     * wider than the extent.
     *
     * The degenerate case matters: `coerceIn(half, extent - half)` has its bounds inverted once
     * the control is larger than the zone, which pins the control to the far edge instead of
     * centring it. Centring is the only placement that stays symmetric and on-screen.
     */
    private fun clampCentre(center: Float, half: Float, extent: Float): Float {
        if (extent <= half * 2f) return extent * 0.5f
        return center.coerceIn(half, extent - half)
    }

    /**
     * Inverse of [resolve]: the nearest reachable fraction for a centre point.
     *
     * The layout editor drags controls in dp, so it converts back through this before storing.
     * Values saturate at 0 and 1, which is exactly what makes a control stick to the edge during a
     * drag instead of the finger outrunning the button.
     */
    fun anchorsFor(
        zone: ControlZone,
        slot: ControlSlot,
        centerXDp: Float,
        centerYDp: Float,
        scale: Float,
    ): ControlAnchors {
        val safeScale = if (scale > 0f) scale else 1f
        val hw = slot.halfWidthDp * safeScale
        val hh = slot.halfHeightDp * safeScale
        val (tx, ty) = travel(zone, slot, safeScale)
        val ax = if (tx <= 0f) 0.5f else (centerXDp - hw) / tx
        val ay = if (ty <= 0f) 0.5f else (centerYDp - hh) / ty
        return ControlAnchors(ax, ay).clamped()
    }

    /** Resolve a whole map at once. Slots missing from [anchors] fall back to [defaults]. */
    fun resolveAll(
        zone: ControlZone,
        anchors: Map<ControlSlot, ControlAnchors>,
        defaults: Map<ControlSlot, ControlAnchors>,
        scale: Float,
    ): Map<ControlSlot, ResolvedControl> = ControlSlot.ordered.associateWith { slot ->
        resolve(zone, slot, anchors[slot] ?: defaults.getValue(slot), scale)
    }
}
