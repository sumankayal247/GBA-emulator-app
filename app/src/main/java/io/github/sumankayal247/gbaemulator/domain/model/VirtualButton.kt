package io.github.sumankayal247.gbaemulator.domain.model

/**
 * Logical handheld controls mapped to Libretro RETRO_DEVICE_ID_JOYPAD_* bit positions.
 */
enum class VirtualButton(val retroId: Int, val label: String) {
    B(0, "B"),
    SELECT(2, "Select"),
    START(3, "Start"),
    UP(4, "Up"),
    DOWN(5, "Down"),
    LEFT(6, "Left"),
    RIGHT(7, "Right"),
    A(8, "A"),
    L(10, "L"),
    R(11, "R"),
    ;

    val bitMask: Int
        get() = 1 shl retroId
}
