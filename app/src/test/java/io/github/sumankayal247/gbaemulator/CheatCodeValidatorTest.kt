package io.github.sumankayal247.gbaemulator

import io.github.sumankayal247.gbaemulator.domain.model.AppResult
import io.github.sumankayal247.gbaemulator.domain.model.CheatCodeValidator
import io.github.sumankayal247.gbaemulator.domain.model.GameSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CheatCodeValidatorTest {

    @Test
    fun acceptsValidGbaMultiLineAndSingleLineCodes() {
        val ar = CheatCodeValidator.validateAndNormalize(
            GameSystem.GBA,
            "83007cee 03e7\n82025840 0044",
        )
        assertTrue(ar is AppResult.Success)
        assertEquals("83007CEE 03E7+82025840 0044", (ar as AppResult.Success).value)

        val gs = CheatCodeValidator.validateAndNormalize(GameSystem.GBA, "deadbeef 01234567")
        assertTrue(gs is AppResult.Success)
        assertEquals("DEADBEEF 01234567", (gs as AppResult.Success).value)

        val raw = CheatCodeValidator.validateAndNormalize(GameSystem.GBA, "0202423c:03e7")
        assertTrue(raw is AppResult.Success)
        assertEquals("0202423C 03E7", (raw as AppResult.Success).value)
    }

    @Test
    fun acceptsValidGbAndGbcCodes() {
        val gg9 = CheatCodeValidator.validateAndNormalize(GameSystem.GBC, "01f-23a-e6e")
        assertTrue(gg9 is AppResult.Success)
        assertEquals("01F-23A-E6E", (gg9 as AppResult.Success).value)

        val gg6 = CheatCodeValidator.validateAndNormalize(GameSystem.GB, "01f-23a")
        assertTrue(gg6 is AppResult.Success)
        assertEquals("01F-23A", (gg6 as AppResult.Success).value)

        val gs = CheatCodeValidator.validateAndNormalize(GameSystem.GBC, "010f38cd")
        assertTrue(gs is AppResult.Success)
        assertEquals("010F38CD", (gs as AppResult.Success).value)
    }

    @Test
    fun rejectsMalformedEmptyAndExcessiveCodes() {
        assertTrue(CheatCodeValidator.validateAndNormalize(GameSystem.GBA, "   ") is AppResult.Failure)
        assertTrue(CheatCodeValidator.validateAndNormalize(GameSystem.GBA, "ZZZZZZZZ 1234") is AppResult.Failure)
        assertTrue(CheatCodeValidator.validateAndNormalize(GameSystem.GBA, "12345") is AppResult.Failure)
        assertTrue(CheatCodeValidator.validateAndNormalize(GameSystem.GBC, "12345678 90ABCDEF") is AppResult.Failure)

        val tooMany = (1..20).joinToString("\n") { "83007CEE 03E7" }
        assertTrue(CheatCodeValidator.validateAndNormalize(GameSystem.GBA, tooMany) is AppResult.Failure)
    }
}
