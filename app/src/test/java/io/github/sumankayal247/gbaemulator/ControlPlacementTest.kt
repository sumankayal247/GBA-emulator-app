package io.github.sumankayal247.gbaemulator

import io.github.sumankayal247.gbaemulator.domain.model.ControlAnchors
import io.github.sumankayal247.gbaemulator.domain.model.ControlAnchorsDefaults
import io.github.sumankayal247.gbaemulator.domain.model.ControlPlacement
import io.github.sumankayal247.gbaemulator.domain.model.ControlSlot
import io.github.sumankayal247.gbaemulator.domain.model.ControlZone
import io.github.sumankayal247.gbaemulator.domain.model.LayoutPreset
import io.github.sumankayal247.gbaemulator.domain.model.TouchLayoutProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the draggable control placement model.
 *
 * The properties that matter are geometric, not cosmetic: a control must never leave the zone no
 * matter how it is dragged or scaled, and the fractional round trip has to survive save/reload so
 * a layout built on one phone lands in the same relative place on another.
 */
class ControlPlacementTest {

    private val phoneZone = ControlZone(widthDp = 393f, heightDp = 559f)

    @Test
    fun anchorsMapToTheExtremesOfTheZone() {
        val slot = ControlSlot.DPAD
        val scale = 1f

        // (0,0) must sit flush against the top-left...
        val topLeft = ControlPlacement.resolve(phoneZone, slot, ControlAnchors(0f, 0f), scale)
        assertEquals(slot.halfWidthDp * scale, topLeft.centerXDp, 0.001f)
        assertEquals(slot.halfHeightDp * scale, topLeft.centerYDp, 0.001f)
        assertEquals(0f, topLeft.leftDp, 0.001f)
        assertEquals(0f, topLeft.topDp, 0.001f)

        // ...and (1,1) flush against the bottom-right.
        val bottomRight = ControlPlacement.resolve(phoneZone, slot, ControlAnchors(1f, 1f), scale)
        assertEquals(phoneZone.widthDp, bottomRight.rightDp, 0.001f)
        assertEquals(phoneZone.heightDp, bottomRight.bottomDp, 0.001f)
    }

