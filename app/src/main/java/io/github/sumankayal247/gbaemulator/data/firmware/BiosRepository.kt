package io.github.sumankayal247.gbaemulator.data.firmware

import io.github.sumankayal247.gbaemulator.domain.model.AppResult
import io.github.sumankayal247.gbaemulator.domain.model.BiosStatus
import io.github.sumankayal247.gbaemulator.domain.model.BiosType
import io.github.sumankayal247.gbaemulator.domain.model.EmulatorError
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

/**
 * Manages optional user-supplied BIOS files inside app-private `files/system/`
 * (passed to mGBA via RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY).
 *
 * No proprietary BIOS files are bundled with the app (ADR-0001 / ADR-0005).
 */
class BiosRepository(private val systemDir: File) {

    fun listStatuses(): List<BiosStatus> = BiosType.entries.map { getStatus(it) }

    fun getStatus(type: BiosType): BiosStatus {
        val file = File(systemDir, type.fileName)
        if (!file.exists() || !file.isFile) {
            return BiosStatus(type = type, installed = false)
        }
        val bytes = runCatching { file.readBytes() }.getOrNull()
        if (bytes == null || bytes.size != type.expectedSizeBytes) {
            return BiosStatus(type = type, installed = false)
        }
        return BiosStatus(
            type = type,
            installed = true,
            sha256 = sha256Hex(bytes),
            fileSizeBytes = bytes.size.toLong(),
        )
    }

    fun importBios(type: BiosType, input: InputStream): AppResult<BiosStatus> {
        val maxRead = type.expectedSizeBytes + 16
        val baos = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        var total = 0
        try {
            input.use { stream ->
                while (true) {
                    val read = stream.read(buf)
                    if (read <= 0) break
                    total += read
                    if (total > maxRead) {
                        return AppResult.Failure(
                            EmulatorError.InvalidBiosFile(
                                "${type.label} must be exactly ${type.expectedSizeBytes} bytes (selected file is too large).",
                            ),
                        )
                    }
                    baos.write(buf, 0, read)
                }
            }
        } catch (e: Exception) {
            return AppResult.Failure(
                EmulatorError.InvalidBiosFile("Could not read selected BIOS file: ${e.message}"),
            )
        }

        val bytes = baos.toByteArray()
        if (bytes.size != type.expectedSizeBytes) {
            return AppResult.Failure(
                EmulatorError.InvalidBiosFile(
                    "${type.label} must be exactly ${type.expectedSizeBytes} bytes (got ${bytes.size} bytes).",
                ),
            )
        }

        if (!systemDir.exists() && !systemDir.mkdirs()) {
            return AppResult.Failure(EmulatorError.InvalidBiosFile("Could not create system firmware directory."))
        }

        val dest = File(systemDir, type.fileName)
        val tmp = File(systemDir, "${type.fileName}.tmp.${System.nanoTime()}")
        return try {
            FileOutputStream(tmp).use { fos ->
                fos.write(bytes)
                fos.flush()
                fos.fd.sync()
            }
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) {
                tmp.delete()
                throw IllegalStateException("Failed to move BIOS file into system directory.")
            }
            AppResult.Success(
                BiosStatus(
                    type = type,
                    installed = true,
                    sha256 = sha256Hex(bytes),
                    fileSizeBytes = bytes.size.toLong(),
                ),
            )
        } catch (e: Exception) {
            tmp.delete()
            AppResult.Failure(EmulatorError.InvalidBiosFile("Failed to store BIOS file: ${e.message}"))
        }
    }

    fun removeBios(type: BiosType): Boolean {
        val file = File(systemDir, type.fileName)
        return file.exists() && file.delete()
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) sb.append(String.format("%02x", b))
        return sb.toString()
    }
}
