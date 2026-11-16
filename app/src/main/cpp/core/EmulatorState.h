#pragma once

#include <cstdint>

namespace gba {

// Explicit emulator runtime state machine (ADR-0003 / Plan §11.1).
enum class EmulatorState : int32_t {
    Unloaded = 0,
    Loading = 1,
    Running = 2,
    Paused = 3,
    Stopping = 4,
    Stopped = 5,
    Error = 6,
};

inline const char* emulatorStateName(EmulatorState state) {
    switch (state) {
        case EmulatorState::Unloaded: return "Unloaded";
        case EmulatorState::Loading:  return "Loading";
        case EmulatorState::Running:  return "Running";
        case EmulatorState::Paused:   return "Paused";
        case EmulatorState::Stopping: return "Stopping";
        case EmulatorState::Stopped:  return "Stopped";
        case EmulatorState::Error:    return "Error";
    }
    return "Unknown";
}

// Validates legal state transitions per ADR-0003.
inline bool isValidStateTransition(EmulatorState from, EmulatorState to) {
    if (from == to) {
        return true;
    }
    switch (from) {
        case EmulatorState::Unloaded:
            return to == EmulatorState::Loading || to == EmulatorState::Error;
        case EmulatorState::Loading:
            return to == EmulatorState::Running ||
                   to == EmulatorState::Paused ||
                   to == EmulatorState::Stopping ||
                   to == EmulatorState::Error;
        case EmulatorState::Running:
            return to == EmulatorState::Paused ||
                   to == EmulatorState::Stopping ||
                   to == EmulatorState::Error;
        case EmulatorState::Paused:
            return to == EmulatorState::Running ||
                   to == EmulatorState::Stopping ||
                   to == EmulatorState::Error;
        case EmulatorState::Stopping:
            return to == EmulatorState::Stopped || to == EmulatorState::Error;
        case EmulatorState::Stopped:
            return to == EmulatorState::Unloaded || to == EmulatorState::Loading;
        case EmulatorState::Error:
            return to == EmulatorState::Stopping || to == EmulatorState::Unloaded;
    }
    return false;
}

}  // namespace gba
