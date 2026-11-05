package dev.smsforwarder.ui.main

import androidx.compose.foundation.clickable
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import dev.smsforwarder.ui.components.SettingRow
import dev.smsforwarder.ui.theme.StatusTone
import dev.smsforwarder.ui.theme.toneColors
import dev.smsforwarder.ui.theme.toneIcon
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.smsforwarder.R
import dev.smsforwarder.core.StatusFilter
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
        HealthIssue.NO_RULES -> R.string.issue_no_senders
        HealthIssue.MIGRATION_FAILED -> R.string.issue_migration_failed
    },
)

/** Uses the app's configuration locale, which can differ from the JVM default when the language is set in the app. */
@Composable
internal fun formatDateTime(millis: Long): String {
    val locale = LocalConfiguration.current.locales[0]
    return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale).format(Date(millis))
}

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

/** Issues that stop forwarding are ERROR; the rest only put it at risk. No `else`: a new issue must be classified. */
fun issueTone(issue: HealthIssue): StatusTone = when (issue) {
    HealthIssue.SMS_PERMISSION,
    HealthIssue.TOKEN_REJECTED,
    HealthIssue.NOT_CONFIGURED,
    HealthIssue.NO_RULES,
    HealthIssue.MIUI_SMS_BLOCKED,
    HealthIssue.MIGRATION_FAILED,
    -> StatusTone.ERROR
    HealthIssue.NOTIFICATION_PERMISSION,
    HealthIssue.BATTERY_OPTIMIZED,
    HealthIssue.BACKGROUND_RESTRICTED,
    HealthIssue.AUTOSTART_OFF,
    HealthIssue.AUTOSTART_UNKNOWN,
    -> StatusTone.PENDING
}

fun bannerTone(issues: Set<HealthIssue>): StatusTone? = when {
    issues.isEmpty() -> null
    issues.any { issueTone(it) == StatusTone.ERROR } -> StatusTone.ERROR
    else -> StatusTone.PENDING
}

