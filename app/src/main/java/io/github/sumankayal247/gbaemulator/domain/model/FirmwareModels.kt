package io.github.sumankayal247.gbaemulator.domain.model

/**
 * Optional user-supplied firmware files recognized by mGBA in RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY.
 * No proprietary BIOS files are bundled with this app (ADR-0001 / ADR-0005).
 */
enum class BiosType(
    val fileName: String,
    val label: String,
    val system: GameSystem,
    val expectedSizeBytes: Int,
) {
    GBA("gba_bios.bin", "Game Boy Advance BIOS", GameSystem.GBA, 16_384),
    GBC("gbc_bios.bin", "Game Boy Color BIOS", GameSystem.GBC, 2_304),
    GB("gb_bios.bin", "Game Boy (DMG) BIOS", GameSystem.GB, 256),
}

data class BiosStatus(
    val type: BiosType,
    val installed: Boolean,
    val sha256: String? = null,
    val fileSizeBytes: Long = 0L,
)
