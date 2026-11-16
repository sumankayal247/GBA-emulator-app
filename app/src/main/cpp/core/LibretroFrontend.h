#pragma once

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstddef>
#include <cstdint>
#include <functional>
#include <future>
#include <mutex>
#include <queue>
#include <string>
#include <thread>
#include <vector>

#include "AudioRingBuffer.h"
#include "CheatValidator.h"
#include "CoreOptionsStore.h"
#include "EmulatorState.h"
#include "InputState.h"
#include "LibretroLoader.h"
#include "RomValidator.h"
#include "VideoFrameBuffer.h"

namespace gba {

struct CoreInfoSnapshot {
    std::string libraryName;
    std::string libraryVersion;
    RomSystem system{RomSystem::Unknown};
    uint32_t baseWidth{0};
    uint32_t baseHeight{0};
    float aspectRatio{1.5f};
    double fps{59.7275};
    double coreSampleRate{65536.0};
    double outputSampleRate{48000.0};
};

struct LoadConfig {
    std::string coreLibraryPath;
    std::string romFilePath;
    std::string systemDirectory;
    std::string saveDirectory;
    std::vector<uint8_t> initialSram;
    double outputSampleRate{48000.0};
    bool startPaused{false};
};

// Single-owner Libretro frontend executing all retro_* core calls on a dedicated
// worker thread (ADR-0003). Safe to drive from Android JNI or host integration tests.
class LibretroFrontend {
public:
    using FrameReadyCallback = std::function<void()>;
    using LogCallback = std::function<void(int level, const std::string& message)>;

    LibretroFrontend();
    ~LibretroFrontend();

    LibretroFrontend(const LibretroFrontend&) = delete;
    LibretroFrontend& operator=(const LibretroFrontend&) = delete;

    void setFrameReadyCallback(FrameReadyCallback cb);
    void setLogCallback(LogCallback cb);

    // Lifecycle API (thread-safe, synchronous completion on worker thread).
    bool loadRom(const LoadConfig& config, std::string& errorOut);
    bool pause();
    bool resume();
    bool reset();
    // Runs exactly N frames synchronously while paused (useful for deterministic testing).
    bool stepFrames(uint32_t frameCount);
    // Bounded shutdown protocol: stops loop, releases inputs, unloads core, joins thread.
    // If outFinalSram != nullptr, captures final SRAM bytes before unloading.
    bool shutdown(std::vector<uint8_t>* outFinalSram = nullptr, uint32_t timeoutMs = 5000);

    // Save/State/Cheat/Option API (serialized safely onto worker thread).
    bool extractSram(std::vector<uint8_t>& outSram);
    bool loadSram(const std::vector<uint8_t>& sramData);
    bool serializeState(std::vector<uint8_t>& outState);
    bool unserializeState(const std::vector<uint8_t>& stateData);
    bool applyCheats(const std::vector<std::string>& enabledCodes, std::string& errorOut);
    std::vector<CoreOptionDefinition> coreOptions() const;
    bool setCoreOption(const std::string& key, const std::string& value);

    // Real-time controls (lock-free / non-blocking).
    void setTouchInputMask(uint16_t mask) noexcept { inputState_.setTouchMask(mask); }
    void setGamepadInputMask(uint16_t mask) noexcept { inputState_.setGamepadMask(mask); }
    void releaseAllInputs() noexcept { inputState_.releaseAll(); }
    void setFastForward(bool enabled) noexcept { fastForward_.store(enabled, std::memory_order_release); }
    bool isFastForward() const noexcept { return fastForward_.load(std::memory_order_acquire); }
    void setAudioMuted(bool muted) noexcept { audioBuffer_.setMuted(muted); }
    void setAudioVolume(float volume) noexcept { audioBuffer_.setVolume(volume); }

    // Inspection accessors.
    EmulatorState state() const noexcept { return state_.load(std::memory_order_acquire); }
    CoreInfoSnapshot coreInfo() const;
    VideoFrameBuffer& videoBuffer() noexcept { return videoBuffer_; }
    const VideoFrameBuffer& videoBuffer() const noexcept { return videoBuffer_; }
    AudioRingBuffer& audioBuffer() noexcept { return audioBuffer_; }
    const AudioRingBuffer& audioBuffer() const noexcept { return audioBuffer_; }
    uint64_t totalFramesExecuted() const noexcept { return totalFrames_.load(std::memory_order_acquire); }
    uint64_t totalAudioFramesProduced() const noexcept { return totalAudioFrames_.load(std::memory_order_acquire); }
    std::string lastError() const;

private:
    bool transitionState(EmulatorState target);
    void workerMain();
    void enqueueAndWait(std::function<void()> task);
    void runSingleCoreFrame();
    void unloadCoreOnWorker();
    void logMessage(int level, const std::string& msg) const;

    // Libretro C static trampolines forwarding to gActiveFrontend.
    static bool cbEnvironment(unsigned cmd, void* data);
    static void cbVideoRefresh(const void* data, unsigned width, unsigned height, size_t pitch);
    static void cbAudioSample(int16_t left, int16_t right);
    static size_t cbAudioSampleBatch(const int16_t* data, size_t frames);
    static void cbInputPoll();
    static int16_t cbInputState(unsigned port, unsigned device, unsigned index, unsigned id);
    static void cbLogPrintf(enum retro_log_level level, const char* fmt, ...);

    bool handleEnvironment(unsigned cmd, void* data);
    void handleVideoRefresh(const void* data, unsigned width, unsigned height, size_t pitch);
    size_t handleAudioBatch(const int16_t* data, size_t frames);

    static std::atomic<LibretroFrontend*> gActiveFrontend_;

    std::atomic<EmulatorState> state_{EmulatorState::Unloaded};
    std::atomic<bool> fastForward_{false};
    std::atomic<bool> workerStopRequested_{false};
    std::atomic<uint64_t> totalFrames_{0};
    std::atomic<uint64_t> totalAudioFrames_{0};
    std::atomic<bool> coreLoaded_{false};
    std::atomic<bool> gameLoaded_{false};
    std::atomic<bool> firstFrameExecuted_{false};

    mutable std::mutex stateMutex_;
    std::mutex queueMutex_;
    std::condition_variable queueCv_;
    std::queue<std::function<void()>> commandQueue_;
    std::thread workerThread_;

    LibretroLoader loader_;
    InputState inputState_;
    CoreOptionsStore optionsStore_;
    VideoFrameBuffer videoBuffer_;
    AudioRingBuffer audioBuffer_{8192};
    LinearStereoResampler resampler_;
    std::vector<int16_t> resampledScratch_;

    mutable std::mutex infoMutex_;
    CoreInfoSnapshot coreInfo_;
    std::string systemDir_;
    std::string saveDir_;
    std::string lastError_;

    mutable std::mutex callbackMutex_;
    FrameReadyCallback frameReadyCb_;
    LogCallback logCb_;

    retro_pixel_format pixelFormat_{RETRO_PIXEL_FORMAT_RGB565};
    uint32_t fastForwardAudioCounter_{0};
};

}  // namespace gba
