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
import androidx.compose.ui.unit.dp
import dev.smsforwarder.core.SmsOrigin
import dev.smsforwarder.core.SmsStatus
import dev.smsforwarder.core.statusLabel
import dev.smsforwarder.data.AttemptEntity
import dev.smsforwarder.data.SmsEntity
import dev.smsforwarder.ui.main.formatDateTime

private fun originLabel(origin: SmsOrigin): String = when (origin) {
    SmsOrigin.RECEIVER -> "Receptor"
    SmsOrigin.SWEEP -> "Barrido de la bandeja"
    SmsOrigin.MANUAL -> "Recuperación manual"
}

private fun attemptLine(attempt: AttemptEntity): String {
    val result = attempt.httpCode?.toString() ?: "sin respuesta"
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
                title = { Text("Detalle del SMS") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Atrás") } },
            )
        },
    ) { padding ->
        val sms = state.sms
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (sms == null) {
                if (state.loaded) Text("SMS no encontrado")
            } else {
                DetailContent(sms, state.attempts, onRetry)
            }
        }
    }
}

@Composable
private fun DetailContent(sms: SmsEntity, attempts: List<AttemptEntity>, onRetry: () -> Unit) {
    Field("Remitente", sms.sender)
    Field("SIM", sms.subId?.toString() ?: "Desconocida")
    Field("Hora", formatDateTime(sms.receivedAt))
    Field("Origen", originLabel(sms.origin))
    Field("Estado", statusLabel(sms.status, sms.origin, sms.attempts, sms.lastHttpCode))
    Field("Texto", sms.body)
    if (sms.status == SmsStatus.ERROR) {
        Button(onClick = onRetry) { Text("Reintentar") }
    }
    Text("Intentos", style = MaterialTheme.typography.titleSmall)
    if (attempts.isEmpty()) {
        Text("Ninguno todavía", style = MaterialTheme.typography.bodyMedium)
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
