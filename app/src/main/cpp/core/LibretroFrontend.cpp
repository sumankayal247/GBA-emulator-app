#include "LibretroFrontend.h"

#include <algorithm>
#include <cstdarg>
#include <cstdio>
#include <cstring>

namespace gba {

std::atomic<LibretroFrontend*> LibretroFrontend::gActiveFrontend_{nullptr};

LibretroFrontend::LibretroFrontend() = default;

LibretroFrontend::~LibretroFrontend() {
    shutdown(nullptr, 3000);
}

void LibretroFrontend::setFrameReadyCallback(FrameReadyCallback cb) {
    std::lock_guard<std::mutex> lock(callbackMutex_);
    frameReadyCb_ = std::move(cb);
}

void LibretroFrontend::setLogCallback(LogCallback cb) {
    std::lock_guard<std::mutex> lock(callbackMutex_);
    logCb_ = std::move(cb);
}

bool LibretroFrontend::transitionState(EmulatorState target) {
    std::lock_guard<std::mutex> lock(stateMutex_);
    const EmulatorState current = state_.load(std::memory_order_acquire);
    if (!isValidStateTransition(current, target)) {
        logMessage(RETRO_LOG_WARN,
                   std::string("Rejected invalid state transition: ") +
                       emulatorStateName(current) + " -> " + emulatorStateName(target));
        return false;
    }
    state_.store(target, std::memory_order_release);
    return true;
}

CoreInfoSnapshot LibretroFrontend::coreInfo() const {
    std::lock_guard<std::mutex> lock(infoMutex_);
    return coreInfo_;
}

std::string LibretroFrontend::lastError() const {
    std::lock_guard<std::mutex> lock(infoMutex_);
    return lastError_;
}

void LibretroFrontend::logMessage(int level, const std::string& msg) const {
    LogCallback cb;
    {
        std::lock_guard<std::mutex> lock(callbackMutex_);
        cb = logCb_;
    }
    if (cb) {
        cb(level, msg);
    }
}

bool LibretroFrontend::loadRom(const LoadConfig& config, std::string& errorOut) {
    const EmulatorState cur = state();
    if (cur != EmulatorState::Unloaded && cur != EmulatorState::Stopped && cur != EmulatorState::Error) {
        errorOut = std::string("Cannot load ROM while emulator is in state ") + emulatorStateName(cur) + ".";
        return false;
    }
    if (cur == EmulatorState::Error || cur == EmulatorState::Stopped) {
        shutdown(nullptr, 3000);
    }

    // Validate ROM file before spinning up the core (untrusted input gate).
    const RomValidator::ValidationResult validation = RomValidator::validateFile(config.romFilePath);
    if (!validation.valid) {
        errorOut = validation.errorMessage;
        {
            std::lock_guard<std::mutex> lock(infoMutex_);
            lastError_ = errorOut;
        }
        return false;
    }

    // Enforce single-owner core guarantee across process.
    LibretroFrontend* expected = nullptr;
    if (!gActiveFrontend_.compare_exchange_strong(expected, this, std::memory_order_acq_rel)) {
        errorOut = "Another emulator core instance is already active.";
        return false;
    }

    if (!transitionState(EmulatorState::Loading)) {
        gActiveFrontend_.store(nullptr, std::memory_order_release);
        errorOut = "Invalid state transition to Loading.";
        return false;
    }

    {
        std::lock_guard<std::mutex> lock(infoMutex_);
        systemDir_ = config.systemDirectory;
        saveDir_ = config.saveDirectory;
        coreInfo_ = CoreInfoSnapshot{};
        coreInfo_.system = validation.detectedSystem;
        coreInfo_.outputSampleRate = config.outputSampleRate > 0.0 ? config.outputSampleRate : 48000.0;
        lastError_.clear();
    }

    inputState_.releaseAll();
    optionsStore_.clear();
    optionsStore_.setPendingOverrides(config.initialCoreOptions);
    videoBuffer_.clear();
    audioBuffer_.clear();
    audioBuffer_.resetStats();
    totalFrames_.store(0, std::memory_order_release);
    totalAudioFrames_.store(0, std::memory_order_release);
    firstFrameExecuted_.store(false, std::memory_order_release);
    workerStopRequested_.store(false, std::memory_order_release);

    workerThread_ = std::thread(&LibretroFrontend::workerMain, this);

    bool loadOk = false;
    std::string workerError;

    enqueueAndWait([&]() {
        if (!loader_.load(config.coreLibraryPath, workerError)) {
            return;
        }
        const auto& sym = loader_.symbols();
        sym.set_environment(&LibretroFrontend::cbEnvironment);
        sym.set_video_refresh(&LibretroFrontend::cbVideoRefresh);
        sym.set_audio_sample(&LibretroFrontend::cbAudioSample);
        sym.set_audio_sample_batch(&LibretroFrontend::cbAudioSampleBatch);
        sym.set_input_poll(&LibretroFrontend::cbInputPoll);
        sym.set_input_state(&LibretroFrontend::cbInputState);

        sym.init();
        coreLoaded_.store(true, std::memory_order_release);

        retro_system_info sysInfo{};
        sym.get_system_info(&sysInfo);

        retro_game_info gameInfo{};
        gameInfo.path = config.romFilePath.c_str();
        gameInfo.data = nullptr;
        gameInfo.size = 0;
        gameInfo.meta = nullptr;

        if (!sym.load_game(&gameInfo)) {
            workerError = "mGBA core rejected the ROM file during retro_load_game.";
            unloadCoreOnWorker();
            return;
        }
        gameLoaded_.store(true, std::memory_order_release);

        // Restore persisted battery SRAM BEFORE first retro_run() so mGBA's _doDeferredSetup()
        // attaches the user's saved progress cleanly (ADR-0001 / ADR-0004).
        if (!config.initialSram.empty()) {
            void* sramPtr = sym.get_memory_data(RETRO_MEMORY_SAVE_RAM);
            const size_t sramCapacity = sym.get_memory_size(RETRO_MEMORY_SAVE_RAM);
            if (sramPtr != nullptr && sramCapacity > 0) {
                const size_t copyLen = std::min(config.initialSram.size(), sramCapacity);
                std::memcpy(sramPtr, config.initialSram.data(), copyLen);
            }
        }

        retro_system_av_info avInfo{};
        sym.get_system_av_info(&avInfo);

        double outputRate = 48000.0;
        {
            std::lock_guard<std::mutex> lock(infoMutex_);
            coreInfo_.libraryName = sysInfo.library_name ? sysInfo.library_name : "mGBA";
            coreInfo_.libraryVersion = sysInfo.library_version ? sysInfo.library_version : "unknown";
            coreInfo_.baseWidth = avInfo.geometry.base_width;
            coreInfo_.baseHeight = avInfo.geometry.base_height;
            coreInfo_.aspectRatio = avInfo.geometry.aspect_ratio > 0.0f
                ? avInfo.geometry.aspect_ratio
                : (avInfo.geometry.base_height > 0
                    ? static_cast<float>(avInfo.geometry.base_width) / static_cast<float>(avInfo.geometry.base_height)
                    : 1.5f);
            coreInfo_.fps = avInfo.timing.fps > 1.0 ? avInfo.timing.fps : 59.7275;
            coreInfo_.coreSampleRate = avInfo.timing.sample_rate > 1000.0 ? avInfo.timing.sample_rate : 65536.0;
            outputRate = coreInfo_.outputSampleRate;
        }
        resampler_.configure(
            avInfo.timing.sample_rate > 1000.0 ? avInfo.timing.sample_rate : 65536.0,
            outputRate
        );

        loadOk = true;
    });

    if (!loadOk) {
        errorOut = workerError.empty() ? "Unknown core load failure." : workerError;
        {
            std::lock_guard<std::mutex> lock(infoMutex_);
            lastError_ = errorOut;
        }
        transitionState(EmulatorState::Error);
        shutdown(nullptr, 3000);
        return false;
    }

    const EmulatorState nextState = config.startPaused ? EmulatorState::Paused : EmulatorState::Running;
    transitionState(nextState);
    queueCv_.notify_all();
    return true;
}

bool LibretroFrontend::pause() {
    const EmulatorState cur = state();
    if (cur == EmulatorState::Paused) {
        return true;
    }
    if (cur != EmulatorState::Running) {
        return false;
    }
    inputState_.releaseAll();
    const bool ok = transitionState(EmulatorState::Paused);
    queueCv_.notify_all();
    return ok;
}

bool LibretroFrontend::resume() {
    const EmulatorState cur = state();
    if (cur == EmulatorState::Running) {
        return true;
    }
    if (cur != EmulatorState::Paused) {
        return false;
    }
    const bool ok = transitionState(EmulatorState::Running);
    queueCv_.notify_all();
    return ok;
}

bool LibretroFrontend::reset() {
    const EmulatorState cur = state();
    if (cur != EmulatorState::Running && cur != EmulatorState::Paused) {
        return false;
    }
    inputState_.releaseAll();
    bool ok = false;
    enqueueAndWait([&]() {
        if (gameLoaded_.load(std::memory_order_acquire) && loader_.isLoaded()) {
            loader_.symbols().reset();
            audioBuffer_.clear();
            resampler_.reset();
            ok = true;
        }
    });
    return ok;
}

bool LibretroFrontend::stepFrames(uint32_t frameCount) {
    if (state() != EmulatorState::Paused || frameCount == 0) {
        return false;
    }
    bool ok = false;
    enqueueAndWait([&]() {
        if (!gameLoaded_.load(std::memory_order_acquire)) {
            return;
        }
        for (uint32_t i = 0; i < frameCount; ++i) {
            runSingleCoreFrame();
        }
        ok = true;
    });
    return ok;
}

bool LibretroFrontend::extractSram(std::vector<uint8_t>& outSram) {
    outSram.clear();
    const EmulatorState cur = state();
    if (cur != EmulatorState::Running && cur != EmulatorState::Paused) {
        return false;
    }
    bool ok = false;
    enqueueAndWait([&]() {
        if (!gameLoaded_.load(std::memory_order_acquire) || !loader_.isLoaded()) {
            return;
        }
        const auto& sym = loader_.symbols();
        void* data = sym.get_memory_data(RETRO_MEMORY_SAVE_RAM);
        const size_t size = sym.get_memory_size(RETRO_MEMORY_SAVE_RAM);
        if (data != nullptr && size > 0) {
            const auto* bytes = static_cast<const uint8_t*>(data);
            outSram.assign(bytes, bytes + size);
            ok = true;
        }
    });
    return ok;
}

bool LibretroFrontend::loadSram(const std::vector<uint8_t>& sramData) {
    if (sramData.empty()) {
        return false;
    }
    const EmulatorState cur = state();
    if (cur != EmulatorState::Running && cur != EmulatorState::Paused) {
        return false;
    }
    bool ok = false;
    enqueueAndWait([&]() {
        if (!gameLoaded_.load(std::memory_order_acquire) || !loader_.isLoaded()) {
            return;
        }
        const auto& sym = loader_.symbols();
        void* data = sym.get_memory_data(RETRO_MEMORY_SAVE_RAM);
        const size_t size = sym.get_memory_size(RETRO_MEMORY_SAVE_RAM);
        if (data != nullptr && size > 0) {
            const size_t copyLen = std::min(sramData.size(), size);
            std::memcpy(data, sramData.data(), copyLen);
            ok = true;
        }
    });
    return ok;
}

bool LibretroFrontend::serializeState(std::vector<uint8_t>& outState) {
    outState.clear();
    const EmulatorState cur = state();
    if (cur != EmulatorState::Running && cur != EmulatorState::Paused) {
        return false;
    }
    bool ok = false;
    enqueueAndWait([&]() {
        if (!gameLoaded_.load(std::memory_order_acquire) || !loader_.isLoaded()) {
            return;
        }
        const auto& sym = loader_.symbols();
        // Ensure deferred setup has run before serializing state.
        if (!firstFrameExecuted_.load(std::memory_order_acquire)) {
            runSingleCoreFrame();
        }
        const size_t size = sym.serialize_size();
        if (size == 0 || size > 32u * 1024u * 1024u) {
            return;
        }
        outState.resize(size);
        if (sym.serialize(outState.data(), outState.size())) {
            ok = true;
        } else {
            outState.clear();
        }
    });
    return ok;
}

bool LibretroFrontend::unserializeState(const std::vector<uint8_t>& stateData) {
    if (stateData.empty() || stateData.size() > 32u * 1024u * 1024u) {
        return false;
    }
    const EmulatorState cur = state();
    if (cur != EmulatorState::Running && cur != EmulatorState::Paused) {
        return false;
    }
    bool ok = false;
    enqueueAndWait([&]() {
        if (!gameLoaded_.load(std::memory_order_acquire) || !loader_.isLoaded()) {
            return;
        }
        const auto& sym = loader_.symbols();
        if (!firstFrameExecuted_.load(std::memory_order_acquire)) {
            runSingleCoreFrame();
        }
        const size_t expected = sym.serialize_size();
        if (expected == 0 || stateData.size() < expected) {
            return;
        }
        ok = sym.unserialize(stateData.data(), stateData.size());
    });
    return ok;
}

bool LibretroFrontend::applyCheats(const std::vector<std::string>& enabledCodes, std::string& errorOut) {
    const EmulatorState cur = state();
    if (cur != EmulatorState::Running && cur != EmulatorState::Paused) {
        errorOut = "No ROM is currently loaded.";
        return false;
    }

    const RomSystem sys = coreInfo().system;
    std::vector<std::string> sanitized;
    sanitized.reserve(enabledCodes.size());
    for (const auto& raw : enabledCodes) {
        const auto res = CheatValidator::validate(sys, raw);
        if (!res.valid) {
            errorOut = res.errorMessage;
            return false;
        }
        sanitized.push_back(res.normalizedCode);
    }

    bool ok = false;
    enqueueAndWait([&]() {
        if (!gameLoaded_.load(std::memory_order_acquire) || !loader_.isLoaded()) {
            return;
        }
        const auto& sym = loader_.symbols();
        // mGBA's retro_cheat_set ignores `enabled`, so we always reset then add only enabled codes.
        sym.cheat_reset();
        for (unsigned i = 0; i < static_cast<unsigned>(sanitized.size()); ++i) {
            sym.cheat_set(i, true, sanitized[i].c_str());
        }
        ok = true;
    });
    return ok;
}

std::vector<CoreOptionDefinition> LibretroFrontend::coreOptions() const {
    return optionsStore_.snapshot();
}

bool LibretroFrontend::setCoreOption(const std::string& key, const std::string& value) {
    const bool updated = optionsStore_.setValue(key, value);
    if (updated && key == "mgba_allow_opposing_directions") {
        inputState_.setAllowOpposingDirections(value == "yes");
    }
    return updated;
}

bool LibretroFrontend::captureLatestFrameArgb(
    uint32_t& outWidth,
    uint32_t& outHeight,
    std::vector<uint32_t>& outArgb
) const {
    std::vector<uint16_t> rgb565;
    VideoFrameBuffer::FrameMetadata meta;
    if (!videoBuffer_.consumeLatest(0, rgb565, meta) || meta.width == 0 || meta.height == 0) {
        outWidth = 0;
        outHeight = 0;
        outArgb.clear();
        return false;
    }
    outWidth = meta.width;
    outHeight = meta.height;
    outArgb.resize(rgb565.size());
    for (size_t i = 0; i < rgb565.size(); ++i) {
        const uint16_t p = rgb565[i];
        const uint32_t r5 = (p >> 11) & 0x1Fu;
        const uint32_t g6 = (p >> 5) & 0x3Fu;
        const uint32_t b5 = p & 0x1Fu;
        const uint32_t r8 = (r5 << 3) | (r5 >> 2);
        const uint32_t g8 = (g6 << 2) | (g6 >> 4);
        const uint32_t b8 = (b5 << 3) | (b5 >> 2);
        outArgb[i] = 0xFF000000u | (r8 << 16) | (g8 << 8) | b8;
    }
    return true;
}

bool LibretroFrontend::shutdown(std::vector<uint8_t>* outFinalSram, uint32_t timeoutMs) {
    if (outFinalSram) {
        outFinalSram->clear();
    }
    const EmulatorState cur = state();
    if (cur == EmulatorState::Unloaded && !workerThread_.joinable()) {
        return true;
    }

    // Bounded shutdown protocol (ADR-0003):
    // 1. Transition to Stopping and release all held inputs.
    transitionState(EmulatorState::Stopping);
    inputState_.releaseAll();

    // 2. Capture final SRAM & unload core cleanly on the worker thread.
    if (workerThread_.joinable()) {
        auto donePromise = std::make_shared<std::promise<void>>();
        std::future<void> doneFuture = donePromise->get_future();

        {
            std::lock_guard<std::mutex> lock(queueMutex_);
            commandQueue_.push([this, outFinalSram, donePromise]() {
                if (outFinalSram && gameLoaded_.load(std::memory_order_acquire) && loader_.isLoaded()) {
                    const auto& sym = loader_.symbols();
                    void* data = sym.get_memory_data(RETRO_MEMORY_SAVE_RAM);
                    const size_t size = sym.get_memory_size(RETRO_MEMORY_SAVE_RAM);
                    if (data != nullptr && size > 0) {
                        const auto* bytes = static_cast<const uint8_t*>(data);
                        outFinalSram->assign(bytes, bytes + size);
                    }
                }
                unloadCoreOnWorker();
                donePromise->set_value();
            });
            workerStopRequested_.store(true, std::memory_order_release);
        }
        queueCv_.notify_all();

        const auto waitResult = doneFuture.wait_for(std::chrono::milliseconds(timeoutMs));
        if (waitResult != std::future_status::ready) {
            logMessage(RETRO_LOG_ERROR, "Bounded shutdown timed out waiting for worker cleanup.");
        }
        workerThread_.join();
    }

    audioBuffer_.clear();
    videoBuffer_.clear();
    optionsStore_.clear();

    if (gActiveFrontend_.load(std::memory_order_acquire) == this) {
        gActiveFrontend_.store(nullptr, std::memory_order_release);
    }

    transitionState(EmulatorState::Stopped);
    transitionState(EmulatorState::Unloaded);
    return true;
}

void LibretroFrontend::enqueueAndWait(std::function<void()> task) {
    auto promise = std::make_shared<std::promise<void>>();
    std::future<void> future = promise->get_future();
    {
        std::lock_guard<std::mutex> lock(queueMutex_);
        commandQueue_.push([t = std::move(task), promise]() {
            t();
            promise->set_value();
        });
    }
    queueCv_.notify_all();
    future.wait();
}

void LibretroFrontend::unloadCoreOnWorker() {
    if (loader_.isLoaded()) {
        const auto& sym = loader_.symbols();
        if (gameLoaded_.exchange(false, std::memory_order_acq_rel)) {
            sym.unload_game();
        }
        if (coreLoaded_.exchange(false, std::memory_order_acq_rel)) {
            sym.deinit();
        }
        loader_.unload();
    }
}

void LibretroFrontend::runSingleCoreFrame() {
    if (!gameLoaded_.load(std::memory_order_acquire) || !loader_.isLoaded()) {
        return;
    }
    loader_.symbols().run();
    firstFrameExecuted_.store(true, std::memory_order_release);
    totalFrames_.fetch_add(1, std::memory_order_release);
}

void LibretroFrontend::workerMain() {
    auto nextFrameDeadline = std::chrono::steady_clock::now();

    while (true) {
        // Drain all queued synchronous commands first.
        while (true) {
            std::function<void()> task;
            {
                std::lock_guard<std::mutex> lock(queueMutex_);
                if (commandQueue_.empty()) {
                    break;
                }
                task = std::move(commandQueue_.front());
                commandQueue_.pop();
            }
            task();
        }

        if (workerStopRequested_.load(std::memory_order_acquire)) {
            break;
        }

        const EmulatorState cur = state();
        if (cur != EmulatorState::Running) {
            // Zero busy-loop while paused or loading.
            std::unique_lock<std::mutex> lock(queueMutex_);
            queueCv_.wait(lock, [this]() {
                return !commandQueue_.empty() ||
                       workerStopRequested_.load(std::memory_order_acquire) ||
                       state() == EmulatorState::Running;
            });
            nextFrameDeadline = std::chrono::steady_clock::now();
            continue;
        }

        runSingleCoreFrame();

        // Steady-clock frame pacing matched to core FPS (2x when fast-forward is enabled).
        double fps = 59.7275;
        {
            std::lock_guard<std::mutex> lock(infoMutex_);
            fps = coreInfo_.fps;
        }
        const double speedMultiplier = fastForward_.load(std::memory_order_acquire) ? 2.0 : 1.0;
        const auto framePeriod = std::chrono::duration_cast<std::chrono::steady_clock::duration>(
            std::chrono::duration<double>(1.0 / (fps * speedMultiplier))
        );
        nextFrameDeadline += framePeriod;
        const auto now = std::chrono::steady_clock::now();
        if (nextFrameDeadline < now - framePeriod * 4) {
            // Avoid spiral of catch-up bursts after OS scheduling stalls.
            nextFrameDeadline = now;
        } else if (nextFrameDeadline > now) {
            std::unique_lock<std::mutex> lock(queueMutex_);
            queueCv_.wait_until(lock, nextFrameDeadline, [this]() {
                return !commandQueue_.empty() ||
                       workerStopRequested_.load(std::memory_order_acquire) ||
                       state() != EmulatorState::Running;
            });
        }
    }
}

bool LibretroFrontend::cbEnvironment(unsigned cmd, void* data) {
    LibretroFrontend* self = gActiveFrontend_.load(std::memory_order_acquire);
    return self ? self->handleEnvironment(cmd, data) : false;
}

void LibretroFrontend::cbVideoRefresh(const void* data, unsigned width, unsigned height, size_t pitch) {
    LibretroFrontend* self = gActiveFrontend_.load(std::memory_order_acquire);
    if (self) {
        self->handleVideoRefresh(data, width, height, pitch);
    }
}

void LibretroFrontend::cbAudioSample(int16_t left, int16_t right) {
    const int16_t stereo[2] = {left, right};
    cbAudioSampleBatch(stereo, 1);
}

size_t LibretroFrontend::cbAudioSampleBatch(const int16_t* data, size_t frames) {
    LibretroFrontend* self = gActiveFrontend_.load(std::memory_order_acquire);
    return self ? self->handleAudioBatch(data, frames) : frames;
}

void LibretroFrontend::cbInputPoll() {
    // InputState is updated atomically by UI/controller threads.
}

int16_t LibretroFrontend::cbInputState(unsigned port, unsigned device, unsigned index, unsigned id) {
    if (port != 0 || device != RETRO_DEVICE_JOYPAD || index != 0) {
        return 0;
    }
    LibretroFrontend* self = gActiveFrontend_.load(std::memory_order_acquire);
    if (!self) {
        return 0;
    }
    if (id == RETRO_DEVICE_ID_JOYPAD_MASK) {
        return static_cast<int16_t>(self->inputState_.currentMask());
    }
    return self->inputState_.isPressed(id) ? 1 : 0;
}

void LibretroFrontend::cbLogPrintf(enum retro_log_level level, const char* fmt, ...) {
    LibretroFrontend* self = gActiveFrontend_.load(std::memory_order_acquire);
    if (!self || !fmt) {
        return;
    }
    char buf[512];
    va_list args;
    va_start(args, fmt);
    std::vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);
    self->logMessage(static_cast<int>(level), buf);
}

