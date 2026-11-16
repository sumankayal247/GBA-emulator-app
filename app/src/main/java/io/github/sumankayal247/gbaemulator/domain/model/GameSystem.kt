package io.github.sumankayal247.gbaemulator.domain.model

enum class GameSystem(
    val id: String,
    val displayName: String,
    val shortLabel: String,
    val fileExtension: String,
    val nativeWidth: Int,
    val nativeHeight: Int,
    val minSizeBytes: Long,
    val maxSizeBytes: Long,
) {
    GBA(
        id = "gba",
        displayName = "Game Boy Advance",
        shortLabel = "GBA",
        fileExtension = "gba",
        nativeWidth = 240,
        nativeHeight = 160,
        minSizeBytes = 0xC0L,
        maxSizeBytes = 32L * 1024L * 1024L,
    ),
    GBC(
        id = "gbc",
        displayName = "Game Boy Color",
        shortLabel = "GBC",
        fileExtension = "gbc",
        nativeWidth = 160,
        nativeHeight = 144,
        minSizeBytes = 0x150L,
        maxSizeBytes = 8L * 1024L * 1024L,
    ),
    GB(
        id = "gb",
        displayName = "Game Boy",
        shortLabel = "GB",
        fileExtension = "gb",
        nativeWidth = 160,
        nativeHeight = 144,
        minSizeBytes = 0x150L,
        maxSizeBytes = 8L * 1024L * 1024L,
    ),
    ;

    val aspectRatio: Float
        get() = nativeWidth.toFloat() / nativeHeight.toFloat()

    companion object {
        val supportedExtensions: Set<String> = entries.map { it.fileExtension }.toSet()

        fun fromId(id: String): GameSystem? = entries.firstOrNull { it.id.equals(id, ignoreCase = true) }

        fun fromExtension(fileName: String): GameSystem? {
            val dot = fileName.lastIndexOf('.')
            if (dot < 0 || dot + 1 >= fileName.length) return null
            val ext = fileName.substring(dot + 1).lowercase()
            return entries.firstOrNull { it.fileExtension == ext }
        }
    }
}
