package io.github.sumankayal247.gbaemulator

import android.Manifest
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DashboardCustomize
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.sumankayal247.gbaemulator.data.rom.StorageAccess
import io.github.sumankayal247.gbaemulator.domain.model.AppResult
import io.github.sumankayal247.gbaemulator.domain.model.BiosType
import io.github.sumankayal247.gbaemulator.domain.model.OrientationLock
import io.github.sumankayal247.gbaemulator.domain.model.RomRecord
import io.github.sumankayal247.gbaemulator.ui.controllers.ControllersScreen
import io.github.sumankayal247.gbaemulator.ui.extras.ExtrasScreen
import io.github.sumankayal247.gbaemulator.ui.gameplay.GameplayScreen
import io.github.sumankayal247.gbaemulator.ui.gameplay.GameplayViewModel
import io.github.sumankayal247.gbaemulator.ui.home.HomeScreen
import io.github.sumankayal247.gbaemulator.ui.home.HomeViewModel
import io.github.sumankayal247.gbaemulator.ui.layout.LayoutEditorScreen
import io.github.sumankayal247.gbaemulator.ui.settings.SettingsScreen
import io.github.sumankayal247.gbaemulator.ui.theme.EmulatorColors
import io.github.sumankayal247.gbaemulator.ui.theme.GbaEmulatorTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class AppDestination {
    SHELL,
    GAMEPLAY,
}

private enum class MainTab(
    val label: String,
    val icon: ImageVector,
) {
    HOME("Home", Icons.Filled.Home),
    LAYOUT("Layout", Icons.Filled.DashboardCustomize),
    CONTROLLERS("Controllers", Icons.Filled.SportsEsports),
    EXTRAS("Extras", Icons.Filled.Extension),
    SETTINGS("Settings", Icons.Filled.Settings),
}

class MainActivity : ComponentActivity() {

    private val appContainer: AppContainer
        get() = (application as GbaEmulatorApp).container

    private var activeGameplayVm: GameplayViewModel? = null
    private var activeHomeVm: HomeViewModel? = null
    private var currentDestination: AppDestination = AppDestination.SHELL
    private var currentTab: MainTab = MainTab.HOME

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            GbaEmulatorTheme {
                val scope = rememberCoroutineScope()
                val homeViewModel: HomeViewModel = viewModel(
                    factory = HomeViewModel.Factory(appContainer),
                )
                val gameplayViewModel: GameplayViewModel = viewModel(
                    factory = GameplayViewModel.Factory(appContainer),
                )
                activeGameplayVm = gameplayViewModel
                activeHomeVm = homeViewModel

                val homeState by homeViewModel.uiState.collectAsStateWithLifecycle()
                val gameplayState by gameplayViewModel.uiState.collectAsStateWithLifecycle()
                val controllers by appContainer.controllerManager.controllers.collectAsStateWithLifecycle()
                val controllerPressed by appContainer.controllerManager.pressedButtons.collectAsStateWithLifecycle()
                val capturingBinding by appContainer.controllerManager.capturingBindingFor.collectAsStateWithLifecycle()

                var destination by rememberSaveable { mutableStateOf(AppDestination.SHELL) }
                var selectedTab by rememberSaveable { mutableStateOf(MainTab.HOME) }

                // BIOS status list & user notice for the Extras tab
                var biosStatuses by remember {
                    mutableStateOf(appContainer.biosRepository.listStatuses())
                }
                var biosNotice by remember { mutableStateOf<String?>(null) }

                // Pending SAF targets
                var pendingRelinkRecord by remember { mutableStateOf<RomRecord?>(null) }
                var pendingExportRecord by remember { mutableStateOf<RomRecord?>(null) }
                var pendingImportRecord by remember { mutableStateOf<RomRecord?>(null) }
                var pendingCoverRecord by remember { mutableStateOf<RomRecord?>(null) }
                var pendingBiosType by remember { mutableStateOf<BiosType?>(null) }

                // SAF: Select a folder tree to index .gba / .gbc / .gb ROMs
                val pickFolderLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocumentTree(),
                ) { treeUri ->
                    if (treeUri != null) {
                        homeViewModel.addAndScanFolder(treeUri)
                    }
                }

