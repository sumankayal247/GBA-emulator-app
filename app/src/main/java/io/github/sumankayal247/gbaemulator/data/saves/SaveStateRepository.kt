package io.github.sumankayal247.gbaemulator.data.saves

import io.github.sumankayal247.gbaemulator.domain.model.AppResult
import io.github.sumankayal247.gbaemulator.domain.model.EmulatorError
import io.github.sumankayal247.gbaemulator.domain.model.GameSystem
import io.github.sumankayal247.gbaemulator.domain.model.SaveStateSlot
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Per-ROM isolated 8-slot save-state storage completely separate from battery SRAM (ADR-0005).
 *
 * Layout:
 *   <savesRoot>/<system>/<sha256>/states/
 *     slot_<1..8>.state      — active raw libretro serialized state
 *     slot_<1..8>.state.bak  — previous verified snapshot
 *     slot_<1..8>.png        — optional captured frame thumbnail
 *     slot_<1..8>.meta       — key=value metadata (schemaVersion, coreVersion, savedEpochMs, sha256, byteSize)
 */
class SaveStateRepository(private val savesRootDir: File) {

    data class LoadedSaveState(
        val bytes: ByteArray,
        val coreVersion: String,
        val recoveredFromBackup: Boolean,
    )

    fun statesDir(system: GameSystem, romSha256: String): File {
        require(romSha256.matches(Regex("^[0-9a-fA-F]{64}$"))) { "Invalid ROM SHA-256." }
        return File(File(File(savesRootDir, system.id), romSha256.lowercase()), "states")
    }

    fun listSlots(system: GameSystem, romSha256: String): List<SaveStateSlot> {
        val dir = statesDir(system, romSha256)
        return (1..SaveStateSlot.SLOT_COUNT).map { slot ->
            readSlotInfo(dir, slot)
        }
    }

    fun writeState(
        system: GameSystem,
        romSha256: String,
        slotIndex: Int,
        stateBytes: ByteArray,
        coreVersion: String,
        thumbnailPngBytes: ByteArray? = null,
    ): AppResult<SaveStateSlot> {
        if (slotIndex !in 1..SaveStateSlot.SLOT_COUNT) {
            return AppResult.Failure(EmulatorError.SaveStateFailure("Invalid save-state slot $slotIndex."))
        }
        if (stateBytes.isEmpty() || stateBytes.size > MAX_STATE_BYTES) {
            return AppResult.Failure(
                EmulatorError.SaveStateFailure("Invalid save-state size (${stateBytes.size} bytes)."),
            )
        }

        val dir = statesDir(system, romSha256)
        if (!dir.exists() && !dir.mkdirs()) {
            return AppResult.Failure(EmulatorError.SaveStateFailure("Could not create save-state directory."))
        }

        val stateFile = File(dir, "slot_$slotIndex.state")
        val backupFile = File(dir, "slot_$slotIndex.state.bak")
        val metaFile = File(dir, "slot_$slotIndex.meta")
        val backupMetaFile = File(dir, "slot_$slotIndex.meta.bak")
        val thumbFile = File(dir, "slot_$slotIndex.png")

        return try {
            val shaHex = sha256Hex(stateBytes)
            val now = System.currentTimeMillis()
            val metaText = buildMeta(
                schemaVersion = SCHEMA_VERSION,
                coreVersion = coreVersion,
                savedEpochMs = now,
                byteSize = stateBytes.size.toLong(),
                sha256 = shaHex,
            )

            // Rotate existing verified primary into .bak before replacing.
            if (stateFile.exists() && stateFile.length() > 0L) {
                atomicWriteBytes(backupFile, stateFile.readBytes())
                if (metaFile.exists()) {
                    atomicWriteBytes(backupMetaFile, metaFile.readBytes())
                }
            }

            atomicWriteBytes(stateFile, stateBytes)
            atomicWriteBytes(metaFile, metaText.toByteArray(Charsets.UTF_8))

            if (thumbnailPngBytes != null && thumbnailPngBytes.isNotEmpty()) {
                atomicWriteBytes(thumbFile, thumbnailPngBytes)
            }

            AppResult.Success(
                SaveStateSlot(
                    slotIndex = slotIndex,
                    occupied = true,
                    savedEpochMs = now,
                    coreVersion = coreVersion,
                    byteSize = stateBytes.size.toLong(),
                    sha256 = shaHex,
                    thumbnailPath = thumbFile.takeIf { it.exists() }?.absolutePath,
                    corrupt = false,
                ),
            )
        } catch (e: Exception) {
            AppResult.Failure(
                EmulatorError.SaveStateFailure("Failed to write save-state slot $slotIndex: ${e.message}"),
            )
        }
    }

