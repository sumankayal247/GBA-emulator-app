package io.github.sumankayal247.gbaemulator.ui.extras

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.sumankayal247.gbaemulator.data.rom.LibraryRepository
import io.github.sumankayal247.gbaemulator.data.saves.SaveStateRepository
import io.github.sumankayal247.gbaemulator.domain.model.AppResult
import io.github.sumankayal247.gbaemulator.domain.model.BiosStatus
import io.github.sumankayal247.gbaemulator.domain.model.BiosType
import io.github.sumankayal247.gbaemulator.domain.model.RomRecord
import io.github.sumankayal247.gbaemulator.domain.model.SaveStateSlot
import io.github.sumankayal247.gbaemulator.ui.gameplay.CheatCodeDialog
import io.github.sumankayal247.gbaemulator.ui.gameplay.SaveStateDialog
import io.github.sumankayal247.gbaemulator.ui.gameplay.SaveStateDialogMode
import io.github.sumankayal247.gbaemulator.ui.theme.EmulatorColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Extras tab (P5 / ADR-0005):
 * - Per-game Cheat Code manager & 8-slot Save-State browser right from the main shell
 * - Optional user-supplied BIOS / Firmware status & SAF import (`gba_bios.bin`, `gbc_bios.bin`, `gb_bios.bin`)
 */
