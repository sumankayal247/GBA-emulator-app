package io.github.sumankayal247.gbaemulator.data.saves

import io.github.sumankayal247.gbaemulator.domain.model.AppResult
import io.github.sumankayal247.gbaemulator.domain.model.EmulatorError
import io.github.sumankayal247.gbaemulator.domain.model.GameSystem
import io.github.sumankayal247.gbaemulator.domain.model.RomRecord
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

data class SramLoadResult(
    val bytes: ByteArray?,
    val recoveredFromBackup: Boolean = false,
    val noticeMessage: String? = null,
)

/**
 * Per-ROM isolated battery-backed SRAM persistence with atomic writes, `.bak` backup rotation,
 * SHA-256 metadata verification, corruption preservation, and SAF export/import (ADR-0004).
 *
 * Directory layout: `<savesRoot>/<system.id>/<sha256>/battery.sav`
 * Never keyed by game title so filename collisions cannot overwrite saves.
 */
class SramSaveRepository(private val savesRootDir: File) {

    companion object {
        const val META_FORMAT_VERSION = 1
        const val MAX_SRAM_BYTES = 256 * 1024  // 256 KiB ceiling (largest GBA Flash is 128 KiB)
        private const val PRIMARY_FILE = "battery.sav"
        private const val BACKUP_FILE = "battery.sav.bak"
        private const val META_FILE = "meta.json"
    }

    fun romSaveDir(system: GameSystem, sha256: String): File {
        val safeHash = sha256.lowercase().filter { it in '0'..'9' || it in 'a'..'f' }
        require(safeHash.length == 64) { "Invalid SHA-256 ROM identity." }
        return File(File(savesRootDir, system.id), safeHash)
    }

    fun hasSave(record: RomRecord): Boolean = hasSave(record.system, record.sha256)

    fun deleteSram(system: GameSystem, sha256: String): Boolean {
        val dir = romSaveDir(system, sha256)
        var deleted = false
        listOf(PRIMARY_FILE, BACKUP_FILE, META_FILE).forEach { name ->
            val f = File(dir, name)
            if (f.exists() && f.delete()) deleted = true
        }
        return deleted
    }

    fun hasSave(system: GameSystem, sha256: String): Boolean {
        val dir = romSaveDir(system, sha256)
        val primary = File(dir, PRIMARY_FILE)
        val backup = File(dir, BACKUP_FILE)
        return (primary.exists() && primary.length() > 0L) || (backup.exists() && backup.length() > 0L)
    }

    /**
     * Atomically persists battery-backed SRAM bytes:
     * 1. Validate size bounds.
     * 2. Write & fsync to `battery.sav.tmp`.
     * 3. Rotate existing valid `battery.sav` to `battery.sav.bak`.
     * 4. Atomically rename `.tmp` -> `battery.sav`.
     * 5. Atomically write `meta.json` containing SHA-256 checksum & format version.
     */
    fun writeSram(system: GameSystem, sha256: String, sramBytes: ByteArray): AppResult<Unit> {
        if (sramBytes.isEmpty()) {
            return AppResult.Failure(EmulatorError.SaveStorageFailure("Cannot write empty SRAM payload."))
        }
        if (sramBytes.size > MAX_SRAM_BYTES) {
            return AppResult.Failure(
                EmulatorError.SaveStorageFailure("SRAM payload exceeds maximum supported size ($MAX_SRAM_BYTES bytes)."),
            )
        }

        val dir = romSaveDir(system, sha256)
        if (!dir.exists() && !dir.mkdirs()) {
            return AppResult.Failure(EmulatorError.SaveStorageFailure("Unable to create save directory."))
        }

        val primary = File(dir, PRIMARY_FILE)
        val backup = File(dir, BACKUP_FILE)
        val temp = File(dir, "$PRIMARY_FILE.tmp")
        val backupTemp = File(dir, "$BACKUP_FILE.tmp")
        val metaFile = File(dir, META_FILE)
        val metaTemp = File(dir, "$META_FILE.tmp")

        val newChecksum = sha256Hex(sramBytes)

        return try {
            // Skip redundant disk write if existing primary already matches exact checksum.
            if (primary.exists() && primary.length().toInt() == sramBytes.size) {
                val existingChecksum = sha256Hex(primary.readBytes())
                if (existingChecksum == newChecksum) {
                    return AppResult.Success(Unit)
                }
            }

            // Write and fsync new payload to temporary file first.
            writeAndSync(temp, sramBytes)

            // Preserve last-known-good primary save as .bak before replacing.
            if (primary.exists() && primary.length() > 0L) {
                val oldBytes = primary.readBytes()
                writeAndSync(backupTemp, oldBytes)
                atomicMove(backupTemp, backup)
            }

            atomicMove(temp, primary)

            val metaJson = encodeMetaJson(
                version = META_FORMAT_VERSION,
                romId = RomRecord.buildRomId(system, sha256),
                byteLength = sramBytes.size,
                sha256 = newChecksum,
                updatedEpochMs = System.currentTimeMillis(),
            )
            writeAndSync(metaTemp, metaJson.toByteArray(Charsets.UTF_8))
            atomicMove(metaTemp, metaFile)

            AppResult.Success(Unit)
        } catch (ioe: IOException) {
            temp.delete()
            backupTemp.delete()
            metaTemp.delete()
            AppResult.Failure(
                EmulatorError.SaveStorageFailure(
                    "Failed to save battery SRAM (check available storage): ${ioe.message ?: "I/O error"}",
                ),
            )
        }
    }