    fun readState(
        system: GameSystem,
        romSha256: String,
        slotIndex: Int,
    ): AppResult<LoadedSaveState> {
        if (slotIndex !in 1..SaveStateSlot.SLOT_COUNT) {
            return AppResult.Failure(EmulatorError.SaveStateFailure("Invalid save-state slot $slotIndex."))
        }
        val dir = statesDir(system, romSha256)
        val primary = File(dir, "slot_$slotIndex.state")
        val backup = File(dir, "slot_$slotIndex.state.bak")
        val metaFile = File(dir, "slot_$slotIndex.meta")
        val backupMetaFile = File(dir, "slot_$slotIndex.meta.bak")

        if (!primary.exists() && !backup.exists()) {
            return AppResult.Failure(EmulatorError.SaveStateFailure("Slot $slotIndex is empty."))
        }

        val primaryMeta = parseMeta(metaFile)
        val primaryBytes = readVerified(primary, primaryMeta["sha256"])
        if (primaryBytes != null) {
            return AppResult.Success(
                LoadedSaveState(
                    bytes = primaryBytes,
                    coreVersion = primaryMeta["coreVersion"].orEmpty(),
                    recoveredFromBackup = false,
                ),
            )
        }

        // Preserve corrupt primary for diagnostics instead of deleting silently (Plan §11.6).
        if (primary.exists()) {
            val quarantine = File(dir, "slot_${slotIndex}.state.corrupt.${System.currentTimeMillis()}")
            primary.renameTo(quarantine)
        }

        val backupMeta = parseMeta(backupMetaFile)
        val backupBytes = readVerified(backup, backupMeta["sha256"])
        if (backupBytes != null) {
            runCatching {
                atomicWriteBytes(primary, backupBytes)
                if (backupMetaFile.exists()) {
                    atomicWriteBytes(metaFile, backupMetaFile.readBytes())
                }
            }
            return AppResult.Success(
                LoadedSaveState(
                    bytes = backupBytes,
                    coreVersion = backupMeta["coreVersion"].orEmpty(),
                    recoveredFromBackup = true,
                ),
            )
        }

        return AppResult.Failure(
            EmulatorError.SaveStateFailure(
                "Save-state slot $slotIndex failed checksum verification and no valid backup was found.",
            ),
        )
    }

    fun deleteSlot(system: GameSystem, romSha256: String, slotIndex: Int): Boolean {
        if (slotIndex !in 1..SaveStateSlot.SLOT_COUNT) return false
        val dir = statesDir(system, romSha256)
        var deletedAny = false
        listOf(
            "slot_$slotIndex.state",
            "slot_$slotIndex.state.bak",
            "slot_$slotIndex.meta",
            "slot_$slotIndex.meta.bak",
            "slot_$slotIndex.png",
        ).forEach { name ->
            val f = File(dir, name)
            if (f.exists() && f.delete()) deletedAny = true
        }
        return deletedAny
    }

    fun deleteAllStatesForRom(system: GameSystem, romSha256: String) {
        val dir = statesDir(system, romSha256)
        if (dir.exists()) {
            dir.deleteRecursively()
        }
    }

    private fun readSlotInfo(dir: File, slotIndex: Int): SaveStateSlot {
        val primary = File(dir, "slot_$slotIndex.state")
        val backup = File(dir, "slot_$slotIndex.state.bak")
        if (!primary.exists() && !backup.exists()) {
            return SaveStateSlot(slotIndex = slotIndex, occupied = false)
        }
        val meta = parseMeta(File(dir, "slot_$slotIndex.meta"))
        val expectedSha = meta["sha256"]
        val primaryOk = primary.exists() && primary.length() in 1..MAX_STATE_BYTES.toLong() &&
            (expectedSha == null || runCatching { sha256Hex(primary.readBytes()) == expectedSha }.getOrDefault(false))
        val thumb = File(dir, "slot_$slotIndex.png").takeIf { it.exists() }?.absolutePath

        return SaveStateSlot(
            slotIndex = slotIndex,
            occupied = true,
            savedEpochMs = meta["savedEpochMs"]?.toLongOrNull() ?: primary.lastModified(),
            coreVersion = meta["coreVersion"].orEmpty(),
            byteSize = meta["byteSize"]?.toLongOrNull() ?: primary.length(),
            sha256 = expectedSha.orEmpty(),
            thumbnailPath = thumb,
            corrupt = !primaryOk && !backup.exists(),
        )
    }

    private fun readVerified(file: File, expectedSha: String?): ByteArray? {
        if (!file.exists() || !file.isFile) return null
        val len = file.length()
        if (len <= 0L || len > MAX_STATE_BYTES.toLong()) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        if (!expectedSha.isNullOrBlank()) {
            if (!sha256Hex(bytes).equals(expectedSha, ignoreCase = true)) {
                return null
            }
        }
        return bytes
    }

    private fun atomicWriteBytes(dest: File, bytes: ByteArray) {
        val tmp = File(dest.parentFile, "${dest.name}.tmp.${System.nanoTime()}")
        FileOutputStream(tmp).use { fos ->
            fos.write(bytes)
            fos.flush()
            fos.fd.sync()
        }
        if (dest.exists() && !tmp.renameTo(dest)) {
            if (!dest.delete() || !tmp.renameTo(dest)) {
                tmp.delete()
                throw IllegalStateException("Atomic rename failed for ${dest.name}")
            }
        } else if (!dest.exists() && !tmp.renameTo(dest)) {
            tmp.delete()
            throw IllegalStateException("Atomic rename failed for ${dest.name}")
        }
    }

    private fun buildMeta(
        schemaVersion: Int,
        coreVersion: String,
        savedEpochMs: Long,
        byteSize: Long,
        sha256: String,
    ): String {
        val cleanCore = coreVersion.replace("\n", " ").replace("\r", " ")
        return buildString {
            appendLine("schemaVersion=$schemaVersion")
            appendLine("coreVersion=$cleanCore")
            appendLine("savedEpochMs=$savedEpochMs")
            appendLine("byteSize=$byteSize")
            appendLine("sha256=$sha256")
        }
    }

    private fun parseMeta(file: File): Map<String, String> {
        if (!file.exists()) return emptyMap()
        return runCatching {
            file.readLines()
                .mapNotNull { line ->
                    val idx = line.indexOf('=')
                    if (idx > 0) line.substring(0, idx).trim() to line.substring(idx + 1).trim() else null
                }
                .toMap()
        }.getOrDefault(emptyMap())
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_STATE_BYTES = 16 * 1024 * 1024
    }
}
