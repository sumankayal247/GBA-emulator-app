package io.github.sumankayal247.gbaemulator

import io.github.sumankayal247.gbaemulator.domain.model.GameSystem
import io.github.sumankayal247.gbaemulator.domain.model.RomRecord
import io.github.sumankayal247.gbaemulator.ui.home.LibraryShelves
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryShelvesTest {

    private fun game(
        index: Int,
        lastPlayed: Long = 0L,
        favorite: Boolean = false,
        title: String = "Game $index",
    ): RomRecord = RomRecord(
        romId = "gba_${index.toString().repeat(64).take(64)}",
        sha256 = index.toString().repeat(64).take(64),
        system = GameSystem.GBA,
        displayTitle = title,
        sourceUri = "content://provider/game$index",
        fileSizeBytes = 1024L,
        lastPlayedEpochMs = lastPlayed,
        isFavorite = favorite,
    )

    @Test
    fun recentsOnlyContainPlayedGamesNewestFirstAndAreBounded() {
        val games = listOf(
            game(1, lastPlayed = 100L, title = "Older"),
            game(2, lastPlayed = 300L, title = "Newest"),
            game(3, lastPlayed = 200L, title = "Middle"),
            game(4, lastPlayed = 0L, title = "Never played"),
        ) + (5..30).map { game(it, lastPlayed = it.toLong()) }

        val recents = LibraryShelves.recents(games)

        assertEquals(LibraryShelves.MAX_ITEMS, recents.size)
        assertEquals("Game 2", recents.first().effectiveTitle)
        assertFalse(recents.any { it.effectiveTitle == "Never played" })
        assertTrue(recents.zipWithNext().all { (a, b) -> a.lastPlayedEpochMs >= b.lastPlayedEpochMs })
    }

    @Test
    fun clearingLastPlayedRemovesGameFromRecentsWithoutLosingTheLibraryGame() {
        val played = game(1, lastPlayed = 500L)
        val neverPlayed = played.copy(lastPlayedEpochMs = 0L)

        assertEquals(1, LibraryShelves.recents(listOf(played)).size)
        assertTrue(LibraryShelves.recents(listOf(neverPlayed)).isEmpty())
        assertEquals(played.romId, neverPlayed.romId)
    }

    @Test
    fun favoritesOnlyContainStarredGamesSortedByTitleAndAreBounded() {
        val games = listOf(
            game(1, favorite = true, title = "Zebra"),
            game(2, favorite = false, title = "Not a favorite"),
            game(3, favorite = true, title = "Alpha"),
        ) + (4..29).map { game(it, favorite = true, title = "F${it.toString().padStart(2, '0')}") }

        val favorites = LibraryShelves.favorites(games)

        assertEquals(LibraryShelves.MAX_ITEMS, favorites.size)
        assertEquals("Alpha", favorites.first().effectiveTitle)
        assertFalse(favorites.any { it.effectiveTitle == "Not a favorite" })

        val unstarred = games[0].copy(isFavorite = false)
        assertTrue(LibraryShelves.favorites(listOf(unstarred)).isEmpty())
    }
}
