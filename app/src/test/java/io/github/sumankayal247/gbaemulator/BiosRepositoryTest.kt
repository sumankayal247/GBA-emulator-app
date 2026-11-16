package io.github.sumankayal247.gbaemulator

import io.github.sumankayal247.gbaemulator.data.firmware.BiosRepository
import io.github.sumankayal247.gbaemulator.domain.model.AppResult
import io.github.sumankayal247.gbaemulator.domain.model.BiosType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream

class BiosRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun validatesExactBiosSizesImportsAndRemovesCleanly() {
        val dir = tempFolder.newFolder("system")
        val repo = BiosRepository(dir)

        // Initially all HLE (none installed)
        assertTrue(repo.listStatuses().none { it.installed })

        // Wrong sizes rejected
        assertTrue(repo.importBios(BiosType.GBA, ByteArrayInputStream(ByteArray(1024))) is AppResult.Failure)
        assertTrue(repo.importBios(BiosType.GBA, ByteArrayInputStream(ByteArray(32768))) is AppResult.Failure)
        assertTrue(repo.importBios(BiosType.GB, ByteArrayInputStream(ByteArray(255))) is AppResult.Failure)

        // Valid 16 KiB GBA BIOS accepted
        val validGbaBios = ByteArray(16 * 1024) { (it and 0xFF).toByte() }
        val res = repo.importBios(BiosType.GBA, ByteArrayInputStream(validGbaBios))
        assertTrue(res is AppResult.Success)
        val status = (res as AppResult.Success).value
        assertTrue(status.installed)
        assertEquals(16_384L, status.fileSizeBytes)
        assertNotNull(status.sha256)
        assertEquals(64, status.sha256!!.length)

        // Invalid re-import must NOT destroy existing valid BIOS
        assertTrue(repo.importBios(BiosType.GBA, ByteArrayInputStream(ByteArray(100))) is AppResult.Failure)
        assertTrue(repo.getStatus(BiosType.GBA).installed)

        // Remove returns cleanly to built-in HLE
        assertTrue(repo.removeBios(BiosType.GBA))
        assertFalse(repo.getStatus(BiosType.GBA).installed)
    }
}
