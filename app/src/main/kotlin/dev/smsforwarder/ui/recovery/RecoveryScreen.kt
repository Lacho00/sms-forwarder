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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.smsforwarder.R
import dev.smsforwarder.ui.components.BottomActionBar
import dev.smsforwarder.ui.components.InsetDivider
import dev.smsforwarder.ui.main.formatDateTime
import dev.smsforwarder.ui.theme.StatusPill
import dev.smsforwarder.ui.theme.StatusTone

/** Material disabled-content alpha, used for rows the app already has. */
private const val DisabledAlpha = 0.38f

/** Explanation + checklist, shared by the Recovery screen and the last onboarding step. */
@Composable
fun RecoveryList(
    items: List<RecoveryItem>,
    unreadable: Boolean,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier) {
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
            val textColor = if (item.alreadyKnown) {
                MaterialTheme.colorScheme.onSurface.copy(alpha = DisabledAlpha)
            } else {
                MaterialTheme.colorScheme.onSurface
            }
            Column(Modifier.animateItem()) {
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
                    Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(
                            item.sender,
                            style = MaterialTheme.typography.titleSmall,
                            color = textColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(formatDateTime(item.receivedAt), style = MaterialTheme.typography.bodySmall, color = textColor)
                        Text(item.preview, style = MaterialTheme.typography.bodyMedium, color = textColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (item.alreadyKnown) {
                            StatusPill(StatusTone.NEUTRAL, stringResource(R.string.recovery_already_saved), Modifier.padding(top = 4.dp))
                        }
                    }
                }
                // Starts at the text column: 48dp checkbox + 8dp gap.
                InsetDivider(start = 56.dp)
            }
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
    val count = items.count { it.selected }
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
        bottomBar = {
            BottomActionBar {
                resultMessage?.let { Text(it) }
                Button(onClick = onSend, enabled = count > 0, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.recovery_send_selected, count))
                }
            }
        },
    ) { padding ->
        RecoveryList(items, unreadable, onToggle, modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp))
    }
}
