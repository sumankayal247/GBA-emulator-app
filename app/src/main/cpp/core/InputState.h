#pragma once

#include <atomic>
#include <cstdint>

namespace gba {

// Matches Libretro RETRO_DEVICE_ID_JOYPAD_* indices.
enum JoypadBit : uint16_t {
    JOYPAD_B      = 1u << 0,
    JOYPAD_Y      = 1u << 1,
    JOYPAD_SELECT = 1u << 2,
    JOYPAD_START  = 1u << 3,
    JOYPAD_UP     = 1u << 4,
    JOYPAD_DOWN   = 1u << 5,
    JOYPAD_LEFT   = 1u << 6,
    JOYPAD_RIGHT  = 1u << 7,
    JOYPAD_A      = 1u << 8,
    JOYPAD_X      = 1u << 9,
    JOYPAD_L      = 1u << 10,
    JOYPAD_R      = 1u << 11,
};

// Thread-safe input router merging virtual touch and physical controller bitmasks.
// Designed for lock-free writes from UI/input threads and reads from the emulator thread.
class InputState {
public:
    void setTouchMask(uint16_t mask) noexcept {
        touchMask_.store(mask, std::memory_order_release);
    }

    void setGamepadMask(uint16_t mask) noexcept {
        gamepadMask_.store(mask, std::memory_order_release);
    }

    void setAllowOpposingDirections(bool allow) noexcept {
        allowOpposing_.store(allow, std::memory_order_release);
    }

    // Releases all held inputs across every input source (required on pause, cancel, disconnect).
    void releaseAll() noexcept {
        touchMask_.store(0, std::memory_order_release);
        gamepadMask_.store(0, std::memory_order_release);
    }

    uint16_t currentMask() const noexcept {
        const uint16_t combined = static_cast<uint16_t>(
            touchMask_.load(std::memory_order_acquire) |
            gamepadMask_.load(std::memory_order_acquire)
        );
        return sanitize(combined, allowOpposing_.load(std::memory_order_acquire));
    }

    bool isPressed(unsigned retroJoypadId) const noexcept {
        if (retroJoypadId >= 16) {
            return false;
        }
        return (currentMask() & static_cast<uint16_t>(1u << retroJoypadId)) != 0;
    }

    static uint16_t sanitize(uint16_t mask, bool allowOpposing) noexcept {
        if (allowOpposing) {
            return mask;
        }
        // Deterministically cancel opposing D-pad axes so simultaneous Left+Right or Up+Down
        // cannot glitch games unless explicitly enabled by core option.
        if ((mask & JOYPAD_UP) && (mask & JOYPAD_DOWN)) {
            mask = static_cast<uint16_t>(mask & ~(JOYPAD_UP | JOYPAD_DOWN));
        }
        if ((mask & JOYPAD_LEFT) && (mask & JOYPAD_RIGHT)) {
            mask = static_cast<uint16_t>(mask & ~(JOYPAD_LEFT | JOYPAD_RIGHT));
        }
        return mask;
    }

private:
    std::atomic<uint16_t> touchMask_{0};
    std::atomic<uint16_t> gamepadMask_{0};
    std::atomic<bool> allowOpposing_{false};
};

}  // namespace gba
