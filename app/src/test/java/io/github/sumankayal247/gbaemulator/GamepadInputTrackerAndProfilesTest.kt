package io.github.sumankayal247.gbaemulator

import android.view.KeyEvent
import io.github.sumankayal247.gbaemulator.data.preferences.ControllerProfilesCodec
import io.github.sumankayal247.gbaemulator.domain.model.ControllerProfile
import io.github.sumankayal247.gbaemulator.domain.model.VirtualButton
import io.github.sumankayal247.gbaemulator.emulator.GamepadInputTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GamepadInputTrackerAndProfilesTest {

    @Test
    fun mapsButtonsRemapsFiltersRepeatsAndReleasesOnDisconnect() {
        val tracker = GamepadInputTracker()
        val profile = ControllerProfile(
            descriptor = "bt_8bitdo_sn30",
            displayName = "8BitDo SN30 Pro",
            buttonKeyCodes = ControllerProfile.defaultBindings() + (VirtualButton.A to KeyEvent.KEYCODE_BUTTON_X),
        )

        // Remapped A (KEYCODE_BUTTON_X) activates A
        assertTrue(tracker.onKeyInput(7, KeyEvent.KEYCODE_BUTTON_X, pressed = true, repeatCount = 0, profile = profile))
        assertEquals(setOf(VirtualButton.A), tracker.pressedButtons())

        // Repeat events are accepted without corrupting state
        assertTrue(tracker.onKeyInput(7, KeyEvent.KEYCODE_BUTTON_X, pressed = true, repeatCount = 2, profile = profile))
        assertEquals(setOf(VirtualButton.A), tracker.pressedButtons())

        // Unmapped key returns false
        assertFalse(tracker.onKeyInput(7, KeyEvent.KEYCODE_VOLUME_UP, pressed = true, repeatCount = 0, profile = profile))

        // Hot-plug disconnect clears all held inputs for deviceId 7 immediately
        assertEquals(0, tracker.onDeviceDisconnected(7))
        assertTrue(tracker.pressedButtons().isEmpty())
    }

    @Test
    fun deadzoneTriggersAndMultiDeviceSanitizationWorkDeterministically() {
        val tracker = GamepadInputTracker()
        val profile = ControllerProfile(
            descriptor = "usb_pad",
            displayName = "USB Pad",
            analogDeadzone = 0.40f,
        )

        // Inside deadzone produces zero directional input
        assertEquals(
            0,
            tracker.onAxesInput(
                deviceId = 1,
                hatX = 0f,
                hatY = 0f,
                stickX = 0.25f,
                stickY = -0.30f,
                lTrigger = 0.1f,
                rTrigger = 0.2f,
                profile = profile,
            ),
        )

        // Past deadzone + L trigger activates RIGHT + UP + L
        tracker.onAxesInput(
            deviceId = 1,
            hatX = 0f,
            hatY = 0f,
            stickX = 0.85f,
            stickY = -0.75f,
            lTrigger = 0.9f,
            rTrigger = 0f,
            profile = profile,
        )
        assertEquals(setOf(VirtualButton.RIGHT, VirtualButton.UP, VirtualButton.L), tracker.pressedButtons())

        // Second device presses LEFT -> opposing LEFT+RIGHT cancel safely while UP+L stay active
        tracker.onKeyInput(2, KeyEvent.KEYCODE_DPAD_LEFT, pressed = true, repeatCount = 0, profile = profile)
        assertEquals(setOf(VirtualButton.UP, VirtualButton.L), tracker.pressedButtons())

        // Disconnecting device 2 restores RIGHT from device 1
        tracker.onDeviceDisconnected(2)
        assertEquals(setOf(VirtualButton.RIGHT, VirtualButton.UP, VirtualButton.L), tracker.pressedButtons())
    }

    @Test
    fun controllerProfilesCodecRoundTripsAndIgnoresCorruptLines() {
        val p1 = ControllerProfile(
            descriptor = "desc_1",
            displayName = "DualSense Wireless",
            vendorId = 0x054C,
            productId = 0x0CE6,
            analogDeadzone = 0.28f,
            buttonKeyCodes = ControllerProfile.defaultBindings() + (VirtualButton.L to KeyEvent.KEYCODE_BUTTON_L2),
        )
        val encoded = ControllerProfilesCodec.encode(mapOf(p1.descriptor to p1))
        val decoded = ControllerProfilesCodec.decode(encoded)

        assertEquals(1, decoded.size)
        val restored = decoded.getValue("desc_1")
        assertEquals("DualSense Wireless", restored.displayName)
        assertEquals(0x054C, restored.vendorId)
        assertEquals(0.28f, restored.analogDeadzone, 0.001f)
        assertEquals(KeyEvent.KEYCODE_BUTTON_L2, restored.buttonKeyCodes[VirtualButton.L])

        assertTrue(ControllerProfilesCodec.decode("corrupt garbage").isEmpty())
    }
}
