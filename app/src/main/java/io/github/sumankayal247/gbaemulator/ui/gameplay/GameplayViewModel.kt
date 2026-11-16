package io.github.sumankayal247.gbaemulator.ui.gameplay

import android.content.ContentResolver
import android.net.Uri
import android.view.Surface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.sumankayal247.gbaemulator.AppContainer
import io.github.sumankayal247.gbaemulator.data.preferences.PreferencesRepository
import io.github.sumankayal247.gbaemulator.data.saves.SramSaveRepository
import io.github.sumankayal247.gbaemulator.domain.model.AppResult
import io.github.sumankayal247.gbaemulator.domain.model.EmulatorError
import io.github.sumankayal247.gbaemulator.domain.model.EmulatorPreferences
import io.github.sumankayal247.gbaemulator.domain.model.OrientationLock
import io.github.sumankayal247.gbaemulator.domain.model.ScalingMode
import io.github.sumankayal247.gbaemulator.emulator.EmulatorSession
import io.github.sumankayal247.gbaemulator.emulator.EmulatorSessionSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class GameplayUiState(
    val session: EmulatorSessionSnapshot = EmulatorSessionSnapshot(),
    val preferences: EmulatorPreferences = EmulatorPreferences(),
    val isMenuOpen: Boolean = false,
    val toastMessage: String? = null,
)

class GameplayViewModel(
    private val emulatorSession: EmulatorSession,
    private val preferencesRepository: PreferencesRepository,
    private val sramSaveRepository: SramSaveRepository,
) : ViewModel() {

    private val menuOpen = MutableStateFlow(false)
    private val toast = MutableStateFlow<String?>(null)

    val uiState: StateFlow<GameplayUiState> = combine(
        emulatorSession.snapshot,
        preferencesRepository.preferencesFlow,
        menuOpen,
        toast,
    ) { session, prefs, isMenu, toastMsg ->
        GameplayUiState(
            session = session,
            preferences = prefs,
            isMenuOpen = isMenu,
            toastMessage = toastMsg ?: session.statusNotice,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = GameplayUiState(),
    )

    fun attachSurface(surface: Surface, width: Int, height: Int) {
        emulatorSession.attachSurface(surface, width, height)
    }

    fun resizeSurface(width: Int, height: Int) {
        emulatorSession.resizeSurface(width, height)
    }

    fun detachSurface() {
        emulatorSession.detachSurface()
    }

    fun updateTouchInputMask(mask: Int) {
        emulatorSession.setTouchInputMask(mask)
    }

    fun releaseAllInputs() {
        emulatorSession.releaseAllInputs()
    }

    fun openMenu() {
        emulatorSession.releaseAllInputs()
        menuOpen.value = true
        viewModelScope.launch {
            emulatorSession.pauseSession()
        }
    }

    fun closeMenuAndResume() {
        menuOpen.value = false
        viewModelScope.launch {
            emulatorSession.resumeSession()
        }
    }

    fun onActivityPaused() {
        emulatorSession.releaseAllInputs()
        viewModelScope.launch {
            emulatorSession.pauseSession()
        }
    }

    fun onActivityResumed() {
        if (!menuOpen.value) {
            viewModelScope.launch {
                emulatorSession.resumeSession()
            }
        }
    }

    fun saveSramNow() {
        viewModelScope.launch {
            when (val res = emulatorSession.flushSramNow()) {
                is AppResult.Failure -> toast.value = res.error.userMessage
                is AppResult.Success -> {
                    toast.value = if (res.value) {
                        "Battery save (SRAM) flushed safely."
                    } else {
                        "No battery save data written by the game yet."
                    }
                }
            }
        }
    }

    fun exportActiveSram(destinationUri: Uri, contentResolver: ContentResolver) {
        val rom = emulatorSession.snapshot.value.activeRom ?: return
        viewModelScope.launch {
            emulatorSession.flushSramNow()
            val res = withContext(Dispatchers.IO) {
                val out = runCatching { contentResolver.openOutputStream(destinationUri) }.getOrNull()
                    ?: return@withContext AppResult.Failure(
                        EmulatorError.SaveStorageFailure("Unable to open export file."),
                    )
                out.use { sramSaveRepository.exportSram(rom.system, rom.sha256, it) }
            }
            toast.value = when (res) {
                is AppResult.Failure -> res.error.userMessage
                is AppResult.Success -> "Exported ${res.value} bytes of battery save."
            }
        }
    }

    fun restartGame() {
        menuOpen.value = false
        viewModelScope.launch {
            val res = emulatorSession.restartGame()
            if (res is AppResult.Failure) {
                toast.value = res.error.userMessage
            }
        }
    }

    fun quitToHome(onQuitComplete: () -> Unit) {
        menuOpen.value = false
        viewModelScope.launch {
            emulatorSession.quitSession()
            onQuitComplete()
        }
    }

    fun toggleMute(muted: Boolean) {
        emulatorSession.setMuted(muted)
    }

    fun toggleFastForward(enabled: Boolean) {
        emulatorSession.setFastForward(enabled)
    }

    fun toggleScalingMode() {
        val current = emulatorSession.snapshot.value.scalingMode
        val next = if (current == ScalingMode.ASPECT_FIT) {
            ScalingMode.INTEGER_SCALE
        } else {
            ScalingMode.ASPECT_FIT
        }
        emulatorSession.setScalingMode(next)
    }

    fun cycleOrientationLock() {
        val current = uiState.value.preferences.orientationLock
        val next = when (current) {
            OrientationLock.AUTO -> OrientationLock.PORTRAIT
            OrientationLock.PORTRAIT -> OrientationLock.LANDSCAPE
            OrientationLock.LANDSCAPE -> OrientationLock.AUTO
        }
        viewModelScope.launch { preferencesRepository.updateOrientationLock(next) }
    }

    fun dismissToast() {
        toast.update { null }
        emulatorSession.clearStatusNotice()
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return GameplayViewModel(
                emulatorSession = container.emulatorSession,
                preferencesRepository = container.preferencesRepository,
                sramSaveRepository = container.sramSaveRepository,
            ) as T
        }
    }
}
