package dev.smsforwarder.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import dev.smsforwarder.core.Clock
import dev.smsforwarder.core.ConnectionTestResult
import dev.smsforwarder.core.UrlValidator
import dev.smsforwarder.data.Credentials
import dev.smsforwarder.data.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

object OnboardingTexts {
    const val CUTOVER =
        "Antes de seguir, desinstala o desactiva la app anterior que reenviaba estos SMS. " +
            "Si las dos siguen activas, el mismo SMS se enviaría dos veces."
    const val REMINDER =
        "Recuerda: la app anterior debe seguir desinstalada o desactivada. " +
            "A continuación puedes recuperar a mano SMS de los últimos días."
    const val CREDENTIALS_SAVE_ERROR = "No se pudieron guardar las credenciales"
    const val FINISH_ERROR = "No se pudo terminar la configuración. Inténtalo de nuevo."
}

enum class OnboardingStep { CUTOVER, PERMISSIONS, BATTERY, MIUI, SENDERS, CONNECTION, REMINDER, RECOVERY }

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.CUTOVER,
    val cutoverConfirmed: Boolean = false,
    val smsPermissionGranted: Boolean = false,
    val restrictedHint: Boolean = false,
    val senders: List<String> = emptyList(),
    val url: String = "",
    val token: String = "",
    val urlError: String? = null,
    val tokenError: String? = null,
    val testing: Boolean = false,
    val testResult: ConnectionTestResult? = null,
    val finishError: String? = null,
) {
    val canContinue: Boolean
        get() = when (step) {
            OnboardingStep.CUTOVER -> cutoverConfirmed
            OnboardingStep.PERMISSIONS -> smsPermissionGranted
            OnboardingStep.BATTERY, OnboardingStep.MIUI, OnboardingStep.REMINDER -> true
            OnboardingStep.SENDERS -> senders.isNotEmpty()
            OnboardingStep.CONNECTION -> testResult == ConnectionTestResult.OK
            OnboardingStep.RECOVERY -> false
        }

    // The token must never end up in a log line.
    override fun toString(): String = "OnboardingUiState(step=$step)"
}

/**
 * First-run wizard. Every function that touches storage or the network is `suspend`, so the screen
 * decides the scope. [connectionTest] is a lambda so tests need no real HTTP.
 */
class OnboardingViewModel(
    private val settings: SettingsRepository,
    private val clock: Clock,
    private val connectionTest: suspend (Credentials) -> ConnectionTestResult,
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    /** Picks up senders already saved (for example if the app was closed halfway through). */
    suspend fun load() {
        val saved = settings.senders.first()
        _state.update { it.copy(senders = saved) }
    }

    fun confirmCutover(confirmed: Boolean) = _state.update { it.copy(cutoverConfirmed = confirmed) }

    fun onPermissionResult(smsGranted: Boolean, restrictedHint: Boolean) =
        _state.update { it.copy(smsPermissionGranted = smsGranted, restrictedHint = restrictedHint && !smsGranted) }

    suspend fun addSender(text: String) {
        settings.addSender(text)
        val saved = settings.senders.first()
        _state.update { it.copy(senders = saved) }
    }

    suspend fun removeSender(sender: String) {
        settings.removeSender(sender)
        val saved = settings.senders.first()
        _state.update { it.copy(senders = saved) }
    }

    fun setUrl(value: String) =
        _state.update { it.copy(url = value, urlError = null, testResult = null) }

    fun setToken(value: String) =
        _state.update { it.copy(token = value, tokenError = null, testResult = null) }

    suspend fun testConnection() {
        val current = _state.value
        if (!UrlValidator.isValid(current.url)) {
            _state.update { it.copy(urlError = "La URL debe empezar por https://", testResult = null) }
            return
        }
        if (current.token.isBlank()) {
            _state.update { it.copy(tokenError = "Escribe el token", testResult = null) }
            return
        }
        _state.update { it.copy(testing = true, urlError = null, tokenError = null, testResult = null) }
        try {
            val result = connectionTest(Credentials(current.url.trim(), current.token))
            // Ignore the result if the user edited the fields while the test was running.
            _state.update {
                if (it.url == current.url && it.token == current.token) it.copy(testResult = result) else it
            }
        } finally {
            _state.update { it.copy(testing = false) }
        }
    }

    /** Moves to the next step if the current one allows it. Leaving CONNECTION stores the credentials. */
    suspend fun next() {
        val current = _state.value
        if (!current.canContinue) return
        if (current.step == OnboardingStep.CONNECTION) {
            if (!UrlValidator.isValid(current.url)) {
                _state.update { it.copy(urlError = "La URL debe empezar por https://", testResult = null) }
                return
            }
            if (current.token.isBlank()) {
                _state.update { it.copy(tokenError = "Escribe el token", testResult = null) }
                return
            }
            try {
                settings.saveCredentials(Credentials(current.url.trim(), current.token))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(tokenError = OnboardingTexts.CREDENTIALS_SAVE_ERROR) }
                return
            }
            // The token is stored encrypted now; drop it from memory state.
            _state.update { it.copy(token = "") }
        }
        val steps = OnboardingStep.entries
        val following = steps.getOrNull(current.step.ordinal + 1) ?: return
        _state.update { it.copy(step = following) }
    }

    fun setFinishError(message: String?) = _state.update { it.copy(finishError = message) }

    /**
     * Ends the wizard. [floor] is the instant the recovery list read the inbox: nothing older is
     * ever swept (the previous app already sent it) and nothing newer is skipped, because SMS that
     * arrived since then were dropped by the receiver while onboarding was not done.
     */
    suspend fun finish(floor: Long) {
        settings.setSweepFloor(floor)
        settings.setSweepCursor(floor)
        settings.setOnboardingDone()
    }

    class Factory(
        private val settings: SettingsRepository,
        private val clock: Clock,
        private val testConnection: suspend (Credentials) -> ConnectionTestResult,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            OnboardingViewModel(settings, clock, testConnection) as T
    }
}
