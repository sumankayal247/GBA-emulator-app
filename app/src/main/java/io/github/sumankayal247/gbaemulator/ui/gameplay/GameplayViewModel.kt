package io.github.sumankayal247.gbaemulator.ui.gameplay

import android.view.Surface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.sumankayal247.gbaemulator.AppContainer
import io.github.sumankayal247.gbaemulator.data.preferences.PreferencesRepository
import io.github.sumankayal247.gbaemulator.data.rom.LibraryRepository
import io.github.sumankayal247.gbaemulator.domain.model.AppResult
import io.github.sumankayal247.gbaemulator.domain.model.CheatCodeEntry
import io.github.sumankayal247.gbaemulator.domain.model.EmulatorPreferences
import io.github.sumankayal247.gbaemulator.domain.model.OrientationLock
import io.github.sumankayal247.gbaemulator.domain.model.SaveStateSlot
import io.github.sumankayal247.gbaemulator.domain.model.ScalingMode
import io.github.sumankayal247.gbaemulator.emulator.ControllerManager
import io.github.sumankayal247.gbaemulator.emulator.EmulatorSession
import io.github.sumankayal247.gbaemulator.emulator.EmulatorSessionSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class ActiveGameplayDialog {
    NONE,
    MAIN_MENU,
    SAVE_STATES,
    LOAD_STATES,
    CORE_OPTIONS,
    CHEATS,
}

data class GameplayUiState(
    val session: EmulatorSessionSnapshot = EmulatorSessionSnapshot(),
    val preferences: EmulatorPreferences = EmulatorPreferences(),
    val activeDialog: ActiveGameplayDialog = ActiveGameplayDialog.NONE,
    val saveStateSlots: List<SaveStateSlot> = emptyList(),
    val activeRomCheats: List<CheatCodeEntry> = emptyList(),
    val hasConnectedController: Boolean = false,
    val hasReceivedGamepadInput: Boolean = false,
    val gamepadInputSignal: Long = 0L,
    val toastMessage: String? = null,
) {
    val isAnyDialogOpen: Boolean
        get() = activeDialog != ActiveGameplayDialog.NONE
}

