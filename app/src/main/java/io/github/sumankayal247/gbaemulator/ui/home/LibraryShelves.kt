package io.github.sumankayal247.gbaemulator.ui.home

import io.github.sumankayal247.gbaemulator.domain.model.RomRecord

/**
 * Pure, bounded projections for the two horizontal library shelves.
 *
 * Recents/favourites are views over the one authoritative game list: removing an item changes
 * only the metadata that places it on that shelf, never the game record, its ROM file, or saves.
 */
object LibraryShelves {
    const val MAX_ITEMS = 24

    fun recents(games: List<RomRecord>): List<RomRecord> = games.asSequence()
        .filter { it.lastPlayedEpochMs > 0L }
        .sortedWith(
            compareByDescending<RomRecord> { it.lastPlayedEpochMs }
                .thenBy { it.effectiveTitle.lowercase() },
        )
        .take(MAX_ITEMS)
        .toList()

    fun favorites(games: List<RomRecord>): List<RomRecord> = games.asSequence()
        .filter { it.isFavorite }
        .sortedBy { it.effectiveTitle.lowercase() }
        .take(MAX_ITEMS)
        .toList()
}
