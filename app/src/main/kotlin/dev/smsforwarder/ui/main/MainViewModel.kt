package dev.smsforwarder.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.smsforwarder.core.DeliveryStatus
import dev.smsforwarder.core.SmsStatus
import dev.smsforwarder.core.isConfigError
import dev.smsforwarder.data.DeliveryEntity
import dev.smsforwarder.data.DestinationEntity
import dev.smsforwarder.data.DestinationRepository
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

/** One destination's delivery of an SMS, as the home row shows it. */
data class DeliveryChipUi(
    val destinationName: String,
    val status: DeliveryStatus,
    val httpCode: Int?,
    val configError: Boolean,
)

data class RowUi(
    val id: Long,
    val sender: String,
    val subId: Int?,
    val receivedAt: Long,
    val preview: String,
    val label: StatusLabel,
    val deliveries: List<DeliveryChipUi> = emptyList(),
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
    /** Live destinations (id, name) for the filter chips. */
    val destinations: List<Pair<Long, String>> = emptyList(),
    val destinationFilter: Long? = null,
)

private const val PREVIEW_LENGTH = 80

private fun matchesFilter(status: SmsStatus, filter: StatusFilter): Boolean = when (filter) {
    StatusFilter.ALL -> true
    StatusFilter.QUEUED -> status == SmsStatus.QUEUED
    StatusFilter.ERROR -> status == SmsStatus.ERROR
}

internal fun DeliveryEntity.toChip() = DeliveryChipUi(
    destinationName = destinationName,
    status = status,
    httpCode = lastHttpCode,
    configError = status == DeliveryStatus.ERROR && isConfigError(lastError),
)

/**
 * The code beside an error status comes from a delivery that failed: the SMS keeps the latest attempt's code, which
 * can be another destination's 200. Without deliveries to look at, the stored code stays.
 */
internal fun shownCode(sms: SmsEntity, deliveries: List<DeliveryEntity>): Int? {
    if (sms.status != SmsStatus.ERROR || deliveries.isEmpty()) return sms.lastHttpCode
    return deliveries.firstOrNull { it.status == DeliveryStatus.ERROR && it.lastHttpCode != null }?.lastHttpCode
}

internal fun SmsEntity.toRowUi(deliveries: List<DeliveryEntity> = emptyList()) = RowUi(
    id = id,
    sender = sender,
    subId = subId,
    receivedAt = receivedAt,
    preview = body.replace(Regex("\\s+"), " ").trim().take(PREVIEW_LENGTH),
    label = statusLabelOf(status, origin, attempts, shownCode(this, deliveries)),
    deliveries = deliveries.map { it.toChip() },
)

class MainViewModel(
    smsRepository: SmsRepository,
    private val settings: SettingsRepository,
    private val healthChecker: HealthChecker,
    destinationRepository: DestinationRepository,
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

    private val destinationFilter = MutableStateFlow<Long?>(null)

    fun setDestinationFilter(id: Long?) {
        destinationFilter.value = id
    }

    private class Controls(val query: String, val filter: StatusFilter, val hideText: Boolean, val destinationFilter: Long?)

    private class Deliveries(val bySms: Map<Long, List<DeliveryEntity>>, val destinations: List<DestinationEntity>)

    // Everything but the SMS list, the sweep time, the issues and the pause switch travels in these two groups
    // so the main combine stays at five flows.
    private val controls = combine(query, filter, settings.hideText, destinationFilter) { q, f, hide, d ->
        Controls(q, f, hide, d)
    }
    private val deliveries = combine(smsRepository.observeDeliveries(), destinationRepository.observeLive()) { all, live ->
        Deliveries(all.groupBy { it.smsId }, live)
    }
    private val view = combine(controls, deliveries) { c, d -> c to d }

    // Filtering happens in memory: the list is already bounded by the retention setting.
    val state: StateFlow<MainUiState> = combine(
        smsRepository.observeAll(),
        settings.lastSweepAtFlow,
        issues,
        settings.forwardingEnabled,
        view,
    ) { all, lastSweepAt, currentIssues, forwarding, (c, d) ->
        // A destination that is gone (or whose chips are hidden) cannot keep filtering the list.
        val destinationFilter = c.destinationFilter?.takeIf { id -> d.destinations.size > 1 && d.destinations.any { it.id == id } }
        MainUiState(
            issues = currentIssues,
            lastSweepAt = lastSweepAt,
            // The counters describe the whole queue, not what the search leaves on screen.
            queued = all.count { it.status == SmsStatus.QUEUED },
            errors = all.count { it.status == SmsStatus.ERROR },
            rows = all
                .filter {
                    matchesFilter(it.status, c.filter) && SmsSearch.matches(it.sender, it.body, c.query) &&
                        (destinationFilter == null ||
                            d.bySms[it.id].orEmpty().any { delivery -> delivery.destinationId == destinationFilter })
                }
                .map { it.toRowUi(d.bySms[it.id].orEmpty()) },
            paused = !forwarding,
            query = c.query,
            filter = c.filter,
            hideText = c.hideText,
            destinations = d.destinations.map { it.id to it.name },
            destinationFilter = destinationFilter,
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
        private val destinationRepository: DestinationRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            MainViewModel(smsRepository, settings, healthChecker, destinationRepository) as T
    }
}
