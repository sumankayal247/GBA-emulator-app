#pragma once

#include <string>

#include "libretro.h"

namespace gba {

// Holds resolved Libretro C API symbols loaded dynamically from libmgba_libretro.so.
struct LibretroSymbols {
    unsigned (*api_version)(void) = nullptr;
    void (*init)(void) = nullptr;
    void (*deinit)(void) = nullptr;
    void (*get_system_info)(struct retro_system_info*) = nullptr;
    void (*get_system_av_info)(struct retro_system_av_info*) = nullptr;
    void (*set_environment)(retro_environment_t) = nullptr;
    void (*set_video_refresh)(retro_video_refresh_t) = nullptr;
    void (*set_audio_sample)(retro_audio_sample_t) = nullptr;
    void (*set_audio_sample_batch)(retro_audio_sample_batch_t) = nullptr;
    void (*set_input_poll)(retro_input_poll_t) = nullptr;
    void (*set_input_state)(retro_input_state_t) = nullptr;
    void (*set_controller_port_device)(unsigned, unsigned) = nullptr;
    bool (*load_game)(const struct retro_game_info*) = nullptr;
    void (*unload_game)(void) = nullptr;
    void (*run)(void) = nullptr;
    void (*reset)(void) = nullptr;
    size_t (*serialize_size)(void) = nullptr;
    bool (*serialize)(void*, size_t) = nullptr;
    bool (*unserialize)(const void*, size_t) = nullptr;
    void (*cheat_reset)(void) = nullptr;
    void (*cheat_set)(unsigned, bool, const char*) = nullptr;
    void* (*get_memory_data)(unsigned) = nullptr;
    size_t (*get_memory_size)(unsigned) = nullptr;
};

// Dynamic library loader for a Libretro shared core (.so).
class LibretroLoader {
public:
    LibretroLoader() = default;
    ~LibretroLoader();

    LibretroLoader(const LibretroLoader&) = delete;
    LibretroLoader& operator=(const LibretroLoader&) = delete;

    bool load(const std::string& libraryPath, std::string& errorOut);
    void unload();

    bool isLoaded() const noexcept { return handle_ != nullptr; }
    const LibretroSymbols& symbols() const noexcept { return symbols_; }

private:
    void* handle_{nullptr};
    LibretroSymbols symbols_{};
};

}  // namespace gba
