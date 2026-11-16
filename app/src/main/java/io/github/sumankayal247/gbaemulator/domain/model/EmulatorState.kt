package io.github.sumankayal247.gbaemulator.domain.model

/**
 * Explicit emulator runtime state machine mirroring native `gba::EmulatorState` (ADR-0003).
 */
enum class EmulatorState(val nativeValue: Int) {
    UNLOADED(0),
    LOADING(1),
    RUNNING(2),
    PAUSED(3),
    STOPPING(4),
    STOPPED(5),
    ERROR(6),
    ;

    fun canTransitionTo(target: EmulatorState): Boolean {
        if (this == target) return true
        return when (this) {
            UNLOADED -> target == LOADING || target == ERROR
            LOADING -> target == RUNNING || target == PAUSED || target == STOPPING || target == ERROR
            RUNNING -> target == PAUSED || target == STOPPING || target == ERROR
            PAUSED -> target == RUNNING || target == STOPPING || target == ERROR
            STOPPING -> target == STOPPED || target == ERROR
            STOPPED -> target == UNLOADED || target == LOADING
            ERROR -> target == STOPPING || target == UNLOADED
        }
    }

    companion object {
        fun fromNative(value: Int): EmulatorState =
            entries.firstOrNull { it.nativeValue == value } ?: ERROR
    }
}
