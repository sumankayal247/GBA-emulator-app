package io.github.sumankayal247.gbaemulator

import io.github.sumankayal247.gbaemulator.data.saves.SramSaveRepository
import io.github.sumankayal247.gbaemulator.domain.model.AppResult
import io.github.sumankayal247.gbaemulator.domain.model.EmulatorError
import io.github.sumankayal247.gbaemulator.domain.model.GameSystem
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

class SramSaveRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val hashA = "a".repeat(64)
    private val hashB = "b".repeat(64)

    @Test
    fun atomicWriteReadAndCrossRomIsolation() {
        val repo = SramSaveRepository(tempFolder.newFolder("saves"))

        assertFalse(repo.hasSave(GameSystem.GBA, hashA))
        val emptyLoad = repo.readSram(GameSystem.GBA, hashA) as AppResult.Success
        assertNull(emptyLoad.value.bytes)

        val payloadA = byteArrayOf(0x5A, 0xA5.toByte(), 0x3C, 0xC3.toByte())
        val payloadB = byteArrayOf(0x11, 0x22, 0x33, 0x44)

        assertTrue(repo.writeSram(GameSystem.GBA, hashA, payloadA) is AppResult.Success)
        assertTrue(repo.writeSram(GameSystem.GBA, hashB, payloadB) is AppResult.Success)

        val readA = (repo.readSram(GameSystem.GBA, hashA) as AppResult.Success).value
        val readB = (repo.readSram(GameSystem.GBA, hashB) as AppResult.Success).value
        assertArrayEquals(payloadA, readA.bytes)
        assertArrayEquals(payloadB, readB.bytes)
        assertFalse(readA.recoveredFromBackup)
    }

    @Test
    fun corruptPrimaryIsPreservedAndRecoversFromBackup() {
        val repo = SramSaveRepository(tempFolder.newFolder("saves_recovery"))
        val firstGood = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val secondGood = byteArrayOf(9, 10, 11, 12, 13, 14, 15, 16)

        assertTrue(repo.writeSram(GameSystem.GBA, hashA, firstGood) is AppResult.Success)
        assertTrue(repo.writeSram(GameSystem.GBA, hashA, secondGood) is AppResult.Success)

        // Corrupt primary save on disk (flipping bytes without updating meta.json checksum)
        val saveDir = repo.romSaveDir(GameSystem.GBA, hashA)
        val primary = File(saveDir, "battery.sav")
        primary.writeBytes(byteArrayOf(0x7F, 0x7F, 0x7F, 0x7F, 0x7F, 0x7F, 0x7F, 0x7F))

        val recovered = (repo.readSram(GameSystem.GBA, hashA) as AppResult.Success).value
        assertTrue(recovered.recoveredFromBackup)
        assertArrayEquals(firstGood, recovered.bytes)

        // Verify corrupt primary was quarantined, NOT silently deleted!
        val quarantined = saveDir.listFiles()?.filter { it.name.startsWith("battery.sav.corrupt.") }
        assertEquals(1, quarantined?.size ?: 0)
    }

    @Test
    fun exportAndImportRoundTripWithValidation() {
        val repo = SramSaveRepository(tempFolder.newFolder("saves_io"))
        val original = ByteArray(1024) { (it and 0xFF).toByte() }
        assertTrue(repo.writeSram(GameSystem.GBC, hashA, original) is AppResult.Success)

        val exported = ByteArrayOutputStream()
        val exportRes = repo.exportSram(GameSystem.GBC, hashA, exported)
        assertTrue(exportRes is AppResult.Success)
        assertArrayEquals(original, exported.toByteArray())

        // Import exported save into another ROM slot
        val importRes = repo.importSram(GameSystem.GBC, hashB, ByteArrayInputStream(exported.toByteArray()))
        assertTrue(importRes is AppResult.Success)
        assertArrayEquals(original, (repo.readSram(GameSystem.GBC, hashB) as AppResult.Success).value.bytes)

        // Reject empty and oversized imports
        val emptyImport = repo.importSram(GameSystem.GBC, hashB, ByteArrayInputStream(ByteArray(0)))
        assertTrue(emptyImport is AppResult.Failure && emptyImport.error is EmulatorError.InvalidSaveImport)

        val hugeImport = repo.importSram(
            GameSystem.GBC,
            hashB,
            ByteArrayInputStream(ByteArray(SramSaveRepository.MAX_SRAM_BYTES + 16)),
        )
        assertTrue(hugeImport is AppResult.Failure && hugeImport.error is EmulatorError.InvalidSaveImport)
    }
}
