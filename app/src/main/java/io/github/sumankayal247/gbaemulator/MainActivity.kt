package io.github.sumankayal247.gbaemulator

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.sumankayal247.gbaemulator.domain.model.OrientationLock
import io.github.sumankayal247.gbaemulator.domain.model.RomRecord
import io.github.sumankayal247.gbaemulator.ui.gameplay.GameplayScreen
import io.github.sumankayal247.gbaemulator.ui.gameplay.GameplayViewModel
import io.github.sumankayal247.gbaemulator.ui.home.HomeScreen
import io.github.sumankayal247.gbaemulator.ui.home.HomeViewModel
import io.github.sumankayal247.gbaemulator.ui.settings.SettingsDialog
import io.github.sumankayal247.gbaemulator.ui.theme.GbaEmulatorTheme

private enum class AppDestination {
    HOME,
    GAMEPLAY,
}

class MainActivity : ComponentActivity() {

    private val appContainer: AppContainer
        get() = (application as GbaEmulatorApp).container

    private var activeGameplayVm: GameplayViewModel? = null
    private var currentDestination: AppDestination = AppDestination.HOME

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            GbaEmulatorTheme {
                val homeViewModel: HomeViewModel = viewModel(
                    factory = HomeViewModel.Factory(appContainer),
                )
                val gameplayViewModel: GameplayViewModel = viewModel(
                    factory = GameplayViewModel.Factory(appContainer),
                )
                activeGameplayVm = gameplayViewModel

                val homeState by homeViewModel.uiState.collectAsStateWithLifecycle()
                val gameplayState by gameplayViewModel.uiState.collectAsStateWithLifecycle()

                var destination by rememberSaveable { mutableStateOf(AppDestination.HOME) }
                var showSettings by rememberSaveable { mutableStateOf(false) }

                // Pending SAF targets for Relink / SRAM Export / SRAM Import
                var pendingRelinkRecord by remember { mutableStateOf<RomRecord?>(null) }
                var pendingExportRecord by remember { mutableStateOf<RomRecord?>(null) }
                var pendingImportRecord by remember { mutableStateOf<RomRecord?>(null) }
                var exportFromGameplay by remember { mutableStateOf(false) }

                // SAF OpenDocument launcher for selecting user-owned .gba / .gbc / .gb ROMs
                val openRomLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument(),
                ) { uri ->
                    if (uri != null) {
                        homeViewModel.openRomFromSaf(uri) {
                            destination = AppDestination.GAMEPLAY
                        }
                    }
                }

                // SAF OpenDocument launcher for relinking a moved/revoked ROM without losing saves
                val relinkRomLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument(),
                ) { uri ->
                    val target = pendingRelinkRecord
                    pendingRelinkRecord = null
                    if (uri != null && target != null) {
                        homeViewModel.relinkRomFromSaf(target, uri) {
                            destination = AppDestination.GAMEPLAY
                        }
                    }
                }

                // SAF CreateDocument launcher for exporting battery SRAM (.sav)
                val exportSramLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
                ) { uri ->
                    val target = pendingExportRecord
                    val fromGameplay = exportFromGameplay
                    pendingExportRecord = null
                    exportFromGameplay = false
                    if (uri != null) {
                        if (fromGameplay) {
                            gameplayViewModel.exportActiveSram(uri, contentResolver)
                        } else if (target != null) {
                            homeViewModel.exportSramToSaf(target, uri, contentResolver)
                        }
                    }
                }

                // SAF OpenDocument launcher for importing battery SRAM (.sav)
                val importSramLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument(),
                ) { uri ->
                    val target = pendingImportRecord
                    pendingImportRecord = null
                    if (uri != null && target != null) {
                        homeViewModel.importSramFromSaf(target, uri, contentResolver)
                    }
                }

                // Manage immersive system bars & orientation lock cleanly per destination.
                LaunchedEffect(destination, gameplayState.preferences.orientationLock) {
                    currentDestination = destination
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
                    AppDestination.HOME -> {
                        HomeScreen(
                            state = homeState,
                            onOpenRomClick = { openRomLauncher.launch(arrayOf("*/*")) },
                            onResumeActiveSession = {
                                destination = AppDestination.GAMEPLAY
                                gameplayViewModel.closeMenuAndResume()
                            },
                            onLaunchRecent = { record ->
                                homeViewModel.launchRecentGame(record) {
                                    destination = AppDestination.GAMEPLAY
                                }
                            },
                            onRelinkRom = { record ->
                                pendingRelinkRecord = record
                                relinkRomLauncher.launch(arrayOf("*/*"))
                            },
                            onExportSram = { record ->
                                pendingExportRecord = record
                                exportFromGameplay = false
                                exportSramLauncher.launch("${record.displayTitle}.sav")
                            },
                            onImportSram = { record ->
                                pendingImportRecord = record
                                importSramLauncher.launch(arrayOf("*/*"))
                            },
                            onRemoveRecent = { record ->
                                homeViewModel.removeFromRecentList(record)
                            },
                            onOpenSettings = { showSettings = true },
                            onDismissBanner = { homeViewModel.dismissBanner() },
                        )
                    }

                    AppDestination.GAMEPLAY -> {
                        GameplayScreen(
                            state = gameplayState,
                            onAttachSurface = gameplayViewModel::attachSurface,
                            onResizeSurface = gameplayViewModel::resizeSurface,
                            onDetachSurface = gameplayViewModel::detachSurface,
                            onTouchInputMask = gameplayViewModel::updateTouchInputMask,
                            onReleaseAllInputs = gameplayViewModel::releaseAllInputs,
                            onOpenMenu = gameplayViewModel::openMenu,
                            onCloseMenuAndResume = gameplayViewModel::closeMenuAndResume,
                            onSaveSramNow = gameplayViewModel::saveSramNow,
                            onExportSram = {
                                val title = gameplayState.session.activeRom?.displayTitle ?: "save"
                                exportFromGameplay = true
                                exportSramLauncher.launch("$title.sav")
                            },
                            onRestartGame = gameplayViewModel::restartGame,
                            onQuitToHome = {
                                gameplayViewModel.quitToHome {
                                    destination = AppDestination.HOME
                                    homeViewModel.refreshSourceAvailability()
                                }
                            },
                            onToggleMute = gameplayViewModel::toggleMute,
                            onToggleFastForward = gameplayViewModel::toggleFastForward,
                            onToggleScalingMode = gameplayViewModel::toggleScalingMode,
                            onCycleOrientationLock = gameplayViewModel::cycleOrientationLock,
                            onDismissToast = gameplayViewModel::dismissToast,
                        )
                    }
                }

                if (showSettings) {
                    SettingsDialog(
                        preferences = homeState.preferences,
                        coreVersion = homeState.session.coreVersion,
                        onScalingModeChange = homeViewModel::updateScalingMode,
                        onMutedChange = homeViewModel::updateMuted,
                        onVolumeChange = homeViewModel::updateAudioVolume,
                        onOrientationLockChange = homeViewModel::updateOrientationLock,
                        onControlsOpacityChange = homeViewModel::updateControlsOpacity,
                        onControlsScaleChange = homeViewModel::updateControlsScale,
                        onDismiss = { showSettings = false },
                    )
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        activeGameplayVm?.onActivityPaused()
    }

    override fun onResume() {
        super.onResume()
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
