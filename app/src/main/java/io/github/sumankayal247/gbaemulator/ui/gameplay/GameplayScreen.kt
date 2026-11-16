package io.github.sumankayal247.gbaemulator.ui.gameplay

import android.content.res.Configuration
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.sumankayal247.gbaemulator.domain.model.EmulatorState
import io.github.sumankayal247.gbaemulator.domain.model.VirtualButton
import io.github.sumankayal247.gbaemulator.emulator.TouchInputTracker
import io.github.sumankayal247.gbaemulator.ui.theme.EmulatorColors
import kotlinx.coroutines.delay

@Composable
fun GameplayScreen(
    state: GameplayUiState,
    onAttachSurface: (android.view.Surface, Int, Int) -> Unit,
    onResizeSurface: (Int, Int) -> Unit,
    onDetachSurface: () -> Unit,
    onTouchInputMask: (Int) -> Unit,
    onReleaseAllInputs: () -> Unit,
    onOpenMenu: () -> Unit,
    onCloseMenuAndResume: () -> Unit,
    onSaveSramNow: () -> Unit,
    onExportSram: () -> Unit,
    onRestartGame: () -> Unit,
    onQuitToHome: () -> Unit,
    onToggleMute: (Boolean) -> Unit,
    onToggleFastForward: (Boolean) -> Unit,
    onToggleScalingMode: () -> Unit,
    onCycleOrientationLock: () -> Unit,
    onDismissToast: () -> Unit,
) {
    val tracker = remember { TouchInputTracker() }
    var pressedButtons by remember { mutableStateOf<Set<VirtualButton>>(emptySet()) }

    val syncInput: () -> Unit = remember(tracker, onTouchInputMask) {
        {
            pressedButtons = tracker.pressedButtons()
            onTouchInputMask(tracker.currentBitMask())
        }
    }

    // Clear all held inputs whenever menu opens or screen leaves composition.
    LaunchedEffect(state.isMenuOpen) {
        if (state.isMenuOpen) {
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

    // Auto-dismiss transient save/status notices after 3.5 seconds.
    LaunchedEffect(state.toastMessage) {
        if (state.toastMessage != null) {
            delay(3_500L)
            onDismissToast()
        }
    }

    // Hardware back button opens pause menu instead of abruptly discarding gameplay.
    BackHandler {
        if (state.isMenuOpen) {
            onCloseMenuAndResume()
        } else {
            onOpenMenu()
        }
    }

    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val opacity = state.preferences.controlsOpacity
    val scale = state.preferences.controlsScale
    val shellBrush = Brush.verticalGradient(
        listOf(EmulatorColors.ShellPurpleLight, EmulatorColors.ShellPurple, EmulatorColors.ShellPurpleDark),
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(shellBrush)
            .safeDrawingPadding(),
    ) {
        if (state.session.state == EmulatorState.ERROR) {
            ErrorOverlay(
                message = state.session.errorMessage ?: "Emulator encountered an unexpected error.",
                onBackToHome = onQuitToHome,
            )
        } else if (isLandscape) {
            LandscapeHandheldLayout(
                tracker = tracker,
                pressedButtons = pressedButtons,
                onInputChanged = syncInput,
                opacity = opacity,
                scale = scale,
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
                opacity = opacity,
                scale = scale,
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

        // Transient status notice pill (e.g. SRAM saved / backup recovered / headphones paused)
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

        if (state.isMenuOpen) {
            InGameMenuDialog(
                gameTitle = state.session.activeRom?.displayTitle ?: "Game",
                muted = state.session.muted,
                fastForward = state.session.fastForward,
                scalingMode = state.session.scalingMode,
                orientationLock = state.preferences.orientationLock,
                onResume = onCloseMenuAndResume,
                onSaveSram = onSaveSramNow,
                onExportSram = onExportSram,
                onRestartConfirmed = onRestartGame,
                onQuitConfirmed = onQuitToHome,
                onToggleMute = onToggleMute,
                onToggleFastForward = onToggleFastForward,
                onToggleScalingMode = onToggleScalingMode,
                onCycleOrientationLock = onCycleOrientationLock,
            )
        }
    }
}

@Composable
private fun GameViewportSurface(
    onAttachSurface: (android.view.Surface, Int, Int) -> Unit,
    onResizeSurface: (Int, Int) -> Unit,
    onDetachSurface: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(EmulatorColors.ScreenBezel)
            .border(2.dp, Color(0xFF1F103D), RoundedCornerShape(18.dp))
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
 * Portrait handheld shell inspired by reference 01-gameplay-portrait.jpg.
 */
@Composable
private fun PortraitHandheldLayout(
    tracker: TouchInputTracker,
    pressedButtons: Set<VirtualButton>,
    onInputChanged: () -> Unit,
    opacity: Float,
    scale: Float,
    onOpenMenu: () -> Unit,
    screenViewport: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // Upper screen bezel (takes generous top region while preserving readability)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.48f),
        ) {
            screenViewport()
        }

        Spacer(modifier = Modifier.height(14.dp))

        // L / R Shoulder triggers row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            HandheldShoulderButton(
                button = VirtualButton.L,
                isLeftShoulder = true,
                tracker = tracker,
                isPressed = VirtualButton.L in pressedButtons,
                onInputChanged = onInputChanged,
                widthDp = (108f * scale).dp,
                heightDp = (36f * scale).dp,
                opacity = opacity,
            )
            HandheldShoulderButton(
                button = VirtualButton.R,
                isLeftShoulder = false,
                tracker = tracker,
                isPressed = VirtualButton.R in pressedButtons,
                onInputChanged = onInputChanged,
                widthDp = (108f * scale).dp,
                heightDp = (36f * scale).dp,
                opacity = opacity,
            )
        }

        // Main thumb controls: D-pad (left) and angled B/A (right)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.35f)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            HandheldDPad(
                tracker = tracker,
                pressedButtons = pressedButtons,
                onInputChanged = onInputChanged,
                sizeDp = (152f * scale).dp,
                opacity = opacity,
            )

            Box(
                modifier = Modifier.size((146f * scale).dp),
            ) {
                HandheldActionButton(
                    button = VirtualButton.A,
                    tracker = tracker,
                    isPressed = VirtualButton.A in pressedButtons,
                    onInputChanged = onInputChanged,
                    diameter = (68f * scale).dp,
                    opacity = opacity,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(y = 8.dp),
                )
                HandheldActionButton(
                    button = VirtualButton.B,
                    tracker = tracker,
                    isPressed = VirtualButton.B in pressedButtons,
                    onInputChanged = onInputChanged,
                    diameter = (68f * scale).dp,
                    opacity = opacity,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .offset(y = (-8).dp),
                )
            }
        }

        // Bottom row: Menu (left), Select & Start (center), Speaker grille (right)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            HandheldMenuButton(
                onClick = onOpenMenu,
                opacity = opacity,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                HandheldSmallTouchButton(
                    button = VirtualButton.SELECT,
                    tracker = tracker,
                    isPressed = VirtualButton.SELECT in pressedButtons,
                    onInputChanged = onInputChanged,
                    opacity = opacity,
                )
                HandheldSmallTouchButton(
                    button = VirtualButton.START,
                    tracker = tracker,
                    isPressed = VirtualButton.START in pressedButtons,
                    onInputChanged = onInputChanged,
                    opacity = opacity,
                )
            }
            HandheldSpeakerGrille()
        }
    }
}

