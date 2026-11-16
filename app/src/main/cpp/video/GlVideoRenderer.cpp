#include "GlVideoRenderer.h"

#include <android/log.h>

#include <algorithm>
#include <cmath>

namespace gba {

namespace {

constexpr const char* kLogTag = "GbaGlRenderer";

constexpr const char* kVertexShaderSrc = R"(#version 300 es
out vec2 vTexCoord;
void main() {
    // Fullscreen triangle strip from vertex ID: 0..3
    const vec2 positions[4] = vec2[4](
        vec2(-1.0, -1.0),
        vec2( 1.0, -1.0),
        vec2(-1.0,  1.0),
        vec2( 1.0,  1.0)
    );
    const vec2 uvs[4] = vec2[4](
        vec2(0.0, 1.0),
        vec2(1.0, 1.0),
        vec2(0.0, 0.0),
        vec2(1.0, 0.0)
    );
    gl_Position = vec4(positions[gl_VertexID], 0.0, 1.0);
    vTexCoord = uvs[gl_VertexID];
}
)";

constexpr const char* kFragmentShaderSrc = R"(#version 300 es
precision mediump float;
in vec2 vTexCoord;
uniform sampler2D uTexture;
out vec4 fragColor;
void main() {
    fragColor = vec4(texture(uTexture, vTexCoord).rgb, 1.0);
}
)";

GLuint compileShader(GLenum type, const char* src) {
    GLuint shader = glCreateShader(type);
    if (shader == 0) {
        return 0;
    }
    glShaderSource(shader, 1, &src, nullptr);
    glCompileShader(shader);
    GLint compiled = GL_FALSE;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (compiled != GL_TRUE) {
        char log[256] = {0};
        glGetShaderInfoLog(shader, sizeof(log), nullptr, log);
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Shader compile error: %s", log);
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

}  // namespace

GlVideoRenderer::GlVideoRenderer(VideoFrameBuffer& frameBuffer)
    : frameBuffer_(frameBuffer) {}

GlVideoRenderer::~GlVideoRenderer() {
    stop();
}

void GlVideoRenderer::start() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (running_) {
        return;
    }
    stopRequested_ = false;
    running_ = true;
    thread_ = std::thread(&GlVideoRenderer::renderLoop, this);
}

void GlVideoRenderer::stop() {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!running_) {
            return;
        }
        stopRequested_ = true;
        if (pendingWindow_) {
            ANativeWindow_release(pendingWindow_);
            pendingWindow_ = nullptr;
        }
        surfaceDirty_ = true;
    }
    cv_.notify_all();
    if (thread_.joinable()) {
        thread_.join();
    }
    std::lock_guard<std::mutex> lock(mutex_);
    running_ = false;
}

void GlVideoRenderer::attachSurface(ANativeWindow* window, int32_t width, int32_t height) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (pendingWindow_) {
        ANativeWindow_release(pendingWindow_);
        pendingWindow_ = nullptr;
    }
    if (window) {
        ANativeWindow_acquire(window);
        pendingWindow_ = window;
    }
    surfaceWidth_ = std::max(0, width);
    surfaceHeight_ = std::max(0, height);
    surfaceDirty_ = true;
    redrawRequested_ = true;
    cv_.notify_all();
}

void GlVideoRenderer::resizeSurface(int32_t width, int32_t height) {
    std::lock_guard<std::mutex> lock(mutex_);
    surfaceWidth_ = std::max(0, width);
    surfaceHeight_ = std::max(0, height);
    redrawRequested_ = true;
    cv_.notify_all();
}

void GlVideoRenderer::detachSurface() {
    std::unique_lock<std::mutex> lock(mutex_);
    if (pendingWindow_) {
        ANativeWindow_release(pendingWindow_);
        pendingWindow_ = nullptr;
    }
    surfaceDirty_ = true;
    cv_.notify_all();
    // Wait until render thread drops the active window so Android can safely destroy the Surface.
    if (running_) {
        cv_.wait(lock, [this]() {
            return !surfaceDirty_ || stopRequested_;
        });
    }
}

void GlVideoRenderer::setScalingMode(VideoScalingMode mode) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (scalingMode_ != mode) {
        scalingMode_ = mode;
        redrawRequested_ = true;
        cv_.notify_all();
    }
}

void GlVideoRenderer::notifyNewFrame() {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        redrawRequested_ = true;
    }
    cv_.notify_one();
}

