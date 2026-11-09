package dev.smsforwarder.ui.transfer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.smsforwarder.R
import dev.smsforwarder.core.ConfigError
import dev.smsforwarder.core.ImportPlan
import dev.smsforwarder.ui.theme.StatusTone
import dev.smsforwarder.ui.theme.toneColors

internal fun configErrorRes(error: ConfigError): Int = when (error) {
    ConfigError.TOO_LARGE -> R.string.import_error_too_large
    ConfigError.NOT_JSON -> R.string.import_error_not_json
    ConfigError.WRONG_FORMAT -> R.string.import_error_wrong_format
    ConfigError.NEWER_VERSION -> R.string.import_error_newer_version
    ConfigError.BAD_VALUE -> R.string.import_error_bad_value
    ConfigError.TOO_MANY -> R.string.import_error_too_many
    ConfigError.DUPLICATE_KEY -> R.string.import_error_duplicate_key
    ConfigError.UNKNOWN_DESTINATION -> R.string.import_error_unknown_destination
    ConfigError.INVALID_DESTINATION -> R.string.import_error_invalid_destination
    ConfigError.INVALID_RULE -> R.string.import_error_invalid_rule
}

internal fun messageRes(message: TransferMessage): Int = when (message) {
    TransferMessage.Exported -> R.string.transfer_exported
    // The result dialog builds the imported lines from plurals and never asks for this.
    is TransferMessage.Imported -> error("Imported uses plurals")
    TransferMessage.ReadFailed -> R.string.transfer_read_failed
    TransferMessage.WriteFailed -> R.string.transfer_write_failed
    TransferMessage.SecretsUnavailable -> R.string.transfer_secrets_unavailable
    TransferMessage.ExportInterrupted -> R.string.transfer_export_interrupted
    TransferMessage.PickerUnavailable -> R.string.transfer_picker_unavailable
    TransferMessage.SecretsNotSaved -> R.string.transfer_secrets_not_saved
}

/** The dialogs of the export/import flow, drawn over any tab. Nothing is drawn for the other states. */
@Composable
fun TransferDialogs(
    state: TransferUi,
    onIncludeSecrets: (Boolean) -> Unit,
    onConfirmExport: () -> Unit,
    onConfirmImport: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        is TransferUi.AskExport -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.export_title)) },
            text = {
                Column {
                    Row(
                        Modifier.fillMaxWidth().toggleable(
                            value = state.includeSecrets,
                            role = Role.Checkbox,
                            onValueChange = onIncludeSecrets,
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = state.includeSecrets, onCheckedChange = null)
                        Text(stringResource(R.string.export_secrets_label), Modifier.padding(start = 8.dp))
                    }
                    if (state.includeSecrets) {
                        val tone = toneColors(StatusTone.PENDING)
                        Row(
                            Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).background(tone.container).padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Warning, contentDescription = null, tint = tone.onContainer)
                            Text(
                                stringResource(R.string.export_secrets_warning),
                                Modifier.padding(start = 12.dp),
                                color = tone.onContainer,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onConfirmExport) { Text(stringResource(R.string.export_confirm)) } },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.transfer_cancel)) } },
        )
        is TransferUi.ImportSummary -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.import_title)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { SummaryBody(state.plan) } },
            confirmButton = { TextButton(onClick = onConfirmImport) { Text(stringResource(R.string.import_confirm)) } },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.transfer_cancel)) } },
        )
        is TransferUi.ImportInvalid -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.import_invalid_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(configErrorRes(state.error)))
                    state.item?.let { Text(stringResource(R.string.import_invalid_item, it)) }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.transfer_close)) } },
        )
        is TransferUi.Done -> AlertDialog(
            onDismissRequest = onDismiss,
            text = {
                val m = state.message
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (m is TransferMessage.Imported) {
                        val r = m.result
                        Text(pluralStringResource(R.plurals.transfer_imported_destinations, r.destinations, r.destinations))
                        Text(pluralStringResource(R.plurals.transfer_imported_rules, r.rules, r.rules))
                        if (r.withoutCredentials > 0) {
                            Text(
                                pluralStringResource(R.plurals.transfer_imported_without_credentials, r.withoutCredentials, r.withoutCredentials),
                                Modifier.padding(top = 8.dp),
                            )
                        }
                    } else {
                        Text(stringResource(messageRes(m)))
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.transfer_close)) } },
        )
        TransferUi.Waiting -> AlertDialog(
            onDismissRequest = {},
            text = {
                val working = stringResource(R.string.transfer_working)
                CircularProgressIndicator(Modifier.semantics { contentDescription = working })
            },
            confirmButton = {},
        )
        else -> Unit
    }
}

@Composable
private fun SummaryBody(plan: ImportPlan) {
    Text(pluralStringResource(R.plurals.import_summary_destinations, plan.destinations.size, plan.destinations.size))
    Text(pluralStringResource(R.plurals.import_summary_rules, plan.rules.size, plan.rules.size))
    plan.renamed.forEach { (from, to) ->
        Text(stringResource(R.string.import_renamed, from, to), Modifier.padding(top = 8.dp))
    }
    if (plan.withoutCredentials.isNotEmpty()) {
        Text(
            stringResource(R.string.import_without_credentials, plan.withoutCredentials.joinToString(", ")),
            Modifier.padding(top = 8.dp),
        )
    }
    plan.destinations.filter { it.droppedSecretHeaders.isNotEmpty() }.forEach {
        Text(
            stringResource(R.string.import_dropped_headers, it.draft.name, it.droppedSecretHeaders.joinToString(", ")),
            Modifier.padding(top = 8.dp),
        )
    }
}
