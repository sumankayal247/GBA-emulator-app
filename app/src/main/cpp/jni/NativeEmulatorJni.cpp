#include <android/log.h>
#include <android/native_window_jni.h>
#include <jni.h>

#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "../audio/AAudioOutput.h"
#include "../core/LibretroFrontend.h"
#include "../video/GlVideoRenderer.h"

namespace {

constexpr const char* kJniTag = "GbaEmulatorJni";

// Single process-wide native runtime owned by EmulatorSession (ADR-0003).
struct NativeRuntime {
    gba::LibretroFrontend frontend;
    gba::GlVideoRenderer renderer{frontend.videoBuffer()};
    gba::AAudioOutput audio{frontend.audioBuffer()};
    std::mutex lifecycleMutex;
    bool started{false};

    NativeRuntime() {
        frontend.setFrameReadyCallback([this]() {
            renderer.notifyNewFrame();
        });
        frontend.setLogCallback([](int level, const std::string& msg) {
            int prio = ANDROID_LOG_INFO;
            if (level >= RETRO_LOG_ERROR) prio = ANDROID_LOG_ERROR;
            else if (level == RETRO_LOG_WARN) prio = ANDROID_LOG_WARN;
            else if (level == RETRO_LOG_DEBUG) prio = ANDROID_LOG_DEBUG;
            __android_log_print(prio, "mGBA-Core", "%s", msg.c_str());
        });
    }

