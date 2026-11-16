package io.github.sumankayal247.gbaemulator.ui.gameplay

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.sumankayal247.gbaemulator.domain.model.CoreOptionItem
import io.github.sumankayal247.gbaemulator.ui.theme.EmulatorColors

/**
 * Dynamic Core Options dialog inspired by `04-core-options-dialog.jpg`.
 * Strictly populated from the live pinned `libretro/mgba` core's advertised options
 * (`RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2` + `SET_CORE_OPTIONS_DISPLAY`).
 * Zero hardcoded or unsupported options are ever displayed (Plan §5.H / ADR-0005).
 */
@Composable
fun CoreOptionsDialog(
    options: List<CoreOptionItem>,
    coreVersion: String,
    onSelectOptionValue: (key: String, value: String, perGame: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var savePerGame by remember { mutableStateOf(true) }
    var editingOption by remember { mutableStateOf<CoreOptionItem?>(null) }

    val visibleOptions = remember(options) { options.filter { it.visible && it.choices.isNotEmpty() } }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = EmulatorColors.DialogBackground,
            tonalElevation = 8.dp,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 580.dp),
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
                            text = "Core Options",
                            style = MaterialTheme.typography.titleLarge,
                            color = EmulatorColors.TextPrimary,
                        )
                        Text(
                            text = coreVersion,
                            style = MaterialTheme.typography.bodySmall,
                            color = EmulatorColors.TextSecondary,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Close core options",
                            tint = EmulatorColors.TextPrimary,
                        )
                    }
                }

                // Scope toggle: save changes for this game or globally
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                ) {
                    FilterChip(
                        selected = savePerGame,
                        onClick = { savePerGame = true },
                        label = { Text("This game") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = EmulatorColors.AccentBlue.copy(alpha = 0.25f),
                            selectedLabelColor = EmulatorColors.AccentCyan,
                        ),
                    )
                    FilterChip(
                        selected = !savePerGame,
                        onClick = { savePerGame = false },
                        label = { Text("Global default") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = EmulatorColors.AccentBlue.copy(alpha = 0.25f),
                            selectedLabelColor = EmulatorColors.AccentCyan,
                        ),
                    )
                }

                HorizontalDivider(color = EmulatorColors.CardBorder)

                if (visibleOptions.isEmpty()) {
                    Text(
                        text = "No core options advertised by the active core.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = EmulatorColors.TextSecondary,
                        modifier = Modifier.padding(20.dp),
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(visibleOptions, key = { it.key }) { item ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { editingOption = item }
                                    .padding(horizontal = 20.dp, vertical = 14.dp),
                            ) {
                                Text(
                                    text = item.description,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = EmulatorColors.TextPrimary,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = item.currentLabel(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = EmulatorColors.AccentCyan,
                                )
                                if (item.info.isNotBlank()) {
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = item.info,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = EmulatorColors.TextMuted,
                                        maxLines = 2,
                                    )
                                }
                            }
                            HorizontalDivider(
                                color = EmulatorColors.CardBorder.copy(alpha = 0.5f),
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    editingOption?.let { option ->
        AlertDialog(
            onDismissRequest = { editingOption = null },
            containerColor = EmulatorColors.DialogBackground,
            title = {
                Text(
                    text = option.description,
                    color = EmulatorColors.TextPrimary,
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(option.choices, key = { it.value }) { choice ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelectOptionValue(option.key, choice.value, savePerGame)
                                    editingOption = null
                                }
                                .padding(vertical = 8.dp),
                        ) {
                            RadioButton(
                                selected = choice.value == option.currentValue,
                                onClick = {
                                    onSelectOptionValue(option.key, choice.value, savePerGame)
                                    editingOption = null
                                },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = EmulatorColors.AccentBlue,
                                    unselectedColor = EmulatorColors.TextSecondary,
                                ),
                            )
                            Text(
                                text = choice.label,
                                style = MaterialTheme.typography.bodyLarge,
                                color = EmulatorColors.TextPrimary,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { editingOption = null }) {
                    Text("Close", color = EmulatorColors.TextSecondary)
                }
            },
        )
    }
}
