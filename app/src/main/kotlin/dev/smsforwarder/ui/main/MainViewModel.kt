package dev.smsforwarder.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.smsforwarder.core.SmsStatus
import dev.smsforwarder.core.statusLabel
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
    val label: String,
)

data class MainUiState(
    val issues: Set<HealthIssue>,
    val lastSweepAt: Long?,
    val queued: Int,
    val errors: Int,
    val rows: List<RowUi>,
)

private const val PREVIEW_LENGTH = 80

internal fun SmsEntity.toRowUi() = RowUi(
    id = id,
    sender = sender,
    subId = subId,
    receivedAt = receivedAt,
    preview = body.replace(Regex("\\s+"), " ").trim().take(PREVIEW_LENGTH),
    label = statusLabel(status, origin, attempts, lastHttpCode),
)

class MainViewModel(
    smsRepository: SmsRepository,
    private val settings: SettingsRepository,
    private val healthChecker: HealthChecker,
) : ViewModel() {

    private val issues = MutableStateFlow<Set<HealthIssue>>(emptySet())

    val state: StateFlow<MainUiState> = combine(
        smsRepository.observeAll(),
        settings.lastSweepAtFlow,
        issues,
    ) { all, lastSweepAt, currentIssues ->
        MainUiState(
            issues = currentIssues,
            lastSweepAt = lastSweepAt,
            queued = all.count { it.status == SmsStatus.QUEUED },
            errors = all.count { it.status == SmsStatus.ERROR },
            rows = all.map { it.toRowUi() },
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
