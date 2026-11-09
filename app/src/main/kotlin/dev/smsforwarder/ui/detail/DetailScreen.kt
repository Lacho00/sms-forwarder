package dev.smsforwarder.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.smsforwarder.R
import dev.smsforwarder.core.SmsOrigin
import dev.smsforwarder.core.SmsStatus
import dev.smsforwarder.core.StatusLabel
import dev.smsforwarder.data.AttemptEntity
import dev.smsforwarder.data.SmsEntity
import dev.smsforwarder.ui.components.DestructiveButton
import dev.smsforwarder.ui.components.SectionTitle
import dev.smsforwarder.ui.main.deliveryChipText
import dev.smsforwarder.ui.main.deliveryStateWord
import dev.smsforwarder.ui.main.formatDateTime
import dev.smsforwarder.ui.main.statusText
import dev.smsforwarder.ui.main.toChip
import dev.smsforwarder.ui.theme.StatusPill
import dev.smsforwarder.ui.theme.StatusTone
import dev.smsforwarder.ui.theme.toneOf

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
    val time = formatDateTime(attempt.at)
    // Attempts from before destinations existed carry no delivery, so they carry no name either.
    return if (destinationName == null) time else stringResource(R.string.detail_attempt_destination, destinationName, time)
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

@OptIn(ExperimentalLayoutApi::class)
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
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            val title = sms.subId?.let { stringResource(R.string.row_sender_sim, sms.sender, it) } ?: sms.sender
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                formatDateTime(sms.receivedAt) + " · " + originLabel(sms.origin),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        StatusPill(toneOf(label.kind), statusText(label))
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (hideText && !revealed) {
                Text(stringResource(R.string.detail_field_text), style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = { revealed = true }) { Text(stringResource(R.string.detail_show_text)) }
            } else {
                SelectionContainer { Text(sms.body, style = MaterialTheme.typography.bodyLarge) }
            }
        }
    }
    if (state.deliveries.isNotEmpty()) {
        SectionTitle(stringResource(R.string.detail_destinations))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.deliveries.forEach {
                StatusPill(toneOf(it.status), deliveryChipText(it.toChip()), iconDescription = deliveryStateWord(it.status))
            }
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (sms.status == SmsStatus.ERROR) {
            Button(onClick = onRetry) { Text(stringResource(R.string.detail_retry)) }
        }
        if (state.destinations.isNotEmpty()) {
            OutlinedButton(onClick = { pickDestination = true }) { Text(stringResource(R.string.detail_resend_to)) }
        }
        DestructiveButton(stringResource(R.string.detail_delete), onClick = { confirmDelete = true })
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
    SectionTitle(stringResource(R.string.detail_attempts))
    if (attempts.isEmpty()) {
        Text(stringResource(R.string.detail_no_attempts), style = MaterialTheme.typography.bodyMedium)
    } else {
        val names = state.deliveries.associate { it.id to it.destinationName }
        attempts.forEach { attempt ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(attemptLine(attempt, attempt.deliveryId?.let(names::get)), style = MaterialTheme.typography.bodyMedium)
                    attempt.error?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                val code = attempt.httpCode
                StatusPill(
                    tone = when {
                        code == null -> StatusTone.NEUTRAL
                        code in 200..299 -> StatusTone.OK
                        else -> StatusTone.ERROR
                    },
                    text = code?.toString() ?: stringResource(R.string.attempt_no_response),
                )
            }
        }
    }
}
