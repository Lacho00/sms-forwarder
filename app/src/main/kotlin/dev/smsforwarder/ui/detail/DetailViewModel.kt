package dev.smsforwarder.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.smsforwarder.data.AttemptEntity
import dev.smsforwarder.data.SmsEntity
import dev.smsforwarder.data.SmsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** [loaded] is false until the first database read; after that a null [sms] means "not found". */
data class DetailUiState(
    val sms: SmsEntity?,
    val attempts: List<AttemptEntity>,
    val loaded: Boolean = true,
)

class DetailViewModel(
    private val smsId: Long,
    private val smsRepository: SmsRepository,
    private val enqueueSend: (Long) -> Unit,
) : ViewModel() {

    val state: StateFlow<DetailUiState> = combine(
        smsRepository.observeAll().map { list -> list.firstOrNull { it.id == smsId } },
        smsRepository.observeAttempts(smsId),
    ) { sms, attempts -> DetailUiState(sms, attempts) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = DetailUiState(null, emptyList(), loaded = false),
        )

    /** Puts an errored SMS back in the queue and starts a send right away. */
    fun retry() {
        viewModelScope.launch {
            smsRepository.requeue(smsId)
            enqueueSend(smsId)
        }
    }

    class Factory(
        private val smsId: Long,
        private val smsRepository: SmsRepository,
        private val enqueueSend: (Long) -> Unit,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            DetailViewModel(smsId, smsRepository, enqueueSend) as T
    }
}
