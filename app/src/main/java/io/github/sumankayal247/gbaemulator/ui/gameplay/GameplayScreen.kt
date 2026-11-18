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
import io.github.sumankayal247.gbaemulator.domain.model.ControlAnchorsDefaults
import io.github.sumankayal247.gbaemulator.domain.model.ControlPlacement
import io.github.sumankayal247.gbaemulator.domain.model.ControlSlot
import io.github.sumankayal247.gbaemulator.domain.model.ControlZone
import io.github.sumankayal247.gbaemulator.domain.model.EmulatorState
import io.github.sumankayal247.gbaemulator.domain.model.GameSystem
import io.github.sumankayal247.gbaemulator.domain.model.OrientationLock
import io.github.sumankayal247.gbaemulator.domain.model.ResolvedControl
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

/**
 * The region of the handheld that controls may be dragged within, in device dp.
 *
 * Sharing one function between gameplay and the layout editor is what guarantees the control you
 * drag in the editor lands in the same place in game. The zoom-out preview measures at real device
 * dp and scales, so both paths resolve against identical numbers.
 */
internal fun portraitControlZone(windowWidthDp: Float, windowHeightDp: Float): ControlZone {
    val bezel = portraitBezelHeightDpForWindow(windowWidthDp, windowHeightDp)
    val height = (windowHeightDp - bezel - PORTRAIT_CHROME_DP).coerceAtLeast(1f)
    val width = (windowWidthDp - 2f * PORTRAIT_HORIZONTAL_INSET_DP).coerceAtLeast(1f)
    return ControlZone(width, height)
}

/** Landscape lets controls use the whole window; the screen floats in the middle. */
internal fun landscapeControlZone(windowWidthDp: Float, windowHeightDp: Float): ControlZone =
    ControlZone(
        (windowWidthDp - 2f * LANDSCAPE_INSET_DP).coerceAtLeast(1f),
        (windowHeightDp - 2f * LANDSCAPE_INSET_DP).coerceAtLeast(1f),
    )

/** Portrait control sizes at `scale = 1.0`. */
private const val PORTRAIT_DPAD_DP = 146f

/** Control sizes at `scale = 1.0`, matched to the reference handheld's proportions. */
internal const val PORTRAIT_ACTION_DP = 68f
internal const val LANDSCAPE_DPAD_DP = 138f
internal const val LANDSCAPE_ACTION_DP = 64f
internal const val SHOULDER_WIDTH_DP = 104f
internal const val SHOULDER_HEIGHT_DP = 36f

/** Landscape keeps the screen clear of the side control columns. */
internal const val LANDSCAPE_SCREEN_INSET_DP = 96f

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
    val configuration = LocalConfiguration.current
    val windowWidth = configuration.screenWidthDp.toFloat()
    val windowHeight = configuration.screenHeightDp.toFloat()
    val scale = portraitControlScale(profile.scale, windowWidth, windowHeight)
    val zone = portraitControlZone(windowWidth, windowHeight)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = PORTRAIT_HORIZONTAL_INSET_DP.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Bezel-framed screen, flush to the top. The frame keeps the reference aspect while the
        // game inside is letterboxed at its native 3:2, so nothing is stretched.
        BezelScreenFrame(
            modifier = Modifier
                .fillMaxWidth()
                .height(portraitBezelHeightDpForWindow(windowWidth, windowHeight).dp),
        ) {
            screenViewport()
        }

        Spacer(Modifier.height(6.dp))

        // Free-form control zone. Controls are absolutely placed by fraction, which is what
        // removes the old dead band: nothing here is pinned to the bottom of the column.
        HandheldControlZone(
            zone = zone,
            profile = profile,
            landscape = false,
            scale = scale,
            tracker = tracker,
            pressedButtons = pressedButtons,
            onInputChanged = onInputChanged,
            onOpenMenu = onOpenMenu,
            interactive = interactive,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )

        // Speaker engraving along the bottom edge of the shell.
        HandheldLabelRow()
    }
}

/**
 * Absolutely positions every control inside [zone] from the profile's fractional anchors.
 *
 * One box per slot, placed with [Modifier.offset] in dp against a measured parent. Using offset
 * rather than a layout pass keeps the controls independent of each other: dragging the D-pad
 * cannot reflow the buttons, which is what made the old Row/Column arrangement impossible to
 * rearrange freely.
 */
