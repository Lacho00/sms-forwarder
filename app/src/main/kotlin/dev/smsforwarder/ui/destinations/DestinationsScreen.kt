package dev.smsforwarder.ui.destinations

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.smsforwarder.R
import dev.smsforwarder.core.TemplateKind

fun templateLabelRes(kind: TemplateKind): Int = when (kind) {
    TemplateKind.STANDARD_JSON -> R.string.template_standard_json
    TemplateKind.TELEGRAM -> R.string.template_telegram
    TemplateKind.NTFY -> R.string.template_ntfy
    TemplateKind.CUSTOM -> R.string.template_custom
}

/** Template label plus, when paused, "Paused"; otherwise the queued and error counters that are not zero. */
@Composable
private fun supportingLine(row: DestinationRowUi): String {
    val parts = mutableListOf(stringResource(templateLabelRes(row.template)))
    if (!row.enabled) {
        parts += stringResource(R.string.destination_paused)
    } else {
        if (row.queued > 0) parts += pluralStringResource(R.plurals.destination_queued_count, row.queued, row.queued)
        if (row.errors > 0) parts += pluralStringResource(R.plurals.destination_error_count, row.errors, row.errors)
    }
    return parts.joinToString(" · ")
}

@Composable
fun DestinationsScreen(
    rows: List<DestinationRowUi>,
    onOpen: (Long) -> Unit,
    onAdd: () -> Unit,
    onToggle: (Long, Boolean) -> Unit,
    padding: PaddingValues,
) {
    Box(Modifier.fillMaxSize().padding(padding)) {
        if (rows.isEmpty()) {
            Text(
                stringResource(R.string.destinations_empty),
                modifier = Modifier.align(Alignment.Center).padding(16.dp),
            )
        } else {
            // Room below the last row for the FAB (56dp) and its 16dp margins.
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
                items(rows, key = { it.id }) { row ->
                    ListItem(
                        headlineContent = { Text(row.name) },
                        supportingContent = { Text(supportingLine(row)) },
                        trailingContent = {
                            Switch(
                                checked = row.enabled,
                                onCheckedChange = { onToggle(row.id, it) },
                                modifier = Modifier.semantics { contentDescription = row.name },
                            )
                        },
                        modifier = Modifier.clickable { onOpen(row.id) },
                    )
                    HorizontalDivider()
                }
            }
        }
        FloatingActionButton(
            onClick = onAdd,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.destination_add))
        }
    }
}