@OptIn(ExperimentalCoroutinesApi::class)
class GameplayViewModel(
    private val emulatorSession: EmulatorSession,
    private val libraryRepository: LibraryRepository,
    private val preferencesRepository: PreferencesRepository,
    private val controllerManager: ControllerManager,
) : ViewModel() {

    private val activeDialog = MutableStateFlow(ActiveGameplayDialog.NONE)
    private val slotsFlow = MutableStateFlow<List<SaveStateSlot>>(emptyList())
    private val toast = MutableStateFlow<String?>(null)

    private val activeRomCheatsFlow = emulatorSession.snapshot
        .map { it.activeRom?.romId }
        .distinctUntilChanged()
        .flatMapLatest { romId ->
            if (romId.isNullOrBlank()) flowOf(emptyList())
            else libraryRepository.observeCheats(romId)
        }

    private val controllerState = combine(
        controllerManager.hasConnectedController,
        controllerManager.hasReceivedGamepadInput,
        controllerManager.gamepadInputSignal,
    ) { hasController, hasReceivedInput, inputSignal ->
        Triple(hasController, hasReceivedInput, inputSignal)
    }

    val uiState: StateFlow<GameplayUiState> = combine(
        combine(
            emulatorSession.snapshot,
            preferencesRepository.preferencesFlow,
            activeDialog,
            slotsFlow,
        ) { session, prefs, dialog, slots ->
            QuadState(session, prefs, dialog, slots)
        },
        activeRomCheatsFlow,
        controllerState,
        toast,
    ) { quad, cheats, controller, toastMsg ->
        GameplayUiState(
            session = quad.session,
            preferences = quad.prefs,
            activeDialog = quad.dialog,
            saveStateSlots = quad.slots,
            activeRomCheats = cheats,
            hasConnectedController = controller.first,
            hasReceivedGamepadInput = controller.second,
            gamepadInputSignal = controller.third,
            toastMessage = toastMsg ?: quad.session.statusNotice,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = GameplayUiState(),
    )

    private data class QuadState(
        val session: EmulatorSessionSnapshot,
        val prefs: EmulatorPreferences,
        val dialog: ActiveGameplayDialog,
        val slots: List<SaveStateSlot>,
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
        controllerManager.releaseAllInputs()
    }

    fun openMenu() {
        releaseAllInputs()
        activeDialog.value = ActiveGameplayDialog.MAIN_MENU
        viewModelScope.launch { emulatorSession.pauseSession() }
    }

    fun closeAllDialogsAndResume() {
        activeDialog.value = ActiveGameplayDialog.NONE
        viewModelScope.launch { emulatorSession.resumeSession() }
    }

    fun openSaveStatesDialog() {
        viewModelScope.launch {
            slotsFlow.value = emulatorSession.listSaveStateSlots()
            activeDialog.value = ActiveGameplayDialog.SAVE_STATES
        }
    }

    fun openLoadStatesDialog() {
        viewModelScope.launch {
            slotsFlow.value = emulatorSession.listSaveStateSlots()
            activeDialog.value = ActiveGameplayDialog.LOAD_STATES
        }
    }

    fun openCoreOptionsDialog() {
        activeDialog.value = ActiveGameplayDialog.CORE_OPTIONS
    }

    fun openCheatsDialog() {
        activeDialog.value = ActiveGameplayDialog.CHEATS
    }

    fun backToMainMenu() {
        activeDialog.value = ActiveGameplayDialog.MAIN_MENU
    }

    fun onActivityPaused() {
        releaseAllInputs()
        viewModelScope.launch { emulatorSession.pauseSession() }
    }

    fun onActivityResumed() {
        if (activeDialog.value == ActiveGameplayDialog.NONE) {
            viewModelScope.launch { emulatorSession.resumeSession() }
        }
    }

    fun saveStateToSlot(slotIndex: Int) {
        viewModelScope.launch {
            when (val res = emulatorSession.saveStateToSlot(slotIndex)) {
                is AppResult.Failure -> toast.value = res.error.userMessage
                is AppResult.Success -> {
                    slotsFlow.value = emulatorSession.listSaveStateSlots()
                    toast.value = "Saved State $slotIndex."
                    activeDialog.value = ActiveGameplayDialog.NONE
                    emulatorSession.resumeSession()
                }
            }
        }
    }

    fun loadStateFromSlot(slotIndex: Int) {
        viewModelScope.launch {
            when (val res = emulatorSession.loadStateFromSlot(slotIndex)) {
                is AppResult.Failure -> toast.value = res.error.userMessage
                is AppResult.Success -> {
                    toast.value = res.value
                    activeDialog.value = ActiveGameplayDialog.NONE
                    emulatorSession.resumeSession()
                }
            }
        }
    }

    fun deleteSaveStateSlot(slotIndex: Int) {
        viewModelScope.launch {
            emulatorSession.deleteSaveStateSlot(slotIndex)
            slotsFlow.value = emulatorSession.listSaveStateSlots()
            toast.value = "Deleted State $slotIndex."
        }
    }

    fun setCoreOption(key: String, value: String, perGame: Boolean) {
        viewModelScope.launch {
            val ok = emulatorSession.setCoreOption(key, value, perGame)
            if (!ok) {
                toast.value = "Option value rejected by the active core."
            }
        }
    }

    fun addCheat(description: String, rawCode: String) {
        val rom = emulatorSession.snapshot.value.activeRom ?: return
        viewModelScope.launch {
            when (val res = libraryRepository.addCheat(rom.romId, rom.system, description, rawCode)) {
                is AppResult.Failure -> toast.value = res.error.userMessage
                is AppResult.Success -> {
                    syncActiveCheats()
                    toast.value = "Added cheat '${res.value.description}'."
                }
            }
        }
    }

    fun updateCheat(entry: CheatCodeEntry, description: String, rawCode: String) {
        val rom = emulatorSession.snapshot.value.activeRom ?: return
        viewModelScope.launch {
            when (val res = libraryRepository.updateCheat(entry.id, rom.system, description, rawCode, entry.enabled)) {
                is AppResult.Failure -> toast.value = res.error.userMessage
                is AppResult.Success -> {
                    syncActiveCheats()
                    toast.value = "Updated cheat."
                }
            }
        }
    }

    fun toggleCheat(id: Long, enabled: Boolean) {
        viewModelScope.launch {
            libraryRepository.toggleCheat(id, enabled)
            syncActiveCheats()
        }
    }

    fun deleteCheat(id: Long) {
        viewModelScope.launch {
            libraryRepository.deleteCheat(id)
            syncActiveCheats()
        }
    }

    private suspend fun syncActiveCheats() {
        val res = emulatorSession.applyEnabledCheatsForActiveRom()
        if (res is AppResult.Failure) {
            toast.value = res.error.userMessage
        }
    }

    fun flushBatterySaveNow() {
        viewModelScope.launch {
            when (val res = emulatorSession.flushBatterySave()) {
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

    fun restartGame() {
        activeDialog.value = ActiveGameplayDialog.NONE
        viewModelScope.launch {
            emulatorSession.resetGame()
            emulatorSession.resumeSession()
        }
    }

    fun quitToHome(onQuitComplete: () -> Unit) {
        activeDialog.value = ActiveGameplayDialog.NONE
        viewModelScope.launch {
            emulatorSession.stopSession()
            onQuitComplete()
        }
    }

    fun toggleMute(muted: Boolean) {
        emulatorSession.setMuted(muted)
    }

    fun toggleFastForward(enabled: Boolean) {
        emulatorSession.setFastForward(enabled)
    }

    fun toggleOrientationLock(locked: Boolean, currentIsLandscape: Boolean) {
        val target = if (!locked) {
            OrientationLock.AUTO
        } else if (currentIsLandscape) {
            OrientationLock.LANDSCAPE
        } else {
            OrientationLock.PORTRAIT
        }
        viewModelScope.launch { preferencesRepository.updateOrientationLock(target) }
    }

    fun toggleAutoHideTouch(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.updateAutoHideTouchWithController(enabled) }
    }

    fun toggleScalingMode() {
        val next = when (emulatorSession.snapshot.value.scalingMode) {
            ScalingMode.ASPECT_FIT -> ScalingMode.INTEGER_SCALE
            ScalingMode.INTEGER_SCALE -> ScalingMode.ASPECT_FIT
        }
        emulatorSession.setScalingMode(next)
    }

    fun dismissToast() {
        toast.value = null
        emulatorSession.clearStatusNotice()
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            GameplayViewModel(
                emulatorSession = container.emulatorSession,
                libraryRepository = container.libraryRepository,
                preferencesRepository = container.preferencesRepository,
                controllerManager = container.controllerManager,
            ) as T
    }
}