@Composable
fun ExtrasScreen(
    games: List<RomRecord>,
    biosStatuses: List<BiosStatus>,
    biosNotice: String?,
    libraryRepository: LibraryRepository,
    saveStateRepository: SaveStateRepository,
    onImportBios: (BiosType) -> Unit,
    onRemoveBios: (BiosType) -> Unit,
    onClearBiosNotice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var cheatsForRom by remember { mutableStateOf<RomRecord?>(null) }
    var statesForRom by remember { mutableStateOf<RomRecord?>(null) }
    var slotsForModal by remember { mutableStateOf<List<SaveStateSlot>>(emptyList()) }
    var feedbackBanner by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(statesForRom) {
        val target = statesForRom
        if (target != null) {
            slotsForModal = withContext(Dispatchers.IO) {
                saveStateRepository.listSlots(target.system, target.sha256)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(EmulatorColors.DarkBackground)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text = "Extras",
            style = MaterialTheme.typography.headlineMedium,
            color = EmulatorColors.AccentBlue,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Manage per-game cheats, save-state slots, and optional user-supplied BIOS files.",
            style = MaterialTheme.typography.bodySmall,
            color = EmulatorColors.TextSecondary,
        )

        val banner = biosNotice ?: feedbackBanner
        if (banner != null) {
            Spacer(Modifier.height(10.dp))
            Surface(
                color = EmulatorColors.AccentBlue.copy(alpha = 0.15f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onClearBiosNotice()
                        feedbackBanner = null
                    },
            ) {
                Text(
                    text = banner,
                    style = MaterialTheme.typography.bodySmall,
                    color = EmulatorColors.TextPrimary,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        // Section 1: Per-game Cheats & Save States
        Text(
            text = "Cheats & Save States",
            style = MaterialTheme.typography.titleMedium,
            color = EmulatorColors.TextPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))

        Surface(
            color = EmulatorColors.CardSurface,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, EmulatorColors.CardBorder, RoundedCornerShape(16.dp)),
        ) {
            if (games.isEmpty()) {
                Text(
                    text = "Import a GBA, GBC, or GB game in Home to configure cheats and browse its 8 save-state slots.",
                    style = MaterialTheme.typography.bodySmall,
                    color = EmulatorColors.TextSecondary,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                Column {
                    games.forEachIndexed { idx, rom ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = rom.effectiveTitle,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = EmulatorColors.TextPrimary,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = rom.system.displayName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = EmulatorColors.TextSecondary,
                                )
                            }
                            IconButton(onClick = { cheatsForRom = rom }) {
                                Icon(
                                    imageVector = Icons.Filled.BugReport,
                                    contentDescription = "Manage cheats for ${rom.effectiveTitle}",
                                    tint = EmulatorColors.AccentCyan,
                                )
                            }
                            IconButton(onClick = { statesForRom = rom }) {
                                Icon(
                                    imageVector = Icons.Filled.Save,
                                    contentDescription = "Browse save states for ${rom.effectiveTitle}",
                                    tint = EmulatorColors.TextPrimary,
                                )
                            }
                        }
                        if (idx < games.lastIndex) {
                            HorizontalDivider(
                                color = EmulatorColors.CardBorder.copy(alpha = 0.6f),
                                modifier = Modifier.padding(horizontal = 14.dp),
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(22.dp))

        // Section 2: Optional BIOS / Firmware
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Memory,
                contentDescription = null,
                tint = EmulatorColors.AccentBlue,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Optional BIOS / Firmware",
                style = MaterialTheme.typography.titleMedium,
                color = EmulatorColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "mGBA includes a built-in open-source HLE BIOS so games run without proprietary firmware. If you legally dumped your own BIOS files, you can optionally import them below.",
            style = MaterialTheme.typography.bodySmall,
            color = EmulatorColors.TextSecondary,
        )
        Spacer(Modifier.height(10.dp))

        Surface(
            color = EmulatorColors.CardSurface,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, EmulatorColors.CardBorder, RoundedCornerShape(16.dp)),
        ) {
            Column {
                biosStatuses.forEachIndexed { idx, status ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = status.type.label,
                                style = MaterialTheme.typography.bodyLarge,
                                color = EmulatorColors.TextPrimary,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = "${status.type.fileName} (${status.type.expectedSizeBytes} bytes)",
                                style = MaterialTheme.typography.labelSmall,
                                color = EmulatorColors.TextMuted,
                            )
                            Spacer(Modifier.height(2.dp))
                            if (status.installed) {
                                Text(
                                    text = "Custom BIOS active • SHA-256 ${status.sha256?.take(12)}...",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = EmulatorColors.AccentCyan,
                                )
                            } else {
                                Text(
                                    text = "Built-in HLE BIOS active",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = EmulatorColors.TextSecondary,
                                )
                            }
                        }

                        if (status.installed) {
                            IconButton(onClick = { onRemoveBios(status.type) }) {
                                Icon(
                                    imageVector = Icons.Filled.Delete,
                                    contentDescription = "Remove ${status.type.label}",
                                    tint = EmulatorColors.ErrorRed,
                                )
                            }
                        } else {
                            OutlinedButton(
                                onClick = { onImportBios(status.type) },
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Icon(Icons.Filled.FileDownload, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text("Import")
                            }
                        }
                    }
                    if (idx < biosStatuses.lastIndex) {
                        HorizontalDivider(
                            color = EmulatorColors.CardBorder.copy(alpha = 0.6f),
                            modifier = Modifier.padding(horizontal = 14.dp),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    // Per-game Cheats modal from Extras tab
    cheatsForRom?.let { rom ->
        val cheats by libraryRepository.observeCheats(rom.romId).collectAsState(initial = emptyList())
        CheatCodeDialog(
            gameTitle = rom.effectiveTitle,
            system = rom.system,
            cheats = cheats,
            onAddCheat = { desc, code ->
                scope.launch {
                    val res = libraryRepository.addCheat(rom.romId, rom.system, desc, code)
                    if (res is AppResult.Failure) feedbackBanner = res.error.userMessage
                }
            },
            onUpdateCheat = { entry, desc, code ->
                scope.launch {
                    val res = libraryRepository.updateCheat(entry.id, rom.system, desc, code, entry.enabled)
                    if (res is AppResult.Failure) feedbackBanner = res.error.userMessage
                }
            },
            onToggleCheat = { id, enabled ->
                scope.launch { libraryRepository.toggleCheat(id, enabled) }
            },
            onDeleteCheat = { id ->
                scope.launch { libraryRepository.deleteCheat(id) }
            },
            onDismiss = { cheatsForRom = null },
        )
    }

    // Save-state slots browser modal from Extras tab
    statesForRom?.let { rom ->
        SaveStateDialog(
            mode = SaveStateDialogMode.MANAGE,
            gameTitle = rom.effectiveTitle,
            slots = slotsForModal,
            onSelectSlot = {},
            onDeleteSlot = { slotIdx ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        saveStateRepository.deleteSlot(rom.system, rom.sha256, slotIdx)
                        slotsForModal = saveStateRepository.listSlots(rom.system, rom.sha256)
                    }
                    feedbackBanner = "Deleted State $slotIdx for '${rom.effectiveTitle}'."
                }
            },
            onDismiss = { statesForRom = null },
        )
    }
}
