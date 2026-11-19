package io.github.sumankayal247.gbaemulator.ui.home

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.sumankayal247.gbaemulator.AppContainer
import io.github.sumankayal247.gbaemulator.data.preferences.PreferencesRepository
import io.github.sumankayal247.gbaemulator.data.rom.DeviceStorageScanner
import io.github.sumankayal247.gbaemulator.data.rom.LibraryRepository
import io.github.sumankayal247.gbaemulator.data.rom.RomStagingRepository
import io.github.sumankayal247.gbaemulator.data.saves.SramSaveRepository
import io.github.sumankayal247.gbaemulator.domain.model.AppResult
import io.github.sumankayal247.gbaemulator.domain.model.EmulatorError
import io.github.sumankayal247.gbaemulator.domain.model.EmulatorPreferences
import io.github.sumankayal247.gbaemulator.domain.model.GameSystem
import io.github.sumankayal247.gbaemulator.domain.model.RomRecord
import io.github.sumankayal247.gbaemulator.domain.model.SavedFolderRecord
import io.github.sumankayal247.gbaemulator.emulator.EmulatorSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class LibraryFilter(val label: String) {
    ALL("All"),
    GBA("GBA"),
    GBC("GBC"),
    GB("GB"),
    FAVORITES("Favorites"),
}

enum class LibrarySort(val label: String) {
    RECENT("Recent"),
    TITLE("Title"),
    SYSTEM("System"),
}

data class HomeUiState(
    val allGames: List<RomRecord> = emptyList(),
    val filteredGames: List<RomRecord> = emptyList(),
    val recentGames: List<RomRecord> = emptyList(),
    val favoriteGames: List<RomRecord> = emptyList(),
    val savedFolders: List<SavedFolderRecord> = emptyList(),
    val preferences: EmulatorPreferences = EmulatorPreferences(),
    val searchQuery: String = "",
    val filter: LibraryFilter = LibraryFilter.ALL,
    val sort: LibrarySort = LibrarySort.RECENT,
    val isLoadingRom: Boolean = false,
    val isScanningFolders: Boolean = false,
    val hasStorageAccess: Boolean = false,
    val statusBanner: String? = null,
    val errorBanner: String? = null,
    val relinkNeededForRom: RomRecord? = null,
)

