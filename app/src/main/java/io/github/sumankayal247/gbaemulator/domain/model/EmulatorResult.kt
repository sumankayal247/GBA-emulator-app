package io.github.sumankayal247.gbaemulator.domain.model

sealed class EmulatorError(val userMessage: String) {
    class UnsupportedFile(message: String) : EmulatorError(message)
    class CorruptOrTruncatedRom(message: String) : EmulatorError(message)
    class MissingSourceFile(val romId: String, message: String) : EmulatorError(message)
    class CoreInitFailure(message: String) : EmulatorError(message)
    class SaveStorageFailure(message: String) : EmulatorError(message)
    class InvalidSaveImport(message: String) : EmulatorError(message)
    class SaveStateFailure(message: String) : EmulatorError(message)
    class InvalidCheatCode(message: String) : EmulatorError(message)
    class InvalidBiosFile(message: String) : EmulatorError(message)
}

sealed interface AppResult<out T> {
    data class Success<T>(val value: T) : AppResult<T>
    data class Failure(val error: EmulatorError) : AppResult<Nothing>
}
