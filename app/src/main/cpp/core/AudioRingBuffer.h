#pragma once

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <vector>

namespace gba {

// Lock-free Single-Producer Single-Consumer (SPSC) stereo int16 PCM ring buffer.
// Producer: emulator worker thread. Consumer: audio output callback thread (AAudio / test).
class AudioRingBuffer {
public:
    // capacityFrames must be a power of two >= 256.
    explicit AudioRingBuffer(size_t capacityFrames = 4096)
        : capacityFrames_(nextPowerOfTwo(std::max<size_t>(capacityFrames, 256))),
          mask_(capacityFrames_ - 1),
          storage_(capacityFrames_ * 2, 0) {}

    size_t capacityFrames() const noexcept { return capacityFrames_; }

    size_t availableFrames() const noexcept {
        const size_t w = writePos_.load(std::memory_order_acquire);
        const size_t r = readPos_.load(std::memory_order_acquire);
        return w - r;
    }

    size_t freeFrames() const noexcept {
        return capacityFrames_ - availableFrames();
    }

    // Producer-only: pushes interleaved stereo int16 frames. Drops excess frames on overrun
    // rather than blocking the emulator thread. Returns frames actually written.
    size_t push(const int16_t* stereoSamples, size_t frameCount) noexcept {
        if (!stereoSamples || frameCount == 0) {
            return 0;
        }
        const size_t w = writePos_.load(std::memory_order_relaxed);
        const size_t r = readPos_.load(std::memory_order_acquire);
        const size_t availSpace = capacityFrames_ - (w - r);
        const size_t toWrite = std::min(frameCount, availSpace);
        if (toWrite < frameCount) {
            overrunFrames_.fetch_add(frameCount - toWrite, std::memory_order_relaxed);
        }
        if (toWrite == 0) {
            return 0;
        }

        const size_t startIdx = w & mask_;
        const size_t firstPart = std::min(toWrite, capacityFrames_ - startIdx);
        std::memcpy(&storage_[startIdx * 2], stereoSamples, firstPart * 2 * sizeof(int16_t));
        const size_t secondPart = toWrite - firstPart;
        if (secondPart > 0) {
            std::memcpy(&storage_[0], stereoSamples + firstPart * 2, secondPart * 2 * sizeof(int16_t));
        }

        writePos_.store(w + toWrite, std::memory_order_release);
        return toWrite;
    }

    // Consumer-only: pops interleaved stereo int16 frames, applying mute & volume scaling.
    // Fills any shortfall with silence and increments underrun counter.
    size_t pop(int16_t* outStereo, size_t frameCount) noexcept {
        if (!outStereo || frameCount == 0) {
            return 0;
        }
        const size_t r = readPos_.load(std::memory_order_relaxed);
        const size_t w = writePos_.load(std::memory_order_acquire);
        const size_t avail = w - r;
        const size_t toRead = std::min(frameCount, avail);

        const bool isMuted = muted_.load(std::memory_order_relaxed);
        const float vol = volume_.load(std::memory_order_relaxed);

        if (toRead > 0) {
            const size_t startIdx = r & mask_;
            const size_t firstPart = std::min(toRead, capacityFrames_ - startIdx);
            copyWithGain(outStereo, &storage_[startIdx * 2], firstPart * 2, isMuted, vol);
            const size_t secondPart = toRead - firstPart;
            if (secondPart > 0) {
                copyWithGain(outStereo + firstPart * 2, &storage_[0], secondPart * 2, isMuted, vol);
            }
            readPos_.store(r + toRead, std::memory_order_release);
        }

        if (toRead < frameCount) {
            std::memset(outStereo + toRead * 2, 0, (frameCount - toRead) * 2 * sizeof(int16_t));
            underrunFrames_.fetch_add(frameCount - toRead, std::memory_order_relaxed);
        }

        return toRead;
    }

    // Safe to call only when producer and consumer are paused/stopped.
    void clear() noexcept {
        readPos_.store(0, std::memory_order_relaxed);
        writePos_.store(0, std::memory_order_relaxed);
        std::fill(storage_.begin(), storage_.end(), 0);
    }

    void setMuted(bool muted) noexcept {
        muted_.store(muted, std::memory_order_relaxed);
    }

    bool isMuted() const noexcept {
        return muted_.load(std::memory_order_relaxed);
    }

    void setVolume(float volume) noexcept {
        const float clamped = std::clamp(volume, 0.0f, 1.0f);
        volume_.store(clamped, std::memory_order_relaxed);
    }

    float volume() const noexcept {
        return volume_.load(std::memory_order_relaxed);
    }

    uint64_t underrunFrames() const noexcept {
        return underrunFrames_.load(std::memory_order_relaxed);
    }

    uint64_t overrunFrames() const noexcept {
        return overrunFrames_.load(std::memory_order_relaxed);
    }