    @Test
    fun everySlotStaysInsideTheZoneForAnyAnchorAndScale() {
        // Sweep the corners and a coarse grid, at every legal scale.
        val fractions = listOf(0f, 0.25f, 0.5f, 0.75f, 1f, -3f, 7f)
        val scales = listOf(
            TouchLayoutProfile.MIN_SCALE,
            1f,
            TouchLayoutProfile.MAX_SCALE,
        )
        for (slot in ControlSlot.ordered) {
            for (scale in scales) {
                for (fx in fractions) {
                    for (fy in fractions) {
                        val r = ControlPlacement.resolve(phoneZone, slot, ControlAnchors(fx, fy), scale)
                        assertTrue(
                            "slot=$slot scale=$scale f=($fx,$fy) left=${r.leftDp}",
                            r.leftDp >= -0.001f,
                        )
                        assertTrue(
                            "slot=$slot scale=$scale f=($fx,$fy) top=${r.topDp}",
                            r.topDp >= -0.001f,
                        )
                        assertTrue(
                            "slot=$slot scale=$scale f=($fx,$fy) right=${r.rightDp}",
                            r.rightDp <= phoneZone.widthDp + 0.001f,
                        )
                        assertTrue(
                            "slot=$slot scale=$scale f=($fx,$fy) bottom=${r.bottomDp}",
                            r.bottomDp <= phoneZone.heightDp + 0.001f,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun oversizedControlIsCentredInsteadOfThrownOffscreen() {
        // A zone far smaller than the control: travel collapses, so the slot centres.
        val tiny = ControlZone(widthDp = 40f, heightDp = 30f)
        val r = ControlPlacement.resolve(tiny, ControlSlot.DPAD, ControlAnchors(0f, 0f), 1f)
        assertEquals(tiny.widthDp * 0.5f, r.centerXDp, 0.001f)
        assertEquals(tiny.heightDp * 0.5f, r.centerYDp, 0.001f)
    }

    @Test
    fun anchorsForInvertsResolve() {
        val slot = ControlSlot.BUTTON_A
        val scale = 1.12f
        val original = ControlAnchors(0.37f, 0.81f)
        val resolved = ControlPlacement.resolve(phoneZone, slot, original, scale)
        val back = ControlPlacement.anchorsFor(
            phoneZone, slot, resolved.centerXDp, resolved.centerYDp, scale,
        )
        assertEquals(original.x, back.x, 0.001f)
        assertEquals(original.y, back.y, 0.001f)
    }

    @Test
    fun draggingBeyondTheEdgeSaturatesAtOne() {
        val slot = ControlSlot.DPAD
        val scale = 1f
        // Far outside the zone on both axes.
        val back = ControlPlacement.anchorsFor(phoneZone, slot, 9999f, -9999f, scale)
        assertEquals(1f, back.x, 0.0001f)
        assertEquals(0f, back.y, 0.0001f)
    }

    @Test
    fun defaultsCoverEverySlotInBothOrientations() {
        for (landscape in listOf(false, true)) {
            val defaults = ControlAnchorsDefaults.forOrientation(landscape)
            for (slot in ControlSlot.ordered) {
                assertNotNull("missing default for $slot (landscape=$landscape)", defaults[slot])
            }
            val resolved = ControlPlacement.resolveAll(phoneZone, defaults, defaults, 1f)
            assertEquals(ControlSlot.ordered.size, resolved.size)
        }
    }

    @Test
    fun editingOneSlotLeavesTheOthersAlone() {
        val base = TouchLayoutProfile.defaultPortrait()
        val moved = base.withAnchor(ControlSlot.BUTTON_A, ControlAnchors(0.2f, 0.3f))

        assertEquals(0.2f, moved.anchorsFor(ControlSlot.BUTTON_A, landscape = false).x, 0.0001f)
        // Every other slot keeps its value.
        for (slot in ControlSlot.ordered) {
            if (slot == ControlSlot.BUTTON_A) continue
            assertEquals(
                "slot $slot moved unexpectedly",
                base.anchorsFor(slot, landscape = false),
                moved.anchorsFor(slot, landscape = false),
            )
        }
        assertEquals(LayoutPreset.CUSTOM, moved.preset)
    }

    @Test
    fun anchorsSurviveSerializeAndReload() {
        val custom = TouchLayoutProfile.defaultPortrait()
            .withAnchor(ControlSlot.DPAD, ControlAnchors(0.11f, 0.42f))
            .withAnchor(ControlSlot.BUTTON_B, ControlAnchors(0.88f, 0.27f))
            .withAnchor(ControlSlot.START, ControlAnchors(0.66f, 0.93f))

        val restored = TouchLayoutProfile.deserialize(custom.serialize(), landscape = false)
        for (slot in ControlSlot.ordered) {
            val expected = custom.anchorsFor(slot, landscape = false)
            val actual = restored.anchorsFor(slot, landscape = false)
            assertEquals("slot $slot drifted", expected.x, actual.x, 0.0001f)
            assertEquals("slot $slot drifted", expected.y, actual.y, 0.0001f)
        }
    }

    @Test
    fun legacyEightFieldProfileStillLoads() {
        // Exactly what the previous release wrote.
        val legacy = "custom|1.05|0.9|4.0|-2.0|6.0|-8.0|3.0"
        val restored = TouchLayoutProfile.deserialize(legacy, landscape = false)

        assertEquals(LayoutPreset.CUSTOM, restored.preset)
        assertEquals(1.05f, restored.scale, 0.0001f)
        assertEquals(0.9f, restored.opacity, 0.0001f)
        assertEquals(4f, restored.dpadOffsetXdp, 0.0001f)
        // No anchors were stored, so the new defaults fill in — never an empty map.
        for (slot in ControlSlot.ordered) {
            assertNotNull(restored.anchorsFor(slot, landscape = false))
        }
    }

    @Test
    fun corruptAnchorTailIsIgnoredRatherThanCrashing() {
        val junk = "default|1.0|0.9|0|0|0|0|0|garbage|dpad:notanumber|b:0.5,0.5||start"
        val restored = TouchLayoutProfile.deserialize(junk, landscape = false)
        // The one well-formed token survives...
        assertEquals(0.5f, restored.anchorsFor(ControlSlot.BUTTON_B, landscape = false).x, 0.0001f)
        // ...and everything else falls back to defaults instead of throwing.
        for (slot in ControlSlot.ordered) {
            assertNotNull(restored.anchorsFor(slot, landscape = false))
        }
    }

    @Test
    fun defaultPresetsDoNotLeaveADeadBandInTheMiddle() {
        // Regression guard for the layout the owner complained looked empty: the thumb controls
        // must reach up into the upper half of the zone, not sit in the bottom sixth.
        val zone = ControlZone(widthDp = 393f, heightDp = 559f)
        val portrait = ControlPlacement.resolveAll(
            zone,
            ControlAnchorsDefaults.portrait(),
            ControlAnchorsDefaults.portrait(),
            1f,
        )
        val dpadTop = portrait.getValue(ControlSlot.DPAD).topDp
        assertTrue(
            "D-pad top at ${dpadTop}dp should be in the top half of a 559dp zone",
            dpadTop < zone.heightDp * 0.5f,
        )
        val buttonATop = portrait.getValue(ControlSlot.BUTTON_A).topDp
        assertTrue(
            "A button top at ${buttonATop}dp should be above the bottom third",
            buttonATop < zone.heightDp * 0.7f,
        )
    }
}
