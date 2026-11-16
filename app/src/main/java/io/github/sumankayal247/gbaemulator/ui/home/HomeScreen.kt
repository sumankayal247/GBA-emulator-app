package io.github.sumankayal247.gbaemulator.ui.home

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.sumankayal247.gbaemulator.domain.model.GameSystem
import io.github.sumankayal247.gbaemulator.domain.model.RomRecord
import io.github.sumankayal247.gbaemulator.ui.theme.EmulatorColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Library Home screen inspired by `08-library-home.jpg`, strictly scoped to
 * Game Boy Advance, Game Boy Color, and Game Boy (no NES/3DS placeholders).
 */
@Composable
fun HomeScreen(
    state: HomeUiState,
    onPickFolder: () -> Unit,
    onImportSingleRom: () -> Unit,
    onRescanFolders: () -> Unit,
    onCancelScan: () -> Unit,
    onSearchChange: (String) -> Unit,
    onFilterChange: (LibraryFilter) -> Unit,
    onSortChange: (LibrarySort) -> Unit,
    onLaunchGame: (RomRecord) -> Unit,
    onRelinkGame: (RomRecord) -> Unit,
    onToggleFavorite: (RomRecord) -> Unit,
    onRenameGame: (RomRecord, String) -> Unit,
    onPickCoverImage: (RomRecord) -> Unit,
    onExportSram: (RomRecord) -> Unit,
    onImportSram: (RomRecord) -> Unit,
    onRemoveFromLibrary: (RomRecord) -> Unit,
    onDeleteAllSaves: (RomRecord) -> Unit,
    onDismissBanners: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var renamingGame by remember { mutableStateOf<RomRecord?>(null) }
    var deletingSavesFor by remember { mutableStateOf<RomRecord?>(null) }
    var seeAllSystem by remember { mutableStateOf<GameSystem?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(EmulatorColors.DarkBackground)
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(14.dp))

        // Top Title Row matching 08-library-home.jpg
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "GBA Emulator",
                style = MaterialTheme.typography.headlineMedium,
                color = EmulatorColors.AccentBlue,
                fontWeight = FontWeight.Bold,
            )

            // Sort selector pill
            Surface(
                color = EmulatorColors.CardSurface,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .border(1.dp, EmulatorColors.CardBorder, RoundedCornerShape(10.dp))
                    .clickable {
                        val nextIdx = (state.sort.ordinal + 1) % LibrarySort.entries.size
                        onSortChange(LibrarySort.entries[nextIdx])
                    },
            ) {
                Text(
                    text = "Sort: ${state.sort.label}",
                    style = MaterialTheme.typography.labelMedium,
                    color = EmulatorColors.TextSecondary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // Game Directory Bar matching 08-library-home.jpg (Folder picker + Import file + Rescan)
        Surface(
            color = EmulatorColors.CardSurface,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, EmulatorColors.CardBorder, RoundedCornerShape(14.dp)),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(EmulatorColors.AccentBlue.copy(alpha = 0.16f))
                        .clickable(onClick = onPickFolder),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Folder,
                        contentDescription = "Choose game folder",
                        tint = EmulatorColors.AccentBlue,
                    )
                }

                Spacer(Modifier.width(12.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(onClick = onPickFolder),
                ) {
                    Text(
                        text = "Game directory",
                        style = MaterialTheme.typography.labelLarge,
                        color = EmulatorColors.TextPrimary,
                    )
                    val folderSummary = when (state.savedFolders.size) {
                        0 -> "Tap to select a ROM folder"
                        1 -> ".../${state.savedFolders.first().displayName}"
                        else -> "${state.savedFolders.size} folders configured"
                    }
                    Text(
                        text = folderSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = EmulatorColors.AccentCyan,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                IconButton(onClick = onImportSingleRom) {
                    Icon(
                        imageVector = Icons.Filled.FolderOpen,
                        contentDescription = "Open single ROM file",
                        tint = EmulatorColors.TextPrimary,
                    )
                }

                if (state.isScanningFolders) {
                    IconButton(onClick = onCancelScan) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = EmulatorColors.AccentCyan,
                        )
                    }
                } else {
                    IconButton(onClick = onRescanFolders) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Rescan game folders",
                            tint = EmulatorColors.TextPrimary,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Search box
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = onSearchChange,
            placeholder = { Text("Search games...", color = EmulatorColors.TextMuted) },
            leadingIcon = {
                Icon(Icons.Filled.Search, contentDescription = null, tint = EmulatorColors.TextSecondary)
            },
            trailingIcon = if (state.searchQuery.isNotEmpty()) {
                {
                    IconButton(onClick = { onSearchChange("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear search", tint = EmulatorColors.TextSecondary)
                    }
                }
            } else {
                null
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = EmulatorColors.CardSurface,
                unfocusedContainerColor = EmulatorColors.CardSurface,
                focusedBorderColor = EmulatorColors.AccentBlue,
                unfocusedBorderColor = EmulatorColors.CardBorder,
                focusedTextColor = EmulatorColors.TextPrimary,
                unfocusedTextColor = EmulatorColors.TextPrimary,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))

        // System + Favorites filter chips
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(LibraryFilter.entries) { chip ->
                FilterChip(
                    selected = state.filter == chip,
                    onClick = {
                        seeAllSystem = null
                        onFilterChange(chip)
                    },
                    label = { Text(chip.label) },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = EmulatorColors.CardSurface,
                        labelColor = EmulatorColors.TextSecondary,
                        selectedContainerColor = EmulatorColors.AccentBlue.copy(alpha = 0.25f),
                        selectedLabelColor = EmulatorColors.AccentCyan,
                    ),
                )
            }
        }

        // Banners
        if (state.errorBanner != null) {
            Spacer(Modifier.height(8.dp))
            StatusCard(
                message = state.errorBanner,
                isError = true,
                actionLabel = if (state.relinkNeededForRom != null) "Relink ROM" else null,
                onAction = state.relinkNeededForRom?.let { rom -> { onRelinkGame(rom) } },
                onDismiss = onDismissBanners,
            )
        } else if (state.statusBanner != null) {
            Spacer(Modifier.height(8.dp))
            StatusCard(
                message = state.statusBanner,
                isError = false,
                actionLabel = null,
                onAction = null,
                onDismiss = onDismissBanners,
            )
        }

        if (state.isLoadingRom) {
            Spacer(Modifier.height(10.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = EmulatorColors.AccentCyan,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Validating ROM & starting mGBA...",
                    style = MaterialTheme.typography.bodySmall,
                    color = EmulatorColors.TextSecondary,
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        if (state.allGames.isEmpty()) {
            EmptyLibraryCard(
                onPickFolder = onPickFolder,
                onImportSingleRom = onImportSingleRom,
            )
        } else if (state.filteredGames.isEmpty()) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                Text(
                    text = "No games match the current filter.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = EmulatorColors.TextSecondary,
                )
            }
        } else {
            val groupedBySystem = remember(state.filteredGames) {
                listOf(GameSystem.GBA, GameSystem.GBC, GameSystem.GB)
                    .map { sys -> sys to state.filteredGames.filter { it.system == sys } }
                    .filter { it.second.isNotEmpty() }
            }

            val showingSingleList = state.filter != LibraryFilter.ALL ||
                state.searchQuery.isNotBlank() ||
                seeAllSystem != null

            LazyColumn(
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                if (showingSingleList) {
                    val itemsToShow = seeAllSystem?.let { sys ->
                        state.filteredGames.filter { it.system == sys }
                    } ?: state.filteredGames

                    if (seeAllSystem != null) {
                        item {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = seeAllSystem!!.displayName,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = EmulatorColors.TextPrimary,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                TextButton(onClick = { seeAllSystem = null }) {
                                    Text("Back to shelves", color = EmulatorColors.AccentCyan)
                                }
                            }
                        }
                    }

                    items(itemsToShow, key = { it.romId }) { rom ->
                        LibraryListRowCard(
                            rom = rom,
                            onPlay = { onLaunchGame(rom) },
                            onToggleFavorite = { onToggleFavorite(rom) },
                            onRename = { renamingGame = rom },
                            onPickCover = { onPickCoverImage(rom) },
                            onExportSram = { onExportSram(rom) },
                            onImportSram = { onImportSram(rom) },
                            onRelink = { onRelinkGame(rom) },
                            onRemoveFromLibrary = { onRemoveFromLibrary(rom) },
                            onDeleteSaves = { deletingSavesFor = rom },
                        )
                    }
                } else {
                    groupedBySystem.forEach { (system, games) ->
                        item(key = "shelf_${system.id}") {
                            Column {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier
                                                .size(28.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(systemAccentColor(system)),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.SportsEsports,
                                                contentDescription = null,
                                                tint = Color.White,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                        Spacer(Modifier.width(10.dp))
                                        Text(
                                            text = system.displayName,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = EmulatorColors.TextPrimary,
                                        )
                                    }
                                    TextButton(onClick = { seeAllSystem = system }) {
                                        Text("See all (${games.size})", color = EmulatorColors.AccentCyan)
                                    }
                                }

                                Spacer(Modifier.height(8.dp))

                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    items(games, key = { it.romId }) { rom ->
                                        ShelfGameCard(
                                            rom = rom,
                                            onPlay = { onLaunchGame(rom) },
                                            onToggleFavorite = { onToggleFavorite(rom) },
                                            onRename = { renamingGame = rom },
                                            onPickCover = { onPickCoverImage(rom) },
                                            onExportSram = { onExportSram(rom) },
                                            onImportSram = { onImportSram(rom) },
                                            onRelink = { onRelinkGame(rom) },
                                            onRemoveFromLibrary = { onRemoveFromLibrary(rom) },
                                            onDeleteSaves = { deletingSavesFor = rom },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    renamingGame?.let { rom ->
        var text by remember(rom.romId) { mutableStateOf(rom.effectiveTitle) }
        AlertDialog(
            onDismissRequest = { renamingGame = null },
            containerColor = EmulatorColors.DialogBackground,
            title = { Text("Rename game", color = EmulatorColors.TextPrimary) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text("Display title") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRenameGame(rom, text)
                        renamingGame = null
                    },
                ) {
                    Text("Save", color = EmulatorColors.AccentCyan)
                }
            },
            dismissButton = {
                TextButton(onClick = { renamingGame = null }) {
                    Text("Cancel", color = EmulatorColors.TextSecondary)
                }
            },
        )
    }

    deletingSavesFor?.let { rom ->
        AlertDialog(
            onDismissRequest = { deletingSavesFor = null },
            containerColor = EmulatorColors.DialogBackground,
            title = { Text("Delete all saves?", color = EmulatorColors.ErrorRed) },
            text = {
                Text(
                    "This permanently deletes the battery save (SRAM) and all 8 save states for '${rom.effectiveTitle}'. Your original ROM file will NOT be deleted.",
                    color = EmulatorColors.TextSecondary,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteAllSaves(rom)
                        deletingSavesFor = null
                    },
                ) {
                    Text("Delete Saves", color = EmulatorColors.ErrorRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingSavesFor = null }) {
                    Text("Cancel", color = EmulatorColors.TextSecondary)
                }
            },
        )
    }
}

@Composable
private fun ShelfGameCard(
    rom: RomRecord,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRename: () -> Unit,
    onPickCover: () -> Unit,
    onExportSram: () -> Unit,
    onImportSram: () -> Unit,
    onRelink: () -> Unit,
    onRemoveFromLibrary: () -> Unit,
    onDeleteSaves: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val coverBitmap = rememberCoverBitmap(rom.coverUri)
    val accent = systemAccentColor(rom.system)

    Box(
        modifier = Modifier
            .width(134.dp)
            .height(176.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.verticalGradient(listOf(accent, accent.copy(alpha = 0.72f), Color(0xFF1A1026))),
            )
            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(16.dp))
            .clickable(onClick = onPlay),
    ) {
        if (coverBitmap != null) {
            Image(
                bitmap = coverBitmap,
                contentDescription = rom.effectiveTitle,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)),
                        ),
                    ),
            )
        } else {
            Icon(
                imageVector = Icons.Filled.SportsEsports,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier
                    .size(54.dp)
                    .align(Alignment.Center)
                    .padding(bottom = 14.dp),
            )
        }

        // Top badges & menu button
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(6.dp)
                .align(Alignment.TopCenter),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (rom.isFavorite) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = "Favorite",
                        tint = Color(0xFFFFD54F),
                        modifier = Modifier.size(18.dp),
                    )
                }
                if (!rom.sourceAvailable) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = "Missing ROM source",
                        tint = EmulatorColors.WarningAmber,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.35f)),
                ) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = "Game options for ${rom.effectiveTitle}",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }
                GameOptionsDropdown(
                    expanded = menuExpanded,
                    rom = rom,
                    onDismiss = { menuExpanded = false },
                    onToggleFavorite = onToggleFavorite,
                    onRename = onRename,
                    onPickCover = onPickCover,
                    onExportSram = onExportSram,
                    onImportSram = onImportSram,
                    onRelink = onRelink,
                    onRemoveFromLibrary = onRemoveFromLibrary,
                    onDeleteSaves = onDeleteSaves,
                )
            }
        }

        // Title footer
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(10.dp),
        ) {
            Text(
                text = rom.effectiveTitle,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (rom.hasBatterySave) {
                Text(
                    text = "SRAM saved",
                    style = MaterialTheme.typography.labelSmall,
                    color = EmulatorColors.AccentCyan,
                )
            }
        }
    }
}

