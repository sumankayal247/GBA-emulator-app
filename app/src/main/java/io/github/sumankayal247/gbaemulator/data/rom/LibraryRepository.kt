package io.github.sumankayal247.gbaemulator.data.rom

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import io.github.sumankayal247.gbaemulator.data.db.CheatEntity
import io.github.sumankayal247.gbaemulator.data.db.CoreOptionOverrideEntity
import io.github.sumankayal247.gbaemulator.data.db.GameEntity
import io.github.sumankayal247.gbaemulator.data.db.LibraryDao
import io.github.sumankayal247.gbaemulator.data.db.RomSourceEntity
import io.github.sumankayal247.gbaemulator.data.db.SavedFolderEntity
import io.github.sumankayal247.gbaemulator.data.preferences.PreferencesRepository
import io.github.sumankayal247.gbaemulator.data.saves.SaveStateRepository
import io.github.sumankayal247.gbaemulator.data.saves.SramSaveRepository
import io.github.sumankayal247.gbaemulator.domain.model.AppResult
import io.github.sumankayal247.gbaemulator.domain.model.CheatCodeEntry
import io.github.sumankayal247.gbaemulator.domain.model.CheatCodeValidator
import io.github.sumankayal247.gbaemulator.domain.model.EmulatorError
import io.github.sumankayal247.gbaemulator.domain.model.GameSystem
import io.github.sumankayal247.gbaemulator.domain.model.RomRecord
import io.github.sumankayal247.gbaemulator.domain.model.SavedFolderRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

data class FolderScanReport(
    val treeUri: String,
    val discoveredGames: Int,
    val skippedFiles: Int,
    val warnings: List<String>,
)

/**
 * Unified Room-backed P3 library repository managing:
 * - Decoupled game records (`games`) and SAF file locators (`rom_sources`)
 * - Bounded, cancellable, idempotent SAF `OpenDocumentTree` directory scanning
 * - Single-file SAF imports & missing-ROM relinking
 * - User metadata (custom title, favourites, cover art, play time) preserved across rescans
 * - Per-ROM cheats and global/per-ROM core option overrides
 */
