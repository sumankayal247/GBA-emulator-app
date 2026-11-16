#include "AAudioOutput.h"

#include <android/log.h>

#include <cstring>

namespace gba {

namespace {
constexpr const char* kAudioLogTag = "GbaAAudio";
}  // namespace

AAudioOutput::AAudioOutput(AudioRingBuffer& ringBuffer)
    : ringBuffer_(ringBuffer) {}

AAudioOutput::~AAudioOutput() {
    stop();
}

bool AAudioOutput::start(int32_t sampleRateHz) {
    std::lock_guard<std::mutex> lock(mutex_);
    requestedRate_ = sampleRateHz > 0 ? sampleRateHz : 48000;
    paused_.store(false, std::memory_order_release);
    active_.store(true, std::memory_order_release);
    return openAndStartLocked(requestedRate_);
}

void AAudioOutput::pause() {
    paused_.store(true, std::memory_order_release);
    std::lock_guard<std::mutex> lock(mutex_);
    if (stream_) {
        AAudioStream_requestPause(stream_);
    }
}

void AAudioOutput::resume() {
    paused_.store(false, std::memory_order_release);
    std::lock_guard<std::mutex> lock(mutex_);
    if (stream_) {
        const aaudio_result_t res = AAudioStream_requestStart(stream_);
        if (res != AAUDIO_OK) {
            __android_log_print(ANDROID_LOG_WARN, kAudioLogTag,
                                "AAudio resume failed (%d), reopening stream", res);
            closeLocked();
            openAndStartLocked(requestedRate_);
        }
    } else if (active_.load(std::memory_order_acquire)) {
        openAndStartLocked(requestedRate_);
    }
}

void AAudioOutput::stop() {
    active_.store(false, std::memory_order_release);
    paused_.store(true, std::memory_order_release);
    if (restartThread_.joinable()) {
        restartThread_.join();
    }
    std::lock_guard<std::mutex> lock(mutex_);
    closeLocked();
}

bool AAudioOutput::openAndStartLocked(int32_t sampleRateHz) {
    closeLocked();

    AAudioStreamBuilder* builder = nullptr;
    aaudio_result_t result = AAudio_createStreamBuilder(&builder);
    if (result != AAUDIO_OK || !builder) {
        __android_log_print(ANDROID_LOG_ERROR, kAudioLogTag, "AAudio_createStreamBuilder failed: %d", result);
        return false;
    }

    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setChannelCount(builder, 2);
    AAudioStreamBuilder_setSampleRate(builder, sampleRateHz);
    AAudioStreamBuilder_setDataCallback(builder, &AAudioOutput::onAudioData, this);
    AAudioStreamBuilder_setErrorCallback(builder, &AAudioOutput::onAudioError, this);

#if __ANDROID_API__ >= 28
    AAudioStreamBuilder_setUsage(builder, AAUDIO_USAGE_GAME);
    AAudioStreamBuilder_setContentType(builder, AAUDIO_CONTENT_TYPE_SONIFICATION);
#endif

    AAudioStream* newStream = nullptr;
    result = AAudioStreamBuilder_openStream(builder, &newStream);
    AAudioStreamBuilder_delete(builder);
    if (result != AAUDIO_OK || !newStream) {
        __android_log_print(ANDROID_LOG_ERROR, kAudioLogTag, "AAudioStreamBuilder_openStream failed: %d", result);
        return false;
    }

    const int32_t framesPerBurst = AAudioStream_getFramesPerBurst(newStream);
    if (framesPerBurst > 0) {
        AAudioStream_setBufferSizeInFrames(newStream, framesPerBurst * 2);
    }

    actualSampleRate_.store(AAudioStream_getSampleRate(newStream), std::memory_order_release);
    stream_ = newStream;

    if (!paused_.load(std::memory_order_acquire)) {
        result = AAudioStream_requestStart(stream_);
        if (result != AAUDIO_OK) {
            __android_log_print(ANDROID_LOG_ERROR, kAudioLogTag, "AAudioStream_requestStart failed: %d", result);
            closeLocked();
            return false;
        }
    }

    __android_log_print(ANDROID_LOG_INFO, kAudioLogTag,
                        "AAudio started: rate=%dHz burst=%d",
                        actualSampleRate_.load(), framesPerBurst);
    return true;
}

void AAudioOutput::closeLocked() {
    if (stream_) {
        AAudioStream_requestStop(stream_);
        AAudioStream_close(stream_);
        stream_ = nullptr;
    }
}

void AAudioOutput::scheduleRestart() {
    if (!active_.load(std::memory_order_acquire)) {
        return;
    }
    bool expected = false;
    if (!restarting_.compare_exchange_strong(expected, true, std::memory_order_acq_rel)) {
        return;
    }
    if (restartThread_.joinable()) {
        restartThread_.detach();
    }
    restartThread_ = std::thread([this]() {
        std::lock_guard<std::mutex> lock(mutex_);
        if (active_.load(std::memory_order_acquire)) {
            __android_log_print(ANDROID_LOG_INFO, kAudioLogTag,
                                "Reopening AAudio stream after audio route change");
            closeLocked();
            openAndStartLocked(requestedRate_);
        }
        restarting_.store(false, std::memory_order_release);
    });
}

aaudio_data_callback_result_t AAudioOutput::onAudioData(
    AAudioStream* /*stream*/,
    void* userData,
    void* audioData,
    int32_t numFrames
) {
    auto* self = static_cast<AAudioOutput*>(userData);
    if (!self || !audioData || numFrames <= 0) {
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }
    if (self->paused_.load(std::memory_order_acquire)) {
        std::memset(audioData, 0, static_cast<size_t>(numFrames) * 2 * sizeof(int16_t));
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }
    self->ringBuffer_.pop(static_cast<int16_t*>(audioData), static_cast<size_t>(numFrames));
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

void AAudioOutput::onAudioError(
    AAudioStream* /*stream*/,
    void* userData,
    aaudio_result_t error
) {
    auto* self = static_cast<AAudioOutput*>(userData);
    if (!self) {
        return;
    }
    if (error == AAUDIO_ERROR_DISCONNECTED) {
        self->scheduleRestart();
    } else {
        __android_log_print(ANDROID_LOG_WARN, kAudioLogTag, "AAudio error callback: %d", error);
    }
}

}  // namespace gba
