package io.github.sumankayal247.gbaemulator

import android.view.InputDevice
import io.github.sumankayal247.gbaemulator.emulator.ControllerManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the "game opens fullscreen with no visible controls" bug:
 * built-in/virtual devices advertising a joystick source were misclassified as
 * connected controllers, which hid the touch controls on launch.
 */
class ControllerManagerGamepadDetectionTest {

    @Test
    fun virtualDevicesAreNeverGamepads() {
        assertFalse(
            ControllerManager.isGamepadDevice(
                sources = InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK,
                motionRangeSources = listOf(InputDevice.SOURCE_JOYSTICK),
                deviceName = "Virtual",
                isVirtual = true,
            ),
        )
    }

    @Test
    fun touchscreenOnlyDevicesAreNotGamepads() {
        assertFalse(
            ControllerManager.isGamepadDevice(
                sources = InputDevice.SOURCE_TOUCHSCREEN,
                motionRangeSources = emptyList(),
                deviceName = "focaltech_ft8719",
                isVirtual = false,
            ),
        )
    }

    @Test
    fun joystickOnlyWithoutAxesIsRejected() {
        // The false-positive shape: joystick source advertised, no usable axes.
        assertFalse(
            ControllerManager.isGamepadDevice(
                sources = InputDevice.SOURCE_JOYSTICK,
                motionRangeSources = emptyList(),
                deviceName = "some_oem_device",
                isVirtual = false,
            ),
        )
    }

    @Test
    fun joystickOnlyWithAxesIsAccepted() {
        assertTrue(
            ControllerManager.isGamepadDevice(
                sources = InputDevice.SOURCE_JOYSTICK,
                motionRangeSources = listOf(InputDevice.SOURCE_JOYSTICK),
                deviceName = "USB Joystick",
                isVirtual = false,
            ),
        )
    }

    @Test
    fun joystickOnlyTouchscreenNameIsRejectedEvenWithAxes() {
        assertFalse(
            ControllerManager.isGamepadDevice(
                sources = InputDevice.SOURCE_JOYSTICK,
                motionRangeSources = listOf(InputDevice.SOURCE_JOYSTICK),
                deviceName = "focaltech_touch",
                isVirtual = false,
            ),
        )
    }

    @Test
    fun gamepadSourceIsAcceptedEvenWithoutAxes() {
        // Button-only pads have no motion ranges but are still real controllers.
        assertTrue(
            ControllerManager.isGamepadDevice(
                sources = InputDevice.SOURCE_GAMEPAD,
                motionRangeSources = emptyList(),
                deviceName = "8BitDo SN30 Pro",
                isVirtual = false,
            ),
        )
    }

    @Test
    fun realBluetoothControllerIsAccepted() {
        assertTrue(
            ControllerManager.isGamepadDevice(
                sources = InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK,
                motionRangeSources = listOf(
                    InputDevice.SOURCE_JOYSTICK,
                    InputDevice.SOURCE_GAMEPAD,
                ),
                deviceName = "Xbox Wireless Controller",
                isVirtual = false,
            ),
        )
    }

    @Test
    fun virtualNamedDevicesAreRejectedEvenWithGamepadSource() {
        assertFalse(
            ControllerManager.isGamepadDevice(
                sources = InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK,
                motionRangeSources = listOf(InputDevice.SOURCE_JOYSTICK),
                deviceName = "Virtual Gamepad",
                isVirtual = false,
            ),
        )
    }
}
