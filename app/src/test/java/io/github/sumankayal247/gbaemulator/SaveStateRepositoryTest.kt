package io.github.sumankayal247.gbaemulator

import io.github.sumankayal247.gbaemulator.data.saves.SaveStateRepository
import io.github.sumankayal247.gbaemulator.data.saves.SramSaveRepository
import io.github.sumankayal247.gbaemulator.domain.model.AppResult
import io.github.sumankayal247.gbaemulator.domain.model.GameSystem
import io.github.sumankayal247.gbaemulator.domain.model.SaveStateSlot
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SaveStateRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val romShaA = "a".repeat(64)
    private val romShaB = "b".repeat(64)

    @Test
    fun writeReadAndIsolateSlotsAcrossRomsAndSram() {
        val root = tempFolder.newFolder("saves")
        val stateRepo = SaveStateRepository(root)
        val sramRepo = SramSaveRepository(root)

        val sramData = ByteArray(1024) { (it and 0xFF).toByte() }
        assertTrue(sramRepo.writeSram(GameSystem.GBA, romShaA, sramData) is AppResult.Success)

        val stateV1 = ByteArray(4096) { ((it * 7) and 0xFF).toByte() }
        val fakePng = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val writeRes = stateRepo.writeState(GameSystem.GBA, romShaA, 1, stateV1, "mGBA 0.11-dev", fakePng)
        assertTrue(writeRes is AppResult.Success)
        val slot = (writeRes as AppResult.Success).value
        assertEquals(1, slot.slotIndex)
        assertTrue(slot.occupied)
        assertNotNull(slot.thumbnailPath)
        assertTrue(File(slot.thumbnailPath!!).exists())

        // Isolated per ROM: ROM B has 8 empty slots.
        val romBSlots = stateRepo.listSlots(GameSystem.GBA, romShaB)
        assertEquals(SaveStateSlot.SLOT_COUNT, romBSlots.size)
        assertTrue(romBSlots.none { it.occupied })

        val readRes = stateRepo.readState(GameSystem.GBA, romShaA, 1)
        assertTrue(readRes is AppResult.Success)
        val loaded = (readRes as AppResult.Success).value
        assertArrayEquals(stateV1, loaded.bytes)
        assertEquals("mGBA 0.11-dev", loaded.coreVersion)
        assertFalse(loaded.recoveredFromBackup)

        // Deleting all save states for ROM A must NEVER touch battery SRAM!
        stateRepo.deleteAllStatesForRom(GameSystem.GBA, romShaA)
        assertTrue(stateRepo.listSlots(GameSystem.GBA, romShaA).none { it.occupied })
        val sramAfter = sramRepo.readSram(GameSystem.GBA, romShaA)
        assertTrue(sramAfter is AppResult.Success)
        assertArrayEquals(sramData, (sramAfter as AppResult.Success).value.bytes)
    }

    @Test
    fun corruptPrimaryStateIsQuarantinedAndRecoveredFromVerifiedBackup() {
        val root = tempFolder.newFolder("saves_recovery")
        val repo = SaveStateRepository(root)

        val v1 = ByteArray(2048) { 0x11 }
        val v2 = ByteArray(2048) { 0x22 }
        assertTrue(repo.writeState(GameSystem.GBA, romShaA, 3, v1, "mGBA") is AppResult.Success)
        assertTrue(repo.writeState(GameSystem.GBA, romShaA, 3, v2, "mGBA") is AppResult.Success)

        // Corrupt primary slot_3.state on disk
        val dir = repo.statesDir(GameSystem.GBA, romShaA)
        val primary = File(dir, "slot_3.state")
        primary.writeBytes(ByteArray(2048) { 0xFF.toByte() })

        val loaded = repo.readState(GameSystem.GBA, romShaA, 3)
        assertTrue(loaded is AppResult.Success)
        val state = (loaded as AppResult.Success).value
        assertTrue(state.recoveredFromBackup)
        assertArrayEquals(v1, state.bytes)

        // Corrupt primary must be preserved as slot_3.state.corrupt.* rather than silently lost
        val quarantined = dir.listFiles()?.filter { it.name.startsWith("slot_3.state.corrupt.") } ?: emptyList()
        assertEquals(1, quarantined.size)
    }

    @Test
    fun rejectsCorruptStateWithNoBackupAndInvalidSlotIndices() {
        val root = tempFolder.newFolder("saves_invalid")
        val repo = SaveStateRepository(root)

        assertTrue(repo.writeState(GameSystem.GBA, romShaA, 0, ByteArray(64), "mGBA") is AppResult.Failure)
        assertTrue(repo.writeState(GameSystem.GBA, romShaA, 9, ByteArray(64), "mGBA") is AppResult.Failure)
        assertTrue(repo.writeState(GameSystem.GBA, romShaA, 1, ByteArray(0), "mGBA") is AppResult.Failure)

        assertTrue(repo.writeState(GameSystem.GBA, romShaA, 2, ByteArray(128) { 0x5A }, "mGBA") is AppResult.Success)
        File(repo.statesDir(GameSystem.GBA, romShaA), "slot_2.state").writeBytes(ByteArray(128) { 0x00 })

        val readRes = repo.readState(GameSystem.GBA, romShaA, 2)
        assertTrue(readRes is AppResult.Failure)
    }
}
