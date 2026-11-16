#pragma once

#include <android/native_window.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <mutex>
#include <thread>
#include <vector>

#include "../core/VideoFrameBuffer.h"

namespace gba {

enum class VideoScalingMode : int32_t {
    AspectFit = 0,
    IntegerScale = 1,
};

// Dedicated EGL / OpenGL ES 3.0 renderer attached to an Android SurfaceView's ANativeWindow.
// Owns its own render thread so EGL context thread-affinity is guaranteed and surface
// recreation/rotation is completely decoupled from the emulator worker thread (ADR-0003).
class GlVideoRenderer {
public:
    explicit GlVideoRenderer(VideoFrameBuffer& frameBuffer);
    ~GlVideoRenderer();

    GlVideoRenderer(const GlVideoRenderer&) = delete;
    GlVideoRenderer& operator=(const GlVideoRenderer&) = delete;

    void start();
    void stop();

    // Takes a reference via ANativeWindow_acquire; safe to call from Android UI thread.
    void attachSurface(ANativeWindow* window, int32_t width, int32_t height);
    void resizeSurface(int32_t width, int32_t height);
    void detachSurface();

    void setScalingMode(VideoScalingMode mode);
    void notifyNewFrame();

private:
    void renderLoop();
    bool initEgl(ANativeWindow* window);
    void destroyEgl();
    bool initGlProgram();
    void renderCurrentFrame();

    VideoFrameBuffer& frameBuffer_;

    std::mutex mutex_;
    std::condition_variable cv_;
    std::thread thread_;

    bool running_{false};
    bool stopRequested_{false};
    bool surfaceDirty_{false};
    bool redrawRequested_{false};

    ANativeWindow* pendingWindow_{nullptr};
    ANativeWindow* activeWindow_{nullptr};
    int32_t surfaceWidth_{0};
    int32_t surfaceHeight_{0};
    VideoScalingMode scalingMode_{VideoScalingMode::AspectFit};

    EGLDisplay eglDisplay_{EGL_NO_DISPLAY};
    EGLSurface eglSurface_{EGL_NO_SURFACE};
    EGLContext eglContext_{EGL_NO_CONTEXT};
    EGLConfig eglConfig_{nullptr};

    GLuint program_{0};
    GLuint texture_{0};
    GLuint vao_{0};
    uint32_t texWidth_{0};
    uint32_t texHeight_{0};

    uint64_t lastFrameIndex_{0};
    std::vector<uint16_t> localPixels_;
    VideoFrameBuffer::FrameMetadata localMeta_;
};

}  // namespace gba
