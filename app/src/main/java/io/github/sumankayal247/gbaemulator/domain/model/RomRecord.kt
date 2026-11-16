package io.github.sumankayal247.gbaemulator.domain.model

/**
 * Durable game identity decoupled from display filename and mutable SAF URI (ADR-0004).
 */
data class RomRecord(
    val romId: String,
    val sha256: String,
    val system: GameSystem,
    val displayTitle: String,
    val sourceUri: String,
    val fileSizeBytes: Long,
    val lastPlayedEpochMs: Long,
    val hasBatterySave: Boolean = false,
    val sourceAvailable: Boolean = true,
) {
    companion object {
        fun buildRomId(system: GameSystem, sha256Hex: String): String =
            "${system.id}_${sha256Hex.lowercase()}"
    }
}
