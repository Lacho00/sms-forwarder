package dev.smsforwarder.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.smsforwarder.SmsForwarderApp
import dev.smsforwarder.core.SendOutcome
import dev.smsforwarder.core.SmsStatus

class SendWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_SMS_ID, -1L)
        if (id < 0) return Result.failure()

        val graph = (applicationContext as SmsForwarderApp).graph
        val repo = graph.smsRepository
        val sms = repo.get(id) ?: return Result.success()
        if (sms.status == SmsStatus.SENT) return Result.success()

        // No credentials yet: keep the SMS queued and try again later.
        val credentials = graph.secretStore.get() ?: return Result.retry()

        val outcome = graph.ingestClient.send(credentials, sms.sender, sms.body, sms.receivedAt)
        repo.recordAttempt(id, outcome)
        return when (outcome) {
            is SendOutcome.Retry -> Result.retry()
            SendOutcome.Accepted, is SendOutcome.Rejected -> Result.success()
        }
    }

    companion object {
        const val KEY_SMS_ID = "smsId"
    }
}
