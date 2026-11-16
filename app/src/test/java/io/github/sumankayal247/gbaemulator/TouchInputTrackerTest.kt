package io.github.sumankayal247.gbaemulator

import io.github.sumankayal247.gbaemulator.domain.model.VirtualButton
import io.github.sumankayal247.gbaemulator.emulator.TouchInputTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchInputTrackerTest {

    @Test
    fun simultaneousMultiTouchHoldsDpadAndActionButtonsIndependently() {
        val tracker = TouchInputTracker()

        // Pointer 1 holds D-pad Up-Right diagonal
        tracker.updateDpadPointer(pointerId = 1L, normalizedDx = 0.7f, normalizedDy = -0.7f)
        // Pointer 2 holds A button
        tracker.updateButtonPointer(pointerId = 2L, button = VirtualButton.A, pressed = true)
        // Pointer 3 holds L shoulder
        val mask = tracker.updateButtonPointer(pointerId = 3L, button = VirtualButton.L, pressed = true)

        val expected = setOf(VirtualButton.UP, VirtualButton.RIGHT, VirtualButton.A, VirtualButton.L)
        assertEquals(expected, tracker.pressedButtons())
        assertEquals(
            VirtualButton.UP.bitMask or VirtualButton.RIGHT.bitMask or VirtualButton.A.bitMask or VirtualButton.L.bitMask,
            mask,
        )

        // Releasing pointer 2 only releases A; D-pad and L remain held!
        tracker.releasePointer(2L)
        assertEquals(setOf(VirtualButton.UP, VirtualButton.RIGHT, VirtualButton.L), tracker.pressedButtons())
    }

    @Test
    fun slidingThumbAcrossDpadUpdatesDirectionAndDeadZone() {
        val tracker = TouchInputTracker()

        tracker.updateDpadPointer(10L, normalizedDx = -0.9f, normalizedDy = 0.0f)
        assertEquals(setOf(VirtualButton.LEFT), tracker.pressedButtons())

        // Slide smoothly to Down-Left
        tracker.updateDpadPointer(10L, normalizedDx = -0.6f, normalizedDy = 0.6f)
        assertEquals(setOf(VirtualButton.LEFT, VirtualButton.DOWN), tracker.pressedButtons())

        // Return inside center dead zone -> no direction held
        tracker.updateDpadPointer(10L, normalizedDx = 0.05f, normalizedDy = -0.05f)
        assertTrue(tracker.pressedButtons().isEmpty())
    }

    @Test
    fun opposingDirectionsAcrossPointersCancelDeterministically() {
        val tracker = TouchInputTracker()
        tracker.updateButtonPointer(1L, VirtualButton.LEFT, true)
        tracker.updateButtonPointer(2L, VirtualButton.RIGHT, true)
        tracker.updateButtonPointer(3L, VirtualButton.B, true)

        assertEquals(setOf(VirtualButton.B), tracker.pressedButtons())
        assertEquals(VirtualButton.B.bitMask, tracker.currentBitMask())
    }

    @Test
    fun releaseAllClearsAllActivePointersOnLifecycleInterrupt() {
        val tracker = TouchInputTracker()
        tracker.updateDpadPointer(1L, 0.0f, 0.9f)
        tracker.updateButtonPointer(2L, VirtualButton.A, true)
        tracker.updateButtonPointer(3L, VirtualButton.R, true)

        assertEquals(0, tracker.releaseAll())
        assertTrue(tracker.pressedButtons().isEmpty())
        assertEquals(0, tracker.currentBitMask())
    }
}
