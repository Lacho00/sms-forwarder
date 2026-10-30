package dev.smsforwarder.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.smsforwarder.health.HealthIssue
import java.text.DateFormat
import java.util.Date

internal fun issueTitle(issue: HealthIssue): String = when (issue) {
    HealthIssue.SMS_PERMISSION -> "Permisos de SMS no concedidos"
    HealthIssue.NOTIFICATION_PERMISSION -> "Notificaciones desactivadas"
    HealthIssue.BATTERY_OPTIMIZED -> "Optimización de batería activa"
    HealthIssue.BACKGROUND_RESTRICTED -> "Segundo plano restringido"
    HealthIssue.AUTOSTART_OFF -> "Inicio automático desactivado"
    HealthIssue.AUTOSTART_UNKNOWN -> "Inicio automático no comprobable"
    HealthIssue.MIUI_SMS_BLOCKED -> "MIUI bloquea la lectura de SMS"
    HealthIssue.TOKEN_REJECTED -> "Token rechazado por el Worker"
    HealthIssue.NOT_CONFIGURED -> "Falta configurar URL y token"
    HealthIssue.NO_SENDERS -> "No hay remitentes configurados"
}

internal fun formatDateTime(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))

/** "hace N min" style text for the status line; [now] is a parameter so it can be tested. */
internal fun agoLabel(lastSweepAt: Long?, now: Long): String {
    if (lastSweepAt == null) return "nunca"
    val minutes = (now - lastSweepAt).coerceAtLeast(0) / 60_000
    return when {
        minutes < 1 -> "hace menos de 1 min"
        minutes < 60 -> "hace $minutes min"
        minutes < 24 * 60 -> "hace ${minutes / 60} h"
        else -> "hace ${minutes / (24 * 60)} d"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    state: MainUiState,
    onOpenIssue: (HealthIssue) -> Unit,
    onSweepNow: () -> Unit,
    onOpen: (Long) -> Unit,
    onSettings: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SMS Forwarder") },
                actions = {
                    IconButton(
                        onClick = onSettings,
                        modifier = Modifier.semantics { contentDescription = "Ajustes" },
                    ) {
                        Text("⚙")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 8.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.issues.sortedBy { it.ordinal }, key = { it.name }) { issue ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(issueTitle(issue), style = MaterialTheme.typography.titleMedium)
                        Button(onClick = { onOpenIssue(issue) }) { Text("Abrir ajustes") }
                    }
                }
            }
            item(key = "status") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Último barrido: ${agoLabel(state.lastSweepAt, System.currentTimeMillis())}" +
                            " · Cola: ${state.queued} · Errores: ${state.errors}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = onSweepNow) { Text("Barrer bandeja ahora") }
                }
            }
            if (state.rows.isEmpty()) {
                item(key = "empty") { Text("Todavía no hay SMS", style = MaterialTheme.typography.bodyMedium) }
            }
            items(state.rows, key = { it.id }) { row ->
                SmsRow(row, onClick = { onOpen(row.id) })
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun SmsRow(row: RowUi, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        val sim = row.subId?.let { " · SIM $it" }.orEmpty()
        Text(row.sender + sim, style = MaterialTheme.typography.titleSmall)
        Text(formatDateTime(row.receivedAt), style = MaterialTheme.typography.bodySmall)
        Text(row.preview, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
        Text(row.label, style = MaterialTheme.typography.labelMedium)
    }
}
