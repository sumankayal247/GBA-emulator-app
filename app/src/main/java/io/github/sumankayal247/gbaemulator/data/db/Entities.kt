package io.github.sumankayal247.gbaemulator.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "games")
data class GameEntity(
    @PrimaryKey
    @ColumnInfo(name = "rom_id")
    val romId: String,
    @ColumnInfo(name = "sha256")
    val sha256: String,
    @ColumnInfo(name = "system")
    val system: String,
    @ColumnInfo(name = "canonical_title")
    val canonicalTitle: String,
    @ColumnInfo(name = "custom_title")
    val customTitle: String? = null,
    @ColumnInfo(name = "cover_uri")
    val coverUri: String? = null,
    @ColumnInfo(name = "is_favorite")
    val isFavorite: Boolean = false,
    @ColumnInfo(name = "date_added_ms")
    val dateAddedMs: Long,
    @ColumnInfo(name = "last_played_ms")
    val lastPlayedMs: Long,
    @ColumnInfo(name = "total_play_time_ms")
    val totalPlayTimeMs: Long = 0L,
    @ColumnInfo(name = "file_size_bytes")
    val fileSizeBytes: Long,
    @ColumnInfo(name = "has_battery_save")
    val hasBatterySave: Boolean = false,
)

@Entity(
    tableName = "rom_sources",
    foreignKeys = [
        ForeignKey(
            entity = GameEntity::class,
            parentColumns = ["rom_id"],
            childColumns = ["rom_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["rom_id"]),
        Index(value = ["tree_uri"]),
    ],
)
data class RomSourceEntity(
    @PrimaryKey
    @ColumnInfo(name = "source_uri")
    val sourceUri: String,
    @ColumnInfo(name = "rom_id")
    val romId: String,
    @ColumnInfo(name = "tree_uri")
    val treeUri: String? = null,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "last_seen_ms")
    val lastSeenMs: Long,
    @ColumnInfo(name = "is_available")
    val isAvailable: Boolean = true,
)

@Entity(tableName = "saved_folders")
data class SavedFolderEntity(
    @PrimaryKey
    @ColumnInfo(name = "tree_uri")
    val treeUri: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "added_ms")
    val addedMs: Long,
    @ColumnInfo(name = "last_scanned_ms")
    val lastScannedMs: Long,
    @ColumnInfo(name = "last_scan_game_count")
    val lastScanGameCount: Int = 0,
    @ColumnInfo(name = "last_scan_error")
    val lastScanError: String? = null,
)

@Entity(
    tableName = "cheats",
    foreignKeys = [
        ForeignKey(
            entity = GameEntity::class,
            parentColumns = ["rom_id"],
            childColumns = ["rom_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["rom_id"])],
)
data class CheatEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,
    @ColumnInfo(name = "rom_id")
    val romId: String,
    @ColumnInfo(name = "description")
    val description: String,
    @ColumnInfo(name = "code")
    val code: String,
    @ColumnInfo(name = "enabled")
    val enabled: Boolean = true,
    @ColumnInfo(name = "created_ms")
    val createdMs: Long,
)

@Entity(
    tableName = "core_option_overrides",
    primaryKeys = ["scope_key", "option_key"],
)
data class CoreOptionOverrideEntity(
    @ColumnInfo(name = "scope_key")
    val scopeKey: String,
    @ColumnInfo(name = "option_key")
    val optionKey: String,
    @ColumnInfo(name = "option_value")
    val optionValue: String,
)
