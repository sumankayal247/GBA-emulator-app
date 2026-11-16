package io.github.sumankayal247.gbaemulator

import io.github.sumankayal247.gbaemulator.domain.model.CoreOptionsJsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreOptionsJsonParserTest {

    @Test
    fun parsesValidCoreOptionsPayloadWithEscapesAndVisibility() {
        val json = """
            [
              {
                "key": "mgba_color_correction",
                "desc": "Color Correction",
                "info": "Matches \"GBA\" LCD gamut",
                "categoryKey": "video",
                "defaultValue": "OFF",
                "currentValue": "GBA",
                "visible": true,
                "choices": [
                  {"value": "OFF", "label": "Off"},
                  {"value": "GBA", "label": "Game Boy Advance"}
                ]
              },
              {
                "key": "mgba_hidden_debug",
                "desc": "Hidden",
                "info": "",
                "categoryKey": "",
                "defaultValue": "OFF",
                "currentValue": "OFF",
                "visible": false,
                "choices": [
                  {"value": "OFF", "label": "Off"}
                ]
              }
            ]
        """.trimIndent()

        val items = CoreOptionsJsonParser.parse(json)
        assertEquals(2, items.size)
        assertEquals("mgba_color_correction", items[0].key)
        assertEquals("Matches \"GBA\" LCD gamut", items[0].info)
        assertEquals("Game Boy Advance", items[0].currentLabel())
        assertTrue(items[0].visible)
        assertFalse(items[1].visible)
    }

    @Test
    fun handlesNullEmptyAndMalformedJsonSafely() {
        assertTrue(CoreOptionsJsonParser.parse(null).isEmpty())
        assertTrue(CoreOptionsJsonParser.parse("").isEmpty())
        assertTrue(CoreOptionsJsonParser.parse("{bad json").isEmpty())
        assertTrue(CoreOptionsJsonParser.parse("12345").isEmpty())
    }
}
