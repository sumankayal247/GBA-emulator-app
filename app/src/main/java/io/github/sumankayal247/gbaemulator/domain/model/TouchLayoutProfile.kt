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
 * Separate portrait / landscape touch control profile.
 *
 * Controls are positioned by [anchors]: a fraction of the free space in the control zone per
 * [ControlSlot]. That replaces the old fixed offsets, which could only nudge whole groups by a few
 * dp and could never place a single button, and which forced the controls into a bottom band that
 * left a large empty gap on tall phones.
 *
 * Older saved profiles are still read: [deserialize] accepts the legacy 8-field format and derives
 * anchors from it. Everything is clamped on the way in and out so a corrupt or stale value can
 * never put a control off-screen (ADR-0005).
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
    val anchors: Map<ControlSlot, ControlAnchors> = emptyMap(),
) {
    fun clamped(): TouchLayoutProfile = copy(
        scale = scale.coerceIn(MIN_SCALE, MAX_SCALE),
        opacity = opacity.coerceIn(MIN_OPACITY, MAX_OPACITY),
        dpadOffsetXdp = dpadOffsetXdp.coerceIn(-MAX_OFFSET_DP, MAX_OFFSET_DP),
        dpadOffsetYdp = dpadOffsetYdp.coerceIn(-MAX_OFFSET_DP, MAX_OFFSET_DP),
        buttonsOffsetXdp = buttonsOffsetXdp.coerceIn(-MAX_OFFSET_DP, MAX_OFFSET_DP),
        buttonsOffsetYdp = buttonsOffsetYdp.coerceIn(-MAX_OFFSET_DP, MAX_OFFSET_DP),
        shouldersOffsetYdp = shouldersOffsetYdp.coerceIn(-MAX_SHOULDER_OFFSET_DP, MAX_SHOULDER_OFFSET_DP),
        anchors = anchors.mapValues { (_, value) -> value.clamped() },
    )

    /** Anchors for [slot], or the built-in default when the user has never moved it. */
    fun anchorsFor(slot: ControlSlot, landscape: Boolean): ControlAnchors =
        anchors[slot] ?: ControlAnchorsDefaults.forOrientation(landscape).getValue(slot)

    /** Full anchor map, defaults filled in for anything the user has not moved. */
    fun resolvedAnchors(landscape: Boolean): Map<ControlSlot, ControlAnchors> {
        val defaults = ControlAnchorsDefaults.forOrientation(landscape)
        return ControlSlot.ordered.associateWith { anchors[it] ?: defaults.getValue(it) }
    }

    fun withAnchor(slot: ControlSlot, anchorsValue: ControlAnchors): TouchLayoutProfile =
        copy(preset = LayoutPreset.CUSTOM, anchors = anchors + (slot to anchorsValue.clamped()))

    /**
     * Serialized form. Field 0..7 keep their original meaning and order so anything already saved
     * still parses; field 8 onward is the anchor tail, written only when anchors exist.
     */
    fun serialize(): String {
        val head = listOf(
            preset.id,
            scale.toString(),
            opacity.toString(),
            dpadOffsetXdp.toString(),
            dpadOffsetYdp.toString(),
            buttonsOffsetXdp.toString(),
            buttonsOffsetYdp.toString(),
            shouldersOffsetYdp.toString(),
        )
        val tail = ControlSlot.ordered.mapNotNull { slot ->
            anchors[slot]?.let { "${slot.id}:${it.x},${it.y}" }
        }
        return (head + tail).joinToString("|")
    }

    companion object {
        const val MIN_SCALE = 0.75f
        const val MAX_SCALE = 1.30f
        const val MIN_OPACITY = 0.35f
        const val MAX_OPACITY = 1.0f
        const val MAX_OFFSET_DP = 36f
        const val MAX_SHOULDER_OFFSET_DP = 24f

        /** Number of fields written before anchors were introduced; still accepted on read. */
        private const val LEGACY_FIELD_COUNT = 8

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
                anchors = ControlAnchorsDefaults.forOrientation(landscape),
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
                anchors = ControlAnchorsDefaults.forOrientation(landscape),
            )
            LayoutPreset.CUSTOM -> if (landscape) defaultLandscape() else defaultPortrait()
        }

        fun defaultPortrait(): TouchLayoutProfile = TouchLayoutProfile(
            preset = LayoutPreset.DEFAULT,
            scale = 1.0f,
            opacity = 0.94f,
            anchors = ControlAnchorsDefaults.portrait(),
        )

        fun defaultLandscape(): TouchLayoutProfile = TouchLayoutProfile(
            preset = LayoutPreset.DEFAULT,
            scale = 0.94f,
            opacity = 0.84f,
            anchors = ControlAnchorsDefaults.landscape(),
        )

        fun deserialize(raw: String?, landscape: Boolean): TouchLayoutProfile {
            val fallback = if (landscape) defaultLandscape() else defaultPortrait()
            if (raw.isNullOrBlank()) return fallback
            val parts = raw.split('|')
            if (parts.size < LEGACY_FIELD_COUNT) return fallback
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
                    anchors = decodeAnchors(parts.drop(LEGACY_FIELD_COUNT)),
                ).clamped()
            }.getOrDefault(fallback)
        }

        /** Reads `slot:x,y` tokens, silently dropping anything malformed. */
        private fun decodeAnchors(tokens: List<String>): Map<ControlSlot, ControlAnchors> {
            val out = LinkedHashMap<ControlSlot, ControlAnchors>()
            for (token in tokens) {
                val slot = ControlSlot.fromId(token.substringBefore(':')) ?: continue
                val coords = token.substringAfter(':', "").split(',')
                if (coords.size != 2) continue
                val x = coords[0].toFloatOrNull() ?: continue
                val y = coords[1].toFloatOrNull() ?: continue
                out[slot] = ControlAnchors(x, y).clamped()
            }
            return out
        }
    }
}
