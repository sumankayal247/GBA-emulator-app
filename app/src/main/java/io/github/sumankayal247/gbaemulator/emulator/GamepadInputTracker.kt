package io.github.sumankayal247.gbaemulator.emulator

import io.github.sumankayal247.gbaemulator.domain.model.ControllerProfile
import io.github.sumankayal247.gbaemulator.domain.model.VirtualButton

/**
 * Pure-Kotlin physical gamepad input tracker (unit-testable on JVM without Android runtime).
 *
 * Guarantees (Plan §11.4 / ADR-0005):
 * - Tracks each connected controller deviceId independently (buttons + analog/HAT directions).
 * - Filters Android key repeats (`repeatCount > 0`).
 * - Applies configurable analog dead zone (`0.15..0.70`, default `0.35`).
 * - Sanitizes opposing directional inputs (`LEFT+RIGHT`, `UP+DOWN`) deterministically.
 * - Releases all held inputs for a device immediately on hot-plug disconnect or lifecycle pause.
 */
class GamepadInputTracker {
    private val digitalMasksByDevice = HashMap<Int, Int>()
    private val axisMasksByDevice = HashMap<Int, Int>()

    @Synchronized
    fun onKeyInput(
        deviceId: Int,
        keyCode: Int,
        pressed: Boolean,
        repeatCount: Int,
        profile: ControllerProfile,
    ): Boolean {
        if (pressed && repeatCount > 0) {
            return profile.buttonKeyCodes.values.contains(keyCode)
        }
        val matchedButtons = profile.buttonKeyCodes.entries.filter { it.value == keyCode }.map { it.key }
        if (matchedButtons.isEmpty()) return false

        var mask = digitalMasksByDevice[deviceId] ?: 0
        for (btn in matchedButtons) {
            mask = if (pressed) mask or btn.bitMask else mask and btn.bitMask.inv()
        }
        if (mask == 0) {
            digitalMasksByDevice.remove(deviceId)
        } else {
            digitalMasksByDevice[deviceId] = mask
        }
        return true
    }

    @Synchronized
    fun onAxesInput(
        deviceId: Int,
        hatX: Float,
        hatY: Float,
        stickX: Float,
        stickY: Float,
        lTrigger: Float,
        rTrigger: Float,
        profile: ControllerProfile,
    ): Int {
        val dz = profile.analogDeadzone.coerceIn(0.15f, 0.70f)
        var mask = 0

        // Prefer digital HAT D-pad when non-zero; fall back to left analog stick.
        val effectiveX = if (kotlin.math.abs(hatX) > 0.5f) hatX else stickX
        val effectiveY = if (kotlin.math.abs(hatY) > 0.5f) hatY else stickY

        if (effectiveX < -dz) mask = mask or VirtualButton.LEFT.bitMask
        else if (effectiveX > dz) mask = mask or VirtualButton.RIGHT.bitMask

        if (effectiveY < -dz) mask = mask or VirtualButton.UP.bitMask
        else if (effectiveY > dz) mask = mask or VirtualButton.DOWN.bitMask

        if (lTrigger > 0.5f) mask = mask or VirtualButton.L.bitMask
        if (rTrigger > 0.5f) mask = mask or VirtualButton.R.bitMask

        if (mask == 0) {
            axisMasksByDevice.remove(deviceId)
        } else {
            axisMasksByDevice[deviceId] = mask
        }
        return currentBitMask()
    }

    @Synchronized
    fun onDeviceDisconnected(deviceId: Int): Int {
        digitalMasksByDevice.remove(deviceId)
        axisMasksByDevice.remove(deviceId)
        return currentBitMask()
    }

    @Synchronized
    fun releaseAll(): Int {
        digitalMasksByDevice.clear()
        axisMasksByDevice.clear()
        return 0
    }

    @Synchronized
    fun currentBitMask(): Int {
        var combined = 0
        for (m in digitalMasksByDevice.values) combined = combined or m
        for (m in axisMasksByDevice.values) combined = combined or m
        return sanitizeOpposing(combined)
    }

    @Synchronized
    fun pressedButtons(): Set<VirtualButton> {
        val mask = currentBitMask()
        if (mask == 0) return emptySet()
        val set = LinkedHashSet<VirtualButton>()
        for (btn in VirtualButton.entries) {
            if ((mask and btn.bitMask) != 0) set.add(btn)
        }
        return set
    }

    private fun sanitizeOpposing(mask: Int): Int {
        var out = mask
        val lr = VirtualButton.LEFT.bitMask or VirtualButton.RIGHT.bitMask
        if ((out and lr) == lr) out = out and lr.inv()
        val ud = VirtualButton.UP.bitMask or VirtualButton.DOWN.bitMask
        if ((out and ud) == ud) out = out and ud.inv()
        return out
    }
}
