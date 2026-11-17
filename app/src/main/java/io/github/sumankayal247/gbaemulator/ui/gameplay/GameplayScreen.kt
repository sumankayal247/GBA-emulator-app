package io.github.sumankayal247.gbaemulator.ui.gameplay

import android.content.res.Configuration
import android.view.Surface as AndroidSurface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.sumankayal247.gbaemulator.domain.model.CheatCodeEntry
import io.github.sumankayal247.gbaemulator.domain.model.EmulatorState
import io.github.sumankayal247.gbaemulator.domain.model.GameSystem
import io.github.sumankayal247.gbaemulator.domain.model.OrientationLock
import io.github.sumankayal247.gbaemulator.domain.model.TouchLayoutProfile
import io.github.sumankayal247.gbaemulator.domain.model.VirtualButton
import io.github.sumankayal247.gbaemulator.emulator.TouchInputTracker
import io.github.sumankayal247.gbaemulator.ui.theme.EmulatorColors
import kotlinx.coroutines.delay

@Composable
fun GameplayScreen(
    state: GameplayUiState,
    onAttachSurface: (AndroidSurface, Int, Int) -> Unit,
    onResizeSurface: (Int, Int) -> Unit,
    onDetachSurface: () -> Unit,
    onTouchInputMask: (Int) -> Unit,
    onReleaseAllInputs: () -> Unit,
    onOpenMenu: () -> Unit,
    onCloseMenuAndResume: () -> Unit,
    onOpenSaveStates: () -> Unit,
    onOpenLoadStates: () -> Unit,
    onOpenCoreOptions: () -> Unit,
    onOpenCheats: () -> Unit,
    onBackToMainMenu: () -> Unit,
    onSaveStateToSlot: (Int) -> Unit,
    onLoadStateFromSlot: (Int) -> Unit,
    onDeleteSaveStateSlot: (Int) -> Unit,
    onSetCoreOption: (key: String, value: String, perGame: Boolean) -> Unit,
    onAddCheat: (description: String, code: String) -> Unit,
    onUpdateCheat: (entry: CheatCodeEntry, description: String, code: String) -> Unit,
    onToggleCheat: (id: Long, enabled: Boolean) -> Unit,
    onDeleteCheat: (id: Long) -> Unit,
    onFlushBatterySave: () -> Unit,
    onRestartGame: () -> Unit,
    onQuitToHome: () -> Unit,
    onToggleMute: (Boolean) -> Unit,
    onToggleFastForward: (Boolean) -> Unit,
    onToggleOrientationLock: (locked: Boolean, currentIsLandscape: Boolean) -> Unit,
    onToggleScalingMode: () -> Unit,
    onToggleAutoHideTouch: (Boolean) -> Unit,
    onDismissToast: () -> Unit,
) {
    val tracker = remember { TouchInputTracker() }
    var pressedButtons by remember { mutableStateOf<Set<VirtualButton>>(emptySet()) }
    val haptics = LocalHapticFeedback.current

    val syncInput: () -> Unit = remember(tracker, onTouchInputMask, state.preferences.hapticsEnabled) {
        {
            val previous = pressedButtons
            val next = tracker.pressedButtons()
            if (state.preferences.hapticsEnabled && (next - previous).isNotEmpty()) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
            pressedButtons = next
            onTouchInputMask(tracker.currentBitMask())
        }
    }

    LaunchedEffect(state.isAnyDialogOpen) {
        if (state.isAnyDialogOpen) {
            tracker.releaseAll()
            pressedButtons = emptySet()
            onReleaseAllInputs()
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            tracker.releaseAll()
            onReleaseAllInputs()
        }
    }

    LaunchedEffect(state.toastMessage) {
        if (state.toastMessage != null) {
            delay(3_500L)
            onDismissToast()
        }
    }

    BackHandler {
        when (state.activeDialog) {
            ActiveGameplayDialog.NONE -> onOpenMenu()
            ActiveGameplayDialog.MAIN_MENU -> onCloseMenuAndResume()
            ActiveGameplayDialog.SAVE_STATES,
            ActiveGameplayDialog.LOAD_STATES,
            ActiveGameplayDialog.CORE_OPTIONS,
            ActiveGameplayDialog.CHEATS,
            -> onBackToMainMenu()
        }
    }

    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val layoutProfile = (if (isLandscape) state.preferences.landscapeLayout else state.preferences.portraitLayout).clamped()

    // Let the user bring the touch controls back while a controller is active.
    // Pressing any controller button again (gamepadInputSignal bump) re-hides them.
    var userForcedShowControls by remember { mutableStateOf(false) }
    LaunchedEffect(state.gamepadInputSignal) {
        if (state.gamepadInputSignal > 0L) {
            userForcedShowControls = false
        }
    }

    // Only hide touch controls after the user has *actually used* a controller,
    // and only in landscape. Hiding on mere device presence (the old behaviour)
    // left the game with no visible controls at all when a phone exposed a
    // built-in/virtual joystick device, and fullscreen portrait is unplayably
    // small for the landscape GBA screen anyway.
    val controllerActivelyUsed = state.hasConnectedController && state.hasReceivedGamepadInput
    val hideTouchForController = controllerActivelyUsed &&
        state.preferences.autoHideTouchWithController &&
        isLandscape &&
        !userForcedShowControls

    val shellPalette = remember(state.preferences.shellColorHex) {
        handheldPalette(state.preferences.shellColorHex)
    }
    val shellBrush = handheldShellBrush(shellPalette)

    CompositionLocalProvider(LocalHandheldPalette provides shellPalette) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(if (hideTouchForController) Color.Black else Color.Transparent)
                .then(if (hideTouchForController) Modifier else Modifier.background(shellBrush))
                .safeDrawingPadding(),
        ) {
            if (state.session.state == EmulatorState.ERROR) {
                ErrorOverlay(
                    message = state.session.errorMessage ?: "Emulator encountered an unexpected error.",
                    onBackToHome = onQuitToHome,
                )
            } else if (hideTouchForController) {
                // Full-screen viewport in landscape after the user has actually used
                // an external controller & touch auto-hide is on. Menu and Controls
                // pills stay visible in the top-end corner so touch controls can
                // always be brought back without leaving the game.
                Box(modifier = Modifier.fillMaxSize()) {
                    GameViewportSurface(
                        onAttachSurface = onAttachSurface,
                        onResizeSurface = onResizeSurface,
                        onDetachSurface = onDetachSurface,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp),
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(14.dp),
                    ) {
                        TopPillButton(
                            icon = Icons.Filled.SportsEsports,
                            label = "Controls",
                            contentDescription = "Show touch controls",
                            onClick = { userForcedShowControls = true },
                        )
                        TopPillButton(
                            icon = Icons.Filled.Menu,
                            label = "Menu",
                            contentDescription = "Open game menu",
                            onClick = onOpenMenu,
                        )
                    }
                }
            } else if (isLandscape) {
                LandscapeHandheldLayout(
                    tracker = tracker,
                    pressedButtons = pressedButtons,
                    onInputChanged = syncInput,
                    profile = layoutProfile,
                    onOpenMenu = onOpenMenu,
                    screenViewport = {
                        GameViewportSurface(
                            onAttachSurface = onAttachSurface,
                            onResizeSurface = onResizeSurface,
                            onDetachSurface = onDetachSurface,
                            modifier = Modifier.fillMaxSize(),
                        )
                    },
                )
            } else {
                PortraitHandheldLayout(
                    tracker = tracker,
                    pressedButtons = pressedButtons,
                    onInputChanged = syncInput,
                    profile = layoutProfile,
                    onOpenMenu = onOpenMenu,
                    screenViewport = {
                        GameViewportSurface(
                            onAttachSurface = onAttachSurface,
                            onResizeSurface = onResizeSurface,
                            onDetachSurface = onDetachSurface,
                            modifier = Modifier.fillMaxSize(),
                        )
                    },
                )
            }

            val toast = state.toastMessage
            if (toast != null) {
                Surface(
                    color = EmulatorColors.DarkSurface.copy(alpha = 0.92f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp, start = 20.dp, end = 20.dp),
                ) {
                    Text(
                        text = toast,
                        style = MaterialTheme.typography.bodySmall,
                        color = EmulatorColors.TextPrimary,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }

            val activeRom = state.session.activeRom
            val activeTitle = activeRom?.effectiveTitle ?: "Game"

            when (state.activeDialog) {
                ActiveGameplayDialog.NONE -> Unit

                ActiveGameplayDialog.MAIN_MENU -> InGameMenuDialog(
                    gameTitle = activeTitle,
                    muted = state.session.muted,
                    fastForward = state.session.fastForward,
                    orientationLocked = state.preferences.orientationLock != OrientationLock.AUTO,
                    autoHideTouchWithController = state.preferences.autoHideTouchWithController,
                    scalingMode = state.session.scalingMode,
                    coreVersion = state.session.coreVersion,
                    onOpenSaveStates = onOpenSaveStates,
                    onOpenLoadStates = onOpenLoadStates,
                    onOpenCoreOptions = onOpenCoreOptions,
                    onOpenCheats = onOpenCheats,
                    onFlushBatterySave = onFlushBatterySave,
                    onRestartConfirmed = onRestartGame,
                    onQuitConfirmed = onQuitToHome,
                    onToggleMute = onToggleMute,
                    onToggleFastForward = onToggleFastForward,
                    onToggleOrientationLock = { locked -> onToggleOrientationLock(locked, isLandscape) },
                    onToggleScalingMode = onToggleScalingMode,
                    onToggleAutoHideTouch = onToggleAutoHideTouch,
                    onDismiss = onCloseMenuAndResume,
                )

                ActiveGameplayDialog.SAVE_STATES -> SaveStateDialog(
                    mode = SaveStateDialogMode.SAVE,
                    gameTitle = activeTitle,
                    slots = state.saveStateSlots,
                    onSelectSlot = onSaveStateToSlot,
                    onDeleteSlot = onDeleteSaveStateSlot,
                    onDismiss = onBackToMainMenu,
                )

                ActiveGameplayDialog.LOAD_STATES -> SaveStateDialog(
                    mode = SaveStateDialogMode.LOAD,
                    gameTitle = activeTitle,
                    slots = state.saveStateSlots,
                    onSelectSlot = onLoadStateFromSlot,
                    onDeleteSlot = onDeleteSaveStateSlot,
                    onDismiss = onBackToMainMenu,
                )

                ActiveGameplayDialog.CORE_OPTIONS -> CoreOptionsDialog(
                    options = state.session.coreOptions,
                    coreVersion = state.session.coreVersion,
                    onSelectOptionValue = onSetCoreOption,
                    onDismiss = onBackToMainMenu,
                )

                ActiveGameplayDialog.CHEATS -> CheatCodeDialog(
                    gameTitle = activeTitle,
                    system = activeRom?.system ?: GameSystem.GBA,
                    cheats = state.activeRomCheats,
                    onAddCheat = onAddCheat,
                    onUpdateCheat = onUpdateCheat,
                    onToggleCheat = onToggleCheat,
                    onDeleteCheat = onDeleteCheat,
                    onDismiss = onBackToMainMenu,
                )
            }
        }
    }
}

