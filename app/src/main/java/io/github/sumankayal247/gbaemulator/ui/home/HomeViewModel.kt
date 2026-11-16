package io.github.sumankayal247.gbaemulator.ui.home

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.sumankayal247.gbaemulator.AppContainer
import io.github.sumankayal247.gbaemulator.data.preferences.PreferencesRepository
import io.github.sumankayal247.gbaemulator.data.rom.RomStagingRepository
import io.github.sumankayal247.gbaemulator.data.saves.SramSaveRepository
import io.github.sumankayal247.gbaemulator.domain.model.AppResult
import io.github.sumankayal247.gbaemulator.domain.model.EmulatorError
import io.github.sumankayal247.gbaemulator.domain.model.EmulatorPreferences
import io.github.sumankayal247.gbaemulator.domain.model.OrientationLock
import io.github.sumankayal247.gbaemulator.domain.model.RomRecord
import io.github.sumankayal247.gbaemulator.domain.model.ScalingMode
import io.github.sumankayal247.gbaemulator.emulator.EmulatorSession
import io.github.sumankayal247.gbaemulator.emulator.EmulatorSessionSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HomeUiState(
    val recentGames: List<RomRecord> = emptyList(),
    val preferences: EmulatorPreferences = EmulatorPreferences(),
    val session: EmulatorSessionSnapshot = EmulatorSessionSnapshot(),
    val isLoadingRom: Boolean = false,
    val bannerMessage: String? = null,
    val isBannerError: Boolean = false,
    val relinkTarget: RomRecord? = null,
)

