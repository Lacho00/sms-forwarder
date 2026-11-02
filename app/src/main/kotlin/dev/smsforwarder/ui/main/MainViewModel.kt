package dev.smsforwarder.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.smsforwarder.core.SmsStatus
import dev.smsforwarder.core.SmsSearch
import dev.smsforwarder.core.StatusFilter
import dev.smsforwarder.core.StatusLabel
import dev.smsforwarder.core.statusLabelOf
import dev.smsforwarder.data.SettingsRepository
import dev.smsforwarder.data.SmsEntity
import dev.smsforwarder.data.SmsRepository
import dev.smsforwarder.health.HealthChecker
import dev.smsforwarder.health.HealthIssue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class RowUi(
    val id: Long,
    val sender: String,
    val subId: Int?,
    val receivedAt: Long,
    val preview: String,
    val label: StatusLabel,
)

data class MainUiState(
    val issues: Set<HealthIssue>,
    val lastSweepAt: Long?,
    val queued: Int,
    val errors: Int,
    val rows: List<RowUi>,
    val paused: Boolean = false,
    val query: String = "",
    val filter: StatusFilter = StatusFilter.ALL,
    val hideText: Boolean = false,
)

private const val PREVIEW_LENGTH = 80

private fun matchesFilter(status: SmsStatus, filter: StatusFilter): Boolean = when (filter) {
    StatusFilter.ALL -> true
    StatusFilter.QUEUED -> status == SmsStatus.QUEUED
    StatusFilter.ERROR -> status == SmsStatus.ERROR
}

internal fun SmsEntity.toRowUi() = RowUi(
    id = id,
    sender = sender,
    subId = subId,
    receivedAt = receivedAt,
    preview = body.replace(Regex("\\s+"), " ").trim().take(PREVIEW_LENGTH),
    label = statusLabelOf(status, origin, attempts, lastHttpCode),
)

class MainViewModel(
    smsRepository: SmsRepository,
    private val settings: SettingsRepository,
    private val healthChecker: HealthChecker,
) : ViewModel() {

    private val issues = MutableStateFlow<Set<HealthIssue>>(emptySet())

    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow(StatusFilter.ALL)

    fun setQuery(q: String) {
        query.value = q
    }

    fun setFilter(f: StatusFilter) {
        filter.value = f
    }

    // Query, filter and the hide-text setting travel together so the main combine stays at five flows.
    private val view = combine(query, filter, settings.hideText) { q, f, hide -> Triple(q, f, hide) }

    // Filtering happens in memory: the list is already bounded by the retention setting.
    val state: StateFlow<MainUiState> = combine(
        smsRepository.observeAll(),
        settings.lastSweepAtFlow,
        issues,
        settings.forwardingEnabled,
        view,
    ) { all, lastSweepAt, currentIssues, forwarding, (q, f, hide) ->
        MainUiState(
            issues = currentIssues,
            lastSweepAt = lastSweepAt,
            // The counters describe the whole queue, not what the search leaves on screen.
            queued = all.count { it.status == SmsStatus.QUEUED },
            errors = all.count { it.status == SmsStatus.ERROR },
            rows = all
                .filter { matchesFilter(it.status, f) && SmsSearch.matches(it.sender, it.body, q) }
                .map { it.toRowUi() },
            paused = !forwarding,
            query = q,
            filter = f,
            hideText = hide,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MainUiState(emptySet(), null, 0, 0, emptyList()),
    )

    init {
        // A finished sweep is a good moment to look at the health again.
        viewModelScope.launch {
            combine(settings.lastSweepAtFlow, settings.inboxUnreadableFlow) { _, _ -> }
                .collect { refreshIssues() }
        }
    }

    /** Runs the health check and publishes the result. Called on resume and after each sweep. */
    fun refreshIssues() {
        viewModelScope.launch {
            try {
                issues.value = healthChecker.check(inboxUnreadable = settings.inboxUnreadable())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Health is best effort: keep the previous warnings if the check itself fails.
            }
        }
    }

    class Factory(
        private val smsRepository: SmsRepository,
        private val settings: SettingsRepository,
        private val healthChecker: HealthChecker,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            MainViewModel(smsRepository, settings, healthChecker) as T
    }
}
