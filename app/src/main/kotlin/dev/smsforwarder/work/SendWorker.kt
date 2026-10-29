package dev.smsforwarder.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.smsforwarder.SmsForwarderApp
import dev.smsforwarder.core.SendOutcome
import dev.smsforwarder.core.SmsStatus
import kotlinx.coroutines.CancellationException

class SendWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_SMS_ID, -1L)
        if (id < 0) return Result.failure()

        val graph = (applicationContext as SmsForwarderApp).graph
        val repo = graph.smsRepository
        val sms = repo.get(id) ?: return Result.success()
        if (sms.status == SmsStatus.SENT) return Result.success()

        // Transient failures (Keystore provider, DataStore read) keep the SMS queued and back off.
        val outcome = try {
            // No credentials yet: keep the SMS queued and try again later.
            val credentials = graph.secretStore.get() ?: return Result.retry()
            graph.ingestClient.send(credentials, sms.sender, sms.body, sms.receivedAt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.retry()
        }
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