class HomeViewModel(
    private val romStagingRepository: RomStagingRepository,
    private val sramSaveRepository: SramSaveRepository,
    private val preferencesRepository: PreferencesRepository,
    private val emulatorSession: EmulatorSession,
) : ViewModel() {

    private val localStatus = MutableStateFlow(LocalHomeStatus())
    private val availabilityMap = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    val uiState: StateFlow<HomeUiState> = combine(
        preferencesRepository.recentGamesFlow,
        preferencesRepository.preferencesFlow,
        emulatorSession.snapshot,
        localStatus,
        availabilityMap,
    ) { recents, prefs, session, status, availability ->
        val enriched = recents.map { r ->
            r.copy(
                hasBatterySave = sramSaveRepository.hasSave(r),
                sourceAvailable = availability[r.romId] ?: r.sourceAvailable,
            )
        }
        HomeUiState(
            recentGames = enriched,
            preferences = prefs,
            session = session,
            isLoadingRom = status.isLoadingRom,
            bannerMessage = status.bannerMessage,
            isBannerError = status.isBannerError,
            relinkTarget = status.relinkTarget,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = HomeUiState(),
    )

    init {
        refreshSourceAvailability()
    }

    fun refreshSourceAvailability() {
        viewModelScope.launch {
            val records = preferencesRepository.recentGamesFlow.first()
            val updated = HashMap<String, Boolean>(records.size)
            for (r in records) {
                updated[r.romId] = romStagingRepository.checkSourceAvailability(r.sourceUri)
            }
            availabilityMap.value = updated
        }
    }

    fun openRomFromSaf(uri: Uri, onLaunched: () -> Unit) {
        viewModelScope.launch {
            localStatus.update {
                it.copy(isLoadingRom = true, bannerMessage = null, isBannerError = false)
            }
            val staged = romStagingRepository.ingestFromSafUri(uri) { record ->
                sramSaveRepository.hasSave(record)
            }
            when (staged) {
                is AppResult.Failure -> {
                    localStatus.update {
                        it.copy(
                            isLoadingRom = false,
                            bannerMessage = staged.error.userMessage,
                            isBannerError = true,
                        )
                    }
                }
                is AppResult.Success -> {
                    val prefs = preferencesRepository.preferencesFlow.first()
                    val startRes = emulatorSession.startSession(
                        record = staged.value.record,
                        stagedRomFile = staged.value.stagedFile,
                        preferences = prefs,
                    )
                    when (startRes) {
                        is AppResult.Failure -> {
                            localStatus.update {
                                it.copy(
                                    isLoadingRom = false,
                                    bannerMessage = startRes.error.userMessage,
                                    isBannerError = true,
                                )
                            }
                        }
                        is AppResult.Success -> {
                            availabilityMap.update { it + (staged.value.record.romId to true) }
                            localStatus.update {
                                it.copy(
                                    isLoadingRom = false,
                                    bannerMessage = null,
                                    isBannerError = false,
                                    relinkTarget = null,
                                )
                            }
                            onLaunched()
                        }
                    }
                }
            }
        }
    }

    fun launchRecentGame(record: RomRecord, onLaunched: () -> Unit) {
        viewModelScope.launch {
            localStatus.update {
                it.copy(isLoadingRom = true, bannerMessage = null, isBannerError = false)
            }
            when (val resolved = romStagingRepository.resolveForLaunch(record)) {
                is AppResult.Failure -> {
                    val missing = resolved.error is EmulatorError.MissingSourceFile
                    if (missing) {
                        availabilityMap.update { it + (record.romId to false) }
                    }
                    localStatus.update {
                        it.copy(
                            isLoadingRom = false,
                            bannerMessage = resolved.error.userMessage,
                            isBannerError = true,
                            relinkTarget = if (missing) record else null,
                        )
                    }
                }
                is AppResult.Success -> {
                    val prefs = preferencesRepository.preferencesFlow.first()
                    val startRes = emulatorSession.startSession(
                        record = record,
                        stagedRomFile = resolved.value,
                        preferences = prefs,
                    )
                    when (startRes) {
                        is AppResult.Failure -> {
                            localStatus.update {
                                it.copy(
                                    isLoadingRom = false,
                                    bannerMessage = startRes.error.userMessage,
                                    isBannerError = true,
                                )
                            }
                        }
                        is AppResult.Success -> {
                            availabilityMap.update { it + (record.romId to true) }
                            localStatus.update {
                                it.copy(
                                    isLoadingRom = false,
                                    bannerMessage = null,
                                    isBannerError = false,
                                    relinkTarget = null,
                                )
                            }
                            onLaunched()
                        }
                    }
                }
            }
        }
    }

    fun relinkRomFromSaf(target: RomRecord, newUri: Uri, onLaunched: () -> Unit) {
        viewModelScope.launch {
            localStatus.update { it.copy(isLoadingRom = true, bannerMessage = null, isBannerError = false) }
            val staged = romStagingRepository.ingestFromSafUri(newUri) { sramSaveRepository.hasSave(it) }
            when (staged) {
                is AppResult.Failure -> {
                    localStatus.update {
                        it.copy(
                            isLoadingRom = false,
                            bannerMessage = staged.error.userMessage,
                            isBannerError = true,
                        )
                    }
                }
                is AppResult.Success -> {
                    if (staged.value.record.romId != target.romId) {
                        localStatus.update {
                            it.copy(
                                isLoadingRom = false,
                                bannerMessage = "The selected file is a different ROM than '${target.displayTitle}'. Select the matching ROM to preserve its save.",
                                isBannerError = true,
                            )
                        }
                        return@launch
                    }
                    val updated = target.copy(
                        sourceUri = newUri.toString(),
                        sourceAvailable = true,
                        lastPlayedEpochMs = System.currentTimeMillis(),
                    )
                    preferencesRepository.upsertRecentGame(updated)
                    availabilityMap.update { it + (updated.romId to true) }
                    val prefs = preferencesRepository.preferencesFlow.first()
                    val startRes = emulatorSession.startSession(updated, staged.value.stagedFile, prefs)
                    when (startRes) {
                        is AppResult.Failure -> {
                            localStatus.update {
                                it.copy(
                                    isLoadingRom = false,
                                    bannerMessage = startRes.error.userMessage,
                                    isBannerError = true,
                                )
                            }
                        }
                        is AppResult.Success -> {
                            localStatus.update {
                                it.copy(isLoadingRom = false, relinkTarget = null, bannerMessage = null)
                            }
                            onLaunched()
                        }
                    }
                }
            }
        }
    }

    fun exportSramToSaf(record: RomRecord, destinationUri: Uri, contentResolver: ContentResolver) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val outStream = runCatching { contentResolver.openOutputStream(destinationUri) }.getOrNull()
                    ?: return@withContext AppResult.Failure(
                        EmulatorError.SaveStorageFailure("Unable to open export destination."),
                    )
                outStream.use { sramSaveRepository.exportSram(record.system, record.sha256, it) }
            }
            when (result) {
                is AppResult.Failure -> localStatus.update {
                    it.copy(bannerMessage = result.error.userMessage, isBannerError = true)
                }
                is AppResult.Success -> localStatus.update {
                    it.copy(
                        bannerMessage = "Exported ${result.value} bytes of battery save for ${record.displayTitle}.",
                        isBannerError = false,
                    )
                }
            }
        }
    }

    fun importSramFromSaf(record: RomRecord, sourceUri: Uri, contentResolver: ContentResolver) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val inStream = runCatching { contentResolver.openInputStream(sourceUri) }.getOrNull()
                    ?: return@withContext AppResult.Failure(
                        EmulatorError.InvalidSaveImport("Unable to open selected save file."),
                    )
                inStream.use { sramSaveRepository.importSram(record.system, record.sha256, it) }
            }
            when (result) {
                is AppResult.Failure -> localStatus.update {
                    it.copy(bannerMessage = result.error.userMessage, isBannerError = true)
                }
                is AppResult.Success -> {
                    preferencesRepository.markBatterySavePresent(record.romId, true)
                    localStatus.update {
                        it.copy(
                            bannerMessage = "Imported ${result.value} bytes of battery save for ${record.displayTitle}. Previous save backed up.",
                            isBannerError = false,
                        )
                    }
                }
            }
        }
    }

    fun removeFromRecentList(record: RomRecord) {
        viewModelScope.launch {
            preferencesRepository.removeRecentGame(record.romId)
            localStatus.update {
                it.copy(
                    bannerMessage = "Removed '${record.displayTitle}' from recent list (ROM file and battery save were not deleted).",
                    isBannerError = false,
                )
            }
        }
    }

    fun updateScalingMode(mode: ScalingMode) {
        emulatorSession.setScalingMode(mode)
    }

    fun updateMuted(muted: Boolean) {
        emulatorSession.setMuted(muted)
    }

    fun updateAudioVolume(volume: Float) {
        emulatorSession.setAudioVolume(volume)
    }

    fun updateOrientationLock(lock: OrientationLock) {
        viewModelScope.launch { preferencesRepository.updateOrientationLock(lock) }
    }

    fun updateControlsOpacity(opacity: Float) {
        viewModelScope.launch { preferencesRepository.updateControlsOpacity(opacity) }
    }

    fun updateControlsScale(scale: Float) {
        viewModelScope.launch { preferencesRepository.updateControlsScale(scale) }
    }

    fun dismissBanner() {
        localStatus.update { it.copy(bannerMessage = null, isBannerError = false, relinkTarget = null) }
    }

    private data class LocalHomeStatus(
        val isLoadingRom: Boolean = false,
        val bannerMessage: String? = null,
        val isBannerError: Boolean = false,
        val relinkTarget: RomRecord? = null,
    )

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return HomeViewModel(
                romStagingRepository = container.romStagingRepository,
                sramSaveRepository = container.sramSaveRepository,
                preferencesRepository = container.preferencesRepository,
                emulatorSession = container.emulatorSession,
            ) as T
        }
    }
}