@Composable
private fun TopPillButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Surface(
        color = EmulatorColors.DarkSurface.copy(alpha = 0.72f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Button
            }
            .clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = EmulatorColors.TextPrimary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = EmulatorColors.TextPrimary,
            )
        }
    }
}

@Composable
private fun GameViewportSurface(
    onAttachSurface: (AndroidSurface, Int, Int) -> Unit,
    onResizeSurface: (Int, Int) -> Unit,
    onDetachSurface: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalHandheldPalette.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(palette.bezel)
            .border(2.dp, palette.shellOutline, RoundedCornerShape(18.dp))
            .padding(6.dp),
    ) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(10.dp)),
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(
                        object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                val frame = holder.surfaceFrame
                                onAttachSurface(holder.surface, frame.width(), frame.height())
                            }

                            override fun surfaceChanged(
                                holder: SurfaceHolder,
                                format: Int,
                                width: Int,
                                height: Int,
                            ) {
                                onResizeSurface(width, height)
                            }

                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                onDetachSurface()
                            }
                        },
                    )
                }
            },
        )
    }
}

@Composable
internal fun PortraitHandheldLayout(
    tracker: TouchInputTracker,
    pressedButtons: Set<VirtualButton>,
    onInputChanged: () -> Unit,
    profile: TouchLayoutProfile,
    onOpenMenu: () -> Unit,
    interactive: Boolean = true,
    screenViewport: @Composable () -> Unit,
) {
    val opacity = profile.opacity
    val scale = profile.scale

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.50f),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.42f, matchHeightConstraintsFirst = false),
            ) {
                screenViewport()
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .offset(y = profile.shouldersOffsetYdp.dp)
                .padding(horizontal = 6.dp),
        ) {
            HandheldShoulderButton(
                button = VirtualButton.L,
                isLeftShoulder = true,
                tracker = tracker,
                isPressed = VirtualButton.L in pressedButtons,
                onInputChanged = onInputChanged,
                interactive = interactive,
                opacity = opacity,
                widthDp = (104f * scale).dp,
                heightDp = (34f * scale).dp,
            )
            HandheldShoulderButton(
                button = VirtualButton.R,
                isLeftShoulder = false,
                tracker = tracker,
                isPressed = VirtualButton.R in pressedButtons,
                onInputChanged = onInputChanged,
                interactive = interactive,
                opacity = opacity,
                widthDp = (104f * scale).dp,
                heightDp = (34f * scale).dp,
            )
        }

        Spacer(Modifier.height(16.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.32f)
                .padding(horizontal = 8.dp),
        ) {
            HandheldDPad(
                tracker = tracker,
                pressedButtons = pressedButtons,
                onInputChanged = onInputChanged,
                interactive = interactive,
                opacity = opacity,
                sizeDp = (146f * scale).dp,
                modifier = Modifier.offset(
                    x = profile.dpadOffsetXdp.dp,
                    y = profile.dpadOffsetYdp.dp,
                ),
            )

            Box(
                modifier = Modifier
                    .width((154f * scale).dp)
                    .height((128f * scale).dp)
                    .offset(
                        x = profile.buttonsOffsetXdp.dp,
                        y = profile.buttonsOffsetYdp.dp,
                    ),
            ) {
                HandheldActionButton(
                    button = VirtualButton.B,
                    tracker = tracker,
                    isPressed = VirtualButton.B in pressedButtons,
                    onInputChanged = onInputChanged,
                    interactive = interactive,
                    opacity = opacity,
                    diameter = (68f * scale).dp,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .offset(x = 2.dp, y = (-4).dp),
                )
                HandheldActionButton(
                    button = VirtualButton.A,
                    tracker = tracker,
                    isPressed = VirtualButton.A in pressedButtons,
                    onInputChanged = onInputChanged,
                    interactive = interactive,
                    opacity = opacity,
                    diameter = (68f * scale).dp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = (-2).dp, y = 4.dp),
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            HandheldMenuButton(
                interactive = interactive,
                opacity = opacity,
                onClick = onOpenMenu,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                HandheldSmallTouchButton(
                    button = VirtualButton.SELECT,
                    tracker = tracker,
                    isPressed = VirtualButton.SELECT in pressedButtons,
                    onInputChanged = onInputChanged,
                    interactive = interactive,
                    opacity = opacity,
                )
                HandheldSmallTouchButton(
                    button = VirtualButton.START,
                    tracker = tracker,
                    isPressed = VirtualButton.START in pressedButtons,
                    onInputChanged = onInputChanged,
                    interactive = interactive,
                    opacity = opacity,
                )
            }

            HandheldSpeakerGrille(
                modifier = Modifier.size(width = 76.dp, height = 52.dp),
            )
        }
    }
}

