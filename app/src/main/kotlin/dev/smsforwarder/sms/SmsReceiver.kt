package dev.smsforwarder.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import android.telephony.SubscriptionManager
import dev.smsforwarder.SmsForwarderApp
import dev.smsforwarder.core.EngineEvent
import dev.smsforwarder.core.EngineEventType
import dev.smsforwarder.core.IncomingSms
import dev.smsforwarder.core.RuleEngine
import dev.smsforwarder.core.SmsOrigin
import dev.smsforwarder.core.SmsRecord
import dev.smsforwarder.core.joinParts
import dev.smsforwarder.data.IngestResult
import dev.smsforwarder.work.WorkScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val TAG = "SmsReceiver"

class SmsReceiver : BroadcastReceiver() {

    /** Replaceable so tests do not need WorkManager. */
    internal var enqueue: (Context, Long) -> Unit = { context, id -> WorkScheduler.enqueueSend(context, id) }

    override fun onReceive(context: Context, intent: Intent) {
        // goAsync keeps the process alive while the coroutine writes to the database.
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                process(context, intent)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Dropped here on purpose: the inbox sweep picks the SMS up later.
            } finally {
                pending?.finish()
            }
        }
    }

    internal suspend fun process(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val graph = (context.applicationContext as SmsForwarderApp).graph
        if (!graph.settings.onboardingDone.first()) return

        // The startup import may still be running on the first SMS after an upgrade from 1.1; routing before
        // it finishes would see no rules. In safe mode the sweep recovers the SMS later.
        if (!graph.legacyImport.run()) return

        val sender = messages.first().displayOriginatingAddress.orEmpty()
        val body = joinParts(messages.map { it.messageBody.orEmpty() })
        val subId = intent.getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, -1).takeIf { it >= 0 }

        val match = RuleEngine.destinationsFor(
            IncomingSms(sender, body, SimSlots.slotOf(context, subId)),
            graph.ruleRepository.activeRules(),
        )
        match.timedOutRuleIds.forEach {
            Log.w(TAG, "rule $it timed out")
            graph.engineLog.add(EngineEvent(graph.clock.now(), EngineEventType.RULE_TIMEOUT, ruleId = it))
        }
        if (match.destinationIds.isEmpty()) {
            graph.engineLog.add(EngineEvent(graph.clock.now(), EngineEventType.RECEIVED, code = "no_rule"))
            return
        }

        val record = SmsRecord(
            sender = sender,
            body = body,
            receivedAt = messages.first().timestampMillis,
            phoneDate = graph.clock.now(),
            subId = subId,
        )
        val result = graph.smsRepository.ingest(record, SmsOrigin.RECEIVER, match.destinationIds)
        if (result is IngestResult.Inserted) enqueue(context.applicationContext, result.id)
        val (id, code) = when (result) {
            is IngestResult.Inserted -> result.id to "inserted"
            is IngestResult.AlreadyKnown -> result.id to "known"
            IngestResult.NoDestination -> null to "no_destination"
        }
        graph.engineLog.add(EngineEvent(graph.clock.now(), EngineEventType.RECEIVED, smsId = id, code = code))
    }
}
