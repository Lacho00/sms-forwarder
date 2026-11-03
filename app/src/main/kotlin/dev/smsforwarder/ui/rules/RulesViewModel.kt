package dev.smsforwarder.ui.rules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.smsforwarder.core.BodyMode
import dev.smsforwarder.core.SenderMode
import dev.smsforwarder.core.SimMatch
import dev.smsforwarder.data.RuleRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** [destinationNames] are the live destinations only; empty means the rule forwards nothing. */
data class RuleRowUi(
    val id: Long,
    val name: String,
    val enabled: Boolean,
    val senderMode: SenderMode,
    val senderPattern: String,
    val bodyMode: BodyMode?,
    val bodyPattern: String?,
    val sim: SimMatch,
    val destinationNames: List<String>,
)

/** [scope] is only passed by tests; in the app it is the ViewModel scope. */
class RulesViewModel(
    private val rules: RuleRepository,
    private val scope: CoroutineScope? = null,
) : ViewModel() {

    val rows: StateFlow<List<RuleRowUi>> = rules.observeAll().map { list ->
        list.map { r ->
            RuleRowUi(
                id = r.rule.id,
                name = r.rule.name,
                enabled = r.rule.enabled,
                senderMode = r.rule.senderMode,
                senderPattern = r.rule.senderPattern,
                bodyMode = r.rule.bodyMode,
                bodyPattern = r.rule.bodyPattern,
                sim = r.rule.sim,
                destinationNames = r.destinations.sortedBy { it.id }.map { it.name },
            )
        }
    }.stateIn(scope ?: viewModelScope, SharingStarted.Eagerly, emptyList())

    fun setEnabled(id: Long, enabled: Boolean) {
        (scope ?: viewModelScope).launch {
            try {
                rules.setEnabled(id, enabled)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                // The list keeps showing the stored state; the switch simply does not change.
            }
        }
    }

    class Factory(private val rules: RuleRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = RulesViewModel(rules) as T
    }
}
