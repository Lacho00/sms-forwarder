package dev.smsforwarder.ui.destinations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.smsforwarder.core.TemplateKind
import dev.smsforwarder.data.DestinationRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DestinationRowUi(
    val id: Long,
    val name: String,
    val template: TemplateKind,
    val enabled: Boolean,
    val queued: Int,
    val errors: Int,
    /** False when the destination has no stored URL or secrets (e.g. imported without credentials). */
    val configured: Boolean = true,
)

/** [scope] is only passed by tests; in the app it is the ViewModel scope. */
class DestinationsViewModel(
    private val repo: DestinationRepository,
    private val enqueue: (List<Long>) -> Unit,
    private val scope: CoroutineScope? = null,
) : ViewModel() {

    val rows: StateFlow<List<DestinationRowUi>> = combine(repo.observeLiveWithConfigured(), repo.observeCounts()) { live, counts ->
        val byId = counts.associateBy { it.destinationId }
        live.map { (it, configured) ->
            val c = byId[it.id]
            DestinationRowUi(it.id, it.name, it.template, it.enabled, c?.queued ?: 0, c?.errors ?: 0, configured)
        }
    }.stateIn(scope ?: viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Pauses or resumes; resuming schedules the SMS that were waiting for this destination. */
    fun setEnabled(id: Long, enabled: Boolean) {
        (scope ?: viewModelScope).launch {
            try {
                val ids = repo.setEnabled(id, enabled)
                if (ids.isNotEmpty()) enqueue(ids)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                // The list keeps showing the stored state; the switch simply does not change.
            }
        }
    }

    class Factory(
        private val repo: DestinationRepository,
        private val enqueue: (List<Long>) -> Unit,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = DestinationsViewModel(repo, enqueue) as T
    }
}
