package dev.smsforwarder.ui.destinations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.smsforwarder.core.DestinationDraft
import dev.smsforwarder.core.DestinationError
import dev.smsforwarder.core.DestinationPresets
import dev.smsforwarder.core.DestinationTestResult
import dev.smsforwarder.core.TestVerdict
import dev.smsforwarder.core.HeaderDraft
import dev.smsforwarder.core.HttpMethod
import dev.smsforwarder.core.SuccessPolicy
import dev.smsforwarder.core.TemplateKind
import dev.smsforwarder.core.validateDestination
import dev.smsforwarder.data.DestinationRepository
import dev.smsforwarder.net.DestinationTester
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the template presets ask for: URL (standard, ntfy), token (standard, Telegram bot), chat id (Telegram). */
data class PresetFields(val url: String = "", val token: String = "", val chatId: String = "") {
    override fun toString(): String = "PresetFields(***)"
}

/**
 * [value] of a stored secret header is empty and [stored] true: the real value never reaches the form. The stored
 * value is kept only while [name] still equals [storedName]; a renamed or new secret row needs a value.
 */
data class HeaderRowUi(
    val name: String,
    val value: String,
    val secret: Boolean,
    val stored: Boolean,
    val storedName: String? = null,
) {
    override fun toString(): String =
        "HeaderRowUi(name=$name, value=${if (secret) "<hidden>" else value}, secret=$secret, stored=$stored)"
}

data class DestinationEditorState(
    val loaded: Boolean = false,
    val isNew: Boolean = true,
    val template: TemplateKind = TemplateKind.STANDARD_JSON,
    val preset: PresetFields = PresetFields(),
    val name: String = "",
    val enabled: Boolean = true,
    val method: HttpMethod = HttpMethod.POST,
    val url: String = "",
    val urlStored: Boolean = false,
    val headers: List<HeaderRowUi> = emptyList(),
    val body: String = "",
    val bodyIsJson: Boolean = true,
    val successPolicy: SuccessPolicy = SuccessPolicy.ANY_2XX,
    val errors: Set<DestinationError> = emptySet(),
    val test: DestinationTestResult? = null,
    val busy: Boolean = false,
    /** Pending deliveries the delete would cancel, while the confirmation is open. */
    val deletePending: Int? = null,
    val done: Boolean = false,
    /** The last save did not complete (for example a Keystore failure); nothing was lost, retry. */
    val saveFailed: Boolean = false,
    /** The saved settings could not be read (for example a Keystore error); the form is not usable. */
    val loadFailed: Boolean = false,
) {
    // URL, token, headers and body can all carry secrets: none of them is printed.
    override fun toString(): String =
        "DestinationEditorState(loaded=$loaded, isNew=$isNew, template=$template, enabled=$enabled, " +
            "method=$method, headers=${headers.size}, errors=$errors, busy=$busy, done=$done, loadFailed=$loadFailed)"
}

