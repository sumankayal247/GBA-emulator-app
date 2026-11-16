package io.github.sumankayal247.gbaemulator.emulator

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import android.view.Surface
import androidx.core.content.ContextCompat
import io.github.sumankayal247.gbaemulator.data.preferences.PreferencesRepository
import io.github.sumankayal247.gbaemulator.data.rom.LibraryRepository
import io.github.sumankayal247.gbaemulator.data.saves.SaveStateRepository
import io.github.sumankayal247.gbaemulator.data.saves.SramSaveRepository
import io.github.sumankayal247.gbaemulator.domain.model.AppResult
import io.github.sumankayal247.gbaemulator.domain.model.CoreOptionItem
import io.github.sumankayal247.gbaemulator.domain.model.CoreOptionsJsonParser
import io.github.sumankayal247.gbaemulator.domain.model.EmulatorError
import io.github.sumankayal247.gbaemulator.domain.model.EmulatorPreferences
import io.github.sumankayal247.gbaemulator.domain.model.EmulatorState
import io.github.sumankayal247.gbaemulator.domain.model.RomRecord
import io.github.sumankayal247.gbaemulator.domain.model.SaveStateSlot
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
import java.io.ByteArrayOutputStream
import java.io.File

data class EmulatorSessionSnapshot(
    val state: EmulatorState = EmulatorState.UNLOADED,
    val activeRom: RomRecord? = null,
    val coreVersion: String = "mGBA (unloaded)",
    val fastForward: Boolean = false,
    val muted: Boolean = false,
    val audioVolume: Float = 1.0f,
    val scalingMode: ScalingMode = ScalingMode.ASPECT_FIT,
    val coreOptions: List<CoreOptionItem> = emptyList(),
    val errorMessage: String? = null,
    val statusNotice: String? = null,
)

/**
 * Single-owner emulator session manager scoped to the Application lifecycle (ADR-0003 / ADR-0005).
 *
 * Guarantees:
 * - Only one native core instance runs at any time.
 * - Survives Activity configuration changes and SurfaceView recreation.
 * - Serializes all lifecycle, save-state, SRAM, cheat, and core-option operations off the main thread.
 * - Handles AudioFocus, headphone disconnects (`ACTION_AUDIO_BECOMING_NOISY`), input release on
 *   pause, play-time accumulation, and atomic periodic + lifecycle SRAM flushing.
 */
