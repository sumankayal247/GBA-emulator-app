#include "LibretroLoader.h"

#include <dlfcn.h>

namespace gba {

namespace {

template <typename Fn>
bool resolveRequired(void* handle, const char* name, Fn& out, std::string& errorOut) {
    dlerror();
    void* sym = dlsym(handle, name);
    const char* err = dlerror();
    if (!sym || err != nullptr) {
        errorOut = std::string("Missing required Libretro symbol '") + name + "'" +
                   (err ? (std::string(": ") + err) : "");
        return false;
    }
    out = reinterpret_cast<Fn>(sym);
    return true;
}

}  // namespace

LibretroLoader::~LibretroLoader() {
    unload();
}

bool LibretroLoader::load(const std::string& libraryPath, std::string& errorOut) {
    unload();
    if (libraryPath.empty()) {
        errorOut = "Libretro core path is empty.";
        return false;
    }

    dlerror();
    handle_ = dlopen(libraryPath.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!handle_) {
        const char* err = dlerror();
        errorOut = std::string("dlopen failed for core: ") + (err ? err : "unknown error");
        return false;
    }

    LibretroSymbols sym{};
    const bool ok =
        resolveRequired(handle_, "retro_api_version", sym.api_version, errorOut) &&
        resolveRequired(handle_, "retro_init", sym.init, errorOut) &&
        resolveRequired(handle_, "retro_deinit", sym.deinit, errorOut) &&
        resolveRequired(handle_, "retro_get_system_info", sym.get_system_info, errorOut) &&
        resolveRequired(handle_, "retro_get_system_av_info", sym.get_system_av_info, errorOut) &&
        resolveRequired(handle_, "retro_set_environment", sym.set_environment, errorOut) &&
        resolveRequired(handle_, "retro_set_video_refresh", sym.set_video_refresh, errorOut) &&
        resolveRequired(handle_, "retro_set_audio_sample", sym.set_audio_sample, errorOut) &&
        resolveRequired(handle_, "retro_set_audio_sample_batch", sym.set_audio_sample_batch, errorOut) &&
        resolveRequired(handle_, "retro_set_input_poll", sym.set_input_poll, errorOut) &&
        resolveRequired(handle_, "retro_set_input_state", sym.set_input_state, errorOut) &&
        resolveRequired(handle_, "retro_set_controller_port_device", sym.set_controller_port_device, errorOut) &&
        resolveRequired(handle_, "retro_load_game", sym.load_game, errorOut) &&
        resolveRequired(handle_, "retro_unload_game", sym.unload_game, errorOut) &&
        resolveRequired(handle_, "retro_run", sym.run, errorOut) &&
        resolveRequired(handle_, "retro_reset", sym.reset, errorOut) &&
        resolveRequired(handle_, "retro_serialize_size", sym.serialize_size, errorOut) &&
        resolveRequired(handle_, "retro_serialize", sym.serialize, errorOut) &&
        resolveRequired(handle_, "retro_unserialize", sym.unserialize, errorOut) &&
        resolveRequired(handle_, "retro_cheat_reset", sym.cheat_reset, errorOut) &&
        resolveRequired(handle_, "retro_cheat_set", sym.cheat_set, errorOut) &&
        resolveRequired(handle_, "retro_get_memory_data", sym.get_memory_data, errorOut) &&
        resolveRequired(handle_, "retro_get_memory_size", sym.get_memory_size, errorOut);

    if (!ok) {
        unload();
        return false;
    }

    if (sym.api_version() != RETRO_API_VERSION) {
        errorOut = "Unsupported Libretro API version.";
        unload();
        return false;
    }

    symbols_ = sym;
    return true;
}

void LibretroLoader::unload() {
    if (handle_) {
        dlclose(handle_);
        handle_ = nullptr;
    }
    symbols_ = LibretroSymbols{};
}

}  // namespace gba
