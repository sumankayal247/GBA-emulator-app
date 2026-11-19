package io.github.sumankayal247.gbaemulator.data.rom

import android.content.Context
import android.os.Build
import android.os.Environment
import io.github.sumankayal247.gbaemulator.domain.model.AppResult
import io.github.sumankayal247.gbaemulator.domain.model.EmulatorError
import io.github.sumankayal247.gbaemulator.domain.model.RomRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class DeviceScanReport(
    val inspectedFiles: Int,
    val discoveredGames: Int,
    val duplicateGames: Int,
    val skippedFiles: Int,
    val warnings: List<String>,
    val newGames: Int,
)

/**
 * Optional device-wide ROM discovery using the storage access granted in ADR-0006.
 * Every candidate is validated with the same header/size/hash rules as SAF imports.
 */
class DeviceStorageScanner(
    private val context: Context,
    private val libraryRepository: LibraryRepository,
    private val rootsProvider: () -> List<File> = { defaultRoots(context) },
) {
    suspend fun scanDevice(): AppResult<DeviceScanReport> = withContext(Dispatchers.IO) {
        if (!StorageAccess.hasAccess(context)) {
            return@withContext AppResult.Failure(
                EmulatorError.UnsupportedFile(
                    "Storage access is not granted. Grant it from the Scan device button, or use Scan selected folders only.",
                ),
            )
        }

        val roots = rootsProvider().filter { it.exists() && it.isDirectory }
        if (roots.isEmpty()) {
            return@withContext AppResult.Failure(
                EmulatorError.UnsupportedFile("No readable storage location was found to scan."),
            )
        }

        val deduplicator = ScanDeduplicator()
        var newGames = 0
        val walkReport = DeviceRomWalker.scan(roots) { file ->
            val inspected = RomIngestionValidator.inspectFile(file)
            if (inspected !is AppResult.Success) {
                return@scan DeviceRomWalker.CandidateOutcome.SKIPPED
            }

            val v = inspected.value
            if (!deduplicator.accept(v.romId)) {
                return@scan DeviceRomWalker.CandidateOutcome.DUPLICATE
            }

            val record = RomRecord(
                romId = v.romId,
                sha256 = v.sha256,
                system = v.system,
                displayTitle = v.displayTitle,
                sourceUri = file.toURI().toString(),
                fileSizeBytes = v.fileSizeBytes,
                lastPlayedEpochMs = 0L,
                sourceAvailable = true,
            )
            try {
                if (libraryRepository.indexDiscoveredRom(record).isNewGame) newGames++
                DeviceRomWalker.CandidateOutcome.INDEXED
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                DeviceRomWalker.CandidateOutcome.SKIPPED
            }
        }

        AppResult.Success(
            DeviceScanReport(
                inspectedFiles = walkReport.inspectedFiles,
                discoveredGames = walkReport.discoveredGames,
                duplicateGames = walkReport.duplicateGames,
                skippedFiles = walkReport.skippedFiles,
                warnings = walkReport.warnings,
                newGames = newGames,
            ),
        )
    }

    companion object {
        @Suppress("DEPRECATION")
        fun defaultRoots(context: Context): List<File> {
            val roots = mutableListOf<File>()
            Environment.getExternalStorageDirectory()?.takeIf { it.isDirectory }?.let { roots.add(it) }

            // With All files access, removable SD cards appear as sibling directories under /storage.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                File("/storage").listFiles()
                    ?.filter { it.isDirectory && it.name != "emulated" && !it.name.startsWith(".") }
                    ?.let { roots.addAll(it) }
            }

            return roots.distinctBy { runCatching { it.canonicalPath }.getOrNull() ?: it.absolutePath }
        }
    }
}