    /**
     * Loads battery-backed SRAM, verifying against `meta.json` checksum when present.
     * If `battery.sav` is corrupted, preserves the corrupt file without deleting it and
     * automatically falls back to `battery.sav.bak`.
     */
    fun readSram(system: GameSystem, sha256: String): AppResult<SramLoadResult> {
        val dir = romSaveDir(system, sha256)
        val primary = File(dir, PRIMARY_FILE)
        val backup = File(dir, BACKUP_FILE)
        val metaFile = File(dir, META_FILE)

        if (!primary.exists() && !backup.exists()) {
            return AppResult.Success(SramLoadResult(bytes = null))
        }

        val expectedChecksum = if (metaFile.exists()) {
            decodeMetaSha256(runCatching { metaFile.readText(Charsets.UTF_8) }.getOrNull())
        } else {
            null
        }

        if (primary.exists() && primary.length() in 1L..MAX_SRAM_BYTES.toLong()) {
            val primaryBytes = runCatching { primary.readBytes() }.getOrNull()
            if (primaryBytes != null && primaryBytes.isNotEmpty()) {
                val actualChecksum = sha256Hex(primaryBytes)
                if (expectedChecksum == null || expectedChecksum == actualChecksum) {
                    return AppResult.Success(SramLoadResult(bytes = primaryBytes))
                }
                // Primary checksum mismatch: preserve corrupt file and attempt recovery from .bak!
                preserveCorruptPrimary(dir, primary)
            }
        } else if (primary.exists()) {
            preserveCorruptPrimary(dir, primary)
        }

        if (backup.exists() && backup.length() in 1L..MAX_SRAM_BYTES.toLong()) {
            val backupBytes = runCatching { backup.readBytes() }.getOrNull()
            if (backupBytes != null && backupBytes.isNotEmpty()) {
                return AppResult.Success(
                    SramLoadResult(
                        bytes = backupBytes,
                        recoveredFromBackup = true,
                        noticeMessage = "Primary save file appeared damaged and was preserved; restored from backup save.",
                    ),
                )
            }
        }

        return AppResult.Failure(
            EmulatorError.SaveStorageFailure(
                "Save file is corrupted and no valid backup was found. Corrupt file was preserved for manual recovery.",
            ),
        )
    }

    fun exportSram(system: GameSystem, sha256: String, outputStream: OutputStream): AppResult<Int> {
        val loaded = readSram(system, sha256)
        if (loaded is AppResult.Failure) return loaded
        val bytes = (loaded as AppResult.Success).value.bytes
            ?: return AppResult.Failure(
                EmulatorError.SaveStorageFailure("No battery save exists yet for this game."),
            )
        return try {
            outputStream.write(bytes)
            outputStream.flush()
            AppResult.Success(bytes.size)
        } catch (ioe: IOException) {
            AppResult.Failure(
                EmulatorError.SaveStorageFailure("Failed to export save file: ${ioe.message ?: "I/O error"}"),
            )
        }
    }

    fun importSram(system: GameSystem, sha256: String, inputStream: InputStream): AppResult<Int> {
        val buffer = ByteArray(MAX_SRAM_BYTES + 1)
        var total = 0
        try {
            while (total < buffer.size) {
                val read = inputStream.read(buffer, total, buffer.size - total)
                if (read <= 0) break
                total += read
            }
        } catch (ioe: IOException) {
            return AppResult.Failure(
                EmulatorError.InvalidSaveImport("Failed to read imported save file: ${ioe.message ?: "I/O error"}"),
            )
        }

        if (total == 0) {
            return AppResult.Failure(EmulatorError.InvalidSaveImport("Imported save file is empty."))
        }
        if (total > MAX_SRAM_BYTES) {
            return AppResult.Failure(
                EmulatorError.InvalidSaveImport("Imported save file exceeds maximum supported size (256 KiB)."),
            )
        }

        val payload = buffer.copyOf(total)
        return when (val written = writeSram(system, sha256, payload)) {
            is AppResult.Failure -> written
            is AppResult.Success -> AppResult.Success(total)
        }
    }

    private fun preserveCorruptPrimary(dir: File, primary: File) {
        val quarantine = File(dir, "$PRIMARY_FILE.corrupt.${System.currentTimeMillis()}")
        runCatching { primary.renameTo(quarantine) }
    }

    private fun writeAndSync(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { fos ->
            fos.write(bytes)
            fos.flush()
            fos.fd.sync()
        }
    }

    private fun atomicMove(src: File, dst: File) {
        try {
            Files.move(
                src.toPath(),
                dst.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: Exception) {
            Files.move(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun encodeMetaJson(
        version: Int,
        romId: String,
        byteLength: Int,
        sha256: String,
        updatedEpochMs: Long,
    ): String {
        val escapedRomId = romId.replace("\\", "\\\\").replace("\"", "\\\"")
        return buildString {
            append("{")
            append("\"version\":").append(version).append(',')
            append("\"romId\":\"").append(escapedRomId).append("\",")
            append("\"byteLength\":").append(byteLength).append(',')
            append("\"sha256\":\"").append(sha256).append("\",")
            append("\"updatedEpochMs\":").append(updatedEpochMs)
            append("}")
        }
    }

    private fun decodeMetaSha256(json: String?): String? {
        if (json.isNullOrBlank()) return null
        val regex = Regex("\"sha256\"\\s*:\\s*\"([0-9a-fA-F]{64})\"")
        return regex.find(json)?.groupValues?.getOrNull(1)?.lowercase()
    }
}
