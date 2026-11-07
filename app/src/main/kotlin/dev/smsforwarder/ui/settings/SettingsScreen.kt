package dev.smsforwarder.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.smsforwarder.R
import dev.smsforwarder.core.Language
import dev.smsforwarder.core.NotifyKind
import dev.smsforwarder.core.Retention
import dev.smsforwarder.core.StuckAfter
import dev.smsforwarder.core.SweepInterval
import dev.smsforwarder.health.HealthIssue
import dev.smsforwarder.ui.components.SectionTitle
import dev.smsforwarder.ui.components.SegmentedChoice
import dev.smsforwarder.ui.components.SettingRow
import dev.smsforwarder.ui.components.SingleChoiceDialog
import dev.smsforwarder.ui.theme.StatusTone
import dev.smsforwarder.ui.theme.toneColors
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

internal const val TOKEN_NOTE_ICON_TAG = "token_note_icon"

private val RowInset = Modifier.padding(horizontal = 16.dp)

/**
 * Groups, in order: forwarding, history, notifications, appearance, maintenance.
 */
@Composable
fun SettingsScreen(
    prefs: SettingsPrefs,
    actions: SettingsActions,
    onOpenRecovery: () -> Unit,
    onOpenSystemIssue: (HealthIssue) -> Unit,
    padding: PaddingValues = PaddingValues(),
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp),
    ) {
        SectionTitle(stringResource(R.string.settings_group_forwarding), RowInset)
        SwitchRow(
            stringResource(R.string.settings_forward_sms),
            prefs.forwarding,
            actions.setForwarding,
            supporting = stringResource(R.string.settings_forward_sms_hint),
        )
        ChoiceRow(
            title = stringResource(R.string.settings_sweep_interval),
            options = SweepInterval.entries,
            selected = prefs.sweepInterval,
            label = { stringResource(R.string.settings_sweep_option, it.minutes.toInt()) },
            onSelect = actions.setSweepInterval,
        )
        ChoiceRow(
            title = stringResource(R.string.settings_stuck_after),
            options = StuckAfter.entries,
            selected = prefs.stuckAfter,
            label = { stringResource(R.string.settings_stuck_option, it.hours.toInt()) },
            onSelect = actions.setStuckAfter,
        )

        SectionTitle(stringResource(R.string.settings_group_history), RowInset)
        ChoiceRow(
            title = stringResource(R.string.settings_retention),
            options = Retention.entries,
            selected = prefs.retention,
            label = { retention ->
                retention.days?.let { stringResource(R.string.settings_retention_days, it.toInt()) }
                    ?: stringResource(R.string.settings_retention_forever)
            },
            onSelect = actions.setRetention,
        )
        Text(
            stringResource(R.string.settings_retention_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = RowInset,
        )
        SwitchRow(stringResource(R.string.settings_hide_text), prefs.hideText, actions.setHideText)

        SectionTitle(stringResource(R.string.settings_group_notifications), RowInset)
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
        TokenNote()

        SectionTitle(stringResource(R.string.settings_group_appearance), RowInset)
        Text(
            stringResource(R.string.settings_theme),
            style = MaterialTheme.typography.bodyLarge,
            modifier = RowInset.padding(bottom = 8.dp),
        )
        SegmentedChoice(
            options = ThemeMode.entries,
            selected = prefs.themeMode,
            label = { themeLabel(it) },
            onSelect = actions.setTheme,
            modifier = RowInset,
        )
        SwitchRow(stringResource(R.string.settings_dynamic_color), prefs.dynamicColor, actions.setDynamicColor)
        ChoiceRow(
            title = stringResource(R.string.settings_language),
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

        SectionTitle(stringResource(R.string.settings_group_maintenance), RowInset)
        SettingRow(
            title = stringResource(R.string.recover_sms),
            leadingIcon = rememberVectorPainter(Icons.Filled.Refresh),
            chevron = true,
            onClick = onOpenRecovery,
        )
        // SMS_PERMISSION opens the permissions screen (the MIUI one on Xiaomi), which reaches the rest from there.
        SettingRow(
            title = stringResource(R.string.settings_permissions_button),
            leadingIcon = rememberVectorPainter(Icons.Filled.Settings),
            chevron = true,
            onClick = { onOpenSystemIssue(HealthIssue.SMS_PERMISSION) },
        )
    }
}

/** The rejected-token alert cannot be turned off: a real warning, so icon plus PENDING tone. */
@Composable
private fun TokenNote() {
    val tone = toneColors(StatusTone.PENDING)
    Surface(
        color = tone.container,
        contentColor = tone.onContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Filled.Info,
                contentDescription = null,
                modifier = Modifier.size(20.dp).testTag(TOKEN_NOTE_ICON_TAG),
            )
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.settings_notify_token_note), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** A row with the current value as supporting text; tapping opens a dialog with the radio list. */
@Composable
private fun <T> ChoiceRow(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    SettingRow(title = title, supporting = label(selected), onClick = { open = true })
    if (open) {
        SingleChoiceDialog(
            title = title,
            options = options,
            selected = selected,
            label = label,
            onSelect = onSelect,
            onDismiss = { open = false },
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, supporting: String? = null) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = supporting?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
    )
}
