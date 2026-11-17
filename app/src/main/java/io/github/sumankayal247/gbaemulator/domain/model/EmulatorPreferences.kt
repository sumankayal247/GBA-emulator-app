package io.github.sumankayal247.gbaemulator.domain.model

enum class ScalingMode(val nativeValue: Int, val label: String) {
    ASPECT_FIT(0, "Aspect Fit"),
    INTEGER_SCALE(1, "Integer Scale"),
    ;

    companion object {
        fun fromNative(value: Int): ScalingMode =
            entries.firstOrNull { it.nativeValue == value } ?: ASPECT_FIT
    }
}

enum class OrientationLock(val id: String, val label: String) {
    AUTO("auto", "Auto"),
    PORTRAIT("portrait", "Portrait"),
    LANDSCAPE("landscape", "Landscape"),
    ;

    companion object {
        fun fromId(id: String): OrientationLock =
            entries.firstOrNull { it.id == id } ?: AUTO
    }
}

data class EmulatorPreferences(
    val scalingMode: ScalingMode = ScalingMode.ASPECT_FIT,
    val muted: Boolean = false,
    val audioVolume: Float = 1.0f,
    val orientationLock: OrientationLock = OrientationLock.AUTO,
    val controlsOpacity: Float = 0.92f,
    val controlsScale: Float = 1.0f,
    val hapticsEnabled: Boolean = true,
    val autoHideTouchWithController: Boolean = true,
    val reducedMotion: Boolean = false,
    val portraitLayout: TouchLayoutProfile = TouchLayoutProfile.defaultPortrait(),
    val landscapeLayout: TouchLayoutProfile = TouchLayoutProfile.defaultLandscape(),
    val shellColorHex: String = ShellColors.DEFAULT_HEX,
)
