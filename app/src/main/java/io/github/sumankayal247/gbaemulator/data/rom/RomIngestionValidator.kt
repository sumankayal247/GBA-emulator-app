package io.github.sumankayal247.gbaemulator.data.rom

import io.github.sumankayal247.gbaemulator.domain.model.AppResult
import io.github.sumankayal247.gbaemulator.domain.model.EmulatorError
import io.github.sumankayal247.gbaemulator.domain.model.GameSystem
import io.github.sumankayal247.gbaemulator.domain.model.RomRecord
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

data class ValidatedRomStream(
    val romId: String,
    val sha256: String,
    val system: GameSystem,
    val displayTitle: String,
    val fileSizeBytes: Long,
    val stagedFile: File,
)

/**
 * Untrusted ROM ingestion validator (ADR-0004).
 * Streams ROM bytes in bounded 64 KiB chunks while validating extension, header magic,
 * size limits, and computing SHA-256 without loading whole files into Java heap.
 */
object RomIngestionValidator {
    private const val CHUNK_SIZE_BYTES = 64 * 1024
    private const val HEADER_CAPTURE_BYTES = 0x200

    private val ARCHIVE_EXTENSIONS = setOf("zip", "7z", "rar", "tar", "gz")

    fun deriveDisplayTitle(fileName: String): String {
        val clean = fileName.substringAfterLast('/').substringAfterLast('\\').trim()
        val dot = clean.lastIndexOf('.')
        val stem = if (dot > 0) clean.substring(0, dot) else clean
        return stem.ifBlank { "Untitled Game" }
    }

    fun validateExtension(fileName: String): AppResult<GameSystem> {
        val dot = fileName.lastIndexOf('.')
        val ext = if (dot >= 0 && dot + 1 < fileName.length) {
            fileName.substring(dot + 1).lowercase()
        } else {
            ""
        }
        if (ext in ARCHIVE_EXTENSIONS) {
            return AppResult.Failure(
                EmulatorError.UnsupportedFile(
                    "Compressed archives (.$ext) are not automatically extracted. Please select an uncompressed .gba, .gbc, or .gb ROM file.",
                ),
            )
        }
        val system = GameSystem.fromExtension(fileName)
            ?: return AppResult.Failure(
                EmulatorError.UnsupportedFile(
                    "Unsupported file type. Select a .gba, .gbc, or .gb ROM file.",
                ),
            )
        return AppResult.Success(system)
    }

    fun validateHeader(
        header: ByteArray,
        headerLength: Int,
        totalSizeBytes: Long,
        extensionSystem: GameSystem,
    ): AppResult<GameSystem> {
        if (totalSizeBytes <= 0L || headerLength <= 0) {
            return AppResult.Failure(EmulatorError.CorruptOrTruncatedRom("ROM file is empty."))
        }

        if (extensionSystem == GameSystem.GBA) {
            if (totalSizeBytes < GameSystem.GBA.minSizeBytes || headerLength < GameSystem.GBA.minSizeBytes) {
                return AppResult.Failure(
                    EmulatorError.CorruptOrTruncatedRom("GBA ROM file is truncated (smaller than 192-byte header)."),
                )
            }
            if (totalSizeBytes > GameSystem.GBA.maxSizeBytes) {
                return AppResult.Failure(
                    EmulatorError.CorruptOrTruncatedRom("GBA ROM exceeds the 32 MiB size limit."),
                )
            }
            val branchOpcode = header[3].toInt() and 0xFF
            val fixedByte = header[0xB2].toInt() and 0xFF
            if (branchOpcode != 0xEA || fixedByte != 0x96) {
                return AppResult.Failure(
                    EmulatorError.CorruptOrTruncatedRom("File does not have a valid Game Boy Advance ROM header."),
                )
            }
            return AppResult.Success(GameSystem.GBA)
        }

        // GB or GBC
        if (totalSizeBytes < GameSystem.GB.minSizeBytes || headerLength < GameSystem.GB.minSizeBytes) {
            return AppResult.Failure(
                EmulatorError.CorruptOrTruncatedRom("GB/GBC ROM file is truncated (smaller than 336-byte header)."),
            )
        }
        if (totalSizeBytes > GameSystem.GB.maxSizeBytes) {
            return AppResult.Failure(
                EmulatorError.CorruptOrTruncatedRom("GB/GBC ROM exceeds the 8 MiB size limit."),
            )
        }

        val b0 = header[0x104].toInt() and 0xFF
        val b1 = header[0x105].toInt() and 0xFF
        val b2 = header[0x106].toInt() and 0xFF
        val b3 = header[0x107].toInt() and 0xFF
        val knownLogo = (b0 == 0xCE && b1 == 0xED && b2 == 0x66 && b3 == 0x66)
        val sachenLogo = (b0 == 0x7C && b1 == 0xE7 && b2 == 0xC0 && b3 == 0x00)
        if (!knownLogo && !sachenLogo) {
            return AppResult.Failure(
                EmulatorError.CorruptOrTruncatedRom("File does not have a valid Game Boy / Game Boy Color header."),
            )
        }

        val cgbFlag = header[0x143].toInt() and 0xFF
        val isCgb = (extensionSystem == GameSystem.GBC) || (cgbFlag == 0x80 || cgbFlag == 0xC0)
        return AppResult.Success(if (isCgb) GameSystem.GBC else GameSystem.GB)
    }

