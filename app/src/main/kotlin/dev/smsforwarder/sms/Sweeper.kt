package dev.smsforwarder.sms

import dev.smsforwarder.core.Clock
import dev.smsforwarder.core.QueuePolicy
import dev.smsforwarder.core.SenderFilter
import dev.smsforwarder.core.SmsIdentity
import dev.smsforwarder.core.SmsOrigin
import dev.smsforwarder.core.SmsRecord
import dev.smsforwarder.data.IngestResult
import dev.smsforwarder.data.SettingsRepository
import dev.smsforwarder.data.SmsEntity
import dev.smsforwarder.data.SmsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

data class SweepResult(val recovered: Int, val inboxUnreadable: Boolean)

/**
 * Safety net for the receiver: re-reads the inbox from (cursor - 24 h), never before the floor,
 * and ingests what the receiver missed. [enqueue] starts a send per new SMS; [onRecovered]
 * gets the new rows so the caller can notify.
 */
class Sweeper(
    private val reader: InboxReader,
    private val smsRepository: SmsRepository,
    private val settings: SettingsRepository,
    private val clock: Clock,
    private val enqueue: (Long) -> Unit,
    private val onRecovered: (List<SmsEntity>) -> Unit,
) {
    suspend fun sweep(): SweepResult {
        if (!settings.onboardingDone.first()) return SweepResult(0, false)

        val now = clock.now()
        // Without a floor nothing older than one overlap window is read, instead of the whole inbox.
        val floor = settings.sweepFloor() ?: (now - QueuePolicy.SWEEP_OVERLAP_MS)
        val cursor = settings.sweepCursor()
        val from = if (cursor != null) QueuePolicy.sweepFrom(cursor, floor) else floor

        val rows = withContext(Dispatchers.IO) { reader.readSince(from) } ?: return SweepResult(0, true)
        if (rows.isEmpty() && withContext(Dispatchers.IO) { reader.hasAnyRow() } == false) {
            return SweepResult(0, true)
        }

        val senders = settings.senders.first()
        val recovered = mutableListOf<SmsEntity>()
        for (row in rows) {
            if (!SenderFilter.matches(row.address, senders)) continue
            val record = SmsRecord(
                sender = row.address,
                body = row.body,
                receivedAt = SmsIdentity.effectiveReceivedAt(row.dateSent, row.date),
                phoneDate = row.date,
                subId = row.subId,
            )
            val result = smsRepository.ingest(record, SmsOrigin.SWEEP)
            if (result is IngestResult.Inserted) {
                enqueue(result.id)
                smsRepository.get(result.id)?.let { recovered += it }
            }
        }
        settings.setSweepCursor(rows.maxOfOrNull { it.date } ?: now)
        if (recovered.isNotEmpty()) onRecovered(recovered)
        return SweepResult(recovered.size, false)
    }
}
