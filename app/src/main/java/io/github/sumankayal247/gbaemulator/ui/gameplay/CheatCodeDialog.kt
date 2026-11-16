package io.github.sumankayal247.gbaemulator.ui.gameplay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.sumankayal247.gbaemulator.domain.model.AppResult
import io.github.sumankayal247.gbaemulator.domain.model.CheatCodeEntry
import io.github.sumankayal247.gbaemulator.domain.model.CheatCodeValidator
import io.github.sumankayal247.gbaemulator.domain.model.GameSystem
import io.github.sumankayal247.gbaemulator.ui.theme.EmulatorColors

/**
 * Per-game Cheat Code manager & creator inspired by `05-cheat-code-dialog.jpg`.
 * Validates every code against mGBA's supported formats before persisting or sending to native code.
 */
@Composable
fun CheatCodeDialog(
    gameTitle: String,
    system: GameSystem,
    cheats: List<CheatCodeEntry>,
    onAddCheat: (description: String, code: String) -> Unit,
    onUpdateCheat: (entry: CheatCodeEntry, description: String, code: String) -> Unit,
    onToggleCheat: (id: Long, enabled: Boolean) -> Unit,
    onDeleteCheat: (id: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var showEditorFor by remember { mutableStateOf<CheatCodeEntry?>(null) }
    var creatingNew by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = EmulatorColors.DialogBackground,
            tonalElevation = 8.dp,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 580.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Cheat Code",
                            style = MaterialTheme.typography.titleLarge,
                            color = EmulatorColors.TextPrimary,
                        )
                        Text(
                            text = "$gameTitle (${system.shortLabel})",
                            style = MaterialTheme.typography.bodySmall,
                            color = EmulatorColors.TextSecondary,
                            maxLines = 1,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Close cheat codes",
                            tint = EmulatorColors.TextPrimary,
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = EmulatorColors.CardBorder)

                if (cheats.isEmpty()) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 36.dp, horizontal = 12.dp),
                    ) {
                        Text(
                            text = "No cheat codes for this game yet.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = EmulatorColors.TextSecondary,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = if (system == GameSystem.GBA) {
                                "Supports GameShark / Action Replay (XXXXXXXX YYYYYYYY) and CodeBreaker (XXXXXXXX YYYY)."
                            } else {
                                "Supports GameGenie (XXX-XXX / XXX-XXX-XXX) and GameShark (XXXXXXXX)."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = EmulatorColors.TextMuted,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .heightIn(max = 360.dp),
                    ) {
                        items(cheats, key = { it.id }) { cheat ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 10.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = cheat.description,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                        color = EmulatorColors.TextPrimary,
                                    )
                                    Text(
                                        text = cheat.code,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = EmulatorColors.AccentCyan,
                                        maxLines = 2,
                                    )
                                }
                                IconButton(onClick = { showEditorFor = cheat }) {
                                    Icon(
                                        imageVector = Icons.Filled.Edit,
                                        contentDescription = "Edit cheat ${cheat.description}",
                                        tint = EmulatorColors.TextSecondary,
                                    )
                                }
                                IconButton(onClick = { onDeleteCheat(cheat.id) }) {
                                    Icon(
                                        imageVector = Icons.Filled.Delete,
                                        contentDescription = "Delete cheat ${cheat.description}",
                                        tint = EmulatorColors.TextSecondary,
                                    )
                                }
                                Switch(
                                    checked = cheat.enabled,
                                    onCheckedChange = { onToggleCheat(cheat.id, it) },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = EmulatorColors.TextPrimary,
                                        checkedTrackColor = EmulatorColors.AccentBlue,
                                    ),
                                )
                            }
                            HorizontalDivider(color = EmulatorColors.CardBorder.copy(alpha = 0.5f))
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                Button(
                    onClick = { creatingNew = true },
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = EmulatorColors.AccentBlue,
                        contentColor = EmulatorColors.TextPrimary,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Create cheat code", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }

    if (creatingNew || showEditorFor != null) {
        val editing = showEditorFor
        CheatEditorModal(
            title = if (editing == null) "Create cheat code" else "Edit cheat code",
            system = system,
            initialDescription = editing?.description.orEmpty(),
            initialCode = editing?.code.orEmpty(),
            onConfirm = { desc, normalizedCode ->
                if (editing == null) {
                    onAddCheat(desc, normalizedCode)
                } else {
                    onUpdateCheat(editing, desc, normalizedCode)
                }
                creatingNew = false
                showEditorFor = null
            },
            onCancel = {
                creatingNew = false
                showEditorFor = null
            },
        )
    }
}

@Composable
private fun CheatEditorModal(
    title: String,
    system: GameSystem,
    initialDescription: String,
    initialCode: String,
    onConfirm: (description: String, normalizedCode: String) -> Unit,
    onCancel: () -> Unit,
) {
    var description by remember(initialDescription) { mutableStateOf(initialDescription) }
    var codeText by remember(initialCode) { mutableStateOf(initialCode) }
    var validationError by remember { mutableStateOf<String?>(null) }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = EmulatorColors.AccentBlue,
        unfocusedBorderColor = EmulatorColors.CardBorder,
        focusedTextColor = EmulatorColors.TextPrimary,
        unfocusedTextColor = EmulatorColors.TextPrimary,
        focusedLabelColor = EmulatorColors.AccentCyan,
        unfocusedLabelColor = EmulatorColors.TextSecondary,
    )

    Dialog(onDismissRequest = onCancel) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = EmulatorColors.DialogBackground,
            tonalElevation = 12.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(20.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = EmulatorColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )

                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    singleLine = true,
                    colors = fieldColors,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = codeText,
                    onValueChange = {
                        codeText = it
                        validationError = null
                    },
                    label = { Text("Cheat Code") },
                    placeholder = {
                        Text(
                            if (system == GameSystem.GBA) "XXXXXXXX YYYYYYYY" else "XXX-XXX-XXX",
                            color = EmulatorColors.TextMuted,
                        )
                    },
                    isError = validationError != null,
                    colors = fieldColors,
                    shape = RoundedCornerShape(12.dp),
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (validationError != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = validationError.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = EmulatorColors.ErrorRed,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(Modifier.height(18.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedButton(
                        onClick = onCancel,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp),
                    ) {
                        Text("Cancel", color = EmulatorColors.TextPrimary)
                    }
                    Button(
                        onClick = {
                            when (val res = CheatCodeValidator.validateAndNormalize(system, codeText)) {
                                is AppResult.Success -> {
                                    onConfirm(
                                        description.trim().ifBlank { "Cheat Code" },
                                        res.value,
                                    )
                                }
                                is AppResult.Failure -> {
                                    validationError = res.error.userMessage
                                }
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = EmulatorColors.AccentBlue,
                            contentColor = EmulatorColors.TextPrimary,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp),
                    ) {
                        Text("Confirm", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
