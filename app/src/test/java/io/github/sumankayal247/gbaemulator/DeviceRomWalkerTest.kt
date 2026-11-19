package io.github.sumankayal247.gbaemulator

import io.github.sumankayal247.gbaemulator.data.rom.DeviceRomWalker
import io.github.sumankayal247.gbaemulator.data.rom.RomIngestionValidator
import io.github.sumankayal247.gbaemulator.domain.model.AppResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream

class DeviceRomWalkerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun writeValidGba(file: File, seed: Byte = 1) {
        file.parentFile?.mkdirs()
        val bytes = ByteArray(1024)
        bytes[3] = 0xEA.toByte()
        bytes[0xB2] = 0x96.toByte()
        bytes[0x200] = seed
        FileOutputStream(file).use { it.write(bytes) }
    }

    private fun writeValidGbc(file: File) {
        file.parentFile?.mkdirs()
        val bytes = ByteArray(1024)
        bytes[0x104] = 0x7C
        bytes[0x105] = 0xE7.toByte()
        bytes[0x106] = 0xC0.toByte()
        bytes[0x107] = 0x00
        bytes[0x143] = 0xC0.toByte()
        FileOutputStream(file).use { it.write(bytes) }
    }

    @Test
    fun findsOnlyValidRomsOutsidePrivateAndHiddenDirectories() = runTest {
        val root = tempFolder.newFolder("storage")
        val roms = File(root, "Roms").apply { mkdirs() }
        val nested = File(roms, "nested").apply { mkdirs() }
        val privateDir = File(root, "Android/data/com.example/files").apply { mkdirs() }
        val hiddenDir = File(root, ".cache").apply { mkdirs() }

        val gba = File(roms, "Homebrew.gba").also { writeValidGba(it) }
        val gbc = File(nested, "Color.gbc").also { writeValidGbc(it) }
        File(roms, "readme.txt").writeText("not a rom")
        writeValidGba(File(privateDir, "private.gba"))
        writeValidGba(File(hiddenDir, "hidden.gba"))

        val inspectedNames = mutableListOf<String>()
        val report = DeviceRomWalker.scan(listOf(root)) { file ->
            inspectedNames.add(file.name)
            RomIngestionValidator.inspectFile(file) is AppResult.Success
        }

        assertEquals(2, report.inspectedFiles)
        assertEquals(2, report.discoveredGames)
        assertEquals(0, report.skippedFiles)
        assertTrue(report.warnings.isEmpty())
        assertEquals(listOf("Color.gbc", "Homebrew.gba"), inspectedNames.sorted())
    }

    @Test
    fun inspectFileValidatesHeaderAndProducesStableIdentity() {
        val root = tempFolder.newFolder("inspect")
        val first = File(root, "Demo.gba").also { writeValidGba(it, seed = 7) }
        val copy = File(root, "Copy.gba").also { writeValidGba(it, seed = 7) }
        val different = File(root, "Other.gba").also { writeValidGba(it, seed = 8) }
        val corrupt = File(root, "Broken.gba").also { it.writeText("not a ROM") }

        val firstResult = RomIngestionValidator.inspectFile(first)
        assertTrue(firstResult is AppResult.Success)
        val firstValue = (firstResult as AppResult.Success).value
        val copyValue = (RomIngestionValidator.inspectFile(copy) as AppResult.Success).value
        val differentValue = (RomIngestionValidator.inspectFile(different) as AppResult.Success).value

        assertEquals(firstValue.romId, copyValue.romId)
        assertTrue(firstValue.romId != differentValue.romId)
        assertEquals("Demo", firstValue.displayTitle)
        assertEquals(1024L, firstValue.fileSizeBytes)
        assertTrue(RomIngestionValidator.inspectFile(corrupt) is AppResult.Failure)
    }
}
