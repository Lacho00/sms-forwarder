package dev.smsforwarder.ui.destinations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import dev.smsforwarder.ui.components.BottomActionBar
import dev.smsforwarder.ui.components.DestructiveButton
import dev.smsforwarder.ui.components.DropdownField
import dev.smsforwarder.R
import dev.smsforwarder.core.DestinationError
import dev.smsforwarder.core.DestinationTestResult
import dev.smsforwarder.core.HttpMethod
import dev.smsforwarder.core.SuccessPolicy
import dev.smsforwarder.core.TemplateKind
import dev.smsforwarder.core.TestVerdict

internal const val STORED_LOCK_TAG = "stored_lock"

private fun errorRes(e: DestinationError): Int = when (e) {
    DestinationError.NAME_EMPTY -> R.string.destination_error_name_empty
    DestinationError.URL_MISSING -> R.string.destination_error_url_missing
    DestinationError.URL_INVALID -> R.string.destination_error_url_invalid
    DestinationError.URL_NOT_HTTPS -> R.string.destination_error_url_not_https
    DestinationError.UNKNOWN_VARIABLE -> R.string.destination_error_unknown_variable
    DestinationError.HEADER_NAME_INVALID -> R.string.destination_error_header_name_invalid
    DestinationError.HEADER_VALUE_INVALID -> R.string.destination_error_header_value_invalid
    DestinationError.BODY_NOT_JSON -> R.string.destination_error_body_not_json
    DestinationError.BODY_WITH_GET -> R.string.destination_error_body_with_get
}

/** The error messages among [mine] that are in [errors], one per line, or null when there are none. */
@Composable
private fun errorText(errors: Set<DestinationError>, vararg mine: DestinationError): String? {
    val found = mine.filter { it in errors }
    if (found.isEmpty()) return null
    return found.map { stringResource(errorRes(it)) }.joinToString("\n")
}

@Composable
private fun testText(r: DestinationTestResult): String {
    val verdict = stringResource(
        when (r.verdict) {
            TestVerdict.OK -> R.string.destination_test_ok
            TestVerdict.BAD_TOKEN -> R.string.destination_test_bad_token
            TestVerdict.FAILED -> R.string.destination_test_failed
            TestVerdict.UNREACHABLE -> R.string.destination_test_unreachable
        },
    )
    return r.httpCode?.let { "$verdict · $it" } ?: verdict
}

/** Full-screen form; Test and Save are pinned in the bottom bar. Stored secrets are never shown: only a hint that they are kept. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DestinationEditorScreen(
    state: DestinationEditorState,
    vm: DestinationEditorViewModel,
    onBack: () -> Unit,
) {
    LaunchedEffect(state.done) { if (state.done) onBack() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(if (state.isNew) R.string.destination_new_title else R.string.destination_edit_title))
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        bottomBar = { if (state.loaded && !state.loadFailed) ActionBar(state, vm) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.loadFailed) {
                Text(stringResource(R.string.destination_load_failed))
            } else if (state.loaded) {
                EditorForm(state, vm)
            }
        }
    }
    state.deletePending?.let { n ->
        AlertDialog(
            onDismissRequest = vm::dismissDelete,
            title = { Text(stringResource(R.string.destination_delete_title)) },
            text = { Text(pluralStringResource(R.plurals.destination_delete_confirm, n, n)) },
            confirmButton = {
                TextButton(onClick = vm::confirmDelete) { Text(stringResource(R.string.detail_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissDelete) { Text(stringResource(R.string.detail_delete_cancel)) }
            },
        )
    }
}

@Composable
private fun EditorForm(state: DestinationEditorState, vm: DestinationEditorViewModel) {
    val errors = state.errors
    if (state.isNew) {
        // A dropdown, not a segmented row: the four labels do not fit at 360 dp (measured), they ellipsize.
        DropdownField(
            label = stringResource(R.string.destination_template),
            options = TemplateKind.entries,
            selected = state.template,
            optionLabel = { stringResource(templateLabelRes(it)) },
            onSelect = vm::chooseTemplate,
        )
        PresetFields(state, vm)
        HorizontalDivider()
    }

    OutlinedTextField(
        value = state.name,
        onValueChange = vm::setName,
        label = { Text(stringResource(R.string.destination_name)) },
        singleLine = true,
        isError = DestinationError.NAME_EMPTY in errors,
        supportingText = errorText(errors, DestinationError.NAME_EMPTY)?.let { { Text(it) } },
        modifier = Modifier.fillMaxWidth(),
    )
    SwitchRow(stringResource(R.string.destination_enabled), state.enabled, vm::setEnabled)
    DropdownField(
        label = stringResource(R.string.destination_method),
        options = HttpMethod.entries,
        selected = state.method,
        optionLabel = { it.name },
        onSelect = vm::setMethod,
    )

    val urlError = errorText(
        errors, DestinationError.URL_MISSING, DestinationError.URL_INVALID, DestinationError.URL_NOT_HTTPS,
    )
    OutlinedTextField(
        value = state.url,
        onValueChange = vm::setUrl,
        label = { Text(stringResource(R.string.destination_url)) },
        singleLine = true,
        isError = urlError != null,
        supportingText = when {
            urlError != null -> ({ Text(urlError) })
            state.urlStored && state.url.isEmpty() -> ({ StoredHelper() })
            else -> null
        },
        modifier = Modifier.fillMaxWidth(),
    )

    HeadersSection(state, vm)

    val bodyError = errorText(
        errors, DestinationError.UNKNOWN_VARIABLE, DestinationError.BODY_NOT_JSON, DestinationError.BODY_WITH_GET,
    )
    OutlinedTextField(
        value = state.body,
        onValueChange = vm::setBody,
        label = { Text(stringResource(R.string.destination_body)) },
        isError = bodyError != null,
        supportingText = bodyError?.let { { Text(it) } },
        minLines = 3,
        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
        modifier = Modifier.fillMaxWidth(),
    )
    SwitchRow(stringResource(R.string.destination_body_json), state.bodyIsJson, vm::setBodyIsJson)

    Text(stringResource(R.string.destination_success_policy), style = MaterialTheme.typography.titleMedium)
    RadioRow(stringResource(R.string.destination_success_2xx), state.successPolicy == SuccessPolicy.ANY_2XX) {
        vm.setSuccessPolicy(SuccessPolicy.ANY_2XX)
    }
    RadioRow(stringResource(R.string.destination_success_200), state.successPolicy == SuccessPolicy.STATUS_200) {
        vm.setSuccessPolicy(SuccessPolicy.STATUS_200)
    }

    if (!state.isNew) {
        Spacer(Modifier.height(8.dp))
        DestructiveButton(
            text = stringResource(R.string.destination_delete),
            onClick = vm::requestDelete,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Test above Save, pinned; the test result and a failed save are shown right above the buttons. */
