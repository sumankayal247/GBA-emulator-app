package io.github.sumankayal247.gbaemulator.domain.model

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class ShellColorPreset(
    val id: String,
    val label: String,
    val hex: String,
)

data class ShellRgbColor(
    val red: Int,
    val green: Int,
    val blue: Int,
)

data class ShellHsvColor(
    val hue: Float,
    val saturation: Float,
    val value: Float,
)

/**
 * Pure-Kotlin colour helpers for the virtual handheld shell.
 *
 * Colours are persisted as six-digit RGB hex strings (without alpha) so the domain model
 * stays free of Android/Compose dependencies and user-selected values can be validated
 * before they reach Preferences DataStore.
 */
object ShellColors {
    const val DEFAULT_HEX = "6E00FF"

    val PRESETS: List<ShellColorPreset> = listOf(
        ShellColorPreset("classic", "Classic", DEFAULT_HEX),
        ShellColorPreset("indigo", "Indigo", "3B2ACC"),
        ShellColorPreset("glacier", "Glacier", "168BD2"),
        ShellColorPreset("emerald", "Emerald", "00A982"),
        ShellColorPreset("sunset", "Sunset", "F26B21"),
        ShellColorPreset("sakura", "Sakura", "E9458C"),
        ShellColorPreset("arctic", "Arctic", "DCE5F2"),
        ShellColorPreset("graphite", "Graphite", "30343E"),
    )

    fun normalized(raw: String?): String = normalize(raw) ?: DEFAULT_HEX

    fun normalize(raw: String?): String? {
        val trimmed = raw?.trim()?.removePrefix("#") ?: return null
        if (trimmed.length != 6 && trimmed.length != 8) return null
        val isHex = trimmed.all { char ->
            char in '0'..'9' || char in 'A'..'F' || char in 'a'..'f'
        }
        if (!isHex) return null
        return (if (trimmed.length == 8) trimmed.substring(2) else trimmed).uppercase()
    }

    fun hexToRgb(raw: String?): ShellRgbColor {
        val hex = normalized(raw)
        return ShellRgbColor(
            red = hex.substring(0, 2).toInt(16),
            green = hex.substring(2, 4).toInt(16),
            blue = hex.substring(4, 6).toInt(16),
        )
    }

    fun rgbToHex(red: Int, green: Int, blue: Int): String = buildString {
        append(channelToHex(red))
        append(channelToHex(green))
        append(channelToHex(blue))
    }

    fun hexToHsv(raw: String?): ShellHsvColor {
        val rgb = hexToRgb(raw)
        val red = rgb.red / 255f
        val green = rgb.green / 255f
        val blue = rgb.blue / 255f
        val maximum = max(red, max(green, blue))
        val minimum = min(red, min(green, blue))
        val delta = maximum - minimum

        val rawHue = when {
            delta < 0.00001f -> 0f
            maximum == red -> 60f * (((green - blue) / delta) % 6f)
            maximum == green -> 60f * (((blue - red) / delta) + 2f)
            else -> 60f * (((red - green) / delta) + 4f)
        }
        val hue = if (rawHue < 0f) rawHue + 360f else rawHue
        val saturation = if (maximum <= 0f) 0f else delta / maximum
        return ShellHsvColor(
            hue = hue.coerceIn(0f, 360f),
            saturation = saturation.coerceIn(0f, 1f),
            value = maximum.coerceIn(0f, 1f),
        )
    }

    fun hsvToHex(
        hue: Float,
        saturation: Float,
        value: Float,
    ): String {
        val wrappedHue = ((hue % 360f) + 360f) % 360f
        val s = saturation.coerceIn(0f, 1f)
        val v = value.coerceIn(0f, 1f)
        val chroma = v * s
        val x = chroma * (1f - abs((wrappedHue / 60f) % 2f - 1f))
        val m = v - chroma
        val sector = (wrappedHue / 60f).toInt() % 6
        val (r1, g1, b1) = when (sector) {
            0 -> Triple(chroma, x, 0f)
            1 -> Triple(x, chroma, 0f)
            2 -> Triple(0f, chroma, x)
            3 -> Triple(0f, x, chroma)
            4 -> Triple(x, 0f, chroma)
            else -> Triple(chroma, 0f, x)
        }
        return rgbToHex(
            red = ((r1 + m) * 255f).roundToInt(),
            green = ((g1 + m) * 255f).roundToInt(),
            blue = ((b1 + m) * 255f).roundToInt(),
        )
    }

    private fun channelToHex(value: Int): String =
        value.coerceIn(0, 255).toString(16).padStart(2, '0').uppercase()
}