/** [id] null creates. [scope] is only passed by tests; in the app it is the ViewModel scope. */
class DestinationEditorViewModel(
    private val id: Long?,
    private val repo: DestinationRepository,
    private val tester: DestinationTester,
    private val enqueue: (List<Long>) -> Unit,
    private val defaultName: (TemplateKind) -> String,
    private val scope: CoroutineScope? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<DestinationEditorState> = _state.asStateFlow()

    /** A new destination starts on Standard JSON with that template's name; an existing one is filled by load(). */
    private fun initialState() = DestinationEditorState(
        isNew = id == null,
        name = if (id == null) defaultName(TemplateKind.STANDARD_JSON) else "",
    )

    private fun launch(block: suspend () -> Unit): Job = (scope ?: viewModelScope).launch { block() }

    private var testJob: Job? = null
    private var generation = 0

    /** Bumped only by [reset]: a save or delete started before it must not touch the fresh state. */
    private var epoch = 0

    private var loadJob: Job? = null
    private var saveJob: Job? = null
    private var deleteJob: Job? = null
    private var requestJob: Job? = null

    init {
        load()
    }

    /**
     * Back to a pristine editor for this target: reloads from the repository and drops everything typed (URL, preset
     * token, header values), the test result, the delete confirmation and any done/failed flag. Called on every open
     * and when leaving, so a secret typed once does not stay in memory.
     */
    fun reset() {
        generation++
        epoch++
        testJob?.cancel()
        loadJob?.cancel()
        saveJob?.cancel()
        deleteJob?.cancel()
        requestJob?.cancel()
        _state.value = initialState()
        load()
    }

    private fun load() {
        if (id == null) {
            _state.update { it.copy(loaded = true) }
        } else {
            loadJob = launch {
                val e = try {
                    repo.editable(id)
                } catch (c: CancellationException) {
                    throw c
                } catch (e: Exception) {
                    _state.update { it.copy(loaded = true, loadFailed = true) }
                    return@launch
                }
                if (e == null) {
                    _state.update { it.copy(loaded = true, done = true) }
                } else {
                    val d = e.draft
                    _state.update {
                        it.copy(
                            loaded = true, isNew = false, template = d.template, name = d.name, enabled = d.enabled,
                            method = d.method, url = "", urlStored = e.urlStored,
                            headers = d.headers.map { h ->
                                HeaderRowUi(
                                    h.name, if (h.secret) "" else h.value.orEmpty(), h.secret,
                                    stored = h.secret && h.value == null, storedName = h.name.takeIf { h.secret },
                                )
                            },
                            body = d.body, bodyIsJson = d.bodyIsJson, successPolicy = d.successPolicy,
                        )
                    }
                }
            }
        }
    }

    private fun DestinationEditorState.toDraft(): DestinationDraft = DestinationDraft(
        name = name, enabled = enabled, template = template, method = method,
        url = if (url.isEmpty() && urlStored) null else url,
        headers = headers.map { h ->
            HeaderDraft(h.name, if (h.keepsStored()) null else h.value, h.secret)
        },
        body = body, bodyIsJson = bodyIsJson, successPolicy = successPolicy,
    )

    private fun HeaderRowUi.keepsStored() = secret && stored && value.isEmpty() && storedName == name

    private fun DestinationEditorState.validate(): Set<DestinationError> {
        val errors = validateDestination(toDraft(), urlStored).toMutableSet()
        // A secret header with no value is only fine while it keeps the stored one under the same name.
        if (headers.any { it.secret && it.value.isBlank() && !it.keepsStored() }) errors += DestinationError.HEADER_VALUE_INVALID
        return errors
    }

    /** Applies an edit; clears the last test result and refreshes shown errors once there are some. */
    private fun edit(change: (DestinationEditorState) -> DestinationEditorState) {
        // An old test result must never land on a changed form.
        generation++
        val testing = testJob?.isActive == true
        testJob?.cancel()
        _state.update {
            val next = change(it).copy(test = null, saveFailed = false, busy = if (testing) false else it.busy)
            if (next.errors.isEmpty()) next else next.copy(errors = next.validate())
        }
    }

    /** Only when creating; the form is the same for every template afterwards. */
    fun chooseTemplate(k: TemplateKind) {
        if (!_state.value.isNew) return
        edit {
            val renamed = it.name.isBlank() || it.name == defaultName(it.template)
            val base = if (k == TemplateKind.CUSTOM) fromDraft(it, DestinationPresets.custom(defaultName(k))) else it
            base.copy(
                template = k, preset = PresetFields(), errors = emptySet(),
                name = if (renamed) defaultName(k) else it.name,
            )
        }
    }

    fun setPreset(p: PresetFields) = edit { it.copy(preset = p) }

    /** Fills the form from the template's preset fields. */
    fun applyPreset() {
        edit {
            val p = it.preset
            val name = it.name.ifBlank { defaultName(it.template) }
            val draft = when (it.template) {
                TemplateKind.STANDARD_JSON -> DestinationPresets.standardJson(name, p.url.trim(), p.token.trim())
                TemplateKind.TELEGRAM -> DestinationPresets.telegram(name, p.token.trim(), p.chatId.trim())
                TemplateKind.NTFY -> DestinationPresets.ntfy(name, p.url.trim())
                TemplateKind.CUSTOM -> return@edit it
            }
            fromDraft(it, draft).copy(preset = PresetFields(), urlStored = false)
        }
    }

    private fun fromDraft(s: DestinationEditorState, d: DestinationDraft) = s.copy(
        name = d.name, method = d.method, url = d.url.orEmpty(),
        headers = d.headers.map { h -> HeaderRowUi(h.name, h.value.orEmpty(), h.secret, stored = false) },
        body = d.body, bodyIsJson = d.bodyIsJson, successPolicy = d.successPolicy,
    )

    fun setName(v: String) = edit { it.copy(name = v) }
    fun setEnabled(v: Boolean) = edit { it.copy(enabled = v) }
    fun setMethod(v: HttpMethod) = edit { it.copy(method = v).custom() }
    fun setUrl(v: String) = edit { it.copy(url = v) }
    fun setHeaders(v: List<HeaderRowUi>) = edit { it.copy(headers = v) }
    fun setBody(v: String) = edit { it.copy(body = v).custom() }
    fun setBodyIsJson(v: Boolean) = edit { it.copy(bodyIsJson = v).custom() }
    fun setSuccessPolicy(v: SuccessPolicy) = edit { it.copy(successPolicy = v).custom() }

    // Method, body and success policy only take effect on a custom destination (the tester and the sender
    // ignore them for the presets), so editing one by hand makes the template honest about it.
    private fun DestinationEditorState.custom() = if (template == TemplateKind.CUSTOM) this else copy(template = TemplateKind.CUSTOM)

    /** Sends a sample request with what the form holds. Blocked, with the errors shown, while the form is invalid. */
    fun test() {
        val s = _state.value
        if (s.busy) return
        val errors = s.validate()
        if (errors.isNotEmpty()) {
            _state.update { it.copy(errors = errors, test = null) }
            return
        }
        _state.update { it.copy(busy = true, errors = emptySet(), test = null) }
        val gen = ++generation
        testJob = launch {
            try {
                val r = try {
                    tester.test(s.toDraft(), id)
                } catch (c: CancellationException) {
                    throw c
                } catch (e: Exception) {
                    DestinationTestResult(null, TestVerdict.FAILED)
                }
                if (gen == generation) _state.update { it.copy(test = r) }
            } finally {
                if (gen == generation) _state.update { it.copy(busy = false) }
            }
        }
    }

    fun save() {
        val s = _state.value
        if (s.busy || s.done) return
        val errors = s.validate()
        if (errors.isNotEmpty()) {
            _state.update { it.copy(errors = errors) }
            return
        }
        _state.update { it.copy(busy = true, errors = emptySet(), saveFailed = false) }
        val gen = epoch
        saveJob = launch {
            try {
                val draft = s.toDraft()
                val requeued = if (id == null) {
                    repo.create(draft)
                    emptyList()
                } else {
                    repo.update(id, draft)
                }
                if (requeued.isNotEmpty()) enqueue(requeued)
                if (gen == epoch) _state.update { it.copy(done = true) }
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                if (gen == epoch) _state.update { it.copy(saveFailed = true) }
            } finally {
                if (gen == epoch) _state.update { it.copy(busy = false) }
            }
        }
    }

    /** Opens the delete confirmation with the number of deliveries it would cancel. */
    fun requestDelete() {
        val target = id ?: return
        if (_state.value.busy || _state.value.done) return
        val gen = epoch
        requestJob = launch {
            try {
                val n = repo.pendingCount(target)
                if (gen == epoch) _state.update { it.copy(deletePending = n) }
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                if (gen == epoch) _state.update { it.copy(saveFailed = true) }
            }
        }
    }

    fun confirmDelete() {
        val target = id ?: return
        val s = _state.value
        if (s.deletePending == null || s.busy || s.done) return
        _state.update { it.copy(busy = true) }
        val gen = epoch
        deleteJob = launch {
            try {
                repo.delete(target)
                if (gen == epoch) _state.update { it.copy(deletePending = null, done = true) }
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                if (gen == epoch) _state.update { it.copy(deletePending = null, saveFailed = true) }
            } finally {
                if (gen == epoch) _state.update { it.copy(busy = false) }
            }
        }
    }

    fun dismissDelete() = _state.update { it.copy(deletePending = null) }

    class Factory(
        private val id: Long?,
        private val repo: DestinationRepository,
        private val tester: DestinationTester,
        private val enqueue: (List<Long>) -> Unit,
        private val defaultName: (TemplateKind) -> String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            DestinationEditorViewModel(id, repo, tester, enqueue, defaultName) as T
    }
}