bool LibretroFrontend::handleEnvironment(unsigned cmd, void* data) {
    switch (cmd) {
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: {
            if (!data) return false;
            const auto fmt = *static_cast<const retro_pixel_format*>(data);
            if (fmt == RETRO_PIXEL_FORMAT_RGB565) {
                pixelFormat_ = fmt;
                return true;
            }
            return false;
        }
        case RETRO_ENVIRONMENT_GET_CAN_DUPE: {
            if (!data) return false;
            *static_cast<bool*>(data) = true;
            return true;
        }
        case RETRO_ENVIRONMENT_GET_LOG_INTERFACE: {
            if (!data) return false;
            auto* cb = static_cast<retro_log_callback*>(data);
            cb->log = &LibretroFrontend::cbLogPrintf;
            return true;
        }
        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY: {
            if (!data) return false;
            std::lock_guard<std::mutex> lock(infoMutex_);
            *static_cast<const char**>(data) = systemDir_.empty() ? nullptr : systemDir_.c_str();
            return true;
        }
        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY: {
            if (!data) return false;
            std::lock_guard<std::mutex> lock(infoMutex_);
            *static_cast<const char**>(data) = saveDir_.empty() ? nullptr : saveDir_.c_str();
            return true;
        }
        case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION: {
            if (!data) return false;
            *static_cast<unsigned*>(data) = 2;
            return true;
        }
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2: {
            if (!data) return false;
            optionsStore_.registerFromV2(static_cast<const retro_core_options_v2*>(data));
            return true;
        }
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2_INTL: {
            if (!data) return false;
            const auto* intl = static_cast<const retro_core_options_v2_intl*>(data);
            if (intl->us) {
                optionsStore_.registerFromV2(intl->us);
                return true;
            }
            return false;
        }
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS: {
            if (!data) return false;
            optionsStore_.registerFromV1(static_cast<const retro_core_option_definition*>(data));
            return true;
        }
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_INTL: {
            if (!data) return false;
            const auto* intl = static_cast<const retro_core_options_intl*>(data);
            if (intl->us) {
                optionsStore_.registerFromV1(intl->us);
                return true;
            }
            return false;
        }
        case RETRO_ENVIRONMENT_SET_VARIABLES: {
            if (!data) return false;
            optionsStore_.registerFromV0(static_cast<const retro_variable*>(data));
            return true;
        }
        case RETRO_ENVIRONMENT_GET_VARIABLE: {
            if (!data) return false;
            auto* var = static_cast<retro_variable*>(data);
            var->value = optionsStore_.getValueCStr(var->key);
            return var->value != nullptr;
        }
        case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE: {
            if (!data) return false;
            *static_cast<bool*>(data) = optionsStore_.consumeDirtyFlag();
            return true;
        }
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_DISPLAY: {
            if (!data) return false;
            const auto* disp = static_cast<const retro_core_option_display*>(data);
            optionsStore_.setVisibility(disp->key, disp->visible);
            return true;
        }
        case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS: {
            return true;
        }
        case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
        case RETRO_ENVIRONMENT_SET_MEMORY_MAPS:
        case RETRO_ENVIRONMENT_SET_SUPPORT_ACHIEVEMENTS:
        case RETRO_ENVIRONMENT_SET_MINIMUM_AUDIO_LATENCY:
        case RETRO_ENVIRONMENT_SET_AUDIO_BUFFER_STATUS_CALLBACK: {
            return true;
        }
        case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO: {
            if (!data) return false;
            const auto* av = static_cast<const retro_system_av_info*>(data);
            double outputRate = 48000.0;
            {
                std::lock_guard<std::mutex> lock(infoMutex_);
                coreInfo_.baseWidth = av->geometry.base_width;
                coreInfo_.baseHeight = av->geometry.base_height;
                if (av->geometry.aspect_ratio > 0.0f) {
                    coreInfo_.aspectRatio = av->geometry.aspect_ratio;
                }
                if (av->timing.fps > 1.0) {
                    coreInfo_.fps = av->timing.fps;
                }
                if (av->timing.sample_rate > 1000.0) {
                    coreInfo_.coreSampleRate = av->timing.sample_rate;
                }
                outputRate = coreInfo_.outputSampleRate;
            }
            if (av->timing.sample_rate > 1000.0) {
                resampler_.configure(av->timing.sample_rate, outputRate);
            }
            return true;
        }
        case RETRO_ENVIRONMENT_SET_GEOMETRY: {
            if (!data) return false;
            const auto* geom = static_cast<const retro_game_geometry*>(data);
            std::lock_guard<std::mutex> lock(infoMutex_);
            coreInfo_.baseWidth = geom->base_width;
            coreInfo_.baseHeight = geom->base_height;
            if (geom->aspect_ratio > 0.0f) {
                coreInfo_.aspectRatio = geom->aspect_ratio;
            }
            return true;
        }
        // Explicitly report unsupported hardware interfaces honestly (Plan §17 / ADR-0001).
        case RETRO_ENVIRONMENT_GET_SENSOR_INTERFACE:
        case RETRO_ENVIRONMENT_GET_CAMERA_INTERFACE:
        case RETRO_ENVIRONMENT_GET_RUMBLE_INTERFACE:
        default:
            return false;
    }
}