/**
 * Landscape handheld shell inspired by reference 02-gameplay-landscape.jpg.
 */
@Composable
private fun LandscapeHandheldLayout(
    tracker: TouchInputTracker,
    pressedButtons: Set<VirtualButton>,
    onInputChanged: () -> Unit,
    opacity: Float,
    scale: Float,
    onOpenMenu: () -> Unit,
    screenViewport: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // Left side: L shoulder top, D-pad center, Menu bottom
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width((172f * scale).coerceAtMost(210f).dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            HandheldShoulderButton(
                button = VirtualButton.L,
                isLeftShoulder = true,
                tracker = tracker,
                isPressed = VirtualButton.L in pressedButtons,
                onInputChanged = onInputChanged,
                widthDp = (104f * scale).dp,
                heightDp = (34f * scale).dp,
                opacity = opacity,
                modifier = Modifier.align(Alignment.Start),
            )
            HandheldDPad(
                tracker = tracker,
                pressedButtons = pressedButtons,
                onInputChanged = onInputChanged,
                sizeDp = (142f * scale).dp,
                opacity = opacity,
            )
            HandheldMenuButton(
                onClick = onOpenMenu,
                opacity = opacity,
                modifier = Modifier.align(Alignment.Start),
            )
        }

        // Centered game viewport (non-overlapping controls, zero pixel stretching)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(1.35f, matchHeightConstraintsFirst = true),
            ) {
                screenViewport()
            }
        }

        // Right side: R shoulder top, angled A/B center, Select/Start bottom
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width((172f * scale).coerceAtMost(210f).dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                HandheldSpeakerGrille(modifier = Modifier.size(width = 48.dp, height = 34.dp))
                HandheldShoulderButton(
                    button = VirtualButton.R,
                    isLeftShoulder = false,
                    tracker = tracker,
                    isPressed = VirtualButton.R in pressedButtons,
                    onInputChanged = onInputChanged,
                    widthDp = (104f * scale).dp,
                    heightDp = (34f * scale).dp,
                    opacity = opacity,
                )
            }

            Box(modifier = Modifier.size((138f * scale).dp)) {
                HandheldActionButton(
                    button = VirtualButton.A,
                    tracker = tracker,
                    isPressed = VirtualButton.A in pressedButtons,
                    onInputChanged = onInputChanged,
                    diameter = (64f * scale).dp,
                    opacity = opacity,
                    modifier = Modifier.align(Alignment.TopEnd),
                )
                HandheldActionButton(
                    button = VirtualButton.B,
                    tracker = tracker,
                    isPressed = VirtualButton.B in pressedButtons,
                    onInputChanged = onInputChanged,
                    diameter = (64f * scale).dp,
                    opacity = opacity,
                    modifier = Modifier.align(Alignment.BottomStart),
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HandheldSmallTouchButton(
                    button = VirtualButton.SELECT,
                    tracker = tracker,
                    isPressed = VirtualButton.SELECT in pressedButtons,
                    onInputChanged = onInputChanged,
                    opacity = opacity,
                )
                HandheldSmallTouchButton(
                    button = VirtualButton.START,
                    tracker = tracker,
                    isPressed = VirtualButton.START in pressedButtons,
                    onInputChanged = onInputChanged,
                    opacity = opacity,
                )
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
        modifier = Modifier
            .fillMaxSize()
            .background(EmulatorColors.DarkBackground)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Unable to run ROM",
                style = MaterialTheme.typography.titleLarge,
                color = EmulatorColors.DangerRed,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = EmulatorColors.TextPrimary,
            )
            Button(onClick = onBackToHome) {
                Text("Return to Library")
            }
        }
    }
}
