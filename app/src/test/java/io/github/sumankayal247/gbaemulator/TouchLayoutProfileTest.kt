package io.github.sumankayal247.gbaemulator

import io.github.sumankayal247.gbaemulator.domain.model.LayoutPreset
import io.github.sumankayal247.gbaemulator.domain.model.TouchLayoutProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchLayoutProfileTest {

    @Test
    fun clampsOutOfBoundsLayoutValuesSoControlsNeverLeaveScreen() {
        val wild = TouchLayoutProfile(
            preset = LayoutPreset.CUSTOM,
            scale = 9.5f,
            opacity = 0.02f,
            dpadOffsetXdp = 999f,
            dpadOffsetYdp = -999f,
            buttonsOffsetXdp = -500f,
            buttonsOffsetYdp = 500f,
            shouldersOffsetYdp = 200f,
        ).clamped()

        assertEquals(TouchLayoutProfile.MAX_SCALE, wild.scale, 0.0001f)
        assertEquals(TouchLayoutProfile.MIN_OPACITY, wild.opacity, 0.0001f)
        assertEquals(TouchLayoutProfile.MAX_OFFSET_DP, wild.dpadOffsetXdp, 0.0001f)
        assertEquals(-TouchLayoutProfile.MAX_OFFSET_DP, wild.dpadOffsetYdp, 0.0001f)
        assertEquals(-TouchLayoutProfile.MAX_OFFSET_DP, wild.buttonsOffsetXdp, 0.0001f)
        assertEquals(TouchLayoutProfile.MAX_OFFSET_DP, wild.buttonsOffsetYdp, 0.0001f)
        assertEquals(TouchLayoutProfile.MAX_SHOULDER_OFFSET_DP, wild.shouldersOffsetYdp, 0.0001f)
    }

    @Test
    fun serializeDeserializeRoundTripAndCorruptFallback() {
        val custom = TouchLayoutProfile(
            preset = LayoutPreset.CUSTOM,
            scale = 1.12f,
            opacity = 0.78f,
            dpadOffsetXdp = 14f,
            dpadOffsetYdp = -12f,
            buttonsOffsetXdp = -8f,
            buttonsOffsetYdp = 10f,
            shouldersOffsetYdp = -6f,
        )
        val restored = TouchLayoutProfile.deserialize(custom.serialize(), landscape = false)
        assertEquals(custom, restored)

        val fallback = TouchLayoutProfile.deserialize("corrupt|data", landscape = true)
        assertEquals(TouchLayoutProfile.defaultLandscape(), fallback)

        val compact = TouchLayoutProfile.forPreset(LayoutPreset.COMPACT, landscape = false)
        val large = TouchLayoutProfile.forPreset(LayoutPreset.LARGE, landscape = false)
        assertTrue(compact.scale < large.scale)
    }
}
