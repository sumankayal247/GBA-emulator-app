package io.github.sumankayal247.gbaemulator.emulator

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import android.view.Surface
import androidx.core.content.ContextCompat
import io.github.sumankayal247.gbaemulator.data.preferences.PreferencesRepository
import io.github.sumankayal247.gbaemulator.data.saves.SramSaveRepository
import io.github.sumankayal247.gbaemulator.domain.model.AppResult
import io.github.sumankayal247.gbaemulator.domain.model.EmulatorError
import io.github.sumankayal247.gbaemulator.domain.model.EmulatorPreferences
import io.github.sumankayal247.gbaemulator.domain.model.EmulatorState
import io.github.sumankayal247.gbaemulator.domain.model.RomRecord
import io.github.sumankayal247.gbaemulator.domain.model.ScalingMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class EmulatorSessionSnapshot(
    val state: EmulatorState = EmulatorState.UNLOADED,
    val activeRom: RomRecord? = null,
    val coreVersion: String = "mGBA (unloaded)",
    val fastForward: Boolean = false,
    val muted: Boolean = false,
    val audioVolume: Float = 1.0f,
    val scalingMode: ScalingMode = ScalingMode.ASPECT_FIT,
    val errorMessage: String? = null,
    val statusNotice: String? = null,
)

/**
 * Single-owner emulator session manager scoped to the Application lifecycle (ADR-0003).
 *
 * Guarantees:
 * - Only one native core instance runs at any time.
 * - Survives Activity configuration changes and SurfaceView recreation.
 * - Serializes all lifecycle & save operations off the main thread.
 * - Handles Android AudioFocus, headphone disconnects (`ACTION_AUDIO_BECOMING_NOISY`),
 *   input release on pause, and atomic periodic + lifecycle SRAM flushing.
 */
