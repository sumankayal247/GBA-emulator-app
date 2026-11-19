package io.github.sumankayal247.gbaemulator.data.rom

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import io.github.sumankayal247.gbaemulator.domain.model.AppResult
import io.github.sumankayal247.gbaemulator.domain.model.EmulatorError
import io.github.sumankayal247.gbaemulator.domain.model.RomRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

data class StagedRom(
    val record: RomRecord,
    val stagedFile: File,
)

/**
 * Handles Android SAF document ingestion without broad storage permissions (ADR-0004).
 * Never assumes a `content://` URI is a POSIX filesystem path.
 */
class RomStagingRepository(private val context: Context) {
    private val stagingDir: File
        get() = File(context.cacheDir, "staged_roms")

    suspend fun ingestFromSafUri(
        uri: Uri,
        hasBatterySaveLookup: (RomRecord) -> Boolean,
    ): AppResult<StagedRom> = withContext(Dispatchers.IO) {
        tryTakePersistablePermission(uri)

        val displayName = queryDisplayName(uri) ?: uri.lastPathSegment ?: "rom.gba"
        val stream = try {
            context.contentResolver.openInputStream(uri)
        } catch (_: SecurityException) {
            return@withContext AppResult.Failure(
                EmulatorError.MissingSourceFile(
                    romId = "",
                    message = "Permission to read this ROM file was denied or revoked.",
                ),
            )
        } catch (e: Exception) {
            return@withContext AppResult.Failure(
                EmulatorError.SaveStorageFailure("Unable to open selected file: ${e.message ?: "unknown error"}"),
            )
        } ?: return@withContext AppResult.Failure(
            EmulatorError.MissingSourceFile(
                romId = "",
                message = "Selected ROM file could not be opened.",
            ),
        )

        val validated = stream.use { input ->
            RomIngestionValidator.stageAndValidate(input, displayName, stagingDir)
        }

        when (validated) {
            is AppResult.Failure -> validated
            is AppResult.Success -> {
                pruneOldStagedFiles(keepFile = validated.value.stagedFile)
                val v = validated.value
                val baseRecord = RomRecord(
                    romId = v.romId,
                    sha256 = v.sha256,
                    system = v.system,
                    displayTitle = v.displayTitle,
                    sourceUri = uri.toString(),
                    fileSizeBytes = v.fileSizeBytes,
                    lastPlayedEpochMs = System.currentTimeMillis(),
                    sourceAvailable = true,
                )
                val record = baseRecord.copy(hasBatterySave = hasBatterySaveLookup(baseRecord))
                AppResult.Success(StagedRom(record = record, stagedFile = v.stagedFile))
            }
        }
    }

    /**
     * Resolves a previously opened [RomRecord] back to a readable staged file.
     * Re-stages from its SAF URI or granted filesystem path if the cache was cleared by Android;
     * reports a recoverable [EmulatorError.MissingSourceFile] if permission was revoked or moved.
     */
    suspend fun resolveForLaunch(record: RomRecord): AppResult<File> = withContext(Dispatchers.IO) {
        val uri = Uri.parse(record.sourceUri)
        val cached = File(stagingDir, "${record.romId}.${record.system.fileExtension}")

        if (uri.scheme?.equals("file", ignoreCase = true) == true) {
            return@withContext resolveFileSource(record, uri, cached)
        }

        // Verify source URI is still readable when possible, or re-stage from SAF if cache evicted.
        val sourceReadable = isUriReadable(uri)
        if (cached.exists() && cached.length() == record.fileSizeBytes && sourceReadable) {
            cached.setLastModified(System.currentTimeMillis())
            return@withContext AppResult.Success(cached)
        }

        if (!sourceReadable) {
            // Do not silently use stale cache if source permission was revoked — let user relink
            // cleanly while preserving their SRAM under record.romId.
            return@withContext AppResult.Failure(
                EmulatorError.MissingSourceFile(
                    romId = record.romId,
                    message = "ROM file is unavailable or permission was revoked. Use Relink ROM to select it again without losing your save.",
                ),
            )
        }

        val reStaged = ingestFromSafUri(uri) { record.hasBatterySave }
        when (reStaged) {
            is AppResult.Failure -> reStaged
            is AppResult.Success -> AppResult.Success(reStaged.value.stagedFile)
        }
    }

    suspend fun checkSourceAvailability(sourceUri: String): Boolean = withContext(Dispatchers.IO) {
        isUriReadable(Uri.parse(sourceUri))
    }

    /** Re-stage a file-backed ROM discovered by the optional device scan (ADR-0006). */
    private suspend fun resolveFileSource(
        record: RomRecord,
        uri: Uri,
        cached: File,
    ): AppResult<File> = withContext(Dispatchers.IO) {
        val path = uri.path
        if (path.isNullOrBlank()) {
            return@withContext AppResult.Failure(
                EmulatorError.MissingSourceFile(
                    record.romId,
                    "Stored ROM path is invalid. Use Relink ROM to choose the file again.",
                ),
            )
        }
        val source = File(path)
        if (!source.isFile || !source.canRead() || source.length() != record.fileSizeBytes) {
            return@withContext AppResult.Failure(
                EmulatorError.MissingSourceFile(
                    record.romId,
                    "ROM file is missing, changed, or no longer readable. Use Relink ROM; saves are kept safe.",
                ),
            )
        }
        if (cached.exists() && cached.length() == record.fileSizeBytes) {
            cached.setLastModified(System.currentTimeMillis())
            return@withContext AppResult.Success(cached)
        }

        val input = try {
            FileInputStream(source)
        } catch (_: SecurityException) {
            return@withContext AppResult.Failure(
                EmulatorError.MissingSourceFile(
                    record.romId,
                    "Storage permission was revoked for this ROM. Restore access or relink it; saves are safe.",
                ),
            )
        } catch (_: Exception) {
            return@withContext AppResult.Failure(
                EmulatorError.MissingSourceFile(record.romId, "Could not open the ROM file."),
            )
        }

        when (val validated = input.use { RomIngestionValidator.stageAndValidate(it, source.name, stagingDir) }) {
            is AppResult.Failure -> validated
            is AppResult.Success -> {
                if (validated.value.romId != record.romId) {
                    validated.value.stagedFile.delete()
                    AppResult.Failure(
                        EmulatorError.MissingSourceFile(
                            record.romId,
                            "ROM contents changed since it was indexed. Relink it to keep saves correctly scoped.",
                        ),
                    )
                } else {
                    pruneOldStagedFiles(validated.value.stagedFile)
                    AppResult.Success(validated.value.stagedFile)
                }
            }
        }
    }

    private fun isUriReadable(uri: Uri): Boolean {
        if (uri.scheme?.equals("file", ignoreCase = true) == true) {
            val path = uri.path ?: return false
            return runCatching { File(path).isFile && File(path).canRead() }.getOrDefault(false)
        }
        return try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
        } catch (_: Exception) {
            false
        }
    }

    private fun tryTakePersistablePermission(uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            // Some SAF providers do not grant persistable permissions; handled via missing-source detection.
        } catch (_: UnsupportedOperationException) {
            // Ignored safely.
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    // Keep at most 8 most recently used staged ROMs in app cache.
    private fun pruneOldStagedFiles(keepFile: File) {
        val files = stagingDir.listFiles()?.filter { it.isFile && it != keepFile } ?: return
        val maxAdditional = 7
        if (files.size <= maxAdditional) return
        files.sortedByDescending { it.lastModified() }
            .drop(maxAdditional)
            .forEach { it.delete() }
    }
}