    /**
     * Streams [inputStream] safely to a temporary file inside [stagingDir], validates headers
     * and size bounds, computes SHA-256, and moves the staged file to `<romId>.<ext>`.
     */
    fun stageAndValidate(
        inputStream: InputStream,
        originalFileName: String,
        stagingDir: File,
    ): AppResult<ValidatedRomStream> {
        val extResult = validateExtension(originalFileName)
        if (extResult is AppResult.Failure) {
            return extResult
        }
        val extSystem = (extResult as AppResult.Success).value

        if (!stagingDir.exists() && !stagingDir.mkdirs()) {
            return AppResult.Failure(
                EmulatorError.SaveStorageFailure("Unable to create ROM staging directory."),
            )
        }

        val tempFile = File(stagingDir, "staging_${System.nanoTime()}.tmp")
        val digest = MessageDigest.getInstance("SHA-256")
        val headerBuf = ByteArray(HEADER_CAPTURE_BYTES)
        var headerCaptured = 0
        var totalBytes = 0L
        val chunk = ByteArray(CHUNK_SIZE_BYTES)

        try {
            FileOutputStream(tempFile).use { fos ->
                while (true) {
                    val read = inputStream.read(chunk)
                    if (read <= 0) break

                    totalBytes += read
                    if (totalBytes > extSystem.maxSizeBytes) {
                        tempFile.delete()
                        return AppResult.Failure(
                            EmulatorError.CorruptOrTruncatedRom(
                                "${extSystem.shortLabel} ROM exceeds maximum allowed size (${extSystem.maxSizeBytes / (1024 * 1024)} MiB).",
                            ),
                        )
                    }

                    if (headerCaptured < HEADER_CAPTURE_BYTES) {
                        val toCopy = minOf(read, HEADER_CAPTURE_BYTES - headerCaptured)
                        System.arraycopy(chunk, 0, headerBuf, headerCaptured, toCopy)
                        headerCaptured += toCopy
                    }

                    digest.update(chunk, 0, read)
                    fos.write(chunk, 0, read)
                }
                fos.flush()
            }
        } catch (ioe: IOException) {
            tempFile.delete()
            return AppResult.Failure(
                EmulatorError.SaveStorageFailure(
                    "Failed while reading ROM file (storage full or unreadable source): ${ioe.message ?: "I/O error"}",
                ),
            )
        }

        val headerCheck = validateHeader(headerBuf, headerCaptured, totalBytes, extSystem)
        if (headerCheck is AppResult.Failure) {
            tempFile.delete()
            return headerCheck
        }
        val detectedSystem = (headerCheck as AppResult.Success).value
        val sha256Hex = digest.digest().joinToString("") { "%02x".format(it) }
        val romId = RomRecord.buildRomId(detectedSystem, sha256Hex)
        val finalFile = File(stagingDir, "$romId.${detectedSystem.fileExtension}")

        if (finalFile.exists()) {
            // Identical content-addressed ROM already staged; replace cleanly.
            finalFile.delete()
        }
        if (!tempFile.renameTo(finalFile)) {
            tempFile.delete()
            return AppResult.Failure(
                EmulatorError.SaveStorageFailure("Unable to finalize staged ROM file."),
            )
        }

        return AppResult.Success(
            ValidatedRomStream(
                romId = romId,
                sha256 = sha256Hex,
                system = detectedSystem,
                displayTitle = deriveDisplayTitle(originalFileName),
                fileSizeBytes = totalBytes,
                stagedFile = finalFile,
            ),
        )
    }
}
