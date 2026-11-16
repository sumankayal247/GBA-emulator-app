package io.github.sumankayal247.gbaemulator

import io.github.sumankayal247.gbaemulator.data.rom.RomIngestionValidator
import io.github.sumankayal247.gbaemulator.domain.model.AppResult
import io.github.sumankayal247.gbaemulator.domain.model.EmulatorError
import io.github.sumankayal247.gbaemulator.domain.model.GameSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream

class RomIngestionValidatorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun makeValidGbaBytes(seed: Byte = 1): ByteArray {
        val bytes = ByteArray(1024)
        bytes[3] = 0xEA.toByte()
        bytes[0xB2] = 0x96.toByte()
        bytes[0x200] = seed
        return bytes
    }

    private fun makeValidGbBytes(cgbFlag: Byte): ByteArray {
        val bytes = ByteArray(1024)
        // Open Sachen 4-byte magic recognized by mGBA GBIsROM
        bytes[0x104] = 0x7C
        bytes[0x105] = 0xE7.toByte()
        bytes[0x106] = 0xC0.toByte()
        bytes[0x107] = 0x00
        bytes[0x143] = cgbFlag
        return bytes
    }

    @Test
    fun stagesAndValidatesGbaGbcAndGbRomsWithStableContentHashIdentity() {
        val dir = tempFolder.newFolder("staged")

        val gbaRes = RomIngestionValidator.stageAndValidate(
            ByteArrayInputStream(makeValidGbaBytes(seed = 1)),
            "Homebrew Demo.gba",
            dir,
        )
        assertTrue(gbaRes is AppResult.Success)
        val gba = (gbaRes as AppResult.Success).value
        assertEquals(GameSystem.GBA, gba.system)
        assertEquals("Homebrew Demo", gba.displayTitle)
        assertTrue(gba.stagedFile.exists())

        // Same filename with different ROM bytes MUST produce a different ROM ID (no collisions!)
        val gbaRes2 = RomIngestionValidator.stageAndValidate(
            ByteArrayInputStream(makeValidGbaBytes(seed = 2)),
            "Homebrew Demo.gba",
            dir,
        )
        assertTrue(gbaRes2 is AppResult.Success)
        assertNotEquals(gba.romId, (gbaRes2 as AppResult.Success).value.romId)

        // Different filename with identical ROM bytes MUST produce the same ROM ID (relinking / duplicates!)
        val gbaDuplicate = RomIngestionValidator.stageAndValidate(
            ByteArrayInputStream(makeValidGbaBytes(seed = 1)),
            "Renamed Copy.gba",
            dir,
        )
        assertEquals(gba.romId, (gbaDuplicate as AppResult.Success).value.romId)

        // GBC detection via header CGB flag
        val gbcRes = RomIngestionValidator.stageAndValidate(
            ByteArrayInputStream(makeValidGbBytes(0xC0.toByte())),
            "ColorDemo.gbc",
            dir,
        )
        assertEquals(GameSystem.GBC, (gbcRes as AppResult.Success).value.system)

        // Original GB (DMG) detection
        val gbRes = RomIngestionValidator.stageAndValidate(
            ByteArrayInputStream(makeValidGbBytes(0x00)),
            "MonoDemo.gb",
            dir,
        )
        assertEquals(GameSystem.GB, (gbRes as AppResult.Success).value.system)
    }

    @Test
    fun rejectsArchivesUnsupportedExtensionsEmptyTruncatedAndCorruptFiles() {
        val dir = tempFolder.newFolder("staged_neg")

        val zipRes = RomIngestionValidator.stageAndValidate(
            ByteArrayInputStream(ByteArray(512)),
            "packed.zip",
            dir,
        )
        assertTrue(zipRes is AppResult.Failure && zipRes.error is EmulatorError.UnsupportedFile)

        val ndsRes = RomIngestionValidator.stageAndValidate(
            ByteArrayInputStream(ByteArray(512)),
            "other.nds",
            dir,
        )
        assertTrue(ndsRes is AppResult.Failure && ndsRes.error is EmulatorError.UnsupportedFile)

        val emptyRes = RomIngestionValidator.stageAndValidate(
            ByteArrayInputStream(ByteArray(0)),
            "empty.gba",
            dir,
        )
        assertTrue(emptyRes is AppResult.Failure && emptyRes.error is EmulatorError.CorruptOrTruncatedRom)

        val truncRes = RomIngestionValidator.stageAndValidate(
            ByteArrayInputStream(ByteArray(64)),
            "short.gba",
            dir,
        )
        assertTrue(truncRes is AppResult.Failure && truncRes.error is EmulatorError.CorruptOrTruncatedRom)

        val corruptRes = RomIngestionValidator.stageAndValidate(
            ByteArrayInputStream(ByteArray(1024) { 0x11 }),
            "notarom.gba",
            dir,
        )
        assertTrue(corruptRes is AppResult.Failure && corruptRes.error is EmulatorError.CorruptOrTruncatedRom)

        // No leftover temp files remain after rejections
        assertEquals(0, dir.listFiles()?.size ?: 0)
    }
}
