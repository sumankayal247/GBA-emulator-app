package io.github.sumankayal247.gbaemulator.domain.model

import android.view.KeyEvent

/**
 * Mapping target for a single logical GBA/GBC/GB button on an external physical gamepad.
 * Stored by Android KeyEvent keycode for crisp digital bindings; D-pad axes/sticks are also
 * translated automatically via [analogDeadzone] in GamepadInputTracker.
 */
data class ControllerProfile(
    val descriptor: String,
    val displayName: String,
    val vendorId: Int = 0,
    val productId: Int = 0,
    val analogDeadzone: Float = 0.35f,
    val buttonKeyCodes: Map<VirtualButton, Int> = defaultBindings(),
) {
    fun serialize(): String {
        val bindings = VirtualButton.entries.joinToString(",") { btn ->
            "${btn.name}=${buttonKeyCodes[btn] ?: defaultBindings().getValue(btn)}"
        }
        val safeName = displayName.replace("\t", " ").replace("\n", " ")
        val safeDesc = descriptor.replace("\t", "_").replace("\n", "_")
        return listOf(safeDesc, safeName, vendorId.toString(), productId.toString(), analogDeadzone.toString(), bindings)
            .joinToString("\t")
    }

    companion object {
        fun defaultBindings(): Map<VirtualButton, Int> = mapOf(
            VirtualButton.UP to KeyEvent.KEYCODE_DPAD_UP,
            VirtualButton.DOWN to KeyEvent.KEYCODE_DPAD_DOWN,
            VirtualButton.LEFT to KeyEvent.KEYCODE_DPAD_LEFT,
            VirtualButton.RIGHT to KeyEvent.KEYCODE_DPAD_RIGHT,
            VirtualButton.A to KeyEvent.KEYCODE_BUTTON_B,       // East button (Nintendo A) or configurable
            VirtualButton.B to KeyEvent.KEYCODE_BUTTON_A,       // South button (Nintendo B) or configurable
            VirtualButton.L to KeyEvent.KEYCODE_BUTTON_L1,
            VirtualButton.R to KeyEvent.KEYCODE_BUTTON_R1,
            VirtualButton.START to KeyEvent.KEYCODE_BUTTON_START,
            VirtualButton.SELECT to KeyEvent.KEYCODE_BUTTON_SELECT,
        )

        fun deserialize(line: String): ControllerProfile? {
            val parts = line.split('\t')
            if (parts.size < 6) return null
            val desc = parts[0].ifBlank { return null }
            val name = parts[1].ifBlank { "Gamepad" }
            val vendor = parts[2].toIntOrNull() ?: 0
            val product = parts[3].toIntOrNull() ?: 0
            val deadzone = (parts[4].toFloatOrNull() ?: 0.35f).coerceIn(0.15f, 0.70f)
            val map = defaultBindings().toMutableMap()
            parts[5].split(',').forEach { pair ->
                val eq = pair.indexOf('=')
                if (eq > 0) {
                    val btn = runCatching { VirtualButton.valueOf(pair.substring(0, eq)) }.getOrNull()
                    val code = pair.substring(eq + 1).toIntOrNull()
                    if (btn != null && code != null && code > 0) {
                        map[btn] = code
                    }
                }
            }
            return ControllerProfile(
                descriptor = desc,
                displayName = name,
                vendorId = vendor,
                productId = product,
                analogDeadzone = deadzone,
                buttonKeyCodes = map,
            )
        }
    }
}

data class ConnectedControllerInfo(
    val deviceId: Int,
    val descriptor: String,
    val displayName: String,
    val vendorId: Int,
    val productId: Int,
    val isConnected: Boolean,
    val isUsb: Boolean,
    val profile: ControllerProfile,
)
