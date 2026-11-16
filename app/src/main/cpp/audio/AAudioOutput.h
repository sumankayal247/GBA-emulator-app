#pragma once

#include <aaudio/AAudio.h>

#include <atomic>
#include <cstdint>
#include <mutex>
#include <thread>

#include "../core/AudioRingBuffer.h"

namespace gba {

// Low-latency Android NDK AAudio consumer draining the lock-free AudioRingBuffer (ADR-0003).
// Handles Bluetooth/headset disconnects (AAUDIO_ERROR_DISCONNECTED) cleanly off the callback thread.
class AAudioOutput {
public:
    explicit AAudioOutput(AudioRingBuffer& ringBuffer);
    ~AAudioOutput();

    AAudioOutput(const AAudioOutput&) = delete;
    AAudioOutput& operator=(const AAudioOutput&) = delete;

    bool start(int32_t sampleRateHz = 48000);
    void pause();
    void resume();
    void stop();

    int32_t actualSampleRate() const noexcept {
        return actualSampleRate_.load(std::memory_order_acquire);
    }

private:
    bool openAndStartLocked(int32_t sampleRateHz);
    void closeLocked();
    void scheduleRestart();

    static aaudio_data_callback_result_t onAudioData(
        AAudioStream* stream,
        void* userData,
        void* audioData,
        int32_t numFrames
    );

    static void onAudioError(
        AAudioStream* stream,
        void* userData,
        aaudio_result_t error
    );

    AudioRingBuffer& ringBuffer_;
    std::mutex mutex_;
    AAudioStream* stream_{nullptr};
    int32_t requestedRate_{48000};
    std::atomic<int32_t> actualSampleRate_{48000};
    std::atomic<bool> active_{false};
    std::atomic<bool> paused_{false};
    std::atomic<bool> restarting_{false};
    std::thread restartThread_;
};

}  // namespace gba
