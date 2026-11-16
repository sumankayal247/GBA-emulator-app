package io.github.sumankayal247.gbaemulator.ui.gameplay

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.sumankayal247.gbaemulator.domain.model.SaveStateSlot
import io.github.sumankayal247.gbaemulator.ui.theme.EmulatorColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class SaveStateDialogMode {
    SAVE,
    LOAD,
    MANAGE,
}

/**
 * 8-slot Save/Load State modal inspired by `07-save-state-slots.jpg`.
 * Shows occupancy, timestamp, thumbnail preview, core version, and confirms before overwriting
 * or deleting any occupied slot (Plan §11.2 / ADR-0005).
 */
@Composable
fun SaveStateDialog(
    mode: SaveStateDialogMode,
    gameTitle: String,
    slots: List<SaveStateSlot>,
    onSelectSlot: (Int) -> Unit,
    onDeleteSlot: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var pendingOverwriteSlot by remember { mutableStateOf<Int?>(null) }
    var pendingDeleteSlot by remember { mutableStateOf<Int?>(null) }

    val titleText = when (mode) {
        SaveStateDialogMode.SAVE -> "Save"
        SaveStateDialogMode.LOAD -> "Load"
        SaveStateDialogMode.MANAGE -> "Save States — $gameTitle"
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = EmulatorColors.DialogBackground,
            tonalElevation = 8.dp,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp),
        ) {
            Column(modifier = Modifier.padding(vertical = 12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = titleText,
                            style = MaterialTheme.typography.titleLarge,
                            color = EmulatorColors.TextPrimary,
                        )
                        if (mode != SaveStateDialogMode.MANAGE) {
                            Text(
                                text = gameTitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = EmulatorColors.TextSecondary,
                                maxLines = 1,
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Close save states",
                            tint = EmulatorColors.TextPrimary,
                        )
                    }
                }

                HorizontalDivider(color = EmulatorColors.CardBorder)

                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(slots, key = { it.slotIndex }) { slot ->
                        SaveStateSlotRow(
                            slot = slot,
                            mode = mode,
                            onClick = {
                                when (mode) {
                                    SaveStateDialogMode.SAVE -> {
                                        if (slot.occupied) {
                                            pendingOverwriteSlot = slot.slotIndex
                                        } else {
                                            onSelectSlot(slot.slotIndex)
                                        }
                                    }
                                    SaveStateDialogMode.LOAD -> {
                                        if (slot.occupied && !slot.corrupt) {
                                            onSelectSlot(slot.slotIndex)
                                        }
                                    }
                                    SaveStateDialogMode.MANAGE -> Unit
                                }
                            },
                            onDeleteClick = if (slot.occupied) {
                                { pendingDeleteSlot = slot.slotIndex }
                            } else {
                                null
                            },
                        )
                        if (slot.slotIndex < SaveStateSlot.SLOT_COUNT) {
                            HorizontalDivider(
                                color = EmulatorColors.CardBorder.copy(alpha = 0.6f),
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    pendingOverwriteSlot?.let { slotIndex ->
        AlertDialog(
            onDismissRequest = { pendingOverwriteSlot = null },
            containerColor = EmulatorColors.DialogBackground,
            title = { Text("Overwrite State $slotIndex?", color = EmulatorColors.TextPrimary) },
            text = {
                Text(
                    "State $slotIndex already contains a saved snapshot. Replacing it will rotate the previous snapshot into a backup.",
                    color = EmulatorColors.TextSecondary,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingOverwriteSlot = null
                        onSelectSlot(slotIndex)
                    },
                ) {
                    Text("Overwrite", color = EmulatorColors.AccentCyan)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingOverwriteSlot = null }) {
                    Text("Cancel", color = EmulatorColors.TextSecondary)
                }
            },
        )
    }

    pendingDeleteSlot?.let { slotIndex ->
        AlertDialog(
            onDismissRequest = { pendingDeleteSlot = null },
            containerColor = EmulatorColors.DialogBackground,
            title = { Text("Delete State $slotIndex?", color = EmulatorColors.TextPrimary) },
            text = {
                Text(
                    "This removes save-state slot $slotIndex and its thumbnail. Your in-game battery save (SRAM) is not affected.",
                    color = EmulatorColors.TextSecondary,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDeleteSlot = null
                        onDeleteSlot(slotIndex)
                    },
                ) {
                    Text("Delete", color = EmulatorColors.ErrorRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteSlot = null }) {
                    Text("Cancel", color = EmulatorColors.TextSecondary)
                }
            },
        )
    }
}

@Composable
private fun SaveStateSlotRow(
    slot: SaveStateSlot,
    mode: SaveStateDialogMode,
    onClick: () -> Unit,
    onDeleteClick: (() -> Unit)?,
) {
    val clickable = when (mode) {
        SaveStateDialogMode.SAVE -> true
        SaveStateDialogMode.LOAD -> slot.occupied && !slot.corrupt
        SaveStateDialogMode.MANAGE -> false
    }

    val thumbnailBitmap = remember(slot.thumbnailPath, slot.savedEpochMs) {
        slot.thumbnailPath?.let { path ->
            runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (clickable) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(width = 56.dp, height = 40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(EmulatorColors.CardSurface)
                .border(1.dp, EmulatorColors.CardBorder, RoundedCornerShape(8.dp)),
        ) {
            when {
                slot.corrupt -> Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = "Corrupt state",
                    tint = EmulatorColors.WarningAmber,
                    modifier = Modifier.size(20.dp),
                )
                thumbnailBitmap != null -> Image(
                    bitmap = thumbnailBitmap,
                    contentDescription = "State ${slot.slotIndex} preview",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
                else -> Icon(
                    imageVector = Icons.Filled.SportsEsports,
                    contentDescription = null,
                    tint = if (slot.occupied) EmulatorColors.AccentCyan else EmulatorColors.TextMuted,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "State ${slot.slotIndex}",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (clickable || slot.occupied) EmulatorColors.TextPrimary else EmulatorColors.TextMuted,
            )
            Spacer(Modifier.height(2.dp))
            val subtitle = when {
                !slot.occupied -> "Empty slot"
                slot.corrupt -> "Checksum failed — unreadable"
                else -> {
                    val fmt = SimpleDateFormat("MMM d, HH:mm", Locale.US)
                    val dateStr = fmt.format(Date(slot.savedEpochMs))
                    val kb = (slot.byteSize / 1024L).coerceAtLeast(1L)
                    "$dateStr • $kb KiB"
                }
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (slot.corrupt) EmulatorColors.WarningAmber else EmulatorColors.TextSecondary,
            )
        }

        if (onDeleteClick != null) {
            IconButton(onClick = onDeleteClick) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Delete State ${slot.slotIndex}",
                    tint = EmulatorColors.TextSecondary,
                )
            }
        }
    }
}
