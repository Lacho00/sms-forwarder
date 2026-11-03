package dev.smsforwarder.ui.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.smsforwarder.R
import dev.smsforwarder.core.BodyMode
import dev.smsforwarder.core.RuleError
import dev.smsforwarder.core.SenderMode
import dev.smsforwarder.core.SimMatch

private fun errorRes(e: RuleError): Int = when (e) {
    RuleError.NAME_EMPTY -> R.string.rule_error_name_empty
    RuleError.SENDER_EMPTY -> R.string.rule_error_sender_empty
    RuleError.SENDER_REGEX_INVALID, RuleError.BODY_REGEX_INVALID -> R.string.rule_error_regex
    RuleError.BODY_EMPTY -> R.string.rule_error_body_empty
    RuleError.NO_DESTINATIONS -> R.string.rule_error_no_destinations
}

/** The messages among [mine] that are in [errors], one per line, or null when there are none. */
@Composable
private fun errorText(errors: Set<RuleError>, vararg mine: RuleError): String? {
    val found = mine.filter { it in errors }
    if (found.isEmpty()) return null
    return found.map { stringResource(errorRes(it)) }.joinToString("\n")
}

/** Full-screen form, no bottom bar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleEditorScreen(
    state: RuleEditorState,
    vm: RuleEditorViewModel,
    onBack: () -> Unit,
) {
    LaunchedEffect(state.done) { if (state.done) onBack() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (state.isNew) R.string.rule_new_title else R.string.rule_edit_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (!state.isNew) {
                        IconButton(onClick = vm::requestDelete) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.rule_delete))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.loadFailed) {
                Text(stringResource(R.string.rule_load_failed))
            } else if (state.loaded) {
                EditorForm(state, vm)
            }
        }
    }
    if (state.deleteAsked) {
        AlertDialog(
            onDismissRequest = vm::dismissDelete,
            title = { Text(stringResource(R.string.rule_delete_title)) },
            text = { Text(stringResource(R.string.rule_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = vm::confirmDelete) { Text(stringResource(R.string.detail_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissDelete) { Text(stringResource(R.string.detail_delete_cancel)) }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditorForm(state: RuleEditorState, vm: RuleEditorViewModel) {
    val d = state.draft
    val errors = state.errors

    OutlinedTextField(
        value = d.name,
        onValueChange = { v -> vm.update { it.copy(name = v) } },
        label = { Text(stringResource(R.string.rule_name)) },
        singleLine = true,
        isError = RuleError.NAME_EMPTY in errors,
        supportingText = errorText(errors, RuleError.NAME_EMPTY)?.let { { Text(it) } },
        modifier = Modifier.fillMaxWidth(),
    )
    SwitchRow(stringResource(R.string.rule_enabled), d.enabled) { v -> vm.update { it.copy(enabled = v) } }

    HorizontalDivider()
    Text(stringResource(R.string.rule_sender), style = MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SenderMode.entries.forEach { mode ->
            FilterChip(
                selected = d.senderMode == mode,
                onClick = { vm.update { it.copy(senderMode = mode) } },
                label = {
                    Text(
                        stringResource(
                            when (mode) {
                                SenderMode.EXACT -> R.string.rule_sender_exact
                                SenderMode.CONTAINS -> R.string.rule_sender_contains
                                SenderMode.REGEX -> R.string.rule_sender_regex
                            },
                        ),
                    )
                },
            )
        }
    }
    val senderError = errorText(errors, RuleError.SENDER_EMPTY, RuleError.SENDER_REGEX_INVALID)
    OutlinedTextField(
        value = d.senderPattern,
        onValueChange = { v -> vm.update { it.copy(senderPattern = v) } },
        label = { Text(stringResource(R.string.rule_sender)) },
        singleLine = true,
        isError = senderError != null,
        supportingText = {
            Text(
                senderError ?: stringResource(
                    when (d.senderMode) {
                        SenderMode.EXACT -> R.string.rule_sender_help_exact
                        SenderMode.CONTAINS -> R.string.rule_sender_help_contains
                        SenderMode.REGEX -> R.string.rule_sender_help_regex
                    },
                ),
            )
        },
        modifier = Modifier.fillMaxWidth(),
    )

    HorizontalDivider()
    Text(stringResource(R.string.rule_body), style = MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf<BodyMode?>(null, BodyMode.CONTAINS, BodyMode.NOT_CONTAINS, BodyMode.REGEX).forEach { mode ->
            FilterChip(
                selected = d.bodyMode == mode,
                onClick = { vm.setBodyMode(mode) },
                label = {
                    Text(
                        stringResource(
                            when (mode) {
                                null -> R.string.rule_body_any
                                BodyMode.CONTAINS -> R.string.rule_body_contains
                                BodyMode.NOT_CONTAINS -> R.string.rule_body_not_contains
                                BodyMode.REGEX -> R.string.rule_body_regex
                            },
                        ),
                    )
                },
            )
        }
    }
    val bodyMode = d.bodyMode
    if (bodyMode != null) {
        val bodyError = errorText(errors, RuleError.BODY_EMPTY, RuleError.BODY_REGEX_INVALID)
        OutlinedTextField(
            value = d.bodyPattern.orEmpty(),
            onValueChange = { v -> vm.update { it.copy(bodyPattern = v) } },
            label = { Text(stringResource(R.string.rule_body)) },
            isError = bodyError != null,
            supportingText = {
                Text(
                    bodyError ?: stringResource(
                        when (bodyMode) {
                            BodyMode.CONTAINS -> R.string.rule_body_help_contains
                            BodyMode.NOT_CONTAINS -> R.string.rule_body_help_not_contains
                            BodyMode.REGEX -> R.string.rule_body_help_regex
                        },
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }

    HorizontalDivider()
    Text(stringResource(R.string.rule_sim), style = MaterialTheme.typography.titleMedium)
    listOf(
        SimMatch.ANY to R.string.rule_sim_any,
        SimMatch.SIM1 to R.string.rule_sim_1,
        SimMatch.SIM2 to R.string.rule_sim_2,
    ).forEach { (sim, label) ->
        RadioRow(stringResource(label), d.sim == sim) { vm.update { it.copy(sim = sim) } }
    }

    HorizontalDivider()
    Text(stringResource(R.string.rule_destinations), style = MaterialTheme.typography.titleMedium)
    if (state.destinations.isEmpty()) {
        Text(stringResource(R.string.rule_destinations_none), style = MaterialTheme.typography.bodyMedium)
    }
    state.destinations.forEach { dest ->
        val checked = dest.id in d.destinationIds
        val label = if (dest.enabled) dest.name else dest.name + " · " + stringResource(R.string.destination_paused)
        Row(
            modifier = Modifier.fillMaxWidth().toggleable(
                value = checked,
                role = Role.Checkbox,
                onValueChange = { on ->
                    vm.update { it.copy(destinationIds = if (on) it.destinationIds + dest.id else it.destinationIds - dest.id) }
                },
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = checked, onCheckedChange = null)
            Text(label, modifier = Modifier.padding(start = 8.dp))
        }
    }
    errorText(errors, RuleError.NO_DESTINATIONS)?.let {
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }

    if (state.saveFailed) {
        Text(
            stringResource(R.string.rule_save_failed),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    Button(onClick = vm::save, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.rule_save))
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}
