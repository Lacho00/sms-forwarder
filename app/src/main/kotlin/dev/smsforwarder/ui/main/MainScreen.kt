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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.smsforwarder.R
import dev.smsforwarder.health.HealthIssue
import java.text.DateFormat
import java.util.Date

@Composable
internal fun issueTitle(issue: HealthIssue): String = stringResource(
    when (issue) {
        HealthIssue.SMS_PERMISSION -> R.string.issue_sms_permission
        HealthIssue.NOTIFICATION_PERMISSION -> R.string.issue_notification_permission
        HealthIssue.BATTERY_OPTIMIZED -> R.string.issue_battery_optimized
        HealthIssue.BACKGROUND_RESTRICTED -> R.string.issue_background_restricted
        HealthIssue.AUTOSTART_OFF -> R.string.issue_autostart_off
        HealthIssue.AUTOSTART_UNKNOWN -> R.string.issue_autostart_unknown
        HealthIssue.MIUI_SMS_BLOCKED -> R.string.issue_miui_sms_blocked
        HealthIssue.TOKEN_REJECTED -> R.string.issue_token_rejected
        HealthIssue.NOT_CONFIGURED -> R.string.issue_not_configured
        HealthIssue.NO_SENDERS -> R.string.issue_no_senders
    },
)

internal fun formatDateTime(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))

/** "N min ago" style text for the status line; [now] is a parameter so it can be tested. */
@Composable
internal fun agoLabel(lastSweepAt: Long?, now: Long): String {
    if (lastSweepAt == null) return stringResource(R.string.ago_never)
    val minutes = (now - lastSweepAt).coerceAtLeast(0) / 60_000
    return when {
        minutes < 1 -> stringResource(R.string.ago_less_than_minute)
        minutes < 60 -> stringResource(R.string.ago_minutes, minutes)
        minutes < 24 * 60 -> stringResource(R.string.ago_hours, minutes / 60)
        else -> stringResource(R.string.ago_days, minutes / (24 * 60))
    }
}

@Composable
fun MainScreen(
    state: MainUiState,
    onOpenIssue: (HealthIssue) -> Unit,
    onSweepNow: () -> Unit,
    onOpen: (Long) -> Unit,
    padding: PaddingValues = PaddingValues(),
) {
    run {
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
                        Button(onClick = { onOpenIssue(issue) }) { Text(stringResource(R.string.main_open_settings)) }
                    }
                }
            }
            item(key = "status") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.paused) {
                        Text(
                            stringResource(R.string.forwarding_paused),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Text(
                        stringResource(
                            R.string.main_status_line,
                            agoLabel(state.lastSweepAt, System.currentTimeMillis()),
                            state.queued,
                            state.errors,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = onSweepNow) { Text(stringResource(R.string.main_sweep_now)) }
                }
            }
            if (state.rows.isEmpty()) {
                item(key = "empty") { Text(stringResource(R.string.main_empty), style = MaterialTheme.typography.bodyMedium) }
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
        val senderLine = row.subId?.let { stringResource(R.string.row_sender_sim, row.sender, it) } ?: row.sender
        Text(senderLine, style = MaterialTheme.typography.titleSmall)
        Text(formatDateTime(row.receivedAt), style = MaterialTheme.typography.bodySmall)
        Text(row.preview, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
        Text(statusText(row.label), style = MaterialTheme.typography.labelMedium)
    }
}
