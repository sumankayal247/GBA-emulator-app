package io.github.sumankayal247.gbaemulator

import io.github.sumankayal247.gbaemulator.data.preferences.RecentGamesCodec
import io.github.sumankayal247.gbaemulator.domain.model.GameSystem
import io.github.sumankayal247.gbaemulator.domain.model.RomRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentGamesCodecTest {

    @Test
    fun roundTripsRecentGamesWithSpecialCharactersAndDeduplication() {
        val hash1 = "1".repeat(64)
        val hash2 = "2".repeat(64)
        val r1 = RomRecord(
            romId = RomRecord.buildRomId(GameSystem.GBA, hash1),
            sha256 = hash1,
            system = GameSystem.GBA,
            displayTitle = "Homebrew [v1.2] & Test\tName",
            sourceUri = "content://com.android.externalstorage.documents/document/primary%3ARoms%2Fdemo.gba",
            fileSizeBytes = 131_072L,
            lastPlayedEpochMs = 2000L,
            hasBatterySave = true,
        )
        val r2 = RomRecord(
            romId = RomRecord.buildRomId(GameSystem.GBC, hash2),
            sha256 = hash2,
            system = GameSystem.GBC,
            displayTitle = "Color Demo",
            sourceUri = "content://provider/doc/2",
            fileSizeBytes = 32_768L,
            lastPlayedEpochMs = 1000L,
            hasBatterySave = false,
        )

        val encoded = RecentGamesCodec.encode(listOf(r2, r1, r1.copy(lastPlayedEpochMs = 500L)))
        val decoded = RecentGamesCodec.decode(encoded)

        assertEquals(2, decoded.size)
        assertEquals(r1.romId, decoded[0].romId)
        assertEquals(r1.displayTitle, decoded[0].displayTitle)
        assertEquals(r1.sourceUri, decoded[0].sourceUri)
        assertEquals(true, decoded[0].hasBatterySave)
        assertEquals(r2.romId, decoded[1].romId)
    }

    @Test
    fun corruptPayloadDegradesGracefullyToEmptyList() {
        assertTrue(RecentGamesCodec.decode(null).isEmpty())
        assertTrue(RecentGamesCodec.decode("corrupt garbage").isEmpty())
        assertTrue(RecentGamesCodec.decode("v1\nbroken\tline\n").isEmpty())
    }
}
