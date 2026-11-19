package io.github.sumankayal247.gbaemulator.data.rom

/**
 * Remembers ROM identities already handled during one scan so duplicate copies of the same
 * game (same system + SHA-256) are counted once and stored under one source (ADR-0004/0006).
 */
class ScanDeduplicator {
    private val seenRomIds = HashSet<String>()

    /** Returns true only the first time a ROM identity is seen in this scan. */
    fun accept(romId: String): Boolean = seenRomIds.add(romId)

    val uniqueCount: Int
        get() = seenRomIds.size
}