class EmulatorSession(
    private val context: Context,
    private val sramSaveRepository: SramSaveRepository,
    private val saveStateRepository: SaveStateRepository,
    private val libraryRepository: LibraryRepository,
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
    private var runningSegmentStartMs: Long = 0L

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

            // Load persisted global + per-ROM dynamic core option overrides before boot.
            val savedOptions = libraryRepository.getEffectiveCoreOptions(record.romId)
            val optionKeys = savedOptions.keys.toTypedArray().takeIf { it.isNotEmpty() }
            val optionValues = savedOptions.values.toTypedArray().takeIf { it.isNotEmpty() }

            val corePath = resolveCoreLibraryPath()
            val systemDir = File(context.filesDir, "system").apply { mkdirs() }
            val saveDir = sramSaveRepository.romSaveDir(record.system, record.sha256).apply { mkdirs() }

            val loadError = try {
                bridge.nativeLoadRom(
                    corePath = corePath,
                    romPath = stagedRomFile.absolutePath,
                    systemDir = systemDir.absolutePath,
                    saveDir = saveDir.absolutePath,
                    initialSram = initialSram,
                    initialOptionKeys = optionKeys,
                    initialOptionValues = optionValues,
                    startPaused = false,
                )
            } catch (t: Throwable) {
                Log.e(logTag, "Native library failure during loadRom", t)
                "Native emulator initialization failed: ${t.message ?: t.javaClass.simpleName}"
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

            bridge.nativeSetScalingMode(preferences.scalingMode.nativeValue)
            bridge.nativeSetAudioMuted(preferences.muted)
            bridge.nativeSetAudioVolume(preferences.audioVolume)
            bridge.nativeSetFastForward(false)

            // Apply any enabled cheats saved for this ROM.
            val enabledCheats = libraryRepository.getEnabledCheatCodes(record.romId)
            if (enabledCheats.isNotEmpty()) {
                val cheatErr = runCatching { bridge.nativeApplyCheats(enabledCheats.toTypedArray()) }.getOrNull()
                if (cheatErr != null) {
                    Log.w(logTag, "Some cheats failed to apply on launch: $cheatErr")
                }
            }

            requestAudioFocus()
            registerNoisyReceiver()
            startPeriodicSramFlushLocked()

            val coreVersion = runCatching { bridge.nativeGetCoreVersion() }.getOrDefault("mGBA")
            val dynamicOptions = CoreOptionsJsonParser.parse(
                runCatching { bridge.nativeGetCoreOptionsJson() }.getOrNull(),
            )
            runningSegmentStartMs = System.currentTimeMillis()

            _snapshot.update {
                it.copy(
                    state = EmulatorState.RUNNING,
                    activeRom = record,
                    coreVersion = coreVersion,
                    coreOptions = dynamicOptions,
                    errorMessage = null,
                    statusNotice = recoveryNotice,
                )
            }
            AppResult.Success(Unit)
        }
    }

    suspend fun pauseSession(reason: String? = null) = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            if (_snapshot.value.state != EmulatorState.RUNNING) return@withLock
            accumulatePlayTimeLocked()
            runCatching {
                bridge.nativeReleaseAllInputs()
                bridge.nativePause()
            }
            flushSramInternalLocked()
            _snapshot.update {
                it.copy(
                    state = EmulatorState.PAUSED,
                    statusNotice = reason ?: it.statusNotice,
                )
            }
        }
    }

    suspend fun resumeSession() = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            if (_snapshot.value.state != EmulatorState.PAUSED) return@withLock
            requestAudioFocus()
            val ok = runCatching { bridge.nativeResume() }.getOrDefault(false)
            if (ok) {
                runningSegmentStartMs = System.currentTimeMillis()
                _snapshot.update { it.copy(state = EmulatorState.RUNNING) }
            }
        }
    }

    suspend fun resetGame() = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            val st = _snapshot.value.state
            if (st != EmulatorState.RUNNING && st != EmulatorState.PAUSED) return@withLock
            flushSramInternalLocked()
            runCatching {
                bridge.nativeReleaseAllInputs()
                bridge.nativeReset()
            }
            _snapshot.update { it.copy(statusNotice = "Game restarted.") }
        }
    }

    suspend fun flushBatterySave(): AppResult<Boolean> = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            flushSramInternalLocked()
        }
    }

    suspend fun saveStateToSlot(slotIndex: Int): AppResult<SaveStateSlot> = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            val rom = _snapshot.value.activeRom
                ?: return@withLock AppResult.Failure(EmulatorError.SaveStateFailure("No active game."))
            val st = _snapshot.value.state
            if (st != EmulatorState.RUNNING && st != EmulatorState.PAUSED) {
                return@withLock AppResult.Failure(EmulatorError.SaveStateFailure("Game is not active."))
            }

            val bytes = runCatching { bridge.nativeSerializeState() }.getOrNull()
                ?: return@withLock AppResult.Failure(
                    EmulatorError.SaveStateFailure("Core failed to serialize state for slot $slotIndex."),
                )

            val thumbPng = captureThumbnailPng()
            val coreVersion = _snapshot.value.coreVersion
            val result = saveStateRepository.writeState(
                system = rom.system,
                romSha256 = rom.sha256,
                slotIndex = slotIndex,
                stateBytes = bytes,
                coreVersion = coreVersion,
                thumbnailPngBytes = thumbPng,
            )
            if (result is AppResult.Success) {
                _snapshot.update { it.copy(statusNotice = "Saved State $slotIndex.") }
            }
            result
        }
    }

    suspend fun loadStateFromSlot(slotIndex: Int): AppResult<String> = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            val rom = _snapshot.value.activeRom
                ?: return@withLock AppResult.Failure(EmulatorError.SaveStateFailure("No active game."))
            val st = _snapshot.value.state
            if (st != EmulatorState.RUNNING && st != EmulatorState.PAUSED) {
                return@withLock AppResult.Failure(EmulatorError.SaveStateFailure("Game is not active."))
            }

            val loaded = saveStateRepository.readState(rom.system, rom.sha256, slotIndex)
            if (loaded is AppResult.Failure) return@withLock loaded
            val stateData = (loaded as AppResult.Success).value

            val ok = runCatching { bridge.nativeUnserializeState(stateData.bytes) }.getOrDefault(false)
            if (!ok) {
                return@withLock AppResult.Failure(
                    EmulatorError.SaveStateFailure(
                        "Could not restore State $slotIndex — it may be incompatible with ${_snapshot.value.coreVersion}.",
                    ),
                )
            }

            // Re-apply enabled cheats after state load so cheat toggles stay consistent.
            val enabledCodes = libraryRepository.getEnabledCheatCodes(rom.romId)
            runCatching { bridge.nativeApplyCheats(enabledCodes.toTypedArray()) }

            val msg = buildString {
                append("Loaded State $slotIndex.")
                if (stateData.recoveredFromBackup) append(" (Recovered from backup.)")
            }
            _snapshot.update { it.copy(statusNotice = msg) }
            AppResult.Success(msg)
        }
    }

    suspend fun listSaveStateSlots(): List<SaveStateSlot> = withContext(Dispatchers.IO) {
        val rom = _snapshot.value.activeRom ?: return@withContext emptyList()
        saveStateRepository.listSlots(rom.system, rom.sha256)
    }

    suspend fun deleteSaveStateSlot(slotIndex: Int): Boolean = withContext(Dispatchers.IO) {
        val rom = _snapshot.value.activeRom ?: return@withContext false
        saveStateRepository.deleteSlot(rom.system, rom.sha256, slotIndex)
    }

    suspend fun setCoreOption(key: String, value: String, perGame: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            lifecycleMutex.withLock {
                val applied = runCatching { bridge.nativeSetCoreOption(key, value) }.getOrDefault(false)
                if (!applied) return@withLock false
                val rom = _snapshot.value.activeRom
                val scopeKey = if (perGame && rom != null) rom.romId else LibraryRepository.GLOBAL_OPTION_SCOPE
                libraryRepository.saveCoreOptionOverride(scopeKey, key, value)
                val refreshed = CoreOptionsJsonParser.parse(
                    runCatching { bridge.nativeGetCoreOptionsJson() }.getOrNull(),
                )
                _snapshot.update { it.copy(coreOptions = refreshed) }
                true
            }
        }

    suspend fun applyEnabledCheatsForActiveRom(): AppResult<Unit> = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            val rom = _snapshot.value.activeRom ?: return@withLock AppResult.Success(Unit)
            val st = _snapshot.value.state
            if (st != EmulatorState.RUNNING && st != EmulatorState.PAUSED) {
                return@withLock AppResult.Success(Unit)
            }
            val enabled = libraryRepository.getEnabledCheatCodes(rom.romId)
            val err = runCatching { bridge.nativeApplyCheats(enabled.toTypedArray()) }.getOrNull()
            if (err != null) {
                AppResult.Failure(EmulatorError.InvalidCheatCode(err))
            } else {
                AppResult.Success(Unit)
            }
        }
    }

    suspend fun stopSession() = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            shutdownInternalLocked()
        }
    }

    fun onAppBackgrounded() {
        sessionScope.launch {
            pauseSession(reason = null)
        }
    }

    // Surface lifecycle callbacks (thread-safe; EGL thread handles reattach without stopping core).
    fun attachSurface(surface: Surface, width: Int, height: Int) {
        runCatching { bridge.nativeAttachSurface(surface, width, height) }
    }

    fun resizeSurface(width: Int, height: Int) {
        runCatching { bridge.nativeResizeSurface(width, height) }
    }

    fun detachSurface() {
        runCatching { bridge.nativeDetachSurface() }
    }

    // Real-time lock-free input & A/V controls.
    fun setTouchInputMask(mask: Int) {
        if (_snapshot.value.state == EmulatorState.RUNNING) {
            bridge.nativeSetTouchInputMask(mask)
        }
    }

    fun setGamepadInputMask(mask: Int) {
        if (_snapshot.value.state == EmulatorState.RUNNING) {
            bridge.nativeSetGamepadInputMask(mask)
        }
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

    private fun captureThumbnailPng(): ByteArray? {
        val raw = runCatching { bridge.nativeCaptureScreenshotArgb() }.getOrNull() ?: return null
        if (raw.size < 3) return null
        val w = raw[0]
        val h = raw[1]
        if (w <= 0 || h <= 0 || w > 1024 || h > 1024 || raw.size < 2 + w * h) return null
        return runCatching {
            val bmp = Bitmap.createBitmap(raw, 2, w, w, h, Bitmap.Config.ARGB_8888)
            val baos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 90, baos)
            bmp.recycle()
            baos.toByteArray()
        }.getOrNull()
    }

    private suspend fun accumulatePlayTimeLocked() {
        val rom = _snapshot.value.activeRom ?: return
        if (runningSegmentStartMs > 0L) {
            val delta = (System.currentTimeMillis() - runningSegmentStartMs).coerceAtLeast(0L)
            runningSegmentStartMs = 0L
            if (delta > 0L) {
                libraryRepository.recordPlayTime(rom.romId, delta)
            }
        }
    }

    private suspend fun shutdownInternalLocked() {
        accumulatePlayTimeLocked()
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
                libraryRepository.markBatterySavePresent(activeRom.romId, true)
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
        val st = _snapshot.value.state
        if (st != EmulatorState.RUNNING && st != EmulatorState.PAUSED) {
            return AppResult.Success(false)
        }
        val bytes = runCatching { bridge.nativeExtractSram() }.getOrNull()
        if (bytes == null || bytes.isEmpty() || isBlankSram(bytes)) {
            return AppResult.Success(false)
        }
        return when (val writeRes = sramSaveRepository.writeSram(rom.system, rom.sha256, bytes)) {
            is AppResult.Failure -> writeRes
            is AppResult.Success -> {
                libraryRepository.markBatterySavePresent(rom.romId, true)
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