@Composable
private fun LibraryListRowCard(
    rom: RomRecord,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRename: () -> Unit,
    onPickCover: () -> Unit,
    onExportSram: () -> Unit,
    onImportSram: () -> Unit,
    onRelink: () -> Unit,
    onRemoveFromLibrary: () -> Unit,
    onDeleteSaves: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val coverBitmap = rememberCoverBitmap(rom.coverUri)
    val accent = systemAccentColor(rom.system)

    Surface(
        color = EmulatorColors.CardSurface,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, EmulatorColors.CardBorder, RoundedCornerShape(14.dp))
            .clickable(onClick = onPlay),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(12.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(accent),
            ) {
                if (coverBitmap != null) {
                    Image(
                        bitmap = coverBitmap,
                        contentDescription = rom.effectiveTitle,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        text = rom.system.shortLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = rom.effectiveTitle,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = EmulatorColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                val metaParts = buildList {
                    add(rom.system.displayName)
                    if (rom.hasBatterySave) add("SRAM")
                    if (!rom.sourceAvailable) add("Missing source")
                    if (rom.totalPlayTimeMs >= 60_000L) {
                        add("${rom.totalPlayTimeMs / 60_000L}m played")
                    }
                }
                Text(
                    text = metaParts.joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (!rom.sourceAvailable) EmulatorColors.WarningAmber else EmulatorColors.TextSecondary,
                )
            }

            IconButton(onClick = onToggleFavorite) {
                Icon(
                    imageVector = if (rom.isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = if (rom.isFavorite) "Remove favorite" else "Add favorite",
                    tint = if (rom.isFavorite) Color(0xFFFFD54F) else EmulatorColors.TextSecondary,
                )
            }

            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = "Options for ${rom.effectiveTitle}",
                        tint = EmulatorColors.TextSecondary,
                    )
                }
                GameOptionsDropdown(
                    expanded = menuExpanded,
                    rom = rom,
                    onDismiss = { menuExpanded = false },
                    onToggleFavorite = onToggleFavorite,
                    onRename = onRename,
                    onPickCover = onPickCover,
                    onExportSram = onExportSram,
                    onImportSram = onImportSram,
                    onRelink = onRelink,
                    onRemoveFromLibrary = onRemoveFromLibrary,
                    onDeleteSaves = onDeleteSaves,
                )
            }
        }
    }
}