class LibraryRepository(
    private val context: Context,
    private val dao: LibraryDao,
    private val stagingRepository: RomStagingRepository,
    private val sramRepository: SramSaveRepository,
    private val saveStateRepository: SaveStateRepository,
    private val preferencesRepository: PreferencesRepository,
) {
    companion object {
        const val GLOBAL_OPTION_SCOPE = "global"
        private const val MAX_SCAN_DEPTH = 4
        private const val MAX_SCAN_FILES = 500
    }

    val gamesFlow: Flow<List<RomRecord>> = combine(
        dao.observeAllGames(),
        dao.observeAllSources(),
    ) { games, sources ->
        val sourcesByRom = sources.groupBy { it.romId }
        games.mapNotNull { entity ->
            val system = GameSystem.fromId(entity.system) ?: return@mapNotNull null
            val romSources = sourcesByRom[entity.romId].orEmpty()
            val preferredSource = romSources.firstOrNull { it.isAvailable } ?: romSources.firstOrNull()
            RomRecord(
                romId = entity.romId,
                sha256 = entity.sha256,
                system = system,
                displayTitle = entity.canonicalTitle,
                sourceUri = preferredSource?.sourceUri.orEmpty(),
                fileSizeBytes = entity.fileSizeBytes,
                lastPlayedEpochMs = entity.lastPlayedMs,
                hasBatterySave = entity.hasBatterySave || sramRepository.hasSave(system, entity.sha256),
                sourceAvailable = romSources.any { it.isAvailable },
                customTitle = entity.customTitle,
                coverUri = entity.coverUri,
                isFavorite = entity.isFavorite,
                dateAddedEpochMs = entity.dateAddedMs,
                totalPlayTimeMs = entity.totalPlayTimeMs,
                sourceCount = romSources.size.coerceAtLeast(1),
            )
        }
    }

    val savedFoldersFlow: Flow<List<SavedFolderRecord>> = dao.observeSavedFolders().map { rows ->
        rows.map { row ->
            SavedFolderRecord(
                treeUri = row.treeUri,
                displayName = row.displayName,
                addedEpochMs = row.addedMs,
                lastScannedEpochMs = row.lastScannedMs,
                lastScanGameCount = row.lastScanGameCount,
                lastScanError = row.lastScanError,
            )
        }
    }

    /**
     * One-time non-destructive migration of any P1/P2 recent ROMs stored in DataStore into Room.
     */
    suspend fun migrateLegacyRecentGamesIfNeeded() = withContext(Dispatchers.IO) {
        val legacy = preferencesRepository.getLegacyRecentGamesOnce()
        if (legacy.isEmpty()) return@withContext
        val now = System.currentTimeMillis()
        for (rec in legacy) {
            dao.insertGameIfAbsent(
                GameEntity(
                    romId = rec.romId,
                    sha256 = rec.sha256,
                    system = rec.system.id,
                    canonicalTitle = rec.displayTitle,
                    dateAddedMs = rec.lastPlayedEpochMs.takeIf { it > 0 } ?: now,
                    lastPlayedMs = rec.lastPlayedEpochMs,
                    fileSizeBytes = rec.fileSizeBytes,
                    hasBatterySave = rec.hasBatterySave || sramRepository.hasSave(rec.system, rec.sha256),
                ),
            )
            if (rec.sourceUri.isNotBlank()) {
                dao.upsertSource(
                    RomSourceEntity(
                        sourceUri = rec.sourceUri,
                        romId = rec.romId,
                        treeUri = null,
                        displayName = rec.displayTitle,
                        lastSeenMs = now,
                        isAvailable = true,
                    ),
                )
            }
        }
        preferencesRepository.clearLegacyRecentGames()
    }

    suspend fun indexStagedRom(
        staged: StagedRom,
        treeUri: String? = null,
        markPlayedNow: Boolean = false,
    ): RomRecord = indexRomRecord(staged.record, treeUri, markPlayedNow)

    /**
     * Adds a filesystem-discovered ROM as an additional source for its existing content-hash
     * identity, preserving favourites, custom titles, covers, and saves (ADR-0006).
     */
    suspend fun indexDiscoveredRom(
        record: RomRecord,
        treeUri: String? = null,
    ): RomRecord = indexRomRecord(record, treeUri, markPlayedNow = false)

    private suspend fun indexRomRecord(
        rec: RomRecord,
        treeUri: String?,
        markPlayedNow: Boolean,
    ): RomRecord = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val hasSave = sramRepository.hasSave(rec.system, rec.sha256)
        val existing = dao.getGame(rec.romId)

        if (existing == null) {
            dao.insertGameIfAbsent(
                GameEntity(
                    romId = rec.romId,
                    sha256 = rec.sha256,
                    system = rec.system.id,
                    canonicalTitle = rec.displayTitle,
                    dateAddedMs = now,
                    lastPlayedMs = if (markPlayedNow) now else 0L,
                    fileSizeBytes = rec.fileSizeBytes,
                    hasBatterySave = hasSave,
                ),
            )
        } else {
            dao.refreshGameScannedMetadata(
                romId = rec.romId,
                canonicalTitle = rec.displayTitle,
                sizeBytes = rec.fileSizeBytes,
                hasBatterySave = hasSave,
            )
            if (markPlayedNow) {
                dao.updateLastPlayed(rec.romId, now)
            }
        }

        dao.upsertSource(
            RomSourceEntity(
                sourceUri = rec.sourceUri,
                romId = rec.romId,
                treeUri = treeUri,
                displayName = rec.displayTitle,
                lastSeenMs = now,
                isAvailable = true,
            ),
        )

        val updated = dao.getGame(rec.romId)
        RomRecord(
            romId = rec.romId,
            sha256 = rec.sha256,
            system = rec.system,
            displayTitle = updated?.canonicalTitle ?: rec.displayTitle,
            sourceUri = rec.sourceUri,
            fileSizeBytes = rec.fileSizeBytes,
            lastPlayedEpochMs = updated?.lastPlayedMs ?: if (markPlayedNow) now else 0L,
            hasBatterySave = (updated?.hasBatterySave ?: false) || hasSave,
            sourceAvailable = true,
            customTitle = updated?.customTitle,
            coverUri = updated?.coverUri,
            isFavorite = updated?.isFavorite ?: false,
            dateAddedEpochMs = updated?.dateAddedMs ?: now,
            totalPlayTimeMs = updated?.totalPlayTimeMs ?: 0L,
        )
    }

    /**
     * Resolves an existing game record from any of its known available SAF sources (or staged cache),
     * updating source availability cleanly if SAF access was revoked.
     */
    suspend fun prepareRomForLaunch(record: RomRecord): AppResult<Pair<RomRecord, File>> =
        withContext(Dispatchers.IO) {
            val sources = dao.getSourcesForRom(record.romId)
            val candidateUris = buildList {
                sources.forEach { add(it.sourceUri) }
                if (record.sourceUri.isNotBlank() && none { it == record.sourceUri }) {
                    add(record.sourceUri)
                }
            }

            var lastError: EmulatorError? = null
            for (uriStr in candidateUris) {
                val candidate = record.copy(sourceUri = uriStr)
                when (val res = stagingRepository.resolveForLaunch(candidate)) {
                    is AppResult.Success -> {
                        dao.setSourceAvailability(uriStr, true)
                        dao.updateLastPlayed(record.romId, System.currentTimeMillis())
                        return@withContext AppResult.Success(
                            candidate.copy(sourceAvailable = true) to res.value,
                        )
                    }
                    is AppResult.Failure -> {
                        if (res.error is EmulatorError.MissingSourceFile) {
                            dao.setSourceAvailability(uriStr, false)
                        }
                        lastError = res.error
                    }
                }
            }

            AppResult.Failure(
                lastError ?: EmulatorError.MissingSourceFile(
                    record.romId,
                    "ROM source is unavailable. Use Relink ROM to select it again without losing your saves.",
                ),
            )
        }

    /**
     * Adds and scans a SAF folder (`OpenDocumentTree`), persisting tree permission and handling
     * provider quirks gracefully without wiping already indexed games on partial failures.
     */
    suspend fun addAndScanFolder(treeUri: Uri): AppResult<FolderScanReport> = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        val displayName = resolveTreeDisplayName(treeUri)
        val now = System.currentTimeMillis()
        dao.upsertFolder(
            SavedFolderEntity(
                treeUri = treeUri.toString(),
                displayName = displayName,
                addedMs = now,
                lastScannedMs = 0L,
                lastScanGameCount = 0,
            ),
        )
        scanFolder(treeUri)
    }

    suspend fun scanAllSavedFolders(): List<FolderScanReport> = withContext(Dispatchers.IO) {
        val folders = dao.getAllSavedFolders()
        folders.mapNotNull { folder ->
            val uri = runCatching { Uri.parse(folder.treeUri) }.getOrNull() ?: return@mapNotNull null
            when (val res = scanFolder(uri)) {
                is AppResult.Success -> res.value
                is AppResult.Failure -> FolderScanReport(
                    treeUri = folder.treeUri,
                    discoveredGames = 0,
                    skippedFiles = 0,
                    warnings = listOf(res.error.userMessage),
                )
            }
        }
    }

    suspend fun scanFolder(treeUri: Uri): AppResult<FolderScanReport> = withContext(Dispatchers.IO) {
        val scanStartedMs = System.currentTimeMillis()
        val treeUriStr = treeUri.toString()
        val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return@withContext AppResult.Failure(
                EmulatorError.UnsupportedFile("Selected folder cannot be scanned through Android SAF."),
            )

        val warnings = mutableListOf<String>()
        var discoveredCount = 0
        var skippedCount = 0
        var inspectedCount = 0

        suspend fun walkDirectory(docId: String, depth: Int) {
            currentCoroutineContext().ensureActive()
            if (depth > MAX_SCAN_DEPTH || inspectedCount >= MAX_SCAN_FILES) return

            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
            )

            val cursor = try {
                context.contentResolver.query(childrenUri, projection, null, null, null)
            } catch (e: SecurityException) {
                warnings.add("Folder access was revoked. Re-select the folder to restore scanning.")
                return
            } catch (e: Exception) {
                warnings.add("Skipped unreadable subfolder: ${e.message ?: "provider error"}")
                return
            }

            if (cursor == null) {
                warnings.add("Storage provider returned an empty directory listing.")
                return
            }

            val subdirs = mutableListOf<String>()
            cursor.use { c ->
                val idIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)

                while (c.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    if (inspectedCount >= MAX_SCAN_FILES) {
                        warnings.add("Stopped after scanning $MAX_SCAN_FILES ROM candidates.")
                        break
                    }
                    if (idIdx < 0 || nameIdx < 0) continue
                    val childId = c.getString(idIdx) ?: continue
                    val name = c.getString(nameIdx) ?: continue
                    val mime = if (mimeIdx >= 0) c.getString(mimeIdx) else null
                    val reportedSize = if (sizeIdx >= 0 && !c.isNull(sizeIdx)) c.getLong(sizeIdx) else -1L

                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        subdirs.add(childId)
                        continue
                    }

                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (ext !in setOf("gba", "gbc", "gb")) {
                        continue
                    }
                    inspectedCount++

                    if (reportedSize > GameSystem.GBA.maxSizeBytes) {
                        skippedCount++
                        continue
                    }

                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                    val indexed = inspectAndIndexDocument(docUri, treeUriStr, name, scanStartedMs)
                    if (indexed) {
                        discoveredCount++
                    } else {
                        skippedCount++
                    }
                }
            }

            for (subId in subdirs) {
                walkDirectory(subId, depth + 1)
            }
        }

        try {
            walkDirectory(rootDocId, 0)
        } catch (ce: CancellationException) {
            throw ce
        }

        // Only mark unseen sources unavailable when no root permission error occurred.
        if (warnings.none { it.contains("revoked", ignoreCase = true) }) {
            dao.markStaleSourcesUnavailableInTree(treeUriStr, scanStartedMs)
        }

        val folderName = resolveTreeDisplayName(treeUri)
        dao.upsertFolder(
            SavedFolderEntity(
                treeUri = treeUriStr,
                displayName = folderName,
                addedMs = scanStartedMs,
                lastScannedMs = System.currentTimeMillis(),
                lastScanGameCount = discoveredCount,
                lastScanError = warnings.firstOrNull(),
            ),
        )

        AppResult.Success(
            FolderScanReport(
                treeUri = treeUriStr,
                discoveredGames = discoveredCount,
                skippedFiles = skippedCount,
                warnings = warnings,
            ),
        )
    }

    /**
     * Streams header + SHA-256 directly from SAF without creating a permanent duplicate copy
     * during directory scans (staging happens lazily on launch).
     */
    private suspend fun inspectAndIndexDocument(
        docUri: Uri,
        treeUriStr: String,
        displayName: String,
        seenEpochMs: Long,
    ): Boolean {
        val extRes = RomIngestionValidator.validateExtension(displayName)
        if (extRes !is AppResult.Success) return false
        val extSystem = extRes.value

        val stream = runCatching { context.contentResolver.openInputStream(docUri) }.getOrNull()
            ?: return false

        val headerBuf = ByteArray(1024)
        var headerFilled = 0
        var totalBytes = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        val chunk = ByteArray(64 * 1024)

        try {
            stream.use { input ->
                while (true) {
                    val read = input.read(chunk)
                    if (read <= 0) break
                    totalBytes += read
                    if (totalBytes > extSystem.maxSizeBytes) return false
                    if (headerFilled < headerBuf.size) {
                        val copyLen = minOf(read, headerBuf.size - headerFilled)
                        System.arraycopy(chunk, 0, headerBuf, headerFilled, copyLen)
                        headerFilled += copyLen
                    }
                    digest.update(chunk, 0, read)
                }
            }
        } catch (_: Exception) {
            return false
        }

        val headerRes = RomIngestionValidator.validateHeader(headerBuf, headerFilled, totalBytes, extSystem)
        if (headerRes !is AppResult.Success) return false
        val system = headerRes.value

        val shaHex = digest.digest().joinToString("") { "%02x".format(it) }
        val romId = RomRecord.buildRomId(system, shaHex)
        val cleanTitle = RomIngestionValidator.deriveDisplayTitle(displayName)
        val hasSave = sramRepository.hasSave(system, shaHex)

        val existing = dao.getGame(romId)
        if (existing == null) {
            dao.insertGameIfAbsent(
                GameEntity(
                    romId = romId,
                    sha256 = shaHex,
                    system = system.id,
                    canonicalTitle = cleanTitle,
                    dateAddedMs = seenEpochMs,
                    lastPlayedMs = 0L,
                    fileSizeBytes = totalBytes,
                    hasBatterySave = hasSave,
                ),
            )
        } else {
            dao.refreshGameScannedMetadata(romId, cleanTitle, totalBytes, hasSave)
        }

        dao.upsertSource(
            RomSourceEntity(
                sourceUri = docUri.toString(),
                romId = romId,
                treeUri = treeUriStr,
                displayName = displayName,
                lastSeenMs = seenEpochMs,
                isAvailable = true,
            ),
        )
        return true
    }

    suspend fun removeSavedFolder(treeUri: String) = withContext(Dispatchers.IO) {
        dao.deleteSourcesForTree(treeUri)
        dao.deleteFolder(treeUri)
    }

    suspend fun setFavorite(romId: String, favorite: Boolean) = withContext(Dispatchers.IO) {
        dao.setFavorite(romId, favorite)
    }

    /**
     * Hides a game from the Recently Played shelf without removing it from the library.
     * Its SRAM, save states, and original ROM file are untouched.
     */
    suspend fun clearRecentHistory(romId: String) = withContext(Dispatchers.IO) {
        dao.clearLastPlayed(romId)
    }

    /** Called when broad storage access is declined/revoked so file-backed sources show a warning. */
    suspend fun markDeviceSourcesUnavailable() = withContext(Dispatchers.IO) {
        dao.markFileSourcesUnavailable()
    }

    suspend fun setCustomTitle(romId: String, title: String?) = withContext(Dispatchers.IO) {
        dao.setCustomTitle(romId, title?.trim()?.takeIf { it.isNotEmpty() })
    }

    suspend fun setCustomCover(romId: String, coverUri: Uri?) = withContext(Dispatchers.IO) {
        if (coverUri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    coverUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
        dao.setCoverUri(romId, coverUri?.toString())
    }

    suspend fun markBatterySavePresent(romId: String, hasSave: Boolean) = withContext(Dispatchers.IO) {
        dao.setHasBatterySave(romId, hasSave)
    }

    suspend fun recordPlayTime(romId: String, deltaMs: Long) = withContext(Dispatchers.IO) {
        if (deltaMs > 0L) {
            dao.addPlayTime(romId, deltaMs)
        }
    }

    /**
     * Removes ONLY the library entry and cached staged copy — NEVER deletes the user's original
     * ROM file or battery/state saves (Plan §11.3).
     */
    suspend fun removeFromLibrary(romId: String) = withContext(Dispatchers.IO) {
        dao.deleteGame(romId)
    }

    /**
     * Explicit user-confirmed save wipe (separate from Remove from Library).
     */
    suspend fun deleteAllSavesForRom(record: RomRecord) = withContext(Dispatchers.IO) {
        sramRepository.deleteSram(record.system, record.sha256)
        saveStateRepository.deleteAllStatesForRom(record.system, record.sha256)
        dao.setHasBatterySave(record.romId, false)
    }

    // --- Cheats (per-ROM isolated) ---
    fun observeCheats(romId: String): Flow<List<CheatCodeEntry>> =
        dao.observeCheatsForRom(romId).map { list ->
            list.map {
                CheatCodeEntry(
                    id = it.id,
                    romId = it.romId,
                    description = it.description,
                    code = it.code,
                    enabled = it.enabled,
                    createdEpochMs = it.createdMs,
                )
            }
        }

    suspend fun getEnabledCheatCodes(romId: String): List<String> = withContext(Dispatchers.IO) {
        dao.getCheatsForRom(romId).filter { it.enabled }.map { it.code }
    }

    suspend fun addCheat(
        romId: String,
        system: GameSystem,
        description: String,
        rawCode: String,
    ): AppResult<CheatCodeEntry> = withContext(Dispatchers.IO) {
        val cleanDesc = description.trim().ifBlank { "Cheat Code" }
        val validated = CheatCodeValidator.validateAndNormalize(system, rawCode)
        if (validated is AppResult.Failure) return@withContext validated
        val normalized = (validated as AppResult.Success).value
        val now = System.currentTimeMillis()
        val id = dao.insertCheat(
            CheatEntity(
                romId = romId,
                description = cleanDesc,
                code = normalized,
                enabled = true,
                createdMs = now,
            ),
        )
        AppResult.Success(
            CheatCodeEntry(
                id = id,
                romId = romId,
                description = cleanDesc,
                code = normalized,
                enabled = true,
                createdEpochMs = now,
            ),
        )
    }

    suspend fun updateCheat(
        id: Long,
        system: GameSystem,
        description: String,
        rawCode: String,
        enabled: Boolean,
    ): AppResult<String> = withContext(Dispatchers.IO) {
        val cleanDesc = description.trim().ifBlank { "Cheat Code" }
        val validated = CheatCodeValidator.validateAndNormalize(system, rawCode)
        if (validated is AppResult.Failure) return@withContext validated
        val normalized = (validated as AppResult.Success).value
        dao.updateCheat(id, cleanDesc, normalized, enabled)
        AppResult.Success(normalized)
    }

    suspend fun toggleCheat(id: Long, enabled: Boolean) = withContext(Dispatchers.IO) {
        dao.setCheatEnabled(id, enabled)
    }

    suspend fun deleteCheat(id: Long) = withContext(Dispatchers.IO) {
        dao.deleteCheat(id)
    }

    // --- Core Options Overrides (global + per-ROM) ---
    suspend fun getEffectiveCoreOptions(romId: String): Map<String, String> = withContext(Dispatchers.IO) {
        val merged = LinkedHashMap<String, String>()
        dao.getCoreOptionsForScope(GLOBAL_OPTION_SCOPE).forEach { merged[it.optionKey] = it.optionValue }
        dao.getCoreOptionsForScope(romId).forEach { merged[it.optionKey] = it.optionValue }
        merged
    }

    suspend fun saveCoreOptionOverride(scopeKey: String, key: String, value: String) = withContext(Dispatchers.IO) {
        dao.upsertCoreOption(CoreOptionOverrideEntity(scopeKey = scopeKey, optionKey = key, optionValue = value))
    }

    suspend fun resetCoreOptions(scopeKey: String) = withContext(Dispatchers.IO) {
        dao.clearCoreOptionsForScope(scopeKey)
    }

    private fun resolveTreeDisplayName(treeUri: Uri): String {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull().orEmpty()
        val tail = docId.substringAfter(':', docId).trimEnd('/').substringAfterLast('/', docId)
        return tail.ifBlank { "Game Folder" }
    }
}