@Composable
internal fun HandheldControlZone(
    zone: ControlZone,
    profile: TouchLayoutProfile,
    landscape: Boolean,
    scale: Float,
    tracker: TouchInputTracker,
    pressedButtons: Set<VirtualButton>,
    onInputChanged: () -> Unit,
    onOpenMenu: () -> Unit,
    interactive: Boolean,
    modifier: Modifier = Modifier,
) {
    val opacity = profile.opacity
    val resolved = ControlPlacement.resolveAll(
        zone = zone,
        anchors = profile.anchors,
        defaults = ControlAnchorsDefaults.forOrientation(landscape),
        scale = scale,
    )
    val dpadSize = if (landscape) LANDSCAPE_DPAD_DP else PORTRAIT_DPAD_DP
    val actionDiameter = if (landscape) LANDSCAPE_ACTION_DP else PORTRAIT_ACTION_DP

    Box(modifier = modifier) {
        // Shoulders.
        SlotBox(resolved, ControlSlot.SHOULDER_L) {
            HandheldShoulderButton(
                button = VirtualButton.L, isLeftShoulder = true, tracker = tracker,
                isPressed = VirtualButton.L in pressedButtons, onInputChanged = onInputChanged,
                interactive = interactive, opacity = opacity,
                widthDp = (SHOULDER_WIDTH_DP * scale).dp, heightDp = (SHOULDER_HEIGHT_DP * scale).dp,
            )
        }
        SlotBox(resolved, ControlSlot.SHOULDER_R) {
            HandheldShoulderButton(
                button = VirtualButton.R, isLeftShoulder = false, tracker = tracker,
                isPressed = VirtualButton.R in pressedButtons, onInputChanged = onInputChanged,
                interactive = interactive, opacity = opacity,
                widthDp = (SHOULDER_WIDTH_DP * scale).dp, heightDp = (SHOULDER_HEIGHT_DP * scale).dp,
            )
        }

        // D-pad.
        SlotBox(resolved, ControlSlot.DPAD) {
            HandheldDPad(
                tracker = tracker, pressedButtons = pressedButtons,
                onInputChanged = onInputChanged, interactive = interactive, opacity = opacity,
                sizeDp = (dpadSize * scale).dp,
            )
        }

        // Action buttons.
        SlotBox(resolved, ControlSlot.BUTTON_A) {
            HandheldActionButton(
                button = VirtualButton.A, tracker = tracker,
                isPressed = VirtualButton.A in pressedButtons, onInputChanged = onInputChanged,
                interactive = interactive, opacity = opacity,
                diameter = (actionDiameter * scale).dp,
            )
        }
        SlotBox(resolved, ControlSlot.BUTTON_B) {
            HandheldActionButton(
                button = VirtualButton.B, tracker = tracker,
                isPressed = VirtualButton.B in pressedButtons, onInputChanged = onInputChanged,
                interactive = interactive, opacity = opacity,
                diameter = (actionDiameter * scale).dp,
            )
        }

        // Small buttons.
        SlotBox(resolved, ControlSlot.MENU) {
            HandheldMenuButton(interactive = interactive, opacity = opacity, onClick = onOpenMenu)
        }
        SlotBox(resolved, ControlSlot.SELECT) {
            HandheldSmallTouchButton(
                button = VirtualButton.SELECT, tracker = tracker,
                isPressed = VirtualButton.SELECT in pressedButtons, onInputChanged = onInputChanged,
                interactive = interactive, opacity = opacity,
            )
        }
        SlotBox(resolved, ControlSlot.START) {
            HandheldSmallTouchButton(
                button = VirtualButton.START, tracker = tracker,
                isPressed = VirtualButton.START in pressedButtons, onInputChanged = onInputChanged,
                interactive = interactive, opacity = opacity,
            )
        }
    }
}

/** Places one slot's content centred on its resolved position. */
@Composable
private fun SlotBox(
    resolved: Map<ControlSlot, ResolvedControl>,
    slot: ControlSlot,
    content: @Composable () -> Unit,
) {
    val r = resolved.getValue(slot)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .offset(
                x = (r.centerXDp - r.halfWidthDp).dp,
                y = (r.centerYDp - r.halfHeightDp).dp,
            )
            .size(width = (r.halfWidthDp * 2f).dp, height = (r.halfHeightDp * 2f).dp),
    ) {
        content()
    }
}

/**
 * The black screen frame.
 *
 * Sized to [SCREEN_BEZEL_ASPECT], which is taller than the game's 3:2. The viewport is centred
 * inside it and constrained to [GBA_SCREEN_ASPECT], so the extra height becomes top and bottom
 * bezel exactly as on the reference handheld, and the 240x160 image is never distorted.
 */
@Composable
private fun BezelScreenFrame(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val palette = LocalHandheldPalette.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(palette.bezel),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(GBA_SCREEN_ASPECT),
        ) {
            content()
        }
    }
}

/** Speaker engraving along the bottom of the shell, mirroring the reference hardware. */
@Composable
private fun HandheldLabelRow() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        HandheldSpeakerGrille(modifier = Modifier.size(width = 76.dp, height = 40.dp))
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
    val configuration = LocalConfiguration.current
    val windowWidth = configuration.screenWidthDp.toFloat()
    val windowHeight = configuration.screenHeightDp.toFloat()
    val scale = profile.scale
    val zone = landscapeControlZone(windowWidth, windowHeight)

    Box(modifier = Modifier.fillMaxSize()) {
        // Screen centred behind the controls, letterboxed at the native ratio.
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = LANDSCAPE_SCREEN_INSET_DP.dp, vertical = 8.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(GBA_SCREEN_ASPECT)
                    .clip(RoundedCornerShape(10.dp)),
            ) {
                screenViewport()
            }
        }

        HandheldControlZone(
            zone = zone,
            profile = profile,
            landscape = true,
            scale = scale,
            tracker = tracker,
            pressedButtons = pressedButtons,
            onInputChanged = onInputChanged,
            onOpenMenu = onOpenMenu,
            interactive = interactive,
            modifier = Modifier
                .fillMaxSize()
                .padding(LANDSCAPE_INSET_DP.dp),
        )
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
