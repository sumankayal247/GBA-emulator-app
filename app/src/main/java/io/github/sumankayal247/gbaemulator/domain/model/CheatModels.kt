package io.github.sumankayal247.gbaemulator.domain.model

data class CheatCodeEntry(
    val id: Long = 0L,
    val romId: String,
    val description: String,
    val code: String,
    val enabled: Boolean = true,
    val createdEpochMs: Long = System.currentTimeMillis(),
)

/**
 * Strict Kotlin-side cheat code validator mirroring native C++ CheatValidator.h
 * (defending against upstream mGBA fixed stack buffers in retro_cheat_set).
 */
object CheatCodeValidator {
    private val HEX_8 = Regex("^[0-9A-F]{8}$")
    private val HEX_4 = Regex("^[0-9A-F]{4}$")
    private val HEX_3 = Regex("^[0-9A-F]{3}$")
    private const val MAX_LINES = 16
    private const val MAX_TOTAL_LENGTH = 512

    fun validateAndNormalize(system: GameSystem, rawCode: String): AppResult<String> {
        val trimmed = rawCode.trim()
        if (trimmed.isEmpty()) {
            return AppResult.Failure(EmulatorError.InvalidCheatCode("Cheat code cannot be empty."))
        }
        if (trimmed.length > MAX_TOTAL_LENGTH) {
            return AppResult.Failure(
                EmulatorError.InvalidCheatCode("Cheat code exceeds maximum allowed length ($MAX_TOTAL_LENGTH chars)."),
            )
        }
        val lines = trimmed.split('+', '\n', '\r', ';')
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() }

        if (lines.isEmpty() || lines.size > MAX_LINES) {
            return AppResult.Failure(
                EmulatorError.InvalidCheatCode("Cheat code must contain 1 to $MAX_LINES valid lines."),
            )
        }

        val normalizedLines = ArrayList<String>(lines.size)
        for (line in lines) {
            val norm = when (system) {
                GameSystem.GBA -> normalizeGbaLine(line)
                GameSystem.GBC, GameSystem.GB -> normalizeGbLine(line)
            }
            if (norm == null) {
                return AppResult.Failure(
                    EmulatorError.InvalidCheatCode(
                        when (system) {
                            GameSystem.GBA ->
                                "Invalid GBA cheat line '$line'. Use GameShark/Action Replay (XXXXXXXX YYYYYYYY) or CodeBreaker (XXXXXXXX YYYY)."
                            GameSystem.GBC, GameSystem.GB ->
                                "Invalid GB/GBC cheat line '$line'. Use GameGenie (XXX-XXX or XXX-XXX-XXX) or GameShark (XXXXXXXX)."
                        },
                    ),
                )
            }
            normalizedLines.add(norm)
        }
        return AppResult.Success(normalizedLines.joinToString("+"))
    }

    private fun normalizeGbaLine(line: String): String? {
        val compact = line.replace(" ", "").replace(":", "").replace("-", "")
        return when {
            compact.length == 16 && HEX_8.matches(compact.substring(0, 8)) && HEX_8.matches(compact.substring(8, 16)) ->
                "${compact.substring(0, 8)} ${compact.substring(8, 16)}"
            compact.length == 12 && HEX_8.matches(compact.substring(0, 8)) && HEX_4.matches(compact.substring(8, 12)) ->
                "${compact.substring(0, 8)} ${compact.substring(8, 12)}"
            else -> null
        }
    }

    private fun normalizeGbLine(line: String): String? {
        val compact = line.replace(" ", "")
        // GameShark: 8 hex digits
        if (HEX_8.matches(compact)) return compact

        // GameGenie: XXX-XXX or XXX-XXX-XXX (hyphens optional)
        val noDash = compact.replace("-", "")
        return when {
            noDash.length == 6 && HEX_3.matches(noDash.substring(0, 3)) && HEX_3.matches(noDash.substring(3, 6)) ->
                "${noDash.substring(0, 3)}-${noDash.substring(3, 6)}"
            noDash.length == 9 &&
                HEX_3.matches(noDash.substring(0, 3)) &&
                HEX_3.matches(noDash.substring(3, 6)) &&
                HEX_3.matches(noDash.substring(6, 9)) ->
                "${noDash.substring(0, 3)}-${noDash.substring(3, 6)}-${noDash.substring(6, 9)}"
            else -> null
        }
    }
}