@Composable
fun MainScreen(
    state: MainUiState,
    onOpenIssue: (HealthIssue) -> Unit,
    onSweepNow: () -> Unit,
    onOpen: (Long) -> Unit,
    padding: PaddingValues = PaddingValues(),
    onFilterChange: (StatusFilter) -> Unit = {},
    onDestinationFilterChange: (Long?) -> Unit = {},
    onClearFilters: () -> Unit = {},
) {
    // While searching, the panel and the banner give the room to the results.
    val searching = state.query.isNotBlank()
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
            if (!searching && state.issues.isNotEmpty()) {
                item(key = "banner") { HealthBanner(state.issues, onOpenIssue) }
            }
            if (!searching) {
                item(key = "status") { StatusPanel(state, onSweepNow, onFilterChange) }
            }
            item(key = "filters") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusFilter.entries.forEach { option ->
                        FilterChip(
                            selected = state.filter == option,
                            onClick = { onFilterChange(option) },
                            label = { Text(filterLabel(option)) },
                        )
                    }
                }
            }
            // A single destination has nothing to filter by.
            if (state.destinations.size > 1) {
                item(key = "destination-filters") {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = state.destinationFilter == null,
                            onClick = { onDestinationFilterChange(null) },
                            label = { Text(stringResource(R.string.main_filter_all)) },
                        )
                        state.destinations.forEach { (id, name) ->
                            FilterChip(
                                selected = state.destinationFilter == id,
                                onClick = { onDestinationFilterChange(id) },
                                label = { Text(name) },
                            )
                        }
                    }
                }
            }
            if (state.rows.isEmpty()) {
                val filtering = state.query.isNotBlank() || state.filter != StatusFilter.ALL ||
                    state.destinationFilter != null
                item(key = "empty") {
                    Text(
                        stringResource(if (filtering) R.string.main_no_results else R.string.main_empty),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            items(state.rows, key = { it.id }) { row ->
                SmsRow(row, hideText = state.hideText, onClick = { onOpen(row.id) })
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun SmsRow(row: RowUi, hideText: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        val senderLine = row.subId?.let { stringResource(R.string.row_sender_sim, row.sender, it) } ?: row.sender
        Text(senderLine, style = MaterialTheme.typography.titleSmall)
        Text(formatDateTime(row.receivedAt), style = MaterialTheme.typography.bodySmall)
        if (!hideText) Text(row.preview, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
        Text(statusText(row.label), style = MaterialTheme.typography.labelMedium)
        if (row.deliveries.isNotEmpty()) {
            Text(deliveryLine(row.deliveries), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun filterLabel(filter: StatusFilter): String = stringResource(
    when (filter) {
        StatusFilter.ALL -> R.string.main_filter_all
        StatusFilter.QUEUED -> R.string.main_filter_queued
        StatusFilter.ERROR -> R.string.main_filter_error
    },
)

@Composable
fun MainSearchField(query: String, onQueryChange: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
        singleLine = true,
        placeholder = { Text(stringResource(R.string.main_search_hint)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.main_search_clear))
                }
            }
        } else {
            null
        },
    )
}

@Composable
private fun StatusPanel(state: MainUiState, onSweepNow: () -> Unit, onFilterChange: (StatusFilter) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 8.dp)) {
            val dotColor = toneColors(if (state.paused) StatusTone.PENDING else StatusTone.OK).onContainer
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(dotColor, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(if (state.paused) R.string.forwarding_paused else R.string.main_forwarding),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.main_last_sweep, agoLabel(state.lastSweepAt, System.currentTimeMillis())),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = onSweepNow) {
                    Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.main_sweep_now))
                }
            }
            Row(modifier = Modifier.padding(end = 12.dp)) {
                Counter(state.sentToday, R.string.main_counter_sent_today, null, Modifier.weight(1f))
                Counter(state.queued, R.string.main_counter_queued, { onFilterChange(StatusFilter.QUEUED) }, Modifier.weight(1f))
                Counter(
                    state.errors, R.string.main_counter_errors, { onFilterChange(StatusFilter.ERROR) }, Modifier.weight(1f),
                    highlight = state.errors > 0,
                )
            }
        }
    }
}

@Composable
private fun Counter(
    value: Int,
    @StringRes label: Int,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
) {
    val click = if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier
    Column(modifier = modifier.heightIn(min = 48.dp).then(click).padding(vertical = 4.dp)) {
        Text(
            value.toString(),
            style = MaterialTheme.typography.titleMedium,
            color = if (highlight) MaterialTheme.colorScheme.error else Color.Unspecified,
        )
        Text(
            stringResource(label),
            style = MaterialTheme.typography.labelSmall,
            color = if (highlight) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HealthBanner(issues: Set<HealthIssue>, onOpenIssue: (HealthIssue) -> Unit) {
    val tone = bannerTone(issues) ?: return
    val colors = toneColors(tone)
    val sorted = issues.sortedBy { it.ordinal }
    var sheet by remember { mutableStateOf(false) }
    val text = if (sorted.size == 1) {
        issueTitle(sorted.first())
    } else {
        pluralStringResource(R.plurals.main_issues_count, sorted.size, sorted.size)
    }
    Surface(
        onClick = { if (sorted.size == 1) onOpenIssue(sorted.first()) else sheet = true },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = colors.container,
        contentColor = colors.onContainer,
    ) {
        Row(
            modifier = Modifier.heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(toneIcon(tone), contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
    if (sheet) {
        ModalBottomSheet(onDismissRequest = { sheet = false }) {
            Column(modifier = Modifier.padding(bottom = 16.dp)) {
                sorted.forEach { issue ->
                    SettingRow(
                        title = issueTitle(issue),
                        leadingIcon = toneIcon(issueTone(issue)),
                        chevron = true,
                        onClick = {
                            sheet = false
                            onOpenIssue(issue)
                        },
                    )
                }
            }
        }
    }
}
