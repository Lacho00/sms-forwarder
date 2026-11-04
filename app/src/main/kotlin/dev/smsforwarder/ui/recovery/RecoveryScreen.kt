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
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.smsforwarder.R
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
                stringResource(R.string.recovery_intro),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        if (unreadable) {
            item(key = "unreadable") {
                Text(stringResource(R.string.recovery_unreadable))
            }
        } else if (items.isEmpty()) {
            item(key = "empty") { Text(stringResource(R.string.recovery_empty)) }
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
                        Text(stringResource(R.string.recovery_already_saved), style = MaterialTheme.typography.labelMedium)
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
                title = { Text(stringResource(R.string.recover_sms)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
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
            Button(onClick = onSend, enabled = count > 0) { Text(stringResource(R.string.recovery_send_selected, count)) }
        }
    }
}