@Composable
private fun GameOptionsDropdown(
    expanded: Boolean,
    rom: RomRecord,
    onDismiss: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRename: () -> Unit,
    onPickCover: () -> Unit,
    onExportSram: () -> Unit,
    onImportSram: () -> Unit,
    onRelink: () -> Unit,
    onRemoveFromLibrary: () -> Unit,
    onDeleteSaves: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        containerColor = EmulatorColors.DialogBackground,
    ) {
        DropdownMenuItem(
            text = { Text(if (rom.isFavorite) "Remove Favorite" else "Add to Favorites", color = EmulatorColors.TextPrimary) },
            leadingIcon = { Icon(Icons.Filled.Star, null, tint = Color(0xFFFFD54F)) },
            onClick = {
                onDismiss()
                onToggleFavorite()
            },
        )
        DropdownMenuItem(
            text = { Text("Rename", color = EmulatorColors.TextPrimary) },
            leadingIcon = { Icon(Icons.Filled.Edit, null, tint = EmulatorColors.TextSecondary) },
            onClick = {
                onDismiss()
                onRename()
            },
        )
        DropdownMenuItem(
            text = { Text("Set Cover Image", color = EmulatorColors.TextPrimary) },
            leadingIcon = { Icon(Icons.Filled.Photo, null, tint = EmulatorColors.TextSecondary) },
            onClick = {
                onDismiss()
                onPickCover()
            },
        )
        DropdownMenuItem(
            text = { Text("Export Battery Save (.sav)", color = EmulatorColors.TextPrimary) },
            leadingIcon = { Icon(Icons.Filled.FileUpload, null, tint = EmulatorColors.AccentCyan) },
            onClick = {
                onDismiss()
                onExportSram()
            },
        )
        DropdownMenuItem(
            text = { Text("Import Battery Save (.sav)", color = EmulatorColors.TextPrimary) },
            leadingIcon = { Icon(Icons.Filled.FileDownload, null, tint = EmulatorColors.AccentCyan) },
            onClick = {
                onDismiss()
                onImportSram()
            },
        )
        DropdownMenuItem(
            text = { Text("Relink ROM File", color = EmulatorColors.TextPrimary) },
            leadingIcon = { Icon(Icons.Filled.Link, null, tint = EmulatorColors.TextSecondary) },
            onClick = {
                onDismiss()
                onRelink()
            },
        )
        DropdownMenuItem(
            text = { Text("Remove from Library", color = EmulatorColors.TextSecondary) },
            leadingIcon = { Icon(Icons.Filled.Close, null, tint = EmulatorColors.TextSecondary) },
            onClick = {
                onDismiss()
                onRemoveFromLibrary()
            },
        )
        DropdownMenuItem(
            text = { Text("Delete All Saves", color = EmulatorColors.ErrorRed) },
            leadingIcon = { Icon(Icons.Filled.DeleteForever, null, tint = EmulatorColors.ErrorRed) },
            onClick = {
                onDismiss()
                onDeleteSaves()
            },
        )
    }
}

