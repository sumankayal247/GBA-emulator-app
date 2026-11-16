package io.github.sumankayal247.gbaemulator.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.sumankayal247.gbaemulator.R
import io.github.sumankayal247.gbaemulator.domain.model.EmulatorState
import io.github.sumankayal247.gbaemulator.domain.model.GameSystem
import io.github.sumankayal247.gbaemulator.domain.model.RomRecord
import io.github.sumankayal247.gbaemulator.ui.theme.EmulatorColors
import io.github.sumankayal247.gbaemulator.ui.theme.accentColor
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    onOpenRomClick: () -> Unit,
    onResumeActiveSession: () -> Unit,
    onLaunchRecent: (RomRecord) -> Unit,
    onRelinkRom: (RomRecord) -> Unit,
    onExportSram: (RomRecord) -> Unit,
    onImportSram: (RomRecord) -> Unit,
    onRemoveRecent: (RomRecord) -> Unit,
    onOpenSettings: () -> Unit,
    onDismissBanner: () -> Unit,
) {
    val hasActiveGame = state.session.activeRom != null &&
        (state.session.state == EmulatorState.RUNNING || state.session.state == EmulatorState.PAUSED)

    Scaffold(
        containerColor = EmulatorColors.DarkBackground,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = EmulatorColors.DarkBackground,
                    titleContentColor = EmulatorColors.AccentBlue,
                ),
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = EmulatorColors.AccentBlue,
                        )
                        Text(
                            text = stringResource(R.string.home_subtitle),
                            style = MaterialTheme.typography.labelSmall,
                            color = EmulatorColors.TextSecondary,
                        )
                    }
                },
                actions = {
                    if (hasActiveGame) {
                        Button(
                            onClick = onResumeActiveSession,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = EmulatorColors.AccentBlue,
                                contentColor = Color(0xFF041423),
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.padding(end = 4.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.action_resume_game),
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.action_settings),
                            tint = EmulatorColors.TextPrimary,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp),
        ) {
            // Primary SAF ROM selection card (inspired by 08-library-home.jpg header bar)
            item(key = "rom_picker_card") {
                RomPickerBanner(
                    isLoading = state.isLoadingRom,
                    onOpenRomClick = onOpenRomClick,
                )
            }

            // Status / error banner with optional Relink recovery action
            if (state.bannerMessage != null) {
                item(key = "status_banner") {
                    StatusBannerCard(
                        message = state.bannerMessage,
                        isError = state.isBannerError,
                        relinkTarget = state.relinkTarget,
                        onRelink = onRelinkRom,
                        onDismiss = onDismissBanner,
                    )
                }
            }

            if (state.recentGames.isEmpty()) {
                item(key = "empty_state") {
                    EmptyLibraryCard(onOpenRomClick = onOpenRomClick)
                }
            } else {
                // Group cleanly by GBA, GBC, and GB (strictly v1 in-scope systems)
                for (system in GameSystem.entries) {
                    val gamesForSystem = state.recentGames.filter { it.system == system }
                    if (gamesForSystem.isEmpty()) continue

                    item(key = "header_${system.id}") {
                        SystemSectionHeader(system = system, count = gamesForSystem.size)
                    }
                    items(items = gamesForSystem, key = { it.romId }) { record ->
                        RecentRomCard(
                            record = record,
                            onLaunch = { onLaunchRecent(record) },
                            onRelink = { onRelinkRom(record) },
                            onExportSram = { onExportSram(record) },
                            onImportSram = { onImportSram(record) },
                            onRemove = { onRemoveRecent(record) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RomPickerBanner(
    isLoading: Boolean,
    onOpenRomClick: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = EmulatorColors.DarkSurface),
        border = BorderStroke(1.dp, EmulatorColors.DarkBorder),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(EmulatorColors.AccentBlueContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.FolderOpen,
                    contentDescription = null,
                    tint = EmulatorColors.AccentBlue,
                    modifier = Modifier.size(26.dp),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Select ROM File",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = EmulatorColors.TextPrimary,
                )
                Text(
                    text = "Supports user-owned .gba, .gbc & .gb files via Android SAF",
                    style = MaterialTheme.typography.bodySmall,
                    color = EmulatorColors.TextSecondary,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = onOpenRomClick,
                enabled = !isLoading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = EmulatorColors.AccentBlue,
                    contentColor = Color(0xFF041423),
                ),
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = Color(0xFF041423),
                    )
                } else {
                    Text(
                        text = stringResource(R.string.action_open_rom),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusBannerCard(
    message: String,
    isError: Boolean,
    relinkTarget: RomRecord?,
    onRelink: (RomRecord) -> Unit,
    onDismiss: () -> Unit,
) {
    val accent = if (isError) EmulatorColors.DangerRed else EmulatorColors.SuccessGreen
    Card(
        colors = CardDefaults.cardColors(
            containerColor = accent.copy(alpha = 0.14f),
        ),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                if (isError) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier
                            .padding(top = 2.dp, end = 8.dp)
                            .size(18.dp),
                    )
                }
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = EmulatorColors.TextPrimary,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Dismiss message",
                        tint = EmulatorColors.TextSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (relinkTarget != null) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { onRelink(relinkTarget) },
                    border = BorderStroke(1.dp, EmulatorColors.AccentBlue),
                ) {
                    Text(
                        text = stringResource(R.string.action_relink_rom),
                        color = EmulatorColors.AccentBlue,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyLibraryCard(onOpenRomClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = EmulatorColors.DarkSurface),
        border = BorderStroke(1.dp, EmulatorColors.DarkBorder),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(EmulatorColors.AccentBlueContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.SportsEsports,
                    contentDescription = null,
                    tint = EmulatorColors.AccentBlue,
                    modifier = Modifier.size(34.dp),
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.empty_library_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = EmulatorColors.TextPrimary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.empty_library_body),
                style = MaterialTheme.typography.bodyMedium,
                color = EmulatorColors.TextSecondary,
            )
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = onOpenRomClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = EmulatorColors.AccentBlue,
                    contentColor = Color(0xFF041423),
                ),
            ) {
                Icon(
                    imageVector = Icons.Filled.FolderOpen,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.action_open_rom),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun SystemSectionHeader(system: GameSystem, count: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 2.dp),
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(system.accentColor()),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = system.shortLabel,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = system.displayName,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = EmulatorColors.TextPrimary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelMedium,
            color = EmulatorColors.TextSecondary,
        )
    }
}

@Composable
private fun RecentRomCard(
    record: RomRecord,
    onLaunch: () -> Unit,
    onRelink: () -> Unit,
    onExportSram: () -> Unit,
    onImportSram: () -> Unit,
    onRemove: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val badgeColor = record.system.accentColor()
    val formattedDate = remember(record.lastPlayedEpochMs) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(record.lastPlayedEpochMs))
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = EmulatorColors.DarkSurface),
        border = BorderStroke(1.dp, EmulatorColors.DarkBorder),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = if (record.sourceAvailable) onLaunch else onRelink),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Neutral system-specific placeholder tile (zero copyrighted box art)
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(badgeColor, badgeColor.copy(alpha = 0.65f)),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.SportsEsports,
                    contentDescription = record.system.displayName,
                    tint = Color.White,
                    modifier = Modifier.size(28.dp),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = record.displayTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = EmulatorColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = formatFileSize(record.fileSizeBytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = EmulatorColors.TextSecondary,
                    )
                    Text(
                        text = formattedDate,
                        style = MaterialTheme.typography.labelSmall,
                        color = EmulatorColors.TextSecondary,
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (record.hasBatterySave) {
                        StatusChip(label = "SRAM Saved", color = EmulatorColors.SuccessGreen)
                    }
                    if (!record.sourceAvailable) {
                        StatusChip(label = "Relink Needed", color = EmulatorColors.DangerRed)
                    }
                }
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = "Options for ${record.displayTitle}",
                        tint = EmulatorColors.TextSecondary,
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    containerColor = EmulatorColors.DarkSurfaceElevated,
                ) {
                    if (record.hasBatterySave) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_export_sram)) },
                            onClick = {
                                menuExpanded = false
                                onExportSram()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_import_sram)) },
                        onClick = {
                            menuExpanded = false
                            onImportSram()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_relink_rom)) },
                        onClick = {
                            menuExpanded = false
                            onRelink()
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(R.string.action_remove_recent),
                                color = EmulatorColors.DangerRed,
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onRemove()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusChip(label: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.16f),
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> "%d KB".format(bytes / 1024L)
        else -> "$bytes B"
    }
}