void GlVideoRenderer::renderLoop() {
    while (true) {
        bool needSurfaceSwap = false;
        ANativeWindow* nextWindow = nullptr;
        bool shouldDraw = false;

        {
            std::unique_lock<std::mutex> lock(mutex_);
            cv_.wait(lock, [this]() {
                return stopRequested_ || surfaceDirty_ || redrawRequested_;
            });

            if (stopRequested_) {
                break;
            }
            if (surfaceDirty_) {
                needSurfaceSwap = true;
                nextWindow = pendingWindow_;
                pendingWindow_ = nullptr;
                surfaceDirty_ = false;
            }
            if (redrawRequested_) {
                shouldDraw = true;
                redrawRequested_ = false;
            }
        }

        if (needSurfaceSwap) {
            destroyEgl();
            if (activeWindow_) {
                ANativeWindow_release(activeWindow_);
                activeWindow_ = nullptr;
            }
            activeWindow_ = nextWindow;
            if (activeWindow_) {
                if (!initEgl(activeWindow_) || !initGlProgram()) {
                    destroyEgl();
                    ANativeWindow_release(activeWindow_);
                    activeWindow_ = nullptr;
                }
            }
            // Notify detachSurface() waiters that the previous window has been released.
            cv_.notify_all();
        }

        if (shouldDraw && eglSurface_ != EGL_NO_SURFACE && eglContext_ != EGL_NO_CONTEXT) {
            renderCurrentFrame();
        }
    }

    destroyEgl();
    if (activeWindow_) {
        ANativeWindow_release(activeWindow_);
        activeWindow_ = nullptr;
    }
    cv_.notify_all();
}

bool GlVideoRenderer::initEgl(ANativeWindow* window) {
    eglDisplay_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (eglDisplay_ == EGL_NO_DISPLAY) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "eglGetDisplay failed");
        return false;
    }
    if (!eglInitialize(eglDisplay_, nullptr, nullptr)) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "eglInitialize failed");
        return false;
    }

    const EGLint configAttribs[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8,
        EGL_ALPHA_SIZE, 8,
        EGL_NONE
    };
    EGLint numConfigs = 0;
    if (!eglChooseConfig(eglDisplay_, configAttribs, &eglConfig_, 1, &numConfigs) || numConfigs < 1) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "eglChooseConfig failed");
        return false;
    }

    const EGLint ctxAttribs[] = {
        EGL_CONTEXT_CLIENT_VERSION, 3,
        EGL_NONE
    };
    eglContext_ = eglCreateContext(eglDisplay_, eglConfig_, EGL_NO_CONTEXT, ctxAttribs);
    if (eglContext_ == EGL_NO_CONTEXT) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "eglCreateContext failed");
        return false;
    }

    eglSurface_ = eglCreateWindowSurface(eglDisplay_, eglConfig_, window, nullptr);
    if (eglSurface_ == EGL_NO_SURFACE) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "eglCreateWindowSurface failed");
        return false;
    }

    if (!eglMakeCurrent(eglDisplay_, eglSurface_, eglSurface_, eglContext_)) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "eglMakeCurrent failed");
        return false;
    }

    // Use vsync-less swap since frame pacing is driven accurately by the emulator worker thread.
    eglSwapInterval(eglDisplay_, 0);
    return true;
}

bool GlVideoRenderer::initGlProgram() {
    GLuint vs = compileShader(GL_VERTEX_SHADER, kVertexShaderSrc);
    GLuint fs = compileShader(GL_FRAGMENT_SHADER, kFragmentShaderSrc);
    if (vs == 0 || fs == 0) {
        if (vs) glDeleteShader(vs);
        if (fs) glDeleteShader(fs);
        return false;
    }
    program_ = glCreateProgram();
    glAttachShader(program_, vs);
    glAttachShader(program_, fs);
    glLinkProgram(program_);
    glDeleteShader(vs);
    glDeleteShader(fs);

    GLint linked = GL_FALSE;
    glGetProgramiv(program_, GL_LINK_STATUS, &linked);
    if (linked != GL_TRUE) {
        glDeleteProgram(program_);
        program_ = 0;
        return false;
    }

    glGenVertexArrays(1, &vao_);
    glGenTextures(1, &texture_);
    glBindTexture(GL_TEXTURE_2D, texture_);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    texWidth_ = 0;
    texHeight_ = 0;
    return true;
}

