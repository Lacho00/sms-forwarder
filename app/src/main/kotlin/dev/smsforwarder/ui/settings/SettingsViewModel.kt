package dev.smsforwarder.ui.settings

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.smsforwarder.R
import dev.smsforwarder.core.ConnectionTestResult
import dev.smsforwarder.core.Language
import dev.smsforwarder.core.NotifyKind
import dev.smsforwarder.core.Retention
import dev.smsforwarder.core.StuckAfter
import dev.smsforwarder.core.SweepInterval
import dev.smsforwarder.core.UrlValidator
import dev.smsforwarder.data.Credentials
import dev.smsforwarder.data.SettingsRepository
import dev.smsforwarder.ui.theme.ThemeMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the credentials form shows. The stored token is never loaded into it. */
data class SettingsForm(
    val url: String = "",
    val token: String = "",
    @StringRes val urlError: Int? = null,
    @StringRes val message: Int? = null,
    val busy: Boolean = false,
) {
    // The token must never end up in a log line.
    override fun toString(): String = "SettingsForm(***)"
}

/** Every preference the settings screen shows besides the credentials form and the sender list. */
data class SettingsPrefs(
    val forwarding: Boolean = true,
    val sweepInterval: SweepInterval = SweepInterval.M15,
    val stuckAfter: StuckAfter = StuckAfter.H6,
    val retention: Retention = Retention.D90,
    val hideText: Boolean = false,
    val notify: Map<NotifyKind, Boolean> = NotifyKind.entries.associateWith { true },
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val language: Language = Language.SYSTEM,
)

/**
 * [scope] is only passed by tests; in the app it is the ViewModel scope. [loadSaved] lets
 * "Probar conexión" use the stored credentials without ever showing them.
 */
class SettingsViewModel(
    private val settings: SettingsRepository,
    private val testConnection: suspend (Credentials) -> ConnectionTestResult,
    private val loadSaved: suspend () -> Credentials?,
    private val saveCredentials: suspend (Credentials) -> Unit,
    private val scope: CoroutineScope? = null,
    private val onSweepIntervalChanged: (SweepInterval) -> Unit = {},
    private val onLanguageChanged: () -> Unit = {},
) : ViewModel() {

    private val _form = MutableStateFlow(SettingsForm())
    val form: StateFlow<SettingsForm> = _form.asStateFlow()

    val senders: Flow<List<String>> = settings.senders
    val themeMode: Flow<ThemeMode> = settings.themeMode

    private val notifyFlow: Flow<Map<NotifyKind, Boolean>> =
        combine(NotifyKind.entries.map { kind -> settings.notifyEnabled(kind) }) { values ->
            NotifyKind.entries.zip(values.toList()).toMap()
        }

    private val firstGroup: Flow<SettingsPrefs> = combine(
        settings.forwardingEnabled,
        settings.sweepInterval,
        settings.stuckAfter,
        settings.retention,
        settings.hideText,
    ) { forwarding, sweep, stuck, retention, hide ->
        SettingsPrefs(
            forwarding = forwarding,
            sweepInterval = sweep,
            stuckAfter = stuck,
            retention = retention,
            hideText = hide,
        )
    }

    val prefs: StateFlow<SettingsPrefs> = combine(
        firstGroup,
        notifyFlow,
        settings.themeMode,
        settings.dynamicColor,
        settings.language,
    ) { base, notify, theme, dynamic, language ->
        base.copy(notify = notify, themeMode = theme, dynamicColor = dynamic, language = language)
    }.stateIn(scope ?: viewModelScope, SharingStarted.Eagerly, SettingsPrefs())

    private fun launch(block: suspend () -> Unit) {
        (scope ?: viewModelScope).launch { block() }
    }

    fun setUrl(value: String) = _form.update { it.copy(url = value, urlError = null, message = null) }

    fun setToken(value: String) = _form.update { it.copy(token = value, message = null) }

    /** Replaces the stored URL and token. On success both fields are cleared. */
    fun save() {
        val current = _form.value
        if (!UrlValidator.isValid(current.url)) {
            _form.update { it.copy(urlError = R.string.error_url_https) }
            return
        }
        if (current.token.isBlank()) {
            _form.update { it.copy(message = R.string.error_token_required) }
            return
        }
        launch {
            _form.update { it.copy(busy = true, message = null) }
            try {
                saveCredentials(Credentials(current.url.trim(), current.token))
                _form.value = SettingsForm(message = R.string.settings_msg_saved)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _form.update { it.copy(busy = false, message = R.string.settings_msg_save_failed) }
            }
        }
    }

    /** Tests the typed URL and token, or the stored ones when the fields are empty. */
    fun test() {
        val current = _form.value
        val typed = current.url.isNotBlank() || current.token.isNotBlank()
        if (typed) {
            if (current.url.isBlank() || current.token.isBlank()) {
                _form.update { it.copy(message = R.string.settings_msg_need_both) }
                return
            }
            if (!UrlValidator.isValid(current.url)) {
                _form.update { it.copy(urlError = R.string.error_url_https) }
                return
            }
        }
        launch {
            _form.update { it.copy(busy = true, message = null) }
            try {
                val credentials = if (typed) {
                    Credentials(current.url.trim(), current.token)
                } else {
                    loadSaved()
                }
                val message = if (credentials == null) {
                    R.string.settings_msg_need_credentials
                } else {
                    when (testConnection(credentials)) {
                        ConnectionTestResult.OK -> R.string.settings_msg_conn_ok
                        ConnectionTestResult.BAD_TOKEN -> R.string.settings_msg_bad_token
                        ConnectionTestResult.UNREACHABLE -> R.string.settings_msg_unreachable
                    }
                }
                _form.update { it.copy(message = message) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _form.update { it.copy(message = R.string.settings_msg_unreachable) }
            } finally {
                _form.update { it.copy(busy = false) }
            }
        }
    }

    fun addSender(text: String) = launch { settings.addSender(text) }

    fun removeSender(sender: String) = launch { settings.removeSender(sender) }

    fun setTheme(mode: ThemeMode) = launch { settings.setThemeMode(mode) }

    fun setForwarding(v: Boolean) = launch { settings.setForwardingEnabled(v) }

    fun setSweepInterval(v: SweepInterval) = launch {
        settings.setSweepInterval(v)
        onSweepIntervalChanged(v)
    }

    fun setStuckAfter(v: StuckAfter) = launch { settings.setStuckAfter(v) }

    fun setRetention(v: Retention) = launch { settings.setRetention(v) }

    fun setHideText(v: Boolean) = launch { settings.setHideText(v) }

    fun setNotify(kind: NotifyKind, v: Boolean) = launch { settings.setNotifyEnabled(kind, v) }

    fun setDynamicColor(v: Boolean) = launch { settings.setDynamicColor(v) }

    /** Saves first, then asks the activity to recreate itself so the new language is applied. */
    fun setLanguage(v: Language) = launch {
        settings.setLanguage(v)
        onLanguageChanged()
    }

    class Factory(
        private val settings: SettingsRepository,
        private val testConnection: suspend (Credentials) -> ConnectionTestResult,
        private val loadSaved: suspend () -> Credentials?,
        private val onSweepIntervalChanged: (SweepInterval) -> Unit = {},
        private val onLanguageChanged: () -> Unit = {},
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(
                settings,
                testConnection,
                loadSaved,
                { settings.saveCredentials(it) },
                onSweepIntervalChanged = onSweepIntervalChanged,
                onLanguageChanged = onLanguageChanged,
            ) as T
    }
}
