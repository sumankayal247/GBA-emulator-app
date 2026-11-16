package io.github.sumankayal247.gbaemulator.domain.model

data class SaveStateSlot(
    val slotIndex: Int,
    val occupied: Boolean = false,
    val savedEpochMs: Long = 0L,
    val coreVersion: String = "",
    val byteSize: Long = 0L,
    val sha256: String = "",
    val thumbnailPath: String? = null,
    val corrupt: Boolean = false,
) {
    companion object {
        const val SLOT_COUNT = 8
    }
}