class HomeViewModel(
    private val libraryRepository: LibraryRepository,
    private val deviceStorageScanner: DeviceStorageScanner,
    private val romStagingRepository: RomStagingRepository,
    private val sramSaveRepository: SramSaveRepository,
    private val preferencesRepository: PreferencesRepository,
    private val emulatorSession: EmulatorSession,
) : ViewModel() {

    private val searchQuery = MutableStateFlow("")
    private val filter = MutableStateFlow(LibraryFilter.ALL)
    private val sort = MutableStateFlow(LibrarySort.RECENT)
    private val loadingRom = MutableStateFlow(false)
    private val scanningFolders = MutableStateFlow(false)
    private val storageAccess = MutableStateFlow(false)
    private val statusBanner = MutableStateFlow<String?>(null)
    private val errorBanner = MutableStateFlow<String?>(null)
    private val relinkNeeded = MutableStateFlow<RomRecord?>(null)

    private var scanJob: Job? = null

    private data class QueryControls(
        val query: String,
        val filter: LibraryFilter,
        val sort: LibrarySort,
    )

    private data class BusyFlags(
        val loading: Boolean,
        val scanning: Boolean,
        val storageAccess: Boolean,
    )

    private data class BannerState(
        val status: String?,
        val error: String?,
        val relink: RomRecord?,
    )

    private data class BusyAndBanners(
        val loading: Boolean,
        val scanning: Boolean,
        val storageAccess: Boolean,
        val status: String?,
        val error: String?,
        val relink: RomRecord?,
    )

    val uiState: StateFlow<HomeUiState> = combine(
        libraryRepository.gamesFlow,
        libraryRepository.savedFoldersFlow,
        preferencesRepository.preferencesFlow,
        combine(searchQuery, filter, sort) { q, f, s -> QueryControls(q, f, s) },
        combine(
            combine(loadingRom, scanningFolders, storageAccess) { l, sc, access ->
                BusyFlags(l, sc, access)
            },
            combine(statusBanner, errorBanner, relinkNeeded) { st, err, rel ->
                BannerState(st, err, rel)
            },
        ) { flags, banners ->
            BusyAndBanners(
                loading = flags.loading,
                scanning = flags.scanning,
                storageAccess = flags.storageAccess,
                status = banners.status,
                error = banners.error,
                relink = banners.relink,
            )
        },
    ) { games, folders, prefs, controls, busy ->
        val trimmedQuery = controls.query.trim().lowercase()
        val filtered = games
            .filter { rec ->
                when (controls.filter) {
                    LibraryFilter.ALL -> true
                    LibraryFilter.GBA -> rec.system == GameSystem.GBA
                    LibraryFilter.GBC -> rec.system == GameSystem.GBC
                    LibraryFilter.GB -> rec.system == GameSystem.GB
                    LibraryFilter.FAVORITES -> rec.isFavorite
                }
            }
            .filter { rec ->
                trimmedQuery.isEmpty() ||
                    rec.effectiveTitle.lowercase().contains(trimmedQuery) ||
                    rec.displayTitle.lowercase().contains(trimmedQuery) ||
                    rec.system.displayName.lowercase().contains(trimmedQuery)
            }
            .let { list ->
                when (controls.sort) {
                    LibrarySort.RECENT -> list.sortedWith(
                        compareByDescending<RomRecord> { it.lastPlayedEpochMs }
                            .thenByDescending { it.dateAddedEpochMs }
                            .thenBy { it.effectiveTitle.lowercase() },
                    )
                    LibrarySort.TITLE -> list.sortedBy { it.effectiveTitle.lowercase() }
                    LibrarySort.SYSTEM -> list.sortedWith(
                        compareBy<RomRecord> { it.system.ordinal }.thenBy { it.effectiveTitle.lowercase() },
                    )
                }
            }

        HomeUiState(
            allGames = games,
            filteredGames = filtered,
            recentGames = LibraryShelves.recents(games),
            favoriteGames = LibraryShelves.favorites(games),
            savedFolders = folders,
            preferences = prefs,
            searchQuery = controls.query,
            filter = controls.filter,
            sort = controls.sort,
            isLoadingRom = busy.loading,
            isScanningFolders = busy.scanning,
            hasStorageAccess = busy.storageAccess,
            statusBanner = busy.status,
            errorBanner = busy.error,
            relinkNeededForRom = busy.relink,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = HomeUiState(),
    )

    fun updateSearchQuery(query: String) {
        searchQuery.value = query
    }

    fun selectFilter(newFilter: LibraryFilter) {
        filter.value = newFilter
    }

    fun selectSort(newSort: LibrarySort) {
        sort.value = newSort
    }

    fun addAndScanFolder(treeUri: Uri) {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            scanningFolders.value = true
            errorBanner.value = null
            statusBanner.value = "Scanning selected folder for GBA / GBC / GB ROMs..."
            try {
                when (val res = libraryRepository.addAndScanFolder(treeUri)) {
                    is AppResult.Failure -> {
                        errorBanner.value = res.error.userMessage
                        statusBanner.value = null
                    }
                    is AppResult.Success -> {
                        val r = res.value
                        val warnText = if (r.warnings.isNotEmpty()) " (${r.warnings.first()})" else ""
                        statusBanner.value =
                            "Folder scan: ${r.discoveredGames} unique ROM(s), " +
                                "${r.newGames} new, ${r.duplicateGames} duplicate copies ignored.$warnText"
                    }
                }
            } finally {
                scanningFolders.value = false
            }
        }
    }

    fun rescanSavedFolders() {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            val folders = uiState.value.savedFolders
            if (folders.isEmpty()) {
                statusBanner.value = "Select a game folder first using the folder button."
                return@launch
            }
            scanningFolders.value = true
            errorBanner.value = null
            statusBanner.value = "Quick scanning ${folders.size} granted folder(s) for supported ROMs..."
            try {
                val reports = libraryRepository.scanAllSavedFolders()
                val totalFound = reports.sumOf { it.discoveredGames }
                val totalNew = reports.sumOf { it.newGames }
                val totalDuplicates = reports.sumOf { it.duplicateGames }
                val firstWarning = reports.flatMap { it.warnings }.firstOrNull()
                statusBanner.value = buildString {
                    append(
                        "Folder rescan: $totalFound unique ROM(s), $totalNew new, " +
                            "$totalDuplicates duplicate copies ignored.",
                    )
                    if (firstWarning != null) append(" Warning: $firstWarning")
                }
            } finally {
                scanningFolders.value = false
            }
        }
    }

    /**
     * SAF-only folder scan entry point. Returns false when no SAF folder is configured so the
     * Activity can open the folder picker. Device-wide scanning is a separate opt-in action.
     */
    fun quickScan(): Boolean {
        if (uiState.value.savedFolders.isEmpty()) {
            errorBanner.value = null
            statusBanner.value =
                "Choose a folder first for the SAF-only scan, or use Scan device with storage access."
            return false
        }
        rescanSavedFolders()
        return true
    }

    /** Kept in sync by MainActivity when the permission dialog or Settings screen closes. */
    fun refreshStorageAccess(granted: Boolean, notifyIfDenied: Boolean = false) {
        if (storageAccess.value == granted) return
        storageAccess.value = granted
        if (!granted) {
            viewModelScope.launch {
                libraryRepository.markDeviceSourcesUnavailable()
                if (notifyIfDenied) {
                    errorBanner.value =
                        "Device storage access is off. Previously discovered file-backed games need access restored or a SAF relink; your saves are safe."
                }
            }
        }
    }

    fun reportStorageAccessDenied() {
        errorBanner.value =
            "Storage access was not granted. You can still use Select folder or Import a single ROM file."
    }

    fun scanDeviceStorage() {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            scanningFolders.value = true
            errorBanner.value = null
            statusBanner.value = "Scanning device storage for .gba / .gbc / .gb ROMs..."
            try {
                when (val res = deviceStorageScanner.scanDevice()) {
                    is AppResult.Failure -> {
                        errorBanner.value = res.error.userMessage
                        statusBanner.value = null
                    }
                    is AppResult.Success -> {
                        val r = res.value
                        val warning = r.warnings.firstOrNull()?.let { " Warning: $it" }.orEmpty()
                        statusBanner.value = buildString {
                            append(
                                "Device scan: ${r.discoveredGames} unique ROM(s), " +
                                    "${r.newGames} new, ${r.duplicateGames} duplicate copies ignored, ",
                            )
                            append("${r.inspectedFiles} checked, ${r.skippedFiles} skipped.$warning")
                        }
                    }
                }
            } finally {
                scanningFolders.value = false
            }
        }
    }

    fun cancelScan() {
        scanJob?.cancel()
        scanningFolders.value = false
        statusBanner.value = "Scan cancelled."
    }

    fun removeSavedFolder(treeUri: String) {
        viewModelScope.launch {
            libraryRepository.removeSavedFolder(treeUri)
            statusBanner.value = "Removed folder from scan list (files on storage were not touched)."
        }
    }

    fun importAndLaunchRom(uri: Uri, onLaunched: () -> Unit) {
        viewModelScope.launch {
            loadingRom.value = true
            errorBanner.value = null
            relinkNeeded.value = null

            val staged = romStagingRepository.ingestFromSafUri(uri) { rec ->
                sramSaveRepository.hasSave(rec)
            }
            when (staged) {
                is AppResult.Failure -> {
                    loadingRom.value = false
                    errorBanner.value = staged.error.userMessage
                }
                is AppResult.Success -> {
                    val record = libraryRepository.indexStagedRom(staged.value, markPlayedNow = true)
                    val prefs = preferencesRepository.preferencesFlow.first()
                    val startRes = emulatorSession.startSession(record, staged.value.stagedFile, prefs)
                    loadingRom.value = false
                    when (startRes) {
                        is AppResult.Failure -> errorBanner.value = startRes.error.userMessage
                        is AppResult.Success -> onLaunched()
                    }
                }
            }
        }
    }

    fun launchGame(record: RomRecord, onLaunched: () -> Unit) {
        viewModelScope.launch {
            loadingRom.value = true
            errorBanner.value = null
            relinkNeeded.value = null

            when (val prepared = libraryRepository.prepareRomForLaunch(record)) {
                is AppResult.Failure -> {
                    loadingRom.value = false
                    if (prepared.error is EmulatorError.MissingSourceFile) {
                        relinkNeeded.value = record
                    }
                    errorBanner.value = prepared.error.userMessage
                }
                is AppResult.Success -> {
                    val (updatedRec, stagedFile) = prepared.value
                    val prefs = preferencesRepository.preferencesFlow.first()
                    val startRes = emulatorSession.startSession(updatedRec, stagedFile, prefs)
                    loadingRom.value = false
                    when (startRes) {
                        is AppResult.Failure -> errorBanner.value = startRes.error.userMessage
                        is AppResult.Success -> onLaunched()
                    }
                }
            }
        }
    }

    fun relinkRom(expectedRom: RomRecord, newUri: Uri, onLaunched: () -> Unit) {
        viewModelScope.launch {
            loadingRom.value = true
            errorBanner.value = null

            val staged = romStagingRepository.ingestFromSafUri(newUri) { rec ->
                sramSaveRepository.hasSave(rec)
            }
            when (staged) {
                is AppResult.Failure -> {
                    loadingRom.value = false
                    errorBanner.value = staged.error.userMessage
                }
                is AppResult.Success -> {
                    val newRec = staged.value.record
                    if (newRec.romId != expectedRom.romId) {
                        loadingRom.value = false
                        errorBanner.value =
                            "Selected file does not match '${expectedRom.effectiveTitle}' (different ROM checksum). Saves were kept safe."
                        return@launch
                    }
                    val updated = libraryRepository.indexStagedRom(staged.value, markPlayedNow = true)
                    relinkNeeded.value = null
                    val prefs = preferencesRepository.preferencesFlow.first()
                    val startRes = emulatorSession.startSession(updated, staged.value.stagedFile, prefs)
                    loadingRom.value = false
                    when (startRes) {
                        is AppResult.Failure -> errorBanner.value = startRes.error.userMessage
                        is AppResult.Success -> {
                            statusBanner.value = "Relinked '${updated.effectiveTitle}' with saves intact."
                            onLaunched()
                        }
                    }
                }
            }
        }
    }

    fun toggleFavorite(record: RomRecord) {
        viewModelScope.launch {
            libraryRepository.setFavorite(record.romId, !record.isFavorite)
        }
    }

    fun removeFromRecents(record: RomRecord) {
        viewModelScope.launch {
            libraryRepository.clearRecentHistory(record.romId)
            statusBanner.value =
                "Removed '${record.effectiveTitle}' from Recently Played. The game and its saves are unchanged."
        }
    }

    fun removeFromFavorites(record: RomRecord) {
        viewModelScope.launch {
            libraryRepository.setFavorite(record.romId, false)
            statusBanner.value =
                "Removed '${record.effectiveTitle}' from Favorites. The game remains in your library."
        }
    }

    fun renameGame(record: RomRecord, newTitle: String) {
        viewModelScope.launch {
            libraryRepository.setCustomTitle(record.romId, newTitle)
            statusBanner.value = "Updated game title."
        }
    }

    fun setCustomCover(record: RomRecord, coverUri: Uri?) {
        viewModelScope.launch {
            libraryRepository.setCustomCover(record.romId, coverUri)
            statusBanner.value = if (coverUri != null) "Updated custom cover image." else "Reset cover image."
        }
    }

    fun exportSram(record: RomRecord, destinationUri: Uri, contentResolver: ContentResolver) {
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) {
                val out = runCatching { contentResolver.openOutputStream(destinationUri) }.getOrNull()
                    ?: return@withContext AppResult.Failure(
                        EmulatorError.SaveStorageFailure("Could not open destination file for export."),
                    )
                out.use { sramSaveRepository.exportSram(record.system, record.sha256, it) }
            }
            when (res) {
                is AppResult.Failure -> errorBanner.value = res.error.userMessage
                is AppResult.Success -> statusBanner.value = "Exported ${res.value} bytes of battery save."
            }
        }
    }

    fun importSram(record: RomRecord, sourceUri: Uri, contentResolver: ContentResolver) {
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) {
                val input = runCatching { contentResolver.openInputStream(sourceUri) }.getOrNull()
                    ?: return@withContext AppResult.Failure(
                        EmulatorError.InvalidSaveImport("Could not read selected save file."),
                    )
                input.use { sramSaveRepository.importSram(record.system, record.sha256, it) }
            }
            when (res) {
                is AppResult.Failure -> errorBanner.value = res.error.userMessage
                is AppResult.Success -> {
                    libraryRepository.markBatterySavePresent(record.romId, true)
                    statusBanner.value = "Imported ${res.value} bytes into '${record.effectiveTitle}'."
                }
            }
        }
    }

    fun removeFromLibrary(record: RomRecord) {
        viewModelScope.launch {
            libraryRepository.removeFromLibrary(record.romId)
            statusBanner.value = "Removed '${record.effectiveTitle}' from library (ROM file and saves kept safe)."
        }
    }

    fun deleteAllSavesForGame(record: RomRecord) {
        viewModelScope.launch {
            libraryRepository.deleteAllSavesForRom(record)
            statusBanner.value = "Deleted battery save and all save states for '${record.effectiveTitle}'."
        }
    }

    fun dismissBanners() {
        statusBanner.value = null
        errorBanner.value = null
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HomeViewModel(
                libraryRepository = container.libraryRepository,
                deviceStorageScanner = container.deviceStorageScanner,
                romStagingRepository = container.romStagingRepository,
                sramSaveRepository = container.sramSaveRepository,
                preferencesRepository = container.preferencesRepository,
                emulatorSession = container.emulatorSession,
            ) as T
    }
}