void GlVideoRenderer::destroyEgl() {
    if (eglDisplay_ != EGL_NO_DISPLAY) {
        if (eglContext_ != EGL_NO_CONTEXT && eglSurface_ != EGL_NO_SURFACE) {
            eglMakeCurrent(eglDisplay_, eglSurface_, eglSurface_, eglContext_);
            if (texture_) {
                glDeleteTextures(1, &texture_);
                texture_ = 0;
            }
            if (vao_) {
                glDeleteVertexArrays(1, &vao_);
                vao_ = 0;
            }
            if (program_) {
                glDeleteProgram(program_);
                program_ = 0;
            }
        }
        eglMakeCurrent(eglDisplay_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (eglSurface_ != EGL_NO_SURFACE) {
            eglDestroySurface(eglDisplay_, eglSurface_);
            eglSurface_ = EGL_NO_SURFACE;
        }
        if (eglContext_ != EGL_NO_CONTEXT) {
            eglDestroyContext(eglDisplay_, eglContext_);
            eglContext_ = EGL_NO_CONTEXT;
        }
        eglTerminate(eglDisplay_);
        eglDisplay_ = EGL_NO_DISPLAY;
    }
    texWidth_ = 0;
    texHeight_ = 0;
}

void GlVideoRenderer::renderCurrentFrame() {
    const bool gotNew = frameBuffer_.consumeLatest(lastFrameIndex_, localPixels_, localMeta_);
    if (gotNew) {
        lastFrameIndex_ = localMeta_.frameIndex;
    }

    EGLint surfW = 0;
    EGLint surfH = 0;
    eglQuerySurface(eglDisplay_, eglSurface_, EGL_WIDTH, &surfW);
    eglQuerySurface(eglDisplay_, eglSurface_, EGL_HEIGHT, &surfH);
    if (surfW <= 0 || surfH <= 0) {
        return;
    }

    VideoScalingMode mode;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        mode = scalingMode_;
    }

    glViewport(0, 0, surfW, surfH);
    glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);

    if (localPixels_.empty() || localMeta_.width == 0 || localMeta_.height == 0) {
        eglSwapBuffers(eglDisplay_, eglSurface_);
        return;
    }

    glBindTexture(GL_TEXTURE_2D, texture_);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 2);
    if (texWidth_ != localMeta_.width || texHeight_ != localMeta_.height) {
        glTexImage2D(
            GL_TEXTURE_2D,
            0,
            GL_RGB565,
            static_cast<GLsizei>(localMeta_.width),
            static_cast<GLsizei>(localMeta_.height),
            0,
            GL_RGB,
            GL_UNSIGNED_SHORT_5_6_5,
            localPixels_.data()
        );
        texWidth_ = localMeta_.width;
        texHeight_ = localMeta_.height;
    } else if (gotNew) {
        glTexSubImage2D(
            GL_TEXTURE_2D,
            0,
            0,
            0,
            static_cast<GLsizei>(localMeta_.width),
            static_cast<GLsizei>(localMeta_.height),
            GL_RGB,
            GL_UNSIGNED_SHORT_5_6_5,
            localPixels_.data()
        );
    }

    // Compute aspect-preserving or integer-scaled viewport without stretching pixels.
    int32_t vpW = surfW;
    int32_t vpH = surfH;
    const float targetAspect = localMeta_.aspectRatio > 0.0f
        ? localMeta_.aspectRatio
        : static_cast<float>(localMeta_.width) / static_cast<float>(localMeta_.height);

    if (mode == VideoScalingMode::IntegerScale) {
        const int32_t scaleX = surfW / static_cast<int32_t>(localMeta_.width);
        const int32_t scaleY = surfH / static_cast<int32_t>(localMeta_.height);
        const int32_t scale = std::min(scaleX, scaleY);
        if (scale >= 1) {
            vpW = static_cast<int32_t>(localMeta_.width) * scale;
            vpH = static_cast<int32_t>(localMeta_.height) * scale;
        } else {
            // Fall back to aspect fit if screen is smaller than 1x integer resolution.
            const float surfAspect = static_cast<float>(surfW) / static_cast<float>(surfH);
            if (surfAspect > targetAspect) {
                vpH = surfH;
                vpW = static_cast<int32_t>(std::lrint(static_cast<float>(surfH) * targetAspect));
            } else {
                vpW = surfW;
                vpH = static_cast<int32_t>(std::lrint(static_cast<float>(surfW) / targetAspect));
            }
        }
    } else {
        const float surfAspect = static_cast<float>(surfW) / static_cast<float>(surfH);
        if (surfAspect > targetAspect) {
            vpH = surfH;
            vpW = static_cast<int32_t>(std::lrint(static_cast<float>(surfH) * targetAspect));
        } else {
            vpW = surfW;
            vpH = static_cast<int32_t>(std::lrint(static_cast<float>(surfW) / targetAspect));
        }
    }

    vpW = std::clamp(vpW, 1, surfW);
    vpH = std::clamp(vpH, 1, surfH);
    const int32_t vpX = (surfW - vpW) / 2;
    const int32_t vpY = (surfH - vpH) / 2;

    glViewport(vpX, vpY, vpW, vpH);
    glUseProgram(program_);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, texture_);
    glBindVertexArray(vao_);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glBindVertexArray(0);

    eglSwapBuffers(eglDisplay_, eglSurface_);
}

}  // namespace gba
