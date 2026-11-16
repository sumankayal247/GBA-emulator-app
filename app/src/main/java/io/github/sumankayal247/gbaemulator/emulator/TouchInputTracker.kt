package io.github.sumankayal247.gbaemulator.emulator

import io.github.sumankayal247.gbaemulator.domain.model.VirtualButton
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Independent multi-pointer touch tracker for virtual handheld controls (ADR-0003 / Plan §11.4).
 *
 * Each active pointer ID owns its own set of currently pressed logical buttons so multiple
 * fingers can simultaneously press D-pad directions, A/B, and L/R without interference.
 * Pointer release, cancellation, view detach, or lifecycle pause deterministically clears
 * pressed controls so buttons never get stuck.
 */
class TouchInputTracker {
    private val pointerStates = HashMap<Long, Set<VirtualButton>>()

    @Synchronized
    fun updateButtonPointer(pointerId: Long, button: VirtualButton, pressed: Boolean): Int {
        if (pressed) {
            pointerStates[pointerId] = setOf(button)
        } else {
            pointerStates.remove(pointerId)
        }
        return computeMaskLocked()
    }

    /**
     * Maps a pointer position relative to a D-pad's center (`dx`, `dy` normalized to [-1, 1])
     * into 8-way directional buttons (supporting diagonals and smooth thumb sliding).
     */
    @Synchronized
    fun updateDpadPointer(
        pointerId: Long,
        normalizedDx: Float,
        normalizedDy: Float,
        deadZone: Float = 0.22f,
    ): Int {
        val dirs = resolveDpadDirections(normalizedDx, normalizedDy, deadZone)
        if (dirs.isEmpty()) {
            pointerStates.remove(pointerId)
        } else {
            pointerStates[pointerId] = dirs
        }
        return computeMaskLocked()
    }

    @Synchronized
    fun releasePointer(pointerId: Long): Int {
        pointerStates.remove(pointerId)
        return computeMaskLocked()
    }

    @Synchronized
    fun releaseAll(): Int {
        pointerStates.clear()
        return 0
    }

    @Synchronized
    fun pressedButtons(): Set<VirtualButton> {
        if (pointerStates.isEmpty()) return emptySet()
        val combined = LinkedHashSet<VirtualButton>()
        for (buttons in pointerStates.values) {
            combined.addAll(buttons)
        }
        return sanitizeOpposing(combined)
    }

    @Synchronized
    fun currentBitMask(): Int = computeMaskLocked()

    private fun computeMaskLocked(): Int {
        val active = pressedButtons()
        var mask = 0
        for (button in active) {
            mask = mask or button.bitMask
        }
        return mask
    }

    companion object {
        /**
         * Resolves normalized D-pad coordinates into 0, 1, or 2 non-opposing directions using
         * eight 45-degree sectors with a centered dead zone.
         */
        fun resolveDpadDirections(
            dx: Float,
            dy: Float,
            deadZone: Float = 0.22f,
        ): Set<VirtualButton> {
            val magnitude = hypot(dx.toDouble(), dy.toDouble()).toFloat()
            if (magnitude < deadZone) {
                return emptySet()
            }
            // Screen coordinates: +X right, +Y down. Convert to degrees in [0, 360).
            var angleDeg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))
            if (angleDeg < 0.0) angleDeg += 360.0

            return when {
                angleDeg < 22.5 || angleDeg >= 337.5 -> setOf(VirtualButton.RIGHT)
                angleDeg < 67.5 -> setOf(VirtualButton.RIGHT, VirtualButton.DOWN)
                angleDeg < 112.5 -> setOf(VirtualButton.DOWN)
                angleDeg < 157.5 -> setOf(VirtualButton.DOWN, VirtualButton.LEFT)
                angleDeg < 202.5 -> setOf(VirtualButton.LEFT)
                angleDeg < 247.5 -> setOf(VirtualButton.LEFT, VirtualButton.UP)
                angleDeg < 292.5 -> setOf(VirtualButton.UP)
                else -> setOf(VirtualButton.UP, VirtualButton.RIGHT)
            }
        }

        private fun sanitizeOpposing(buttons: Set<VirtualButton>): Set<VirtualButton> {
            val result = buttons.toMutableSet()
            if (VirtualButton.UP in result && VirtualButton.DOWN in result) {
                result.remove(VirtualButton.UP)
                result.remove(VirtualButton.DOWN)
            }
            if (VirtualButton.LEFT in result && VirtualButton.RIGHT in result) {
                result.remove(VirtualButton.LEFT)
                result.remove(VirtualButton.RIGHT)
            }
            return result
        }
    }
}
