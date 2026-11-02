package dev.smsforwarder.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.smsforwarder.R
import dev.smsforwarder.core.Language
import dev.smsforwarder.core.NotifyKind
import dev.smsforwarder.core.Retention
import dev.smsforwarder.core.StuckAfter
import dev.smsforwarder.core.SweepInterval
import dev.smsforwarder.health.HealthIssue
import dev.smsforwarder.ui.theme.ThemeMode

@Composable
private fun themeLabel(mode: ThemeMode): String = stringResource(
    when (mode) {
        ThemeMode.SYSTEM -> R.string.theme_system
        ThemeMode.LIGHT -> R.string.theme_light
        ThemeMode.DARK -> R.string.theme_dark
    },
)

/** Setters for every preference group. The screen only reports choices; the caller persists them. */
class SettingsActions(
    var setForwarding: (Boolean) -> Unit = {},
    var setSweepInterval: (SweepInterval) -> Unit = {},
    var setStuckAfter: (StuckAfter) -> Unit = {},
    var setRetention: (Retention) -> Unit = {},
    var setHideText: (Boolean) -> Unit = {},
    var setNotify: (NotifyKind, Boolean) -> Unit = { _, _ -> },
    var setTheme: (ThemeMode) -> Unit = {},
    var setDynamicColor: (Boolean) -> Unit = {},
    var setLanguage: (Language) -> Unit = {},
)

/**
 * Groups, in order: connection, senders, forwarding, history, notifications, appearance, maintenance.
 * Both credential fields are hidden while typing and start empty: the stored token is never shown.
 */
@Composable
fun SettingsScreen(
    form: SettingsForm,
    senders: List<String>,
    prefs: SettingsPrefs,
    actions: SettingsActions,
    onUrlChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onSave: () -> Unit,
    onTest: () -> Unit,
    onAddSender: (String) -> Unit,
    onRemoveSender: (String) -> Unit,
    onOpenRecovery: () -> Unit,
    onOpenSystemIssue: (HealthIssue) -> Unit,
    padding: PaddingValues = PaddingValues(),
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.settings_worker_connection), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.settings_credentials_hidden),
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = form.url,
            onValueChange = onUrlChange,
            label = { Text(stringResource(R.string.field_worker_url)) },
            singleLine = true,
            isError = form.urlError != null,
            supportingText = form.urlError?.let { error -> { Text(stringResource(error)) } },
            modifier = Modifier.fillMaxWidth().testTag("field_url"),
        )
        OutlinedTextField(
            value = form.token,
            onValueChange = onTokenChange,
            label = { Text(stringResource(R.string.field_token)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().testTag("field_token"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSave, enabled = !form.busy) { Text(stringResource(R.string.settings_save_credentials)) }
            OutlinedButton(onClick = onTest, enabled = !form.busy) { Text(stringResource(R.string.action_test_connection)) }
        }
        form.message?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium) }

        HorizontalDivider()
        SendersSection(senders, onAddSender, onRemoveSender)

        HorizontalDivider()
        Text(stringResource(R.string.settings_group_forwarding), style = MaterialTheme.typography.titleMedium)
        SwitchRow(stringResource(R.string.settings_forward_sms), prefs.forwarding, actions.setForwarding)
        Text(stringResource(R.string.settings_forward_sms_hint), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.settings_sweep_interval), style = MaterialTheme.typography.bodyLarge)
        ChoiceGroup(
            options = SweepInterval.entries,
            selected = prefs.sweepInterval,
            label = { stringResource(R.string.settings_sweep_option, it.minutes.toInt()) },
            onSelect = actions.setSweepInterval,
        )
        Text(stringResource(R.string.settings_stuck_after), style = MaterialTheme.typography.bodyLarge)
        ChoiceGroup(
            options = StuckAfter.entries,
            selected = prefs.stuckAfter,
            label = { stringResource(R.string.settings_stuck_option, it.hours.toInt()) },
            onSelect = actions.setStuckAfter,
        )

        HorizontalDivider()
        Text(stringResource(R.string.settings_group_history), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.settings_retention), style = MaterialTheme.typography.bodyLarge)
        ChoiceGroup(
            options = Retention.entries,
            selected = prefs.retention,
            label = { retention ->
                retention.days?.let { stringResource(R.string.settings_retention_days, it.toInt()) }
                    ?: stringResource(R.string.settings_retention_forever)
            },
            onSelect = actions.setRetention,
        )
        Text(stringResource(R.string.settings_retention_hint), style = MaterialTheme.typography.bodySmall)
        SwitchRow(stringResource(R.string.settings_hide_text), prefs.hideText, actions.setHideText)

        HorizontalDivider()
        Text(stringResource(R.string.settings_group_notifications), style = MaterialTheme.typography.titleMedium)
        NotifyKind.entries.forEach { kind ->
            SwitchRow(
                label = stringResource(
                    when (kind) {
                        NotifyKind.RECOVERED -> R.string.settings_notify_recovered
                        NotifyKind.STUCK -> R.string.settings_notify_stuck
                        NotifyKind.PERMISSIONS -> R.string.settings_notify_permissions
                    },
                ),
                checked = prefs.notify[kind] ?: true,
                onChange = { actions.setNotify(kind, it) },
            )
        }
        Text(stringResource(R.string.settings_notify_token_note), style = MaterialTheme.typography.bodySmall)

        HorizontalDivider()
        Text(stringResource(R.string.settings_group_appearance), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.bodyLarge)
        ChoiceGroup(
            options = ThemeMode.entries,
            selected = prefs.themeMode,
            label = { themeLabel(it) },
            onSelect = actions.setTheme,
        )
        SwitchRow(stringResource(R.string.settings_dynamic_color), prefs.dynamicColor, actions.setDynamicColor)
        Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.bodyLarge)
        ChoiceGroup(
            options = Language.entries,
            selected = prefs.language,
            label = {
                stringResource(
                    when (it) {
                        Language.SYSTEM -> R.string.language_system
                        Language.ES -> R.string.language_es
                        Language.EN -> R.string.language_en
                    },
                )
            },
            onSelect = actions.setLanguage,
        )

        HorizontalDivider()
        Text(stringResource(R.string.settings_group_maintenance), style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = onOpenRecovery) { Text(stringResource(R.string.recover_sms)) }
        // SMS_PERMISSION opens the permissions screen (the MIUI one on Xiaomi), which reaches the rest from there.
        OutlinedButton(onClick = { onOpenSystemIssue(HealthIssue.SMS_PERMISSION) }) {
            Text(stringResource(R.string.settings_permissions_button))
        }
    }
}

/** The radio-row list the v1 theme selector used, generalised to any option list. */
@Composable
private fun <T> ChoiceGroup(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    options.forEach { option ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(selected = option == selected, role = Role.RadioButton) { onSelect(option) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = option == selected, onClick = null)
            Text(label(option), modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun SendersSection(
    senders: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    Text(stringResource(R.string.settings_senders), style = MaterialTheme.typography.titleMedium)
    if (senders.isEmpty()) {
        Text(stringResource(R.string.settings_no_senders), style = MaterialTheme.typography.bodySmall)
    }
    senders.forEach { sender ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(sender)
            TextButton(onClick = { onRemove(sender) }) { Text(stringResource(R.string.action_remove)) }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text(stringResource(R.string.settings_new_sender)) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = {
                onAdd(draft)
                draft = ""
            },
            enabled = draft.isNotBlank(),
        ) { Text(stringResource(R.string.action_add)) }
    }
}
