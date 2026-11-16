#pragma once

#include <cstddef>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <vector>

namespace gba {

// Thread-safe tightly-packed RGB565 frame mailbox decoupling the emulator worker thread
// from the OpenGL ES surface renderer (or host test inspector).
class VideoFrameBuffer {
public:
    struct FrameMetadata {
        uint32_t width{0};
        uint32_t height{0};
        float aspectRatio{1.5f};
        uint64_t frameIndex{0};
    };

    // Unpacks row-by-row from libretro's potentially padded pitch into a contiguous
    // width * height RGB565 buffer. Handles NULL dup frames per Libretro spec.
    void publishRgb565(
        const void* data,
        unsigned width,
        unsigned height,
        size_t pitchBytes,
        float aspectRatio
    ) {
        if (width == 0 || height == 0 || width > 1024 || height > 1024) {
            return;
        }
        std::lock_guard<std::mutex> lock(mutex_);
        if (data != nullptr) {
            const size_t rowBytes = static_cast<size_t>(width) * sizeof(uint16_t);
            if (pitchBytes < rowBytes) {
                return;
            }
            pixels_.resize(static_cast<size_t>(width) * static_cast<size_t>(height));
            const auto* src = static_cast<const uint8_t*>(data);
            auto* dst = reinterpret_cast<uint8_t*>(pixels_.data());
            for (unsigned y = 0; y < height; ++y) {
                std::memcpy(dst + static_cast<size_t>(y) * rowBytes,
                            src + static_cast<size_t>(y) * pitchBytes,
                            rowBytes);
            }
        }
        meta_.width = width;
        meta_.height = height;
        meta_.aspectRatio = aspectRatio > 0.0f
            ? aspectRatio
            : (static_cast<float>(width) / static_cast<float>(height));
        ++meta_.frameIndex;
    }

    // Copies the latest frame out only if frameIndex > lastConsumedIndex.
    bool consumeLatest(
        uint64_t lastConsumedIndex,
        std::vector<uint16_t>& outPixels,
        FrameMetadata& outMeta
    ) const {
        std::lock_guard<std::mutex> lock(mutex_);
        if (meta_.frameIndex == 0 || meta_.frameIndex == lastConsumedIndex || pixels_.empty()) {
            return false;
        }
        outPixels = pixels_;
        outMeta = meta_;
        return true;
    }

    FrameMetadata metadata() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return meta_;
    }

    void clear() {
        std::lock_guard<std::mutex> lock(mutex_);
        pixels_.clear();
        meta_ = FrameMetadata{};
    }

private:
    mutable std::mutex mutex_;
    std::vector<uint16_t> pixels_;
    FrameMetadata meta_;
};

}  // namespace gba
