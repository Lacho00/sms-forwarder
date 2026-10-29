package dev.smsforwarder.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SubscriptionManager
import dev.smsforwarder.SmsForwarderApp
import dev.smsforwarder.core.SenderFilter
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

        val sender = messages.first().displayOriginatingAddress.orEmpty()
        if (!SenderFilter.matches(sender, graph.settings.senders.first())) return

        val record = SmsRecord(
            sender = sender,
            body = joinParts(messages.map { it.messageBody.orEmpty() }),
            receivedAt = messages.first().timestampMillis,
            phoneDate = graph.clock.now(),
            subId = intent.getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, -1).takeIf { it >= 0 },
        )
        val result = graph.smsRepository.ingest(record, SmsOrigin.RECEIVER)
        if (result is IngestResult.Inserted) enqueue(context.applicationContext, result.id)
    }
}