    ~NativeRuntime() {
        audio.stop();
        frontend.shutdown(nullptr, 3000);
        renderer.stop();
    }
};

std::mutex gRuntimeMutex;
std::unique_ptr<NativeRuntime> gRuntime;

NativeRuntime& getOrCreateRuntime() {
    std::lock_guard<std::mutex> lock(gRuntimeMutex);
    if (!gRuntime) {
        gRuntime = std::make_unique<NativeRuntime>();
        gRuntime->renderer.start();
        gRuntime->started = true;
    }
    return *gRuntime;
}

std::string jstringToStd(JNIEnv* env, jstring jstr) {
    if (!env || !jstr) {
        return "";
    }
    const char* chars = env->GetStringUTFChars(jstr, nullptr);
    if (!chars) {
        return "";
    }
    std::string out(chars);
    env->ReleaseStringUTFChars(jstr, chars);
    return out;
}

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_io_github_sumankayal247_gbaemulator_emulator_NativeEmulatorBridge_nativeLoadRom(
    JNIEnv* env,
    jobject /*thiz*/,
    jstring jCorePath,
    jstring jRomPath,
    jstring jSystemDir,
    jstring jSaveDir,
    jbyteArray jInitialSram,
    jboolean jStartPaused
) {
    if (!env || !jCorePath || !jRomPath) {
        return env ? env->NewStringUTF("Invalid null arguments to nativeLoadRom.") : nullptr;
    }

    NativeRuntime& rt = getOrCreateRuntime();
    std::lock_guard<std::mutex> lock(rt.lifecycleMutex);

    // Ensure clean state before loading a new ROM.
    if (rt.frontend.state() != gba::EmulatorState::Unloaded) {
        rt.audio.stop();
        rt.frontend.shutdown(nullptr, 3000);
    }

    // Start AAudio first so we learn the device's actual output sample rate (typically 48 kHz).
    const bool audioStarted = rt.audio.start(48000);
    const int32_t outputRate = audioStarted ? rt.audio.actualSampleRate() : 48000;
    if (jStartPaused == JNI_TRUE) {
        rt.audio.pause();
    }

    gba::LoadConfig cfg;
    cfg.coreLibraryPath = jstringToStd(env, jCorePath);
    cfg.romFilePath = jstringToStd(env, jRomPath);
    cfg.systemDirectory = jstringToStd(env, jSystemDir);
    cfg.saveDirectory = jstringToStd(env, jSaveDir);
    cfg.outputSampleRate = static_cast<double>(outputRate);
    cfg.startPaused = (jStartPaused == JNI_TRUE);

    if (jInitialSram != nullptr) {
        const jsize len = env->GetArrayLength(jInitialSram);
        if (len > 0 && len <= 512 * 1024) {
            cfg.initialSram.resize(static_cast<size_t>(len));
            env->GetByteArrayRegion(
                jInitialSram,
                0,
                len,
                reinterpret_cast<jbyte*>(cfg.initialSram.data())
            );
        }
    }

    std::string err;
    if (!rt.frontend.loadRom(cfg, err)) {
        rt.audio.stop();
        __android_log_print(ANDROID_LOG_ERROR, kJniTag, "nativeLoadRom failed: %s", err.c_str());
        return env->NewStringUTF(err.c_str());
    }

    return nullptr;  // null == success
}

JNIEXPORT jboolean JNICALL
Java_io_github_sumankayal247_gbaemulator_emulator_NativeEmulatorBridge_nativePause(
    JNIEnv* /*env*/,
    jobject /*thiz*/
) {
    NativeRuntime& rt = getOrCreateRuntime();
    std::lock_guard<std::mutex> lock(rt.lifecycleMutex);
    rt.audio.pause();
    return rt.frontend.pause() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_io_github_sumankayal247_gbaemulator_emulator_NativeEmulatorBridge_nativeResume(
    JNIEnv* /*env*/,
    jobject /*thiz*/
) {
    NativeRuntime& rt = getOrCreateRuntime();
    std::lock_guard<std::mutex> lock(rt.lifecycleMutex);
    const bool ok = rt.frontend.resume();
    if (ok) {
        rt.audio.resume();
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_io_github_sumankayal247_gbaemulator_emulator_NativeEmulatorBridge_nativeReset(
    JNIEnv* /*env*/,
    jobject /*thiz*/
) {
    NativeRuntime& rt = getOrCreateRuntime();
    std::lock_guard<std::mutex> lock(rt.lifecycleMutex);
    return rt.frontend.reset() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jbyteArray JNICALL
Java_io_github_sumankayal247_gbaemulator_emulator_NativeEmulatorBridge_nativeShutdownAndExtractSram(
    JNIEnv* env,
    jobject /*thiz*/
) {
    if (!env) return nullptr;
    NativeRuntime& rt = getOrCreateRuntime();
    std::lock_guard<std::mutex> lock(rt.lifecycleMutex);

    rt.audio.stop();
    std::vector<uint8_t> finalSram;
    rt.frontend.shutdown(&finalSram, 4000);

    if (finalSram.empty()) {
        return nullptr;
    }
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(finalSram.size()));
    if (!arr) return nullptr;
    env->SetByteArrayRegion(
        arr,
        0,
        static_cast<jsize>(finalSram.size()),
        reinterpret_cast<const jbyte*>(finalSram.data())
    );
    return arr;
}

JNIEXPORT jbyteArray JNICALL
Java_io_github_sumankayal247_gbaemulator_emulator_NativeEmulatorBridge_nativeExtractSram(
    JNIEnv* env,
    jobject /*thiz*/
) {
    if (!env) return nullptr;
    NativeRuntime& rt = getOrCreateRuntime();
    std::vector<uint8_t> sram;
    if (!rt.frontend.extractSram(sram) || sram.empty()) {
        return nullptr;
    }
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(sram.size()));
    if (!arr) return nullptr;
    env->SetByteArrayRegion(
        arr,
        0,
        static_cast<jsize>(sram.size()),
        reinterpret_cast<const jbyte*>(sram.data())
    );
    return arr;
}

JNIEXPORT void JNICALL
Java_io_github_sumankayal247_gbaemulator_emulator_NativeEmulatorBridge_nativeAttachSurface(
    JNIEnv* env,
    jobject /*thiz*/,
    jobject jSurface,
    jint width,
    jint height
) {
    if (!env || !jSurface) {
        return;
    }
    ANativeWindow* window = ANativeWindow_fromSurface(env, jSurface);
    if (!window) {
        return;
    }
    NativeRuntime& rt = getOrCreateRuntime();
    rt.renderer.attachSurface(window, width, height);
    // Renderer acquired its own reference; release our local reference.
    ANativeWindow_release(window);
}

JNIEXPORT void JNICALL
Java_io_github_sumankayal247_gbaemulator_emulator_NativeEmulatorBridge_nativeResizeSurface(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jint width,
    jint height
) {
    NativeRuntime& rt = getOrCreateRuntime();
    rt.renderer.resizeSurface(width, height);
}

JNIEXPORT void JNICALL
Java_io_github_sumankayal247_gbaemulator_emulator_NativeEmulatorBridge_nativeDetachSurface(
    JNIEnv* /*env*/,
    jobject /*thiz*/
) {
    NativeRuntime& rt = getOrCreateRuntime();
    rt.renderer.detachSurface();
}

JNIEXPORT void JNICALL
Java_io_github_sumankayal247_gbaemulator_emulator_NativeEmulatorBridge_nativeSetScalingMode(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jint mode
) {
    NativeRuntime& rt = getOrCreateRuntime();
    const auto scaling = (mode == 1)
        ? gba::VideoScalingMode::IntegerScale
        : gba::VideoScalingMode::AspectFit;
    rt.renderer.setScalingMode(scaling);
}

JNIEXPORT void JNICALL
Java_io_github_sumankayal247_gbaemulator_emulator_NativeEmulatorBridge_nativeSetTouchInputMask(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jint mask
) {
    NativeRuntime& rt = getOrCreateRuntime();
    rt.frontend.setTouchInputMask(static_cast<uint16_t>(mask & 0xFFFF));
}

JNIEXPORT void JNICALL
Java_io_github_sumankayal247_gbaemulator_emulator_NativeEmulatorBridge_nativeReleaseAllInputs(
    JNIEnv* /*env*/,
    jobject /*thiz*/
) {
    NativeRuntime& rt = getOrCreateRuntime();
    rt.frontend.releaseAllInputs();
}

JNIEXPORT void JNICALL
Java_io_github_sumankayal247_gbaemulator_emulator_NativeEmulatorBridge_nativeSetAudioMuted(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jboolean muted
) {
    NativeRuntime& rt = getOrCreateRuntime();
    rt.frontend.setAudioMuted(muted == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_io_github_sumankayal247_gbaemulator_emulator_NativeEmulatorBridge_nativeSetAudioVolume(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jfloat volume
) {
    NativeRuntime& rt = getOrCreateRuntime();
    rt.frontend.setAudioVolume(volume);
}

JNIEXPORT void JNICALL
Java_io_github_sumankayal247_gbaemulator_emulator_NativeEmulatorBridge_nativeSetFastForward(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jboolean enabled
) {
    NativeRuntime& rt = getOrCreateRuntime();
    rt.frontend.setFastForward(enabled == JNI_TRUE);
}

JNIEXPORT jint JNICALL
Java_io_github_sumankayal247_gbaemulator_emulator_NativeEmulatorBridge_nativeGetState(
    JNIEnv* /*env*/,
    jobject /*thiz*/
) {
    NativeRuntime& rt = getOrCreateRuntime();
    return static_cast<jint>(rt.frontend.state());
}

JNIEXPORT jstring JNICALL
Java_io_github_sumankayal247_gbaemulator_emulator_NativeEmulatorBridge_nativeGetCoreVersion(
    JNIEnv* env,
    jobject /*thiz*/
) {
    if (!env) return nullptr;
    NativeRuntime& rt = getOrCreateRuntime();
    const auto info = rt.frontend.coreInfo();
    const std::string version = info.libraryName.empty()
        ? "mGBA (unloaded)"
        : (info.libraryName + " " + info.libraryVersion);
    return env->NewStringUTF(version.c_str());
}

}  // extern "C"
