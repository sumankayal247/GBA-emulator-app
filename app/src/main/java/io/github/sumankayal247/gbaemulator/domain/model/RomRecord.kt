package io.github.sumankayal247.gbaemulator.domain.model

/**
 * Durable game identity decoupled from display filename and mutable SAF URI (ADR-0004 / ADR-0005).
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
    val customTitle: String? = null,
    val coverUri: String? = null,
    val isFavorite: Boolean = false,
    val dateAddedEpochMs: Long = lastPlayedEpochMs,
    val totalPlayTimeMs: Long = 0L,
    val sourceCount: Int = 1,
) {
    val effectiveTitle: String
        get() = customTitle?.takeIf { it.isNotBlank() } ?: displayTitle

    companion object {
        fun buildRomId(system: GameSystem, sha256Hex: String): String =
            "${system.id}_${sha256Hex.lowercase()}"
    }
}

data class SavedFolderRecord(
    val treeUri: String,
    val displayName: String,
    val addedEpochMs: Long,
    val lastScannedEpochMs: Long,
    val lastScanGameCount: Int,
    val lastScanError: String? = null,
)