void LibretroFrontend::handleVideoRefresh(const void* data, unsigned width, unsigned height, size_t pitch) {
    float aspect = 0.0f;
    {
        std::lock_guard<std::mutex> lock(infoMutex_);
        aspect = coreInfo_.aspectRatio;
    }
    videoBuffer_.publishRgb565(data, width, height, pitch, aspect);

    FrameReadyCallback cb;
    {
        std::lock_guard<std::mutex> lock(callbackMutex_);
        cb = frameReadyCb_;
    }
    if (cb) {
        cb();
    }
}

size_t LibretroFrontend::handleAudioBatch(const int16_t* data, size_t frames) {
    if (!data || frames == 0) {
        return 0;
    }
    totalAudioFrames_.fetch_add(frames, std::memory_order_relaxed);

    // During 2x fast-forward, skip alternating batches to keep ring buffer latency bounded
    // instead of saturating audio output or drifting.
    if (fastForward_.load(std::memory_order_relaxed)) {
        if ((++fastForwardAudioCounter_ & 1u) != 0u) {
            return frames;
        }
    }

    resampler_.process(data, frames, resampledScratch_);
    if (!resampledScratch_.empty()) {
        audioBuffer_.push(resampledScratch_.data(), resampledScratch_.size() / 2);
    }
    return frames;
}

}  // namespace gba