class EmulatorSession(
    private val context: Context,
    private val sramSaveRepository: SramSaveRepository,
    private val preferencesRepository: PreferencesRepository,
) {
    private val logTag = "EmulatorSession"
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleMutex = Mutex()
    private val bridge: NativeEmulatorBridge by lazy { NativeEmulatorBridge() }
    private val audioManager: AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val _snapshot = MutableStateFlow(EmulatorSessionSnapshot())
    val snapshot: StateFlow<EmulatorSessionSnapshot> = _snapshot.asStateFlow()

    private var periodicSaveJob: Job? = null
    private var noisyReceiverRegistered = false
    private var audioFocusRequest: AudioFocusRequest? = null
    private var pausedByAudioFocus = false
    private var userMuted = false
    private var userVolume = 1.0f

    private val noisyAudioReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                Log.i(logTag, "Audio output becoming noisy; pausing session safely.")
                sessionScope.launch { pauseSession(reason = "Headphones disconnected — game paused.") }
            }
        }
    }

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                pausedByAudioFocus = false
                sessionScope.launch { pauseSession(reason = null) }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (_snapshot.value.state == EmulatorState.RUNNING) {
                    pausedByAudioFocus = true
                    sessionScope.launch { pauseSession(reason = null) }
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                runCatching { bridge.nativeSetAudioVolume(userVolume * 0.25f) }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                runCatching {
                    bridge.nativeSetAudioMuted(userMuted)
                    bridge.nativeSetAudioVolume(userVolume)
                }
                if (pausedByAudioFocus) {
                    pausedByAudioFocus = false
                    sessionScope.launch { resumeSession() }
                }
            }
        }
    }

    suspend fun startSession(
        record: RomRecord,
        stagedRomFile: File,
        preferences: EmulatorPreferences,
    ): AppResult<Unit> = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            // Cleanly flush and shut down any previously active ROM before starting another.
            if (_snapshot.value.state != EmulatorState.UNLOADED) {
                shutdownInternalLocked()
            }

            userMuted = preferences.muted
            userVolume = preferences.audioVolume

            _snapshot.update {
                it.copy(
                    state = EmulatorState.LOADING,
                    activeRom = record,
                    muted = preferences.muted,
                    audioVolume = preferences.audioVolume,
                    scalingMode = preferences.scalingMode,
                    fastForward = false,
                    errorMessage = null,
                    statusNotice = null,
                )
            }

            // Load persisted battery SRAM if present (with automatic .bak corruption recovery).
            var initialSram: ByteArray? = null
            var recoveryNotice: String? = null
            when (val loadRes = sramSaveRepository.readSram(record.system, record.sha256)) {
                is AppResult.Success -> {
                    initialSram = loadRes.value.bytes
                    recoveryNotice = loadRes.value.noticeMessage
                }
                is AppResult.Failure -> {
                    recoveryNotice = loadRes.error.userMessage
                }
            }

            val corePath = resolveCoreLibraryPath()
            val systemDir = File(context.filesDir, "bios").apply { mkdirs() }
            val saveDir = sramSaveRepository.romSaveDir(record.system, record.sha256).apply { mkdirs() }

            val loadError = try {
                bridge.nativeSetScalingMode(preferences.scalingMode.nativeValue)
                bridge.nativeSetAudioMuted(preferences.muted)
                bridge.nativeSetAudioVolume(preferences.audioVolume)
                bridge.nativeSetFastForward(false)
                bridge.nativeLoadRom(
                    corePath = corePath,
                    romPath = stagedRomFile.absolutePath,
                    systemDir = systemDir.absolutePath,
                    saveDir = saveDir.absolutePath,
                    initialSram = initialSram,
                    startPaused = false,
                )
            } catch (t: Throwable) {
                "Native core initialization failed: ${t.message ?: t::class.java.simpleName}"
            }

            if (loadError != null) {
                _snapshot.update {
                    it.copy(
                        state = EmulatorState.ERROR,
                        errorMessage = loadError,
                    )
                }
                return@withLock AppResult.Failure(EmulatorError.CoreInitFailure(loadError))
            }

            val coreVersion = runCatching { bridge.nativeGetCoreVersion() }.getOrDefault("mGBA")
            val updatedRecord = record.copy(
                lastPlayedEpochMs = System.currentTimeMillis(),
                hasBatterySave = initialSram != null || sramSaveRepository.hasSave(record),
            )
            preferencesRepository.upsertRecentGame(updatedRecord)

            requestAudioFocus()
            registerNoisyReceiver()
            startPeriodicSramFlushLocked()

            _snapshot.update {
                it.copy(
                    state = EmulatorState.RUNNING,
                    activeRom = updatedRecord,
                    coreVersion = coreVersion,
                    errorMessage = null,
                    statusNotice = recoveryNotice,
                )
            }

            AppResult.Success(Unit)
        }
    }

    suspend fun pauseSession(reason: String? = null) = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            val cur = _snapshot.value.state
            if (cur != EmulatorState.RUNNING) return@withLock
            bridge.nativeReleaseAllInputs()
            if (bridge.nativePause()) {
                _snapshot.update {
                    it.copy(
                        state = EmulatorState.PAUSED,
                        statusNotice = reason ?: it.statusNotice,
                    )
                }
                flushSramInternalLocked()
            }
        }
    }

    suspend fun resumeSession() = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            val cur = _snapshot.value.state
            if (cur != EmulatorState.PAUSED) return@withLock
            requestAudioFocus()
            if (bridge.nativeResume()) {
                _snapshot.update {
                    it.copy(
                        state = EmulatorState.RUNNING,
                        statusNotice = null,
                    )
                }
            }
        }
    }

    suspend fun restartGame(): AppResult<Unit> = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            val cur = _snapshot.value.state
            if (cur != EmulatorState.RUNNING && cur != EmulatorState.PAUSED) {
                return@withLock AppResult.Failure(EmulatorError.CoreInitFailure("No active game to restart."))
            }
            bridge.nativeReleaseAllInputs()
            flushSramInternalLocked()
            val ok = bridge.nativeReset()
            if (!ok) {
                return@withLock AppResult.Failure(EmulatorError.CoreInitFailure("Failed to reset emulator core."))
            }
            if (cur == EmulatorState.PAUSED) {
                requestAudioFocus()
                bridge.nativeResume()
                _snapshot.update { it.copy(state = EmulatorState.RUNNING) }
            }
            AppResult.Success(Unit)
        }
    }

    suspend fun flushSramNow(): AppResult<Boolean> = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            flushSramInternalLocked()
        }
    }

    suspend fun quitSession() = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            shutdownInternalLocked()
        }
    }

    fun onAppBackgrounded() {
        sessionScope.launch {
            pauseSession()
        }
    }

    // Surface callbacks — safe to invoke across rotations without touching core lifecycle.
    fun attachSurface(surface: Surface, width: Int, height: Int) {
        runCatching { bridge.nativeAttachSurface(surface, width, height) }
    }

    fun resizeSurface(width: Int, height: Int) {
        runCatching { bridge.nativeResizeSurface(width, height) }
    }

    fun detachSurface() {
        runCatching { bridge.nativeDetachSurface() }
    }

    // Lock-free real-time input / AV controls.
    fun setTouchInputMask(mask: Int) {
        runCatching { bridge.nativeSetTouchInputMask(mask) }
    }

    fun releaseAllInputs() {
        runCatching { bridge.nativeReleaseAllInputs() }
    }

    fun setMuted(muted: Boolean) {
        userMuted = muted
        runCatching { bridge.nativeSetAudioMuted(muted) }
        _snapshot.update { it.copy(muted = muted) }
        sessionScope.launch { preferencesRepository.updateMuted(muted) }
    }

    fun setAudioVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        userVolume = clamped
        runCatching { bridge.nativeSetAudioVolume(clamped) }
        _snapshot.update { it.copy(audioVolume = clamped) }
        sessionScope.launch { preferencesRepository.updateAudioVolume(clamped) }
    }

    fun setFastForward(enabled: Boolean) {
        runCatching { bridge.nativeSetFastForward(enabled) }
        _snapshot.update { it.copy(fastForward = enabled) }
    }

    fun setScalingMode(mode: ScalingMode) {
        runCatching { bridge.nativeSetScalingMode(mode.nativeValue) }
        _snapshot.update { it.copy(scalingMode = mode) }
        sessionScope.launch { preferencesRepository.updateScalingMode(mode) }
    }

    fun clearStatusNotice() {
        _snapshot.update { it.copy(statusNotice = null) }
    }

    private suspend fun shutdownInternalLocked() {
        periodicSaveJob?.cancel()
        periodicSaveJob = null
        pausedByAudioFocus = false
        unregisterNoisyReceiver()
        abandonAudioFocus()

        val activeRom = _snapshot.value.activeRom
        _snapshot.update { it.copy(state = EmulatorState.STOPPING) }

        val finalSram = runCatching {
            bridge.nativeReleaseAllInputs()
            bridge.nativeShutdownAndExtractSram()
        }.getOrNull()

        if (activeRom != null && finalSram != null && finalSram.isNotEmpty() && !isBlankSram(finalSram)) {
            val res = sramSaveRepository.writeSram(activeRom.system, activeRom.sha256, finalSram)
            if (res is AppResult.Success) {
                preferencesRepository.markBatterySavePresent(activeRom.romId, true)
            }
        }

        _snapshot.update {
            EmulatorSessionSnapshot(
                state = EmulatorState.UNLOADED,
                muted = userMuted,
                audioVolume = userVolume,
                scalingMode = it.scalingMode,
            )
        }
    }

    private suspend fun flushSramInternalLocked(): AppResult<Boolean> {
        val rom = _snapshot.value.activeRom ?: return AppResult.Success(false)
        val state = _snapshot.value.state
        if (state != EmulatorState.RUNNING && state != EmulatorState.PAUSED) {
            return AppResult.Success(false)
        }
        val bytes = runCatching { bridge.nativeExtractSram() }.getOrNull()
        if (bytes == null || bytes.isEmpty() || isBlankSram(bytes)) {
            return AppResult.Success(false)
        }
        return when (val writeRes = sramSaveRepository.writeSram(rom.system, rom.sha256, bytes)) {
            is AppResult.Failure -> {
                _snapshot.update { it.copy(statusNotice = writeRes.error.userMessage) }
                writeRes
            }
            is AppResult.Success -> {
                preferencesRepository.markBatterySavePresent(rom.romId, true)
                _snapshot.update { snap ->
                    snap.copy(activeRom = snap.activeRom?.copy(hasBatterySave = true))
                }
                AppResult.Success(true)
            }
        }
    }

    private fun startPeriodicSramFlushLocked() {
        periodicSaveJob?.cancel()
        periodicSaveJob = sessionScope.launch {
            while (isActive) {
                delay(15_000L)
                if (_snapshot.value.state == EmulatorState.RUNNING) {
                    lifecycleMutex.withLock {
                        flushSramInternalLocked()
                    }
                }
            }
        }
    }

    private fun resolveCoreLibraryPath(): String {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val extracted = File(nativeDir, "libmgba_libretro.so")
        return if (extracted.exists()) extracted.absolutePath else "libmgba_libretro.so"
    }

    private fun requestAudioFocus() {
        val am = audioManager ?: return
        val req = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setOnAudioFocusChangeListener(audioFocusListener)
            .build()
            .also { audioFocusRequest = it }
        am.requestAudioFocus(req)
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
    }

    private fun registerNoisyReceiver() {
        if (!noisyReceiverRegistered) {
            ContextCompat.registerReceiver(
                context,
                noisyAudioReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            noisyReceiverRegistered = true
        }
    }

    private fun unregisterNoisyReceiver() {
        if (noisyReceiverRegistered) {
            runCatching { context.unregisterReceiver(noisyAudioReceiver) }
            noisyReceiverRegistered = false
        }
    }

    // mGBA initializes unformatted SRAM buffers with 0xFF (or 0x00). Avoid creating bogus save
    // files on disk for games that never wrote battery save data.
    private fun isBlankSram(bytes: ByteArray): Boolean {
        var allFF = true
        var all00 = true
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            if (v != 0xFF) allFF = false
            if (v != 0x00) all00 = false
            if (!allFF && !all00) return false
        }
        return true
    }
}
