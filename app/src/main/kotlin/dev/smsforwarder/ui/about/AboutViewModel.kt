package dev.smsforwarder.ui.about

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.smsforwarder.diag.DiagnosticsCollector
import dev.smsforwarder.ui.DocumentIo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

enum class DiagnosticMessage { SAVED, FAILED, INTERRUPTED }

/** What the About screen shows. The report text never lives here. */
data class AboutState(
    val busy: Boolean = false,
    val message: DiagnosticMessage? = null,
    val pickFile: String? = null,
)

/**
 * Builds the diagnostic report before the file picker opens and keeps it in a private field, outside [AboutState]; it is
 * dropped after writing, on cancel and in [onCleared]. [scope] is only passed by tests.
 */
class AboutViewModel(
    private val diagnostics: DiagnosticsCollector,
    private val io: DocumentIo,
    private val today: () -> LocalDate,
    private val scope: CoroutineScope? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(AboutState())
    val state: StateFlow<AboutState> = _state.asStateFlow()

    private var report: String? = null

    private val sc get() = scope ?: viewModelScope

    fun startExport() {
        if (_state.value.busy || _state.value.pickFile != null) return
        _state.value = AboutState(busy = true)
        sc.launch {
            try {
                report = diagnostics.report()
                _state.value = AboutState(busy = true, pickFile = "sms-forwarder-diagnostic-${today()}.txt")
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                _state.value = AboutState(message = DiagnosticMessage.FAILED)
            }
        }
    }

    /** Called once the picker has been launched, so a recomposition does not launch it again. */
    fun pickerLaunched() {
        if (_state.value.pickFile != null) _state.value = _state.value.copy(pickFile = null)
    }

    fun onFile(uri: Uri?) {
        val text = report
        report = null
        if (uri == null) {
            _state.value = AboutState()
            return
        }
        _state.value = AboutState(busy = true)
        sc.launch {
            if (text == null) {
                // The process died while the picker was open: the file exists but there is nothing to write.
                deleteQuietly(uri)
                _state.value = AboutState(message = DiagnosticMessage.INTERRUPTED)
                return@launch
            }
            try {
                io.write(uri, text)
                _state.value = AboutState(message = DiagnosticMessage.SAVED)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                deleteQuietly(uri)
                _state.value = AboutState(message = DiagnosticMessage.FAILED)
            }
        }
    }

    private suspend fun deleteQuietly(uri: Uri) {
        try {
            io.delete(uri)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            // Nothing more can be done about a file the provider will not remove.
        }
    }

    override fun onCleared() {
        report = null
    }

    class Factory(private val diagnostics: DiagnosticsCollector, private val io: DocumentIo) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AboutViewModel(diagnostics, io, { LocalDate.now() }) as T
    }
}
