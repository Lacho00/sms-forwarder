package dev.smsforwarder.ui.transfer

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.smsforwarder.core.CONFIG_MAX_BYTES
import dev.smsforwarder.ui.DocumentIo
import dev.smsforwarder.core.ConfigError
import dev.smsforwarder.core.ConfigFile
import dev.smsforwarder.core.ImportPlan
import dev.smsforwarder.data.ConfigTransfer
import dev.smsforwarder.data.ImportPreview
import dev.smsforwarder.data.ImportResult
import dev.smsforwarder.data.TransientSecretException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

/** What the export/import flow shows. The exported text and the parsed file never live here. */
sealed interface TransferUi {
    data object Idle : TransferUi
    data class AskExport(val includeSecrets: Boolean) : TransferUi
    data class PickExportFile(val fileName: String) : TransferUi
    data object PickImportFile : TransferUi
    data object Waiting : TransferUi
    data class ImportSummary(val plan: ImportPlan) : TransferUi {
        override fun toString(): String = "ImportSummary(destinations=${plan.destinations.size}, rules=${plan.rules.size})"
    }
    data class ImportInvalid(val error: ConfigError, val item: String?) : TransferUi
    data class Done(val message: TransferMessage) : TransferUi
}

sealed interface TransferMessage {
    data object Exported : TransferMessage
    data class Imported(val result: ImportResult) : TransferMessage
    data object ReadFailed : TransferMessage
    data object WriteFailed : TransferMessage
    data object SecretsUnavailable : TransferMessage
}

/**
 * Drives export and import through the system file picker. The exported text is built before the picker opens and
 * kept in a private field, outside [TransferUi]; it is dropped after writing, on cancel and on [dismiss].
 * [scope] is only passed by tests.
 */
class TransferViewModel(
    private val transfer: ConfigTransfer,
    private val io: DocumentIo,
    private val appVersion: String,
    private val today: () -> LocalDate,
    private val scope: CoroutineScope? = null,
) : ViewModel() {
    private val _state = MutableStateFlow<TransferUi>(TransferUi.Idle)
    val state: StateFlow<TransferUi> = _state.asStateFlow()

    private var exportText: String? = null
    private var pending: ConfigFile? = null

    private val sc get() = scope ?: viewModelScope

    private fun canStart() = _state.value.let { it is TransferUi.Idle || it is TransferUi.Done }

    fun startExport() {
        if (canStart()) _state.value = TransferUi.AskExport(false)
    }

    fun setIncludeSecrets(include: Boolean) {
        if (_state.value is TransferUi.AskExport) _state.value = TransferUi.AskExport(include)
    }

    fun confirmExport() {
        val ask = _state.value as? TransferUi.AskExport ?: return
        _state.value = TransferUi.Waiting
        sc.launch {
            try {
                exportText = transfer.export(ask.includeSecrets, appVersion)
                _state.value = TransferUi.PickExportFile("sms-forwarder-config-${today()}.json")
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                _state.value = TransferUi.Done(TransferMessage.SecretsUnavailable)
            }
        }
    }

    /** Called once the picker has been launched, so a recomposition does not launch it again. */
    fun pickerLaunched() {
        val s = _state.value
        if (s is TransferUi.PickExportFile || s is TransferUi.PickImportFile) _state.value = TransferUi.Waiting
    }

    fun onExportFile(uri: Uri?) {
        val text = exportText
        exportText = null
        if (uri == null || text == null) {
            _state.value = TransferUi.Idle
            return
        }
        _state.value = TransferUi.Waiting
        sc.launch {
            try {
                io.write(uri, text)
                _state.value = TransferUi.Done(TransferMessage.Exported)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                _state.value = TransferUi.Done(TransferMessage.WriteFailed)
            }
        }
    }

    fun startImport() {
        if (canStart()) _state.value = TransferUi.PickImportFile
    }

    fun onImportFile(uri: Uri?) {
        if (uri == null) {
            _state.value = TransferUi.Idle
            return
        }
        _state.value = TransferUi.Waiting
        sc.launch {
            try {
                val text = io.read(uri, CONFIG_MAX_BYTES)
                _state.value = if (text == null) {
                    TransferUi.ImportInvalid(ConfigError.TOO_LARGE, null)
                } else {
                    when (val p = transfer.preview(text)) {
                        is ImportPreview.Invalid -> TransferUi.ImportInvalid(p.error, p.item)
                        is ImportPreview.Ready -> {
                            pending = p.file
                            TransferUi.ImportSummary(p.plan)
                        }
                    }
                }
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                _state.value = TransferUi.Done(TransferMessage.ReadFailed)
            }
        }
    }

    fun confirmImport() {
        if (_state.value !is TransferUi.ImportSummary) return
        val file = pending ?: return
        pending = null
        _state.value = TransferUi.Waiting
        sc.launch {
            try {
                _state.value = TransferUi.Done(TransferMessage.Imported(transfer.apply(file)))
            } catch (c: CancellationException) {
                throw c
            } catch (e: TransientSecretException) {
                _state.value = TransferUi.Done(TransferMessage.SecretsUnavailable)
            } catch (e: Exception) {
                _state.value = TransferUi.Done(TransferMessage.WriteFailed)
            }
        }
    }

    fun dismiss() {
        exportText = null
        pending = null
        _state.value = TransferUi.Idle
    }

    override fun onCleared() {
        exportText = null
        pending = null
    }

    class Factory(
        private val transfer: ConfigTransfer,
        private val io: DocumentIo,
        private val appVersion: String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            TransferViewModel(transfer, io, appVersion, { LocalDate.now() }) as T
    }
}
