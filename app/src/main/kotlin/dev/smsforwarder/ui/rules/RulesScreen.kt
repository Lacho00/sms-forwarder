package dev.smsforwarder.ui.rules

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.smsforwarder.R
import dev.smsforwarder.core.BodyMode
import dev.smsforwarder.core.SenderMode
import dev.smsforwarder.core.SimMatch
import dev.smsforwarder.ui.components.EmptyState
import dev.smsforwarder.ui.components.InsetDivider
import dev.smsforwarder.ui.theme.StatusPill
import dev.smsforwarder.ui.theme.StatusTone

/** Sender, text filter, SIM (only when not "any") and destination names, in that order. */
@Composable
private fun summaryLine(row: RuleRowUi): String {
    val parts = mutableListOf<String>()
    parts += stringResource(
        when (row.senderMode) {
            SenderMode.EXACT -> R.string.rule_row_sender_exact
            SenderMode.CONTAINS -> R.string.rule_row_sender_contains
            SenderMode.REGEX -> R.string.rule_row_sender_regex
        },
        row.senderPattern,
    )
    row.bodyMode?.let { mode ->
        parts += stringResource(
            when (mode) {
                BodyMode.CONTAINS -> R.string.rule_row_body_contains
                BodyMode.NOT_CONTAINS -> R.string.rule_row_body_not_contains
                BodyMode.REGEX -> R.string.rule_row_body_regex
            },
            row.bodyPattern.orEmpty(),
        )
    }
    when (row.sim) {
        SimMatch.ANY -> Unit
        SimMatch.SIM1 -> parts += stringResource(R.string.rule_row_sim, 1)
        SimMatch.SIM2 -> parts += stringResource(R.string.rule_row_sim, 2)
    }
    if (row.destinationNames.isNotEmpty()) parts += row.destinationNames.joinToString(", ")
    return parts.joinToString(" · ")
}

@Composable
fun RulesScreen(
    rows: List<RuleRowUi>,
    onOpen: (Long) -> Unit,
    onAdd: () -> Unit,
    onToggle: (Long, Boolean) -> Unit,
    padding: PaddingValues,
) {
    Box(Modifier.fillMaxSize().padding(padding)) {
        if (rows.isEmpty()) {
            EmptyState(
                icon = painterResource(R.drawable.ic_tab_rules),
                title = stringResource(R.string.rules_empty),
                body = stringResource(R.string.rules_empty_body),
                actionLabel = stringResource(R.string.action_add),
                onAction = onAdd,
            )
        } else {
            // Room below the last row for the extended FAB (56dp) and its 16dp margins.
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
                items(rows, key = { it.id }) { row ->
                    ListItem(
                        headlineContent = { Text(row.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(summaryLine(row))
                                if (row.invalidRegex) {
                                    StatusPill(StatusTone.ERROR, stringResource(R.string.rule_invalid_regex))
                                }
                                if (row.destinationNames.isEmpty()) {
                                    StatusPill(StatusTone.ERROR, stringResource(R.string.rule_no_destinations))
                                }
                            }
                        },
                        trailingContent = {
                            Switch(
                                checked = row.enabled,
                                onCheckedChange = { onToggle(row.id, it) },
                                modifier = Modifier.semantics { contentDescription = row.name },
                            )
                        },
                        modifier = Modifier.clickable { onOpen(row.id) },
                    )
                    InsetDivider()
                }
            }
        }
        val addLabel = stringResource(R.string.action_add)
        ExtendedFloatingActionButton(
            onClick = onAdd,
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text(addLabel) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).semantics(mergeDescendants = true) { text = AnnotatedString(addLabel) },
        )
    }
}