@Composable
private fun EmptyLibraryCard(
    onPickFolder: () -> Unit,
    onImportSingleRom: () -> Unit,
) {
    Surface(
        color = EmulatorColors.CardSurface,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, EmulatorColors.CardBorder, RoundedCornerShape(18.dp)),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(60.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(EmulatorColors.AccentBlue.copy(alpha = 0.16f)),
            ) {
                Icon(
                    imageVector = Icons.Filled.SportsEsports,
                    contentDescription = null,
                    tint = EmulatorColors.AccentBlue,
                    modifier = Modifier.size(34.dp),
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = "Your game library is empty",
                style = MaterialTheme.typography.titleMedium,
                color = EmulatorColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Select a folder containing your legally obtained .gba, .gbc, or .gb ROMs, or open a single ROM file directly via Android Storage Access Framework.",
                style = MaterialTheme.typography.bodySmall,
                color = EmulatorColors.TextSecondary,
            )
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = onPickFolder,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = EmulatorColors.AccentBlue),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Folder, null)
                Spacer(Modifier.width(8.dp))
                Text("Select Game Folder")
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onImportSingleRom) {
                Text("Open Single ROM File", color = EmulatorColors.AccentCyan)
            }
        }
    }
}

@Composable
private fun StatusCard(
    message: String,
    isError: Boolean,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    Surface(
        color = if (isError) EmulatorColors.ErrorRed.copy(alpha = 0.15f) else EmulatorColors.AccentBlue.copy(alpha = 0.15f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                if (isError) EmulatorColors.ErrorRed.copy(alpha = 0.45f) else EmulatorColors.AccentBlue.copy(alpha = 0.45f),
                RoundedCornerShape(12.dp),
            ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = EmulatorColors.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) {
                    Text(actionLabel, color = EmulatorColors.AccentCyan)
                }
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Dismiss notice",
                    tint = EmulatorColors.TextSecondary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun rememberCoverBitmap(uriString: String?): ImageBitmap? {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = uriString) {
        value = if (uriString.isNullOrBlank()) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    val uri = Uri.parse(uriString)
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, bounds)
                    }
                    val maxDim = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
                    val sampleSize = (maxDim / 384).coerceAtLeast(1)
                    val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                    context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, opts)?.asImageBitmap()
                    }
                }.getOrNull()
            }
        }
    }
    return bitmap
}

private fun systemAccentColor(system: GameSystem): Color = when (system) {
    GameSystem.GBA -> Color(0xFFE91E78)
    GameSystem.GBC -> Color(0xFF7B42F6)
    GameSystem.GB -> Color(0xFF1AA782)
}
