package dev.smsforwarder.ui.recovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import dev.smsforwarder.core.Clock
import dev.smsforwarder.core.QueuePolicy
import dev.smsforwarder.core.SenderFilter
import dev.smsforwarder.core.SmsIdentity
import dev.smsforwarder.core.SmsOrigin
import dev.smsforwarder.core.SmsRecord
import dev.smsforwarder.data.IngestResult
import dev.smsforwarder.data.SettingsRepository
import dev.smsforwarder.data.SmsRepository
import dev.smsforwarder.sms.InboxReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

data class RecoveryItem(
    val key: String,
    val sender: String,
    val receivedAt: Long,
    val preview: String,
    val alreadyKnown: Boolean,
    val selected: Boolean,
)

private const val PREVIEW_LENGTH = 80

/**
 * Lists the SMS of the last 7 days from the allowed senders so the user can send some by hand.
 * Nothing is selected by default and SMS the app already has cannot be selected.
 * [enqueue] starts a send for one stored SMS id.
 */
class RecoveryViewModel(
    private val reader: InboxReader,
    private val smsRepository: SmsRepository,
    private val settings: SettingsRepository,
    private val clock: Clock,
    private val enqueue: (Long) -> Unit,
) : ViewModel() {

    private val _candidates = MutableStateFlow<List<RecoveryItem>>(emptyList())
    val candidates: StateFlow<List<RecoveryItem>> = _candidates.asStateFlow()

    private val _unreadable = MutableStateFlow(false)

    /** True when the inbox could not be read (permission missing), as opposed to "nothing found". */
    val unreadable: StateFlow<Boolean> = _unreadable.asStateFlow()

    private var records: Map<String, SmsRecord> = emptyMap()

    @Volatile
    private var lastLoadAt: Long? = null

    /**
     * The instant [load] read the inbox. Onboarding uses it as the sweep floor, so SMS that arrive
     * after this read are still swept. Falls back to the current time if [load] never ran.
     */
    val loadedAt: Long
        get() = lastLoadAt ?: clock.now()

    /** Reads the inbox again. Called by the screen when it opens. */
    suspend fun load() {
        val now = clock.now()
        lastLoadAt = now
        val from = now - QueuePolicy.MANUAL_RECOVERY_MS
        val rows = withContext(Dispatchers.IO) { reader.readSince(from) }
        if (rows == null) {
            _unreadable.value = true
            _candidates.value = emptyList()
            records = emptyMap()
            return
        }
        _unreadable.value = false
        val senders = settings.senders.first()
        val found = LinkedHashMap<String, SmsRecord>()
        rows.filter { SenderFilter.matches(it.address, senders) }
            .map {
                SmsRecord(
                    sender = it.address,
                    body = it.body,
                    receivedAt = SmsIdentity.effectiveReceivedAt(it.dateSent, it.date),
                    phoneDate = it.date,
                    subId = it.subId,
                )
            }
            .sortedByDescending { it.receivedAt }
            .forEach { found.putIfAbsent(SmsIdentity.localKey(it), it) }
        records = found
        _candidates.value = found.map { (key, record) ->
            RecoveryItem(
                key = key,
                sender = record.sender,
                receivedAt = record.receivedAt,
                preview = record.body.replace(Regex("\\s+"), " ").trim().take(PREVIEW_LENGTH),
                alreadyKnown = smsRepository.isKnown(record),
                selected = false,
            )
        }
    }

    fun toggle(key: String) {
        _candidates.update { list ->
            list.map { if (it.key == key && !it.alreadyKnown) it.copy(selected = !it.selected) else it }
        }
    }

    /** Stores the selected SMS as manual recoveries and starts their send. Returns how many were new. */
    suspend fun sendSelected(): Int {
        var inserted = 0
        for (item in _candidates.value.filter { it.selected && !it.alreadyKnown }) {
            val record = records[item.key] ?: continue
            val result = smsRepository.ingest(record, SmsOrigin.MANUAL)
            if (result is IngestResult.Inserted) {
                enqueue(result.id)
                inserted++
            }
        }
        _candidates.update { list ->
            list.map { if (it.selected) it.copy(alreadyKnown = true, selected = false) else it }
        }
        return inserted
    }

    class Factory(
        private val reader: InboxReader,
        private val smsRepository: SmsRepository,
        private val settings: SettingsRepository,
        private val clock: Clock,
        private val enqueue: (Long) -> Unit,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            RecoveryViewModel(reader, smsRepository, settings, clock, enqueue) as T
    }
}
