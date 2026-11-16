package io.github.sumankayal247.gbaemulator.emulator

import android.view.Surface

/**
 * Low-level JNI bridge to `libgba_frontend.so` (which dynamically loads `libmgba_libretro.so`).
 * Never call these methods directly from Compose UI or the Android main thread — always route
 * through [EmulatorSession] (ADR-0003 / AGENTS.md).
 */
class NativeEmulatorBridge {

    init {
        System.loadLibrary("gba_frontend")
    }

    external fun nativeLoadRom(
        corePath: String,
        romPath: String,
        systemDir: String,
        saveDir: String,
        initialSram: ByteArray?,
        initialOptionKeys: Array<String>?,
        initialOptionValues: Array<String>?,
        startPaused: Boolean,
    ): String?

    external fun nativePause(): Boolean

    external fun nativeResume(): Boolean

    external fun nativeReset(): Boolean

    external fun nativeShutdownAndExtractSram(): ByteArray?

    external fun nativeExtractSram(): ByteArray?

    external fun nativeSerializeState(): ByteArray?

    external fun nativeUnserializeState(stateBytes: ByteArray): Boolean

    external fun nativeCaptureScreenshotArgb(): IntArray?

    external fun nativeGetCoreOptionsJson(): String?

    external fun nativeSetCoreOption(key: String, value: String): Boolean

    external fun nativeApplyCheats(enabledCodes: Array<String>): String?

    external fun nativeAttachSurface(surface: Surface, width: Int, height: Int)

    external fun nativeResizeSurface(width: Int, height: Int)

    external fun nativeDetachSurface()

    external fun nativeSetScalingMode(mode: Int)

    external fun nativeSetTouchInputMask(mask: Int)

    external fun nativeSetGamepadInputMask(mask: Int)

    external fun nativeReleaseAllInputs()

    external fun nativeSetAudioMuted(muted: Boolean)

    external fun nativeSetAudioVolume(volume: Float)

    external fun nativeSetFastForward(enabled: Boolean)

    external fun nativeGetState(): Int

    external fun nativeGetCoreVersion(): String
}
