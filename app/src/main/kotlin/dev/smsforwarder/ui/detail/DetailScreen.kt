package dev.smsforwarder.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.smsforwarder.core.SmsOrigin
import dev.smsforwarder.core.SmsStatus
import dev.smsforwarder.core.StatusLabel
import dev.smsforwarder.R
import dev.smsforwarder.data.AttemptEntity
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
private fun attemptLine(attempt: AttemptEntity): String {
    val result = attempt.httpCode?.toString() ?: stringResource(R.string.attempt_no_response)
    val detail = attempt.error?.let { " ($it)" }.orEmpty()
    return "${formatDateTime(attempt.at)} · $result$detail"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    state: DetailUiState,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.detail_title)) },
                navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.back)) } },
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
                DetailContent(sms, label, state.attempts, onRetry)
            }
        }
    }
}

@Composable
private fun DetailContent(sms: SmsEntity, label: StatusLabel, attempts: List<AttemptEntity>, onRetry: () -> Unit) {
    Field(stringResource(R.string.field_sender), sms.sender)
    Field(stringResource(R.string.detail_field_sim), sms.subId?.toString() ?: stringResource(R.string.detail_sim_unknown))
    Field(stringResource(R.string.detail_field_time), formatDateTime(sms.receivedAt))
    Field(stringResource(R.string.detail_field_origin), originLabel(sms.origin))
    Field(stringResource(R.string.detail_field_status), statusText(label))
    Field(stringResource(R.string.detail_field_text), sms.body)
    if (sms.status == SmsStatus.ERROR) {
        Button(onClick = onRetry) { Text(stringResource(R.string.detail_retry)) }
    }
    Text(stringResource(R.string.detail_attempts), style = MaterialTheme.typography.titleSmall)
    if (attempts.isEmpty()) {
        Text(stringResource(R.string.detail_no_attempts), style = MaterialTheme.typography.bodyMedium)
    } else {
        attempts.forEach { Text(attemptLine(it), style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun Field(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
