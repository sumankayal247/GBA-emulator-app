package io.github.sumankayal247.gbaemulator.ui.gameplay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import io.github.sumankayal247.gbaemulator.domain.model.ShellColors
import io.github.sumankayal247.gbaemulator.domain.model.ShellRgbColor
import io.github.sumankayal247.gbaemulator.ui.theme.EmulatorColors

/**
 * One palette drives the shell, engraved labels, screen outline, and button chrome in both
 * gameplay and the layout editor. Keeping it in one place prevents the editor preview from
 * drifting away from the real handheld.
 */
data class HandheldPalette(
    val shellLight: Color,
    val shell: Color,
    val shellDark: Color,
    val shellOutline: Color,
    val shellLabel: Color,
    val shellLabelHighlight: Color,
    val speakerHighlight: Color,
    val bezel: Color,
    val buttonOutline: Color,
    val buttonRimLight: Color,
    val buttonRimMid: Color,
    val buttonRimDark: Color,
    val buttonHighlight: Color,
    val buttonFace: Color,
    val buttonFacePressed: Color,
    val buttonShadow: Color,
    val buttonLabel: Color,
    val buttonLabelHighlight: Color,
    val castShadow: Color,
)

val LocalHandheldPalette = staticCompositionLocalOf { classicHandheldPalette() }

fun classicHandheldPalette(): HandheldPalette = handheldPalette(ShellColors.DEFAULT_HEX)

fun handheldPalette(rawHex: String): HandheldPalette {
    val base = rgbToComposeColor(ShellColors.hexToRgb(rawHex))
    val black = Color(0f, 0f, 0f)
    val white = Color(1f, 1f, 1f)
    val luminance = 0.2126f * base.red + 0.7152f * base.green + 0.0722f * base.blue
    val isVeryDarkShell = luminance < 0.13f

    return HandheldPalette(
        shellLight = blend(base, white, 0.20f),
        shell = base,
        shellDark = blend(base, black, 0.30f),
        shellOutline = blend(base, black, 0.72f),
        shellLabel = if (isVeryDarkShell) {
            blend(base, white, 0.76f)
        } else {
            blend(base, black, 0.42f)
        },
        shellLabelHighlight = if (isVeryDarkShell) {
            black.copy(alpha = 0.42f)
        } else {
            white.copy(alpha = 0.30f)
        },
        speakerHighlight = white.copy(alpha = if (isVeryDarkShell) 0.20f else 0.16f),
        bezel = EmulatorColors.ScreenBezel,
        buttonOutline = blend(base, black, 0.70f),
        buttonRimLight = Color(0.98f, 0.99f, 1f),
        buttonRimMid = Color(0.72f, 0.76f, 0.83f),
        buttonRimDark = Color(0.42f, 0.47f, 0.55f),
        buttonHighlight = Color(0.98f, 0.99f, 1f),
        buttonFace = Color(0.84f, 0.87f, 0.92f),
        buttonFacePressed = Color(0.63f, 0.68f, 0.76f),
        buttonShadow = Color(0.48f, 0.53f, 0.61f),
        buttonLabel = Color(0.34f, 0.37f, 0.43f),
        buttonLabelHighlight = white.copy(alpha = 0.72f),
        castShadow = black.copy(alpha = 0.38f),
    )
}

fun handheldShellBrush(palette: HandheldPalette): Brush = Brush.verticalGradient(
    listOf(palette.shellLight, palette.shell, palette.shellDark),
)

@Composable
fun ProvideHandheldPalette(
    shellColorHex: String,
    content: @Composable () -> Unit,
) {
    val palette = remember(shellColorHex) { handheldPalette(shellColorHex) }
    CompositionLocalProvider(LocalHandheldPalette provides palette) {
        content()
    }
}

/**
 * A neutral, non-emulating stand-in for the SurfaceView in the layout editor. It uses the
 * same bezel shape and sizing as [GameplayScreen]'s viewport so the preview is dimensionally
 * faithful without starting a second emulator surface.
 */
@Composable
fun HandheldScreenPlaceholder(modifier: Modifier = Modifier) {
    val palette = LocalHandheldPalette.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(palette.bezel)
            .border(2.dp, palette.shellOutline, RoundedCornerShape(18.dp))
            .padding(6.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(10.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0.02f, 0.02f, 0.03f),
                            Color(0.06f, 0.05f, 0.09f),
                            Color(0f, 0f, 0f),
                        ),
                    ),
                )
                .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(10.dp)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.White.copy(alpha = 0.055f), Color.Transparent),
                        ),
                    ),
            )
        }
    }
}

private fun blend(from: Color, to: Color, amount: Float): Color =
    lerp(from, to, amount.coerceIn(0f, 1f))

private fun rgbToComposeColor(rgb: ShellRgbColor): Color = Color(
    red = rgb.red / 255f,
    green = rgb.green / 255f,
    blue = rgb.blue / 255f,
)
