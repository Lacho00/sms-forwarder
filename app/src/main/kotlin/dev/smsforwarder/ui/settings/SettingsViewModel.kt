package dev.smsforwarder.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.smsforwarder.core.Language
import dev.smsforwarder.core.NotifyKind
import dev.smsforwarder.core.Retention
import dev.smsforwarder.core.StuckAfter
import dev.smsforwarder.core.SweepInterval
import dev.smsforwarder.data.SettingsRepository
import dev.smsforwarder.ui.theme.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Every preference the settings screen shows besides the sender list. */
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

/** [scope] is only passed by tests; in the app it is the ViewModel scope. */
class SettingsViewModel(
    private val settings: SettingsRepository,
    private val scope: CoroutineScope? = null,
    private val onSweepIntervalChanged: (SweepInterval) -> Unit = {},
) : ViewModel() {

    // One-shot, and not tied to any activity: the ViewModel outlives them, so the current one collects it.
    private val languageChangedChannel = Channel<Unit>(Channel.CONFLATED)
    val languageChanged: Flow<Unit> = languageChangedChannel.receiveAsFlow()

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

    /** Saves first, then emits [languageChanged] so the current activity recreates itself and applies the new language. */
    fun setLanguage(v: Language) = launch {
        settings.setLanguage(v)
        languageChangedChannel.trySend(Unit)
    }

    class Factory(
        private val settings: SettingsRepository,
        private val onSweepIntervalChanged: (SweepInterval) -> Unit = {},
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(settings, onSweepIntervalChanged = onSweepIntervalChanged) as T
    }
}
