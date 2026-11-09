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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.VerticalDivider
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.style.TextOverflow
import dev.smsforwarder.core.StatusLabel
import dev.smsforwarder.ui.components.EmptyState
import dev.smsforwarder.ui.components.InsetDivider
import dev.smsforwarder.ui.theme.StatusPill
import dev.smsforwarder.ui.theme.toneOf
import java.time.LocalDate
import java.time.ZoneId
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
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
    onResume: () -> Unit = {},
) {
    // While searching, the panel and the banner give the room to the results.
    val searching = state.query.isNotBlank()
    val listState = rememberLazyListState()
    // Showing the panel again (search closed) inserts it above the anchored item: go back to the top.
    // Only on the true -> false change: the first composition (rotation, theme change, coming back to the tab)
    // must keep the scroll position restored by rememberLazyListState.
    val searchingNow by rememberUpdatedState(searching)
    LaunchedEffect(Unit) {
        snapshotFlow { searchingNow }.drop(1).filter { !it }.collect { listState.scrollToItem(0) }
    }
    val filtering = searching || state.filter != StatusFilter.ALL || state.destinationFilter != null
    // "Today" must follow the clock: re-group when the date changes (checked on every resume).
    val zone = ZoneId.systemDefault()
    var resumes by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val onResumeNow by rememberUpdatedState(onResume)
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                resumes++
                onResumeNow()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val today = remember(resumes) { LocalDate.now(zone) }
    val groups = remember(state.rows, today) { groupByDay(state.rows, System.currentTimeMillis(), zone) }
    Column(Modifier.fillMaxSize()) {
        // The banner lives outside the list: an item inserted above the first visible one (issues load after
        // the rows) would be scrolled off by the list's key anchoring.
        if (!searching && state.issues.isNotEmpty()) {
            Box(
                Modifier.padding(
                    start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp,
                ),
            ) { HealthBanner(state.issues, onOpenIssue) }
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            state = listState,
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = if (!searching && state.issues.isNotEmpty()) 8.dp else padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 8.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!searching) {
                item(key = "status") { StatusPanel(state, onSweepNow, onFilterChange) }
            }
            item(key = "filters") {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusFilter.entries.forEach { option ->
                        FilterChip(
                            selected = state.filter == option,
                            onClick = { onFilterChange(option) },
                            label = { Text(filterLabel(option)) },
                        )
                    }
                    // A single destination has nothing to filter by.
                    if (state.destinations.size > 1) {
                        VerticalDivider(Modifier.height(24.dp))
                        FilterChip(
                            selected = state.destinationFilter == null,
                            onClick = { onDestinationFilterChange(null) },
                            label = { Text(stringResource(R.string.main_filter_all)) },
                        )
                        state.destinations.forEach { (id, name) ->
                            FilterChip(
                                selected = state.destinationFilter == id,
                                onClick = { onDestinationFilterChange(id) },
                                label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            )
                        }
                    }
                }
            }
            if (state.rows.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        icon = if (filtering) rememberVectorPainter(Icons.Filled.Search) else painterResource(R.drawable.ic_tab_home),
                        title = stringResource(if (filtering) R.string.main_no_results else R.string.main_empty),
                        body = stringResource(if (filtering) R.string.main_no_results_body else R.string.main_empty_body),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        actionLabel = if (filtering) stringResource(R.string.main_clear_filters) else null,
                        onAction = if (filtering) onClearFilters else null,
                    )
                }
            }
            groups.forEach { group ->
                item(key = "day-${group.day}") { DayHeader(group) }
                itemsIndexed(group.rows, key = { _, row -> row.id }) { index, row ->
                    SmsRow(row, group.kind, hideText = state.hideText, onClick = { onOpen(row.id) })
                    if (index < group.rows.lastIndex) InsetDivider()
                }
            }
        }
    }
}

@Composable
private fun DayHeader(group: DayGroup) {
    val locale = LocalConfiguration.current.locales[0]
    val text = when (group.kind) {
        DayKind.TODAY -> stringResource(R.string.main_day_today)
        DayKind.YESTERDAY -> stringResource(R.string.main_day_yesterday)
        DayKind.OTHER -> DateFormat.getDateInstance(DateFormat.MEDIUM, locale)
            .format(Date.from(group.day.atStartOfDay(ZoneId.systemDefault()).toInstant()))
    }
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/** Short time for today and yesterday, short date for older days. */
@Composable
internal fun rowTime(millis: Long, kind: DayKind): String {
    val locale = LocalConfiguration.current.locales[0]
    val format = if (kind == DayKind.OTHER) {
        DateFormat.getDateInstance(DateFormat.SHORT, locale)
    } else {
        DateFormat.getTimeInstance(DateFormat.SHORT, locale)
    }
    return format.format(Date(millis))
}

/** One pill per destination; without deliveries, a single pill from the SMS status. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeliveryPills(chips: List<DeliveryChipUi>, fallback: StatusLabel) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (chips.isEmpty()) {
            StatusPill(toneOf(fallback.kind), statusText(fallback))
        } else {
            chips.forEach { StatusPill(toneOf(it.status), deliveryChipText(it)) }
        }
    }
}

@Composable
private fun SmsRow(row: RowUi, kind: DayKind, hideText: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val senderLine = row.subId?.let { stringResource(R.string.row_sender_sim, row.sender, it) } ?: row.sender
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                senderLine,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                rowTime(row.receivedAt, kind),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (!hideText) {
            Text(
                row.preview,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DeliveryPills(row.deliveries, row.label)
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
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(R.string.main_last_sweep, agoLabel(state.lastSweepAt, System.currentTimeMillis())),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
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
    // A sheet left open must not come back when the issues grow again.
    val showSheet = sheet && sorted.size >= 2
    LaunchedEffect(showSheet) { if (!showSheet) sheet = false }
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
    if (showSheet) {
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
