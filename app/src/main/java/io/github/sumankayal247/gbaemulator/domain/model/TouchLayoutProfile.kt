package io.github.sumankayal247.gbaemulator.domain.model

enum class LayoutPreset(val id: String, val label: String) {
    DEFAULT("default", "Default"),
    COMPACT("compact", "Compact"),
    LARGE("large", "Large"),
    CUSTOM("custom", "Custom"),
    ;

    companion object {
        fun fromId(id: String): LayoutPreset = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/**
 * Separate portrait / landscape touch control profile with strict clamped safety bounds
 * so user adjustments can never push controls off-screen or into unusable positions (ADR-0005).
 */
data class TouchLayoutProfile(
    val preset: LayoutPreset = LayoutPreset.DEFAULT,
    val scale: Float = 1.0f,
    val opacity: Float = 0.92f,
    val dpadOffsetXdp: Float = 0f,
    val dpadOffsetYdp: Float = 0f,
    val buttonsOffsetXdp: Float = 0f,
    val buttonsOffsetYdp: Float = 0f,
    val shouldersOffsetYdp: Float = 0f,
) {
    fun clamped(): TouchLayoutProfile = copy(
        scale = scale.coerceIn(MIN_SCALE, MAX_SCALE),
        opacity = opacity.coerceIn(MIN_OPACITY, MAX_OPACITY),
        dpadOffsetXdp = dpadOffsetXdp.coerceIn(-MAX_OFFSET_DP, MAX_OFFSET_DP),
        dpadOffsetYdp = dpadOffsetYdp.coerceIn(-MAX_OFFSET_DP, MAX_OFFSET_DP),
        buttonsOffsetXdp = buttonsOffsetXdp.coerceIn(-MAX_OFFSET_DP, MAX_OFFSET_DP),
        buttonsOffsetYdp = buttonsOffsetYdp.coerceIn(-MAX_OFFSET_DP, MAX_OFFSET_DP),
        shouldersOffsetYdp = shouldersOffsetYdp.coerceIn(-MAX_SHOULDER_OFFSET_DP, MAX_SHOULDER_OFFSET_DP),
    )

    fun serialize(): String = listOf(
        preset.id,
        scale.toString(),
        opacity.toString(),
        dpadOffsetXdp.toString(),
        dpadOffsetYdp.toString(),
        buttonsOffsetXdp.toString(),
        buttonsOffsetYdp.toString(),
        shouldersOffsetYdp.toString(),
    ).joinToString("|")

    companion object {
        const val MIN_SCALE = 0.75f
        const val MAX_SCALE = 1.30f
        const val MIN_OPACITY = 0.35f
        const val MAX_OPACITY = 1.0f
        const val MAX_OFFSET_DP = 36f
        const val MAX_SHOULDER_OFFSET_DP = 24f

        fun forPreset(preset: LayoutPreset, landscape: Boolean): TouchLayoutProfile = when (preset) {
            LayoutPreset.DEFAULT -> if (landscape) defaultLandscape() else defaultPortrait()
            LayoutPreset.COMPACT -> TouchLayoutProfile(
                preset = LayoutPreset.COMPACT,
                scale = 0.84f,
                opacity = if (landscape) 0.78f else 0.88f,
                dpadOffsetXdp = 0f,
                dpadOffsetYdp = 8f,
                buttonsOffsetXdp = 0f,
                buttonsOffsetYdp = 8f,
                shouldersOffsetYdp = 0f,
            )
            LayoutPreset.LARGE -> TouchLayoutProfile(
                preset = LayoutPreset.LARGE,
                scale = 1.18f,
                opacity = if (landscape) 0.88f else 0.96f,
                dpadOffsetXdp = 0f,
                dpadOffsetYdp = -6f,
                buttonsOffsetXdp = 0f,
                buttonsOffsetYdp = -6f,
                shouldersOffsetYdp = 0f,
            )
            LayoutPreset.CUSTOM -> if (landscape) defaultLandscape() else defaultPortrait()
        }

        fun defaultPortrait(): TouchLayoutProfile = TouchLayoutProfile(
            preset = LayoutPreset.DEFAULT,
            scale = 1.0f,
            opacity = 0.94f,
        )

        fun defaultLandscape(): TouchLayoutProfile = TouchLayoutProfile(
            preset = LayoutPreset.DEFAULT,
            scale = 0.94f,
            opacity = 0.84f,
        )

        fun deserialize(raw: String?, landscape: Boolean): TouchLayoutProfile {
            val fallback = if (landscape) defaultLandscape() else defaultPortrait()
            if (raw.isNullOrBlank()) return fallback
            val parts = raw.split('|')
            if (parts.size < 8) return fallback
            return runCatching {
                TouchLayoutProfile(
                    preset = LayoutPreset.fromId(parts[0]),
                    scale = parts[1].toFloat(),
                    opacity = parts[2].toFloat(),
                    dpadOffsetXdp = parts[3].toFloat(),
                    dpadOffsetYdp = parts[4].toFloat(),
                    buttonsOffsetXdp = parts[5].toFloat(),
                    buttonsOffsetYdp = parts[6].toFloat(),
                    shouldersOffsetYdp = parts[7].toFloat(),
                ).clamped()
            }.getOrDefault(fallback)
        }
    }
}
