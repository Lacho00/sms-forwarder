package dev.smsforwarder.diag

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import dev.smsforwarder.core.joinParts
import java.io.File

/** Records every incoming SMS without filtering by sender, so the phone's real values can be compared. */
class DiagReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return
        val body = joinParts(messages.map { it.messageBody.orEmpty() })
        val capture = DiagCapture(
            sender = messages.first().displayOriginatingAddress.orEmpty(),
            pduTimestamp = messages.first().timestampMillis,
            bodyHash = shortHash(body),
            bodyLength = body.length,
            parts = messages.size,
            subId = intent.getIntExtra("android.telephony.extra.SUBSCRIPTION_INDEX", -1),
            capturedAt = System.currentTimeMillis(),
        )
        DiagStore(File(context.filesDir, DiagStore.FILE_NAME)).add(capture)
    }
}