                // ADR-0006: API 26-29 runtime prompt; API 30+ all-files access in Settings.
                val requestLegacyStorageLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    homeViewModel.refreshStorageAccess(granted, notifyIfDenied = true)
                    if (granted) homeViewModel.scanDeviceStorage() else homeViewModel.reportStorageAccessDenied()
                }
                val allFilesSettingsLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult(),
                ) {
                    val granted = StorageAccess.hasAccess(this@MainActivity)
                    homeViewModel.refreshStorageAccess(granted, notifyIfDenied = true)
                    if (granted) homeViewModel.scanDeviceStorage() else homeViewModel.reportStorageAccessDenied()
                }

                LaunchedEffect(Unit) {
                    homeViewModel.refreshStorageAccess(StorageAccess.hasAccess(this@MainActivity))
                }

                // SAF: Import and launch a single ROM file
                val openRomLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument(),
                ) { uri ->
                    if (uri != null) {
                        homeViewModel.importAndLaunchRom(uri) {
                            destination = AppDestination.GAMEPLAY
                        }
                    }
                }

                // SAF: Relink a moved/revoked ROM preserving its saves by checksum
                val relinkRomLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument(),
                ) { uri ->
                    val target = pendingRelinkRecord
                    pendingRelinkRecord = null
                    if (uri != null && target != null) {
                        homeViewModel.relinkRom(target, uri) {
                            destination = AppDestination.GAMEPLAY
                        }
                    }
                }

                // SAF: Export battery SRAM (.sav)
                val exportSramLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
                ) { uri ->
                    val target = pendingExportRecord
                    pendingExportRecord = null
                    if (uri != null && target != null) {
                        homeViewModel.exportSram(target, uri, contentResolver)
                    }
                }

                // SAF: Import battery SRAM (.sav)
                val importSramLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument(),
                ) { uri ->
                    val target = pendingImportRecord
                    pendingImportRecord = null
                    if (uri != null && target != null) {
                        homeViewModel.importSram(target, uri, contentResolver)
                    }
                }

                // SAF: Pick user-owned cover art image
                val pickCoverLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument(),
                ) { uri ->
                    val target = pendingCoverRecord
                    pendingCoverRecord = null
                    if (uri != null && target != null) {
                        homeViewModel.setCustomCover(target, uri)
                    }
                }

                // SAF: Import optional user-supplied BIOS file
                val importBiosLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument(),
                ) { uri ->
                    val targetType = pendingBiosType
                    pendingBiosType = null
                    if (uri != null && targetType != null) {
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                val stream = runCatching { contentResolver.openInputStream(uri) }.getOrNull()
                                if (stream == null) {
                                    null
                                } else {
                                    appContainer.biosRepository.importBios(targetType, stream)
                                }
                            }
                            biosStatuses = withContext(Dispatchers.IO) {
                                appContainer.biosRepository.listStatuses()
                            }
                            biosNotice = when (result) {
                                null -> "Could not open selected BIOS file."
                                is AppResult.Failure -> result.error.userMessage
                                is AppResult.Success ->
                                    "Imported ${targetType.label} (${targetType.fileName}). It will be used on next game launch."
                            }
                        }
                    }
                }

                // Keep Activity orientation and immersive bars synchronized with active destination.
                LaunchedEffect(destination, selectedTab, gameplayState.preferences.orientationLock) {
                    currentDestination = destination
                    currentTab = selectedTab
                    applySystemBars(immersive = destination == AppDestination.GAMEPLAY)
                    requestedOrientation = if (destination == AppDestination.GAMEPLAY) {
                        when (gameplayState.preferences.orientationLock) {
                            OrientationLock.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                            OrientationLock.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            OrientationLock.AUTO -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        }
                    } else {
                        ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }
                }

                when (destination) {
                    AppDestination.GAMEPLAY -> {
                        GameplayScreen(
                            state = gameplayState,
                            onAttachSurface = gameplayViewModel::attachSurface,
                            onResizeSurface = gameplayViewModel::resizeSurface,
                            onDetachSurface = gameplayViewModel::detachSurface,
                            onTouchInputMask = gameplayViewModel::updateTouchInputMask,
                            onReleaseAllInputs = gameplayViewModel::releaseAllInputs,
                            onOpenMenu = gameplayViewModel::openMenu,
                            onCloseMenuAndResume = gameplayViewModel::closeAllDialogsAndResume,
                            onOpenSaveStates = gameplayViewModel::openSaveStatesDialog,
                            onOpenLoadStates = gameplayViewModel::openLoadStatesDialog,
                            onOpenCoreOptions = gameplayViewModel::openCoreOptionsDialog,
                            onOpenCheats = gameplayViewModel::openCheatsDialog,
                            onBackToMainMenu = gameplayViewModel::backToMainMenu,
                            onSaveStateToSlot = gameplayViewModel::saveStateToSlot,
                            onLoadStateFromSlot = gameplayViewModel::loadStateFromSlot,
                            onDeleteSaveStateSlot = gameplayViewModel::deleteSaveStateSlot,
                            onSetCoreOption = gameplayViewModel::setCoreOption,
                            onAddCheat = gameplayViewModel::addCheat,
                            onUpdateCheat = gameplayViewModel::updateCheat,
                            onToggleCheat = gameplayViewModel::toggleCheat,
                            onDeleteCheat = gameplayViewModel::deleteCheat,
                            onFlushBatterySave = gameplayViewModel::flushBatterySaveNow,
                            onRestartGame = gameplayViewModel::restartGame,
                            onQuitToHome = {
                                gameplayViewModel.quitToHome {
                                    destination = AppDestination.SHELL
                                }
                            },
                            onToggleMute = gameplayViewModel::toggleMute,
                            onToggleFastForward = gameplayViewModel::toggleFastForward,
                            onToggleOrientationLock = gameplayViewModel::toggleOrientationLock,
                            onToggleScalingMode = gameplayViewModel::toggleScalingMode,
                            onToggleAutoHideTouch = gameplayViewModel::toggleAutoHideTouch,
                            onDismissToast = gameplayViewModel::dismissToast,
                        )
                    }

                    AppDestination.SHELL -> {
                        Scaffold(
                            containerColor = EmulatorColors.DarkBackground,
                            bottomBar = {
                                NavigationBar(
                                    containerColor = EmulatorColors.DarkSurface,
                                    contentColor = EmulatorColors.TextPrimary,
                                ) {
                                    MainTab.entries.forEach { tab ->
                                        val selected = selectedTab == tab
                                        NavigationBarItem(
                                            selected = selected,
                                            onClick = { selectedTab = tab },
                                            icon = {
                                                Icon(
                                                    imageVector = tab.icon,
                                                    contentDescription = tab.label,
                                                )
                                            },
                                            label = { Text(tab.label) },
                                            colors = NavigationBarItemDefaults.colors(
                                                selectedIconColor = EmulatorColors.AccentBlue,
                                                selectedTextColor = EmulatorColors.AccentBlue,
                                                unselectedIconColor = EmulatorColors.TextSecondary,
                                                unselectedTextColor = EmulatorColors.TextSecondary,
                                                indicatorColor = EmulatorColors.AccentBlue.copy(alpha = 0.16f),
                                            ),
                                        )
                                    }
                                }
                            },
                        ) { innerPadding ->
                            when (selectedTab) {
                                MainTab.HOME -> HomeScreen(
                                    state = homeState,
                                    onPickFolder = { pickFolderLauncher.launch(null) },
                                    onImportSingleRom = { openRomLauncher.launch(arrayOf("*/*")) },
                                    onQuickScan = {
                                        if (!homeViewModel.quickScan()) {
                                            // No SAF folders yet: let the user grant one, then scan it.
                                            pickFolderLauncher.launch(null)
                                        }
                                    },
                                    onScanDevice = homeViewModel::scanDeviceStorage,
                                    onRequestStorageAccess = {
                                        if (StorageAccess.usesRuntimeDialog()) {
                                            requestLegacyStorageLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                                        } else {
                                            allFilesSettingsLauncher.launch(
                                                StorageAccess.allFilesAccessIntent(this@MainActivity),
                                            )
                                        }
                                    },
                                    onCancelScan = homeViewModel::cancelScan,
                                    onSearchChange = homeViewModel::updateSearchQuery,
                                    onFilterChange = homeViewModel::selectFilter,
                                    onSortChange = homeViewModel::selectSort,
                                    onLaunchGame = { record ->
                                        homeViewModel.launchGame(record) {
                                            destination = AppDestination.GAMEPLAY
                                        }
                                    },
                                    onRelinkGame = { record ->
                                        pendingRelinkRecord = record
                                        relinkRomLauncher.launch(arrayOf("*/*"))
                                    },
                                    onToggleFavorite = homeViewModel::toggleFavorite,
                                    onRemoveFromRecents = homeViewModel::removeFromRecents,
                                    onRemoveFromFavorites = homeViewModel::removeFromFavorites,
                                    onRenameGame = homeViewModel::renameGame,
                                    onPickCoverImage = { record ->
                                        pendingCoverRecord = record
                                        pickCoverLauncher.launch(arrayOf("image/*"))
                                    },
                                    onExportSram = { record ->
                                        pendingExportRecord = record
                                        exportSramLauncher.launch("${record.effectiveTitle}.sav")
                                    },
                                    onImportSram = { record ->
                                        pendingImportRecord = record
                                        importSramLauncher.launch(arrayOf("*/*"))
                                    },
                                    onRemoveFromLibrary = homeViewModel::removeFromLibrary,
                                    onDeleteAllSaves = homeViewModel::deleteAllSavesForGame,
                                    onDismissBanners = homeViewModel::dismissBanners,
                                    modifier = Modifier.padding(innerPadding),
                                )

                                MainTab.LAYOUT -> LayoutEditorScreen(
                                    preferences = homeState.preferences,
                                    onSavePortraitLayout = { profile ->
                                        scope.launch {
                                            appContainer.preferencesRepository.updatePortraitLayout(profile)
                                        }
                                    },
                                    onSaveLandscapeLayout = { profile ->
                                        scope.launch {
                                            appContainer.preferencesRepository.updateLandscapeLayout(profile)
                                        }
                                    },
                                    onToggleHaptics = { enabled ->
                                        scope.launch {
                                            appContainer.preferencesRepository.updateHapticsEnabled(enabled)
                                        }
                                    },
                                    onSaveShellColor = { color ->
                                        scope.launch {
                                            appContainer.preferencesRepository.updateShellColorHex(color)
                                        }
                                    },
                                    modifier = Modifier.padding(innerPadding),
                                )

                                MainTab.CONTROLLERS -> ControllersScreen(
                                    controllers = controllers,
                                    pressedButtons = controllerPressed,
                                    capturingBindingFor = capturingBinding,
                                    autoHideTouchWithController = homeState.preferences.autoHideTouchWithController,
                                    onStartCapturingBinding = appContainer.controllerManager::startCapturingBinding,
                                    onCancelCapturingBinding = appContainer.controllerManager::cancelCapturingBinding,
                                    onUpdateDeadzone = appContainer.controllerManager::updateDeadzone,
                                    onResetProfile = appContainer.controllerManager::resetProfileToDefault,
                                    onRemoveSavedProfile = appContainer.controllerManager::removeSavedProfile,
                                    onToggleAutoHideTouch = { enabled ->
                                        scope.launch {
                                            appContainer.preferencesRepository.updateAutoHideTouchWithController(enabled)
                                        }
                                    },
                                    modifier = Modifier.padding(innerPadding),
                                )

                                MainTab.EXTRAS -> ExtrasScreen(
                                    games = homeState.allGames,
                                    biosStatuses = biosStatuses,
                                    biosNotice = biosNotice,
                                    libraryRepository = appContainer.libraryRepository,
                                    saveStateRepository = appContainer.saveStateRepository,
                                    onImportBios = { type ->
                                        pendingBiosType = type
                                        importBiosLauncher.launch(arrayOf("*/*"))
                                    },
                                    onRemoveBios = { type ->
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                appContainer.biosRepository.removeBios(type)
                                                biosStatuses = appContainer.biosRepository.listStatuses()
                                            }
                                            biosNotice = "Removed custom ${type.label}. Built-in HLE BIOS is now active."
                                        }
                                    },
                                    onClearBiosNotice = { biosNotice = null },
                                    modifier = Modifier.padding(innerPadding),
                                )

                                MainTab.SETTINGS -> SettingsScreen(
                                    preferences = homeState.preferences,
                                    savedFolders = homeState.savedFolders,
                                    onUpdateAudioVolume = { vol ->
                                        scope.launch { appContainer.preferencesRepository.updateAudioVolume(vol) }
                                    },
                                    onUpdateMuted = { muted ->
                                        scope.launch { appContainer.preferencesRepository.updateMuted(muted) }
                                    },
                                    onUpdateScalingMode = { mode ->
                                        scope.launch { appContainer.preferencesRepository.updateScalingMode(mode) }
                                    },
                                    onUpdateOrientationLock = { lock ->
                                        scope.launch { appContainer.preferencesRepository.updateOrientationLock(lock) }
                                    },
                                    onUpdateReducedMotion = { enabled ->
                                        scope.launch { appContainer.preferencesRepository.updateReducedMotion(enabled) }
                                    },
                                    onRemoveSavedFolder = homeViewModel::removeSavedFolder,
                                    modifier = Modifier.padding(innerPadding),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val shouldRouteToGamepad = currentDestination == AppDestination.GAMEPLAY ||
            currentTab == MainTab.CONTROLLERS
        if (shouldRouteToGamepad && appContainer.controllerManager.handleKeyEvent(event)) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val shouldRouteToGamepad = currentDestination == AppDestination.GAMEPLAY ||
            currentTab == MainTab.CONTROLLERS
        if (shouldRouteToGamepad && appContainer.controllerManager.handleKeyEvent(event)) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val shouldRouteToGamepad = currentDestination == AppDestination.GAMEPLAY ||
            currentTab == MainTab.CONTROLLERS
        if (shouldRouteToGamepad && appContainer.controllerManager.handleGenericMotionEvent(event)) {
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun onPause() {
        super.onPause()
        appContainer.controllerManager.releaseAllInputs()
        activeGameplayVm?.onActivityPaused()
    }

    override fun onResume() {
        super.onResume()
        activeHomeVm?.refreshStorageAccess(StorageAccess.hasAccess(this))
        appContainer.controllerManager.refreshDevices()
        if (currentDestination == AppDestination.GAMEPLAY) {
            activeGameplayVm?.onActivityResumed()
        }
    }

    private fun applySystemBars(immersive: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        if (immersive) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}