@Composable
private fun ActionBar(state: DestinationEditorState, vm: DestinationEditorViewModel) {
    BottomActionBar {
        state.test?.let { Text(testText(it), style = MaterialTheme.typography.bodyMedium) }
        if (state.saveFailed) {
            Text(
                stringResource(R.string.destination_save_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        OutlinedButton(onClick = vm::test, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.destination_test))
        }
        Button(onClick = vm::save, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.destination_save))
        }
    }
}

/** "Stored. Leave empty to keep it." with a lock, so it reads as a kept secret and not as an error. */
@Composable
private fun StoredHelper() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(16.dp).testTag(STORED_LOCK_TAG))
        Spacer(Modifier.width(4.dp))
        Text(stringResource(R.string.destination_secret_kept))
    }
}

/** What the chosen template asks for before it fills in the form. Nothing for Custom. */
@Composable
private fun PresetFields(state: DestinationEditorState, vm: DestinationEditorViewModel) {
    val p = state.preset
    when (state.template) {
        TemplateKind.CUSTOM -> return
        TemplateKind.STANDARD_JSON -> {
            PresetField(p.url, R.string.destination_preset_url, false) { vm.setPreset(p.copy(url = it)) }
            PresetField(p.token, R.string.field_token, true) { vm.setPreset(p.copy(token = it)) }
        }
        TemplateKind.TELEGRAM -> {
            PresetField(p.token, R.string.destination_preset_bot_token, true) { vm.setPreset(p.copy(token = it)) }
            PresetField(p.chatId, R.string.destination_preset_chat_id, false) { vm.setPreset(p.copy(chatId = it)) }
        }
        TemplateKind.NTFY ->
            PresetField(p.url, R.string.destination_preset_url, false) { vm.setPreset(p.copy(url = it)) }
    }
    OutlinedButton(onClick = vm::applyPreset) { Text(stringResource(R.string.destination_preset_apply)) }
}

@Composable
private fun PresetField(value: String, label: Int, hidden: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        singleLine = true,
        visualTransformation = if (hidden) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun HeadersSection(state: DestinationEditorState, vm: DestinationEditorViewModel) {
    val headers = state.headers
    val error = errorText(state.errors, DestinationError.HEADER_NAME_INVALID, DestinationError.HEADER_VALUE_INVALID)
    Text(stringResource(R.string.destination_headers), style = MaterialTheme.typography.titleMedium)
    headers.forEachIndexed { index, h ->
        fun change(next: HeaderRowUi) = vm.setHeaders(headers.toMutableList().also { it[index] = next })
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // The name field takes the remaining width: a long name scrolls inside it and never moves the remove button.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = h.name,
                        onValueChange = { change(h.copy(name = it)) },
                        label = { Text(stringResource(R.string.destination_header_name)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { vm.setHeaders(headers.filterIndexed { i, _ -> i != index }) }) {
                        val who = h.name.ifBlank { stringResource(R.string.destination_header_fallback_name, index + 1) }
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.destination_header_remove, who))
                    }
                }
                val keeps = h.secret && h.stored && h.value.isEmpty() && h.storedName == h.name
                OutlinedTextField(
                    value = h.value,
                    onValueChange = { change(h.copy(value = it)) },
                    label = { Text(stringResource(R.string.destination_header_value)) },
                    singleLine = true,
                    visualTransformation = if (h.secret) PasswordVisualTransformation() else VisualTransformation.None,
                    supportingText = if (keeps) ({ StoredHelper() }) else null,
                    modifier = Modifier.fillMaxWidth(),
                )
                SwitchRow(stringResource(R.string.destination_header_secret), h.secret) { change(h.copy(secret = it)) }
            }
        }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    OutlinedButton(onClick = { vm.setHeaders(headers + HeaderRowUi("", "", secret = false, stored = false)) }) {
        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(stringResource(R.string.destination_header_add))
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}