    void resetStats() noexcept {
        underrunFrames_.store(0, std::memory_order_relaxed);
        overrunFrames_.store(0, std::memory_order_relaxed);
    }

private:
    static size_t nextPowerOfTwo(size_t value) noexcept {
        size_t p = 1;
        while (p < value) {
            p <<= 1;
        }
        return p;
    }

    static void copyWithGain(
        int16_t* dst,
        const int16_t* src,
        size_t sampleCount,
        bool muted,
        float volume
    ) noexcept {
        if (muted || volume <= 0.0001f) {
            std::memset(dst, 0, sampleCount * sizeof(int16_t));
            return;
        }
        if (volume >= 0.9999f) {
            std::memcpy(dst, src, sampleCount * sizeof(int16_t));
            return;
        }
        for (size_t i = 0; i < sampleCount; ++i) {
            const float scaled = static_cast<float>(src[i]) * volume;
            const int32_t clamped = std::clamp(static_cast<int32_t>(std::lrint(scaled)), -32768, 32767);
            dst[i] = static_cast<int16_t>(clamped);
        }
    }

    const size_t capacityFrames_;
    const size_t mask_;
    std::vector<int16_t> storage_;
    alignas(64) std::atomic<size_t> writePos_{0};
    alignas(64) std::atomic<size_t> readPos_{0};
    std::atomic<bool> muted_{false};
    std::atomic<float> volume_{1.0f};
    std::atomic<uint64_t> underrunFrames_{0};
    std::atomic<uint64_t> overrunFrames_{0};
};

// Lightweight linear stereo resampler converting mGBA core rates (e.g. 65536 Hz / 32768 Hz)
// to Android native hardware sample rates (e.g. 48000 Hz).
class LinearStereoResampler {
public:
    void configure(double inputRate, double outputRate) {
        if (inputRate <= 0.0 || outputRate <= 0.0) {
            ratio_ = 1.0;
        } else {
            ratio_ = inputRate / outputRate;
        }
        reset();
    }

    void reset() {
        phase_ = 0.0;
        prevL_ = 0;
        prevR_ = 0;
        hasPrev_ = false;
    }

    double ratio() const noexcept { return ratio_; }

    void process(
        const int16_t* inStereo,
        size_t inFrames,
        std::vector<int16_t>& outStereo
    ) {
        outStereo.clear();
        if (!inStereo || inFrames == 0) {
            return;
        }
        if (std::abs(ratio_ - 1.0) < 0.0001) {
            outStereo.assign(inStereo, inStereo + inFrames * 2);
            prevL_ = inStereo[(inFrames - 1) * 2];
            prevR_ = inStereo[(inFrames - 1) * 2 + 1];
            hasPrev_ = true;
            return;
        }

        const size_t estOut = static_cast<size_t>(std::ceil(static_cast<double>(inFrames) / ratio_)) + 4;
        outStereo.reserve(estOut * 2);

        if (!hasPrev_) {
            prevL_ = inStereo[0];
            prevR_ = inStereo[1];
            hasPrev_ = true;
        }

        double pos = phase_;
        while (pos < static_cast<double>(inFrames)) {
            const int64_t idx = static_cast<int64_t>(std::floor(pos));
            const double frac = pos - static_cast<double>(idx);

            int16_t l0 = 0;
            int16_t r0 = 0;
            int16_t l1 = 0;
            int16_t r1 = 0;

            if (idx < 0) {
                l0 = prevL_;
                r0 = prevR_;
                l1 = inStereo[0];
                r1 = inStereo[1];
            } else {
                const size_t i0 = static_cast<size_t>(idx);
                const size_t i1 = std::min(i0 + 1, inFrames - 1);
                l0 = inStereo[i0 * 2];
                r0 = inStereo[i0 * 2 + 1];
                l1 = inStereo[i1 * 2];
                r1 = inStereo[i1 * 2 + 1];
            }

            const auto interp = [frac](int16_t a, int16_t b) -> int16_t {
                const double val = static_cast<double>(a) + (static_cast<double>(b) - static_cast<double>(a)) * frac;
                return static_cast<int16_t>(std::clamp(static_cast<int32_t>(std::lrint(val)), -32768, 32767));
            };

            outStereo.push_back(interp(l0, l1));
            outStereo.push_back(interp(r0, r1));
            pos += ratio_;
        }

        phase_ = pos - static_cast<double>(inFrames);
        prevL_ = inStereo[(inFrames - 1) * 2];
        prevR_ = inStereo[(inFrames - 1) * 2 + 1];
    }

private:
    double ratio_{1.0};
    double phase_{0.0};
    int16_t prevL_{0};
    int16_t prevR_{0};
    bool hasPrev_{false};
};

}  // namespace gba
