package dev.smsforwarder.ui.recovery

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.smsforwarder.ui.main.formatDateTime

/** Explanation + checklist, shared by the Recovery screen and the last onboarding step. */
@Composable
fun RecoveryList(
    items: List<RecoveryItem>,
    unreadable: Boolean,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item(key = "intro") {
            Text(
                "SMS de los últimos 7 días de tus remitentes. Ninguno está marcado: " +
                    "elige los que quieras enviar. Los que la app ya tiene aparecen desactivados.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        if (unreadable) {
            item(key = "unreadable") {
                Text("No se puede leer la bandeja de SMS. Revisa los permisos.")
            }
        } else if (items.isEmpty()) {
            item(key = "empty") { Text("No hay SMS de tus remitentes en los últimos 7 días.") }
        }
        items(items, key = { it.key }) { item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !item.alreadyKnown) { onToggle(item.key) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = item.selected,
                    onCheckedChange = { onToggle(item.key) },
                    enabled = !item.alreadyKnown,
                )
                Column(modifier = Modifier.padding(start = 8.dp)) {
                    Text(item.sender, style = MaterialTheme.typography.titleSmall)
                    Text(formatDateTime(item.receivedAt), style = MaterialTheme.typography.bodySmall)
                    Text(item.preview, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                    if (item.alreadyKnown) {
                        Text("Ya guardado en la app", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            HorizontalDivider()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecoveryScreen(
    items: List<RecoveryItem>,
    unreadable: Boolean,
    resultMessage: String?,
    onToggle: (String) -> Unit,
    onSend: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recuperar SMS") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Atrás") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RecoveryList(items, unreadable, onToggle, modifier = Modifier.weight(1f))
            resultMessage?.let { Text(it) }
            val count = items.count { it.selected }
            Button(onClick = onSend, enabled = count > 0) { Text("Enviar seleccionados ($count)") }
        }
    }
}