@Composable
internal fun LandscapeHandheldLayout(
    tracker: TouchInputTracker,
    pressedButtons: Set<VirtualButton>,
    onInputChanged: () -> Unit,
    profile: TouchLayoutProfile,
    onOpenMenu: () -> Unit,
    interactive: Boolean = true,
    screenViewport: @Composable () -> Unit,
) {
    val opacity = profile.opacity
    val scale = profile.scale

    Box(modifier = Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxHeight()
                    .width((164f * scale).dp)
                    .padding(vertical = 8.dp),
            ) {
                HandheldShoulderButton(
                    button = VirtualButton.L,
                    isLeftShoulder = true,
                    tracker = tracker,
                    isPressed = VirtualButton.L in pressedButtons,
                    onInputChanged = onInputChanged,
                    interactive = interactive,
                    opacity = opacity,
                    widthDp = (104f * scale).dp,
                    heightDp = (32f * scale).dp,
                    modifier = Modifier.offset(y = profile.shouldersOffsetYdp.dp),
                )

                HandheldDPad(
                    tracker = tracker,
                    pressedButtons = pressedButtons,
                    onInputChanged = onInputChanged,
                    interactive = interactive,
                    opacity = opacity,
                    sizeDp = (138f * scale).dp,
                    modifier = Modifier.offset(
                        x = profile.dpadOffsetXdp.dp,
                        y = profile.dpadOffsetYdp.dp,
                    ),
                )

                // Reference 02: MENU alone at bottom-left (SELECT lives on the right).
                HandheldMenuButton(
                    interactive = interactive,
                    opacity = opacity,
                    onClick = onOpenMenu,
                )
            }

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                screenViewport()
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxHeight()
                    .width((164f * scale).dp)
                    .padding(vertical = 8.dp),
            ) {
                HandheldShoulderButton(
                    button = VirtualButton.R,
                    isLeftShoulder = false,
                    tracker = tracker,
                    isPressed = VirtualButton.R in pressedButtons,
                    onInputChanged = onInputChanged,
                    interactive = interactive,
                    opacity = opacity,
                    widthDp = (104f * scale).dp,
                    heightDp = (32f * scale).dp,
                    modifier = Modifier.offset(y = profile.shouldersOffsetYdp.dp),
                )

                // Reference 02: speaker grille at top-right, below R, above A/B.
                HandheldSpeakerGrille()

                Box(
                    modifier = Modifier
                        .width((148f * scale).dp)
                        .height((122f * scale).dp)
                        .offset(
                            x = profile.buttonsOffsetXdp.dp,
                            y = profile.buttonsOffsetYdp.dp,
                        ),
                ) {
                    HandheldActionButton(
                        button = VirtualButton.B,
                        tracker = tracker,
                        isPressed = VirtualButton.B in pressedButtons,
                        onInputChanged = onInputChanged,
                        interactive = interactive,
                        opacity = opacity,
                        diameter = (64f * scale).dp,
                        modifier = Modifier.align(Alignment.BottomStart),
                    )
                    HandheldActionButton(
                        button = VirtualButton.A,
                        tracker = tracker,
                        isPressed = VirtualButton.A in pressedButtons,
                        onInputChanged = onInputChanged,
                        interactive = interactive,
                        opacity = opacity,
                        diameter = (64f * scale).dp,
                        modifier = Modifier.align(Alignment.TopEnd),
                    )
                }

                // Reference 02: SELECT + START together at bottom-right.
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    HandheldSmallTouchButton(
                        button = VirtualButton.SELECT,
                        tracker = tracker,
                        isPressed = VirtualButton.SELECT in pressedButtons,
                        onInputChanged = onInputChanged,
                        interactive = interactive,
                        opacity = opacity,
                    )
                    HandheldSmallTouchButton(
                        button = VirtualButton.START,
                        tracker = tracker,
                        isPressed = VirtualButton.START in pressedButtons,
                        onInputChanged = onInputChanged,
                        interactive = interactive,
                        opacity = opacity,
                    )
                }
            }
        }
    }
}

@Composable
private fun ErrorOverlay(
    message: String,
    onBackToHome: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(EmulatorColors.DarkBackground.copy(alpha = 0.94f))
            .padding(24.dp),
    ) {
        Surface(
            color = EmulatorColors.CardSurface,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp),
            ) {
                Text(
                    text = "Unable to run ROM",
                    style = MaterialTheme.typography.titleLarge,
                    color = EmulatorColors.ErrorRed,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = EmulatorColors.TextPrimary,
                )
                Spacer(Modifier.height(18.dp))
                Button(onClick = onBackToHome) {
                    Text("Return to Library")
                }
            }
        }
    }
}
