package dev.smsforwarder.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.smsforwarder.ui.theme.ThemeMode

private fun themeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> "Sistema"
    ThemeMode.LIGHT -> "Claro"
    ThemeMode.DARK -> "Oscuro"
}

/** Both credential fields are hidden while typing and start empty: the stored token is never shown. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    form: SettingsForm,
    senders: List<String>,
    themeMode: ThemeMode,
    onUrlChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onSave: () -> Unit,
    onTest: () -> Unit,
    onAddSender: (String) -> Unit,
    onRemoveSender: (String) -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
    onOpenRecovery: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ajustes") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Atrás") } },
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
            Text("Conexión con el Worker", style = MaterialTheme.typography.titleMedium)
            Text(
                "Por seguridad la URL y el token guardados no se muestran. " +
                    "Escribe valores nuevos para sustituirlos.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = form.url,
                onValueChange = onUrlChange,
                label = { Text("URL del Worker") },
                singleLine = true,
                isError = form.urlError != null,
                supportingText = form.urlError?.let { error -> { Text(error) } },
                modifier = Modifier.fillMaxWidth().testTag("field_url"),
            )
            OutlinedTextField(
                value = form.token,
                onValueChange = onTokenChange,
                label = { Text("Token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().testTag("field_token"),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSave, enabled = !form.busy) { Text("Guardar credenciales") }
                OutlinedButton(onClick = onTest, enabled = !form.busy) { Text("Probar conexión") }
            }
            form.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

            HorizontalDivider()
            SendersSection(senders, onAddSender, onRemoveSender)

            HorizontalDivider()
            Text("Tema", style = MaterialTheme.typography.titleMedium)
            ThemeMode.entries.forEach { mode ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(selected = mode == themeMode, role = Role.RadioButton) { onThemeChange(mode) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = mode == themeMode, onClick = null)
                    Text(themeLabel(mode), modifier = Modifier.padding(start = 8.dp))
                }
            }

            HorizontalDivider()
            OutlinedButton(onClick = onOpenRecovery) { Text("Recuperar SMS") }
        }
    }
}

@Composable
private fun SendersSection(
    senders: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    Text("Remitentes", style = MaterialTheme.typography.titleMedium)
    if (senders.isEmpty()) {
        Text("No hay remitentes: la app no reenviará nada.", style = MaterialTheme.typography.bodySmall)
    }
    senders.forEach { sender ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(sender)
            TextButton(onClick = { onRemove(sender) }) { Text("Quitar") }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text("Nuevo remitente") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = {
                onAdd(draft)
                draft = ""
            },
            enabled = draft.isNotBlank(),
        ) { Text("Añadir") }
    }
}
