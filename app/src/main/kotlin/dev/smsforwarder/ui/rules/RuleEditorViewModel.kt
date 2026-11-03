package dev.smsforwarder.ui.rules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.smsforwarder.core.BodyMode
import dev.smsforwarder.core.RuleDraft
import dev.smsforwarder.core.RuleError
import dev.smsforwarder.core.SenderMode
import dev.smsforwarder.core.SimMatch
import dev.smsforwarder.core.validateRule
import dev.smsforwarder.data.DestinationEntity
import dev.smsforwarder.data.DestinationRepository
import dev.smsforwarder.data.RuleRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

val NEW_RULE_DRAFT = RuleDraft(
    name = "", enabled = true, senderMode = SenderMode.EXACT, senderPattern = "",
    bodyMode = null, bodyPattern = null, sim = SimMatch.ANY, destinationIds = emptySet(),
)

/** [destinations] are every live one, paused included. */
data class RuleEditorState(
    val loaded: Boolean = false,
    val isNew: Boolean = true,
    val draft: RuleDraft = NEW_RULE_DRAFT,
    val destinations: List<DestinationEntity> = emptyList(),
    val errors: Set<RuleError> = emptySet(),
    val deleteAsked: Boolean = false,
    val done: Boolean = false,
    /** The last save or delete did not complete; nothing was lost, retry. */
    val saveFailed: Boolean = false,
    /** The rule could not be read; the form is not usable. */
    val loadFailed: Boolean = false,
    val busy: Boolean = false,
)

/** [id] null creates. [scope] is only passed by tests; in the app it is the ViewModel scope. */
class RuleEditorViewModel(
    private val id: Long?,
    private val rules: RuleRepository,
    private val destinations: DestinationRepository,
    private val scope: CoroutineScope? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(RuleEditorState(isNew = id == null))
    val state: StateFlow<RuleEditorState> = _state.asStateFlow()

    private fun launch(block: suspend () -> Unit): Job = (scope ?: viewModelScope).launch { block() }

    private var loadJob: Job? = null
    private var listJob: Job? = null
    private var saveJob: Job? = null
    private var deleteJob: Job? = null
    private var generation = 0

    /** False until the destination list has emitted once; before that nothing can be told dead. */
    @Volatile private var listEmitted = false

    init {
        load()
    }

    /** Back to a pristine editor for this target: reloads from the repository and drops everything typed. */
    fun reset() {
        generation++
        loadJob?.cancel()
        listJob?.cancel()
        saveJob?.cancel()
        deleteJob?.cancel()
        listEmitted = false
        _state.value = RuleEditorState(isNew = id == null)
        load()
    }

    private fun load() {
        listJob = launch {
            try {
                destinations.observeLive().collect { list ->
                    listEmitted = true
                    _state.update { it.copy(destinations = list) }
                }
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                _state.update { it.copy(loaded = true, loadFailed = true) }
            }
        }
        if (id == null) {
            _state.update { it.copy(loaded = true) }
        } else {
            loadJob = launch {
                val d = try {
                    rules.draft(id)
                } catch (c: CancellationException) {
                    throw c
                } catch (e: Exception) {
                    _state.update { it.copy(loaded = true, loadFailed = true) }
                    return@launch
                }
                if (d == null) {
                    _state.update { it.copy(loaded = true, done = true) }
                } else {
                    _state.update { it.copy(loaded = true, isNew = false, draft = d) }
                }
            }
        }
    }

    /** Applies an edit; refreshes the shown errors once there are some. */
    fun update(transform: (RuleDraft) -> RuleDraft) {
        _state.update {
            val next = it.copy(draft = transform(it.draft), saveFailed = false)
            if (next.errors.isEmpty()) next else next.copy(errors = validateRule(next.liveDraft()))
        }
    }

    /** Destinations deleted while the form was open are dropped before validating and saving. */
    private fun RuleEditorState.liveDraft(): RuleDraft {
        // The repository drops dead ids inside its own transaction, so before the list is known keep them all.
        if (!listEmitted) return draft
        val live = destinations.map { it.id }.toSet()
        return draft.copy(destinationIds = draft.destinationIds.filter { it in live }.toSet())
    }

    /** "Any" is `null`; picking a mode keeps what was typed. */
    fun setBodyMode(mode: BodyMode?) = update {
        if (mode == null) it.copy(bodyMode = null, bodyPattern = null)
        else it.copy(bodyMode = mode, bodyPattern = it.bodyPattern.orEmpty())
    }

    fun save() {
        val s = _state.value
        if (s.busy || s.done) return
        val draft = s.liveDraft()
        val errors = validateRule(draft)
        if (errors.isNotEmpty()) {
            _state.update { it.copy(errors = errors) }
            return
        }
        _state.update { it.copy(busy = true, errors = emptySet(), saveFailed = false) }
        val gen = generation
        saveJob = launch {
            try {
                if (id == null) rules.create(draft) else rules.update(id, draft)
                if (gen == generation) _state.update { it.copy(done = true) }
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                if (gen == generation) _state.update { it.copy(saveFailed = true) }
            } finally {
                if (gen == generation) _state.update { it.copy(busy = false) }
            }
        }
    }

    fun requestDelete() {
        if (id == null) return
        _state.update { if (it.busy || it.done) it else it.copy(deleteAsked = true) }
    }

    fun confirmDelete() {
        val target = id ?: return
        val s = _state.value
        if (!s.deleteAsked || s.busy || s.done) return
        _state.update { it.copy(busy = true) }
        val gen = generation
        deleteJob = launch {
            try {
                rules.delete(target)
                if (gen == generation) _state.update { it.copy(deleteAsked = false, done = true) }
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                if (gen == generation) _state.update { it.copy(deleteAsked = false, saveFailed = true) }
            } finally {
                if (gen == generation) _state.update { it.copy(busy = false) }
            }
        }
    }

    fun dismissDelete() = _state.update { it.copy(deleteAsked = false) }

    class Factory(
        private val id: Long?,
        private val rules: RuleRepository,
        private val destinations: DestinationRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = RuleEditorViewModel(id, rules, destinations) as T
    }
}
