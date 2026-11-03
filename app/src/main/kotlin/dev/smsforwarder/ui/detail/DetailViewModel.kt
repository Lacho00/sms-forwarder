package dev.smsforwarder.ui.detail

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.smsforwarder.core.StatusLabel
import dev.smsforwarder.core.statusLabelOf
import dev.smsforwarder.data.AttemptEntity
import dev.smsforwarder.data.DeliveryEntity
import dev.smsforwarder.data.DestinationEntity
import dev.smsforwarder.data.SmsEntity
import dev.smsforwarder.data.SmsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** [loaded] is false until the first database read; after that a null [sms] means "not found". */
data class DetailUiState(
    val sms: SmsEntity?,
    val attempts: List<AttemptEntity>,
    val loaded: Boolean = true,
    val hideText: Boolean = false,
    val deliveries: List<DeliveryEntity> = emptyList(),
    /** Live destinations, the targets of "resend to a destination". */
    val destinations: List<DestinationEntity> = emptyList(),
) {
    val label: StatusLabel?
        get() = sms?.let { statusLabelOf(it.status, it.origin, it.attempts, it.lastHttpCode) }
}

class DetailViewModel(
    private val smsId: Long,
    private val smsRepository: SmsRepository,
    private val enqueueSend: (Long) -> Unit,
    hideText: Flow<Boolean> = flowOf(false),
    private val cancelSend: (Long) -> Unit = {},
    destinations: Flow<List<DestinationEntity>> = flowOf(emptyList()),
) : ViewModel() {

    val state: StateFlow<DetailUiState> = combine(
        smsRepository.observe(smsId),
        smsRepository.observeAttempts(smsId),
        hideText,
        smsRepository.observeDeliveriesOf(smsId),
        destinations,
    ) { sms, attempts, hide, deliveries, live ->
        DetailUiState(sms, attempts, hideText = hide, deliveries = deliveries, destinations = live)
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = DetailUiState(null, emptyList(), loaded = false),
        )

    /** Puts an errored SMS back in the queue and starts a send right away. */
    fun retry() {
        launchSafely {
            smsRepository.requeue(smsId)
            enqueueSend(smsId)
        }
    }

    /** Queues the SMS again for one destination and starts a send right away. */
    fun resendTo(destinationId: Long) {
        launchSafely {
            smsRepository.resendTo(smsId, destinationId)
            enqueueSend(smsId)
        }
    }

    /** Hides the SMS from the app and stops its send; the row stays so the sweep does not bring it back. */
    fun delete() {
        launchSafely {
            smsRepository.softDelete(smsId)
            cancelSend(smsId)
        }
    }

    // A failing write or enqueue must not crash the app; the screen simply keeps showing the stored state.
    private fun launchSafely(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("DetailViewModel", "Action failed: ${e.javaClass.simpleName}")
            }
        }
    }

    class Factory(
        private val smsId: Long,
        private val smsRepository: SmsRepository,
        private val enqueueSend: (Long) -> Unit,
        private val hideText: Flow<Boolean> = flowOf(false),
        private val cancelSend: (Long) -> Unit = {},
        private val destinations: Flow<List<DestinationEntity>> = flowOf(emptyList()),
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            DetailViewModel(smsId, smsRepository, enqueueSend, hideText, cancelSend, destinations) as T
    }
}
