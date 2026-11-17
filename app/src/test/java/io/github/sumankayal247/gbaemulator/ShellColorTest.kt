package io.github.sumankayal247.gbaemulator

import io.github.sumankayal247.gbaemulator.domain.model.ShellColors
import io.github.sumankayal247.gbaemulator.domain.model.EmulatorPreferences
import io.github.sumankayal247.gbaemulator.domain.model.TouchLayoutProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShellColorTest {

    @Test
    fun normalizesValidHexAndRejectsMalformedInput() {
        assertEquals("6E00FF", ShellColors.normalize("#6e00ff"))
        assertEquals("A1B2C3", ShellColors.normalize(" a1b2c3 "))
        assertEquals("A1B2C3", ShellColors.normalize("FFA1B2C3"))
        assertEquals(ShellColors.DEFAULT_HEX, ShellColors.normalized("not-a-colour"))
        assertNull(ShellColors.normalize("#12345"))
        assertNull(ShellColors.normalize("GGHHII"))
        assertNull(ShellColors.normalize("XXA1B2C3"))
        assertNull(ShellColors.normalize(null))
    }

    @Test
    fun oldPreferencesKeepClassicColourAndExistingLayoutSerialization() {
        val oldPortrait = TouchLayoutProfile.defaultPortrait()
        val oldLandscape = TouchLayoutProfile.defaultLandscape()
        val portraitEncoded = oldPortrait.serialize()
        val landscapeEncoded = oldLandscape.serialize()
        val restored = EmulatorPreferences(
            portraitLayout = TouchLayoutProfile.deserialize(portraitEncoded, landscape = false),
            landscapeLayout = TouchLayoutProfile.deserialize(landscapeEncoded, landscape = true),
        )
        assertEquals(ShellColors.DEFAULT_HEX, restored.shellColorHex)
        assertEquals(portraitEncoded, restored.portraitLayout.serialize())
        assertEquals(landscapeEncoded, restored.landscapeLayout.serialize())
    }

    @Test
    fun hsvConversionRoundTripsRepresentativeShellColours() {
        listOf(
            ShellColors.DEFAULT_HEX,
            "168BD2",
            "00A982",
            "F26B21",
            "30343E",
            "FFFFFF",
        ).forEach { original ->
            val hsv = ShellColors.hexToHsv(original)
            val restored = ShellColors.hsvToHex(hsv.hue, hsv.saturation, hsv.value)
            assertChannelsWithinOneChannelStep(original, restored)
        }
    }

    @Test
    fun grayscaleHasNoHueOrSaturationAndBlackIsNotDefaulted() {
        val blackHsv = ShellColors.hexToHsv("000000")
        assertEquals(0f, blackHsv.hue, 0.0001f)
        assertEquals(0f, blackHsv.saturation, 0.0001f)
        assertEquals(0f, blackHsv.value, 0.0001f)
        assertNotEquals(ShellColors.DEFAULT_HEX, ShellColors.normalized("000000"))

        val whiteHsv = ShellColors.hexToHsv("FFFFFF")
        assertEquals(0f, whiteHsv.hue, 0.0001f)
        assertEquals(0f, whiteHsv.saturation, 0.0001f)
        assertEquals(1f, whiteHsv.value, 0.0001f)
    }

    @Test
    fun hsvBoundsAreClampedWithoutProducingInvalidHex() {
        val dark = ShellColors.hexToRgb(ShellColors.hsvToHex(-90f, 2.5f, -1f))
        assertEquals(0, dark.red)
        assertEquals(0, dark.green)
        assertEquals(0, dark.blue)

        val bright = ShellColors.hexToRgb(ShellColors.hsvToHex(450f, -2f, 2f))
        assertEquals(255, bright.red)
        assertEquals(255, bright.green)
        assertEquals(255, bright.blue)
    }

    private fun assertChannelsWithinOneChannelStep(expectedHex: String, actualHex: String) {
        val expected = ShellColors.hexToRgb(expectedHex)
        val actual = ShellColors.hexToRgb(actualHex)
        assertChannelClose(expected.red, actual.red, 1)
        assertChannelClose(expected.green, actual.green, 1)
        assertChannelClose(expected.blue, actual.blue, 1)
    }

    private fun assertChannelClose(expected: Int, actual: Int, tolerance: Int) {
        org.junit.Assert.assertTrue(
            "Expected $expected +/- $tolerance but was $actual",
            kotlin.math.abs(expected - actual) <= tolerance,
        )
    }
}
