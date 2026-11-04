package dev.smsforwarder.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.smsforwarder.core.SmsOrigin
import dev.smsforwarder.core.SmsStatus
import dev.smsforwarder.core.StatusLabel
import dev.smsforwarder.R
import dev.smsforwarder.data.AttemptEntity
import dev.smsforwarder.ui.main.toChip
import dev.smsforwarder.ui.main.deliveryChipText
import dev.smsforwarder.data.SmsEntity
import dev.smsforwarder.ui.main.formatDateTime
import dev.smsforwarder.ui.main.statusText

@Composable
private fun originLabel(origin: SmsOrigin): String = stringResource(
    when (origin) {
        SmsOrigin.RECEIVER -> R.string.origin_receiver
        SmsOrigin.SWEEP -> R.string.origin_sweep
        SmsOrigin.MANUAL -> R.string.origin_manual
    },
)

@Composable
private fun attemptLine(attempt: AttemptEntity, destinationName: String?): String {
    val result = attempt.httpCode?.toString() ?: stringResource(R.string.attempt_no_response)
    val detail = attempt.error?.let { " ($it)" }.orEmpty()
    val line = "${formatDateTime(attempt.at)} · $result$detail"
    // Attempts from before destinations existed carry no delivery, so they carry no name either.
    return if (destinationName == null) line else stringResource(R.string.detail_attempt_destination, destinationName, line)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    state: DetailUiState,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
    onResendTo: (Long) -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        val sms = state.sms
        val label = state.label
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (sms == null || label == null) {
                if (state.loaded) Text(stringResource(R.string.detail_not_found))
            } else {
                DetailContent(sms, label, state, onRetry, onDelete, onResendTo)
            }
        }
    }
}

@Composable
private fun DetailContent(
    sms: SmsEntity,
    label: StatusLabel,
    state: DetailUiState,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onResendTo: (Long) -> Unit,
) {
    val attempts = state.attempts
    val hideText = state.hideText
    var revealed by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var pickDestination by rememberSaveable { mutableStateOf(false) }
    Field(stringResource(R.string.field_sender), sms.sender)
    Field(stringResource(R.string.detail_field_sim), sms.subId?.toString() ?: stringResource(R.string.detail_sim_unknown))
    Field(stringResource(R.string.detail_field_time), formatDateTime(sms.receivedAt))
    Field(stringResource(R.string.detail_field_origin), originLabel(sms.origin))
    Field(stringResource(R.string.detail_field_status), statusText(label))
    if (hideText && !revealed) {
        Column {
            Text(stringResource(R.string.detail_field_text), style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = { revealed = true }) { Text(stringResource(R.string.detail_show_text)) }
        }
    } else {
        Field(stringResource(R.string.detail_field_text), sms.body)
    }
    if (state.deliveries.isNotEmpty()) {
        Column {
            Text(stringResource(R.string.detail_deliveries), style = MaterialTheme.typography.labelMedium)
            state.deliveries.forEach { Text(deliveryChipText(it.toChip()), style = MaterialTheme.typography.bodyLarge) }
        }
    }
    if (sms.status == SmsStatus.ERROR) {
        Button(onClick = onRetry) { Text(stringResource(R.string.detail_retry)) }
    }
    if (state.destinations.isNotEmpty()) {
        OutlinedButton(onClick = { pickDestination = true }) { Text(stringResource(R.string.detail_resend_to)) }
    }
    if (pickDestination) {
        AlertDialog(
            onDismissRequest = { pickDestination = false },
            title = { Text(stringResource(R.string.detail_resend_to_title)) },
            text = {
                Column {
                    state.destinations.forEach { destination ->
                        TextButton(onClick = {
                            pickDestination = false
                            onResendTo(destination.id)
                        }) {
                            val paused = if (destination.enabled) "" else " · " + stringResource(R.string.destination_paused)
                            Text(destination.name + paused)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { pickDestination = false }) { Text(stringResource(R.string.detail_delete_cancel)) }
            },
        )
    }
    OutlinedButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.detail_delete)) }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.detail_delete_title)) },
            text = { Text(stringResource(R.string.detail_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text(stringResource(R.string.detail_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.detail_delete_cancel)) }
            },
        )
    }
    Text(stringResource(R.string.detail_attempts), style = MaterialTheme.typography.titleSmall)
    if (attempts.isEmpty()) {
        Text(stringResource(R.string.detail_no_attempts), style = MaterialTheme.typography.bodyMedium)
    } else {
        val names = state.deliveries.associate { it.id to it.destinationName }
        attempts.forEach { Text(attemptLine(it, it.deliveryId?.let(names::get)), style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun Field(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
