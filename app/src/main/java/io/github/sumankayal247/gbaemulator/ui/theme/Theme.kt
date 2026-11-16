package io.github.sumankayal247.gbaemulator.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.github.sumankayal247.gbaemulator.domain.model.GameSystem

object EmulatorColors {
    val DarkBackground = Color(0xFF0C0E12)
    val DarkSurface = Color(0xFF151921)
    val DarkSurfaceElevated = Color(0xFF1E2430)
    val DarkBorder = Color(0xFF2B3342)
    val AccentBlue = Color(0xFF2D9CFF)
    val AccentBlueContainer = Color(0xFF12304C)
    val TextPrimary = Color(0xFFF2F5FA)
    val TextSecondary = Color(0xFF9AA5B8)
    val DangerRed = Color(0xFFEF5350)
    val SuccessGreen = Color(0xFF34D399)

    // System category accents
    val GbaBadge = Color(0xFFF01E7C)
    val GbcBadge = Color(0xFF38BDF8)
    val GbBadge = Color(0xFF4ADE80)

    // Virtual handheld shell (reference 01 & 02 purple aesthetic)
    val ShellPurple = Color(0xFF6E00FF)
    val ShellPurpleLight = Color(0xFF8326FF)
    val ShellPurpleDark = Color(0xFF4B00B3)
    val ScreenBezel = Color(0xFF090A0E)
    val ButtonFace = Color(0xFFE7EAF0)
    val ButtonFacePressed = Color(0xFFC2C9D6)
    val ButtonHighlight = Color(0xFFFFFFFF)
    val ButtonShadow = Color(0xFF9EA6B6)
    val ButtonOutline = Color(0xFF1E0F38)
    val ButtonLabel = Color(0xFF6B7280)
    val ShellLabel = Color(0xFF4600A3)
}

fun GameSystem.accentColor(): Color = when (this) {
    GameSystem.GBA -> EmulatorColors.GbaBadge
    GameSystem.GBC -> EmulatorColors.GbcBadge
    GameSystem.GB -> EmulatorColors.GbBadge
}

private val AppDarkColorScheme = darkColorScheme(
    primary = EmulatorColors.AccentBlue,
    onPrimary = Color(0xFF041423),
    primaryContainer = EmulatorColors.AccentBlueContainer,
    onPrimaryContainer = EmulatorColors.TextPrimary,
    secondary = EmulatorColors.ShellPurpleLight,
    onSecondary = Color.White,
    background = EmulatorColors.DarkBackground,
    onBackground = EmulatorColors.TextPrimary,
    surface = EmulatorColors.DarkSurface,
    onSurface = EmulatorColors.TextPrimary,
    surfaceVariant = EmulatorColors.DarkSurfaceElevated,
    onSurfaceVariant = EmulatorColors.TextSecondary,
    outline = EmulatorColors.DarkBorder,
    error = EmulatorColors.DangerRed,
    onError = Color.White,
)

@Composable
fun GbaEmulatorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppDarkColorScheme,
        content = content,
    )
}
