package dev.smsforwarder.sms

import android.content.Context
import android.util.Log
import dev.smsforwarder.core.Clock
import dev.smsforwarder.core.EngineEvent
import dev.smsforwarder.core.EngineEventType
import dev.smsforwarder.diag.EngineLog
import dev.smsforwarder.core.QueuePolicy
import dev.smsforwarder.core.IncomingSms
import dev.smsforwarder.core.RuleEngine
import dev.smsforwarder.core.SmsIdentity
import dev.smsforwarder.core.SmsOrigin
import dev.smsforwarder.core.SmsRecord
import dev.smsforwarder.data.IngestResult
import dev.smsforwarder.data.RuleRepository
import dev.smsforwarder.data.SettingsRepository
import dev.smsforwarder.data.SmsEntity
import dev.smsforwarder.data.SmsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private const val TAG = "Sweeper"

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
    private val ruleRepository: RuleRepository,
    private val context: Context,
    private val clock: Clock,
    private val enqueue: (Long) -> Unit,
    private val onRecovered: (List<SmsEntity>) -> Unit,
    private val engineLog: EngineLog? = null,
) {
    private suspend fun log(e: EngineEvent) { engineLog?.add(e) }

    suspend fun sweep(): SweepResult {
        if (!settings.onboardingDone.first()) return SweepResult(0, false)

        val now = clock.now()
        // Without a floor nothing older than one overlap window is read, instead of the whole inbox.
        val floor = settings.sweepFloor() ?: (now - QueuePolicy.SWEEP_OVERLAP_MS)
        val cursor = settings.sweepCursor()
        val from = if (cursor != null) QueuePolicy.sweepFrom(cursor, floor) else floor

        val rows = withContext(Dispatchers.IO) { reader.readSince(from) } ?: return SweepResult(0, true).also { log(EngineEvent(clock.now(), EngineEventType.SWEEP, code = "unreadable", count = 0)) }
        if (rows.isEmpty() && withContext(Dispatchers.IO) { reader.hasAnyRow() } == false) {
            log(EngineEvent(clock.now(), EngineEventType.SWEEP, code = "unreadable", count = 0))
            return SweepResult(0, true)
        }

        val rules = ruleRepository.activeRules()
        val recovered = mutableListOf<SmsEntity>()
        for (row in rows) {
            val match = RuleEngine.destinationsFor(
                IncomingSms(row.address, row.body, SimSlots.slotOf(context, row.subId)),
                rules,
            )
            match.timedOutRuleIds.forEach {
                Log.w(TAG, "rule $it timed out")
                log(EngineEvent(clock.now(), EngineEventType.RULE_TIMEOUT, ruleId = it))
            }
            if (match.destinationIds.isEmpty()) continue
            val record = SmsRecord(
                sender = row.address,
                body = row.body,
                receivedAt = SmsIdentity.effectiveReceivedAt(row.dateSent, row.date),
                phoneDate = row.date,
                subId = row.subId,
            )
            val result = smsRepository.ingest(record, SmsOrigin.SWEEP, match.destinationIds)
            if (result is IngestResult.Inserted) {
                enqueue(result.id)
                smsRepository.get(result.id)?.let { recovered += it }
            }
        }
        settings.setSweepCursor(rows.maxOfOrNull { it.date } ?: now)
        if (recovered.isNotEmpty()) onRecovered(recovered)
        log(EngineEvent(clock.now(), EngineEventType.SWEEP, code = "ok", count = recovered.size))
        return SweepResult(recovered.size, false)
    }
}
