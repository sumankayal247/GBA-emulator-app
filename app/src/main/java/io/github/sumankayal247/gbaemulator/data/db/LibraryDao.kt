package io.github.sumankayal247.gbaemulator.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {

    // --- Games ---
    @Query("SELECT * FROM games ORDER BY last_played_ms DESC, date_added_ms DESC")
    fun observeAllGames(): Flow<List<GameEntity>>

    @Query("SELECT * FROM games WHERE rom_id = :romId LIMIT 1")
    suspend fun getGame(romId: String): GameEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertGameIfAbsent(game: GameEntity): Long

    @Query(
        "UPDATE games SET canonical_title = :canonicalTitle, file_size_bytes = :sizeBytes, " +
            "has_battery_save = CASE WHEN :hasBatterySave = 1 THEN 1 ELSE has_battery_save END " +
            "WHERE rom_id = :romId",
    )
    suspend fun refreshGameScannedMetadata(
        romId: String,
        canonicalTitle: String,
        sizeBytes: Long,
        hasBatterySave: Boolean,
    )

    @Query("UPDATE games SET last_played_ms = :epochMs WHERE rom_id = :romId")
    suspend fun updateLastPlayed(romId: String, epochMs: Long)

    @Query("UPDATE games SET last_played_ms = 0 WHERE rom_id = :romId")
    suspend fun clearLastPlayed(romId: String)

    @Query("UPDATE games SET total_play_time_ms = total_play_time_ms + :deltaMs WHERE rom_id = :romId")
    suspend fun addPlayTime(romId: String, deltaMs: Long)

    @Query("UPDATE games SET is_favorite = :favorite WHERE rom_id = :romId")
    suspend fun setFavorite(romId: String, favorite: Boolean)

    @Query("UPDATE games SET custom_title = :customTitle WHERE rom_id = :romId")
    suspend fun setCustomTitle(romId: String, customTitle: String?)

    @Query("UPDATE games SET cover_uri = :coverUri WHERE rom_id = :romId")
    suspend fun setCoverUri(romId: String, coverUri: String?)

    @Query("UPDATE games SET has_battery_save = :hasSave WHERE rom_id = :romId")
    suspend fun setHasBatterySave(romId: String, hasSave: Boolean)

    @Query("DELETE FROM games WHERE rom_id = :romId")
    suspend fun deleteGame(romId: String)

    // --- ROM Sources (decoupled file locators) ---
    @Query("SELECT * FROM rom_sources")
    fun observeAllSources(): Flow<List<RomSourceEntity>>

    @Query("SELECT * FROM rom_sources WHERE rom_id = :romId ORDER BY is_available DESC, last_seen_ms DESC")
    suspend fun getSourcesForRom(romId: String): List<RomSourceEntity>

    @Query("SELECT * FROM rom_sources WHERE source_uri = :sourceUri LIMIT 1")
    suspend fun getSource(sourceUri: String): RomSourceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSource(source: RomSourceEntity)

    @Query("UPDATE rom_sources SET is_available = :available WHERE source_uri = :sourceUri")
    suspend fun setSourceAvailability(sourceUri: String, available: Boolean)

    @Query("UPDATE rom_sources SET is_available = 0 WHERE tree_uri = :treeUri AND last_seen_ms < :scanStartedMs")
    suspend fun markStaleSourcesUnavailableInTree(treeUri: String, scanStartedMs: Long)

    @Query("UPDATE rom_sources SET is_available = 0 WHERE source_uri LIKE 'file:%'")
    suspend fun markFileSourcesUnavailable()

    @Query("DELETE FROM rom_sources WHERE tree_uri = :treeUri")
    suspend fun deleteSourcesForTree(treeUri: String)

    // --- Saved SAF Folders ---
    @Query("SELECT * FROM saved_folders ORDER BY added_ms DESC")
    fun observeSavedFolders(): Flow<List<SavedFolderEntity>>

    @Query("SELECT * FROM saved_folders")
    suspend fun getAllSavedFolders(): List<SavedFolderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFolder(folder: SavedFolderEntity)

    @Query("DELETE FROM saved_folders WHERE tree_uri = :treeUri")
    suspend fun deleteFolder(treeUri: String)

    // --- Cheats (per-ROM isolated) ---
    @Query("SELECT * FROM cheats WHERE rom_id = :romId ORDER BY created_ms ASC, id ASC")
    fun observeCheatsForRom(romId: String): Flow<List<CheatEntity>>

    @Query("SELECT * FROM cheats WHERE rom_id = :romId ORDER BY created_ms ASC, id ASC")
    suspend fun getCheatsForRom(romId: String): List<CheatEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCheat(cheat: CheatEntity): Long

    @Query("UPDATE cheats SET description = :description, code = :code, enabled = :enabled WHERE id = :id")
    suspend fun updateCheat(id: Long, description: String, code: String, enabled: Boolean)

    @Query("UPDATE cheats SET enabled = :enabled WHERE id = :id")
    suspend fun setCheatEnabled(id: Long, enabled: Boolean)

    @Query("DELETE FROM cheats WHERE id = :id")
    suspend fun deleteCheat(id: Long)

    // --- Dynamic Core Option Overrides (global + per-ROM) ---
    @Query("SELECT * FROM core_option_overrides WHERE scope_key = :scopeKey")
    suspend fun getCoreOptionsForScope(scopeKey: String): List<CoreOptionOverrideEntity>

    @Query("SELECT * FROM core_option_overrides WHERE scope_key = :scopeKey")
    fun observeCoreOptionsForScope(scopeKey: String): Flow<List<CoreOptionOverrideEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCoreOption(entity: CoreOptionOverrideEntity)

    @Query("DELETE FROM core_option_overrides WHERE scope_key = :scopeKey")
    suspend fun clearCoreOptionsForScope(scopeKey: String)
}
