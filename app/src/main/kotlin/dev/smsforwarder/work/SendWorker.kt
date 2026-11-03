package dev.smsforwarder.work

import android.content.Context
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.smsforwarder.AppGraph
import dev.smsforwarder.SmsForwarderApp
import dev.smsforwarder.core.DeliveryStatus
import dev.smsforwarder.core.DestinationSpec
import dev.smsforwarder.core.SendOutcome
import dev.smsforwarder.core.SmsStatus
import dev.smsforwarder.core.SmsVars
import dev.smsforwarder.core.Templates
import dev.smsforwarder.core.isAuthRejection
import dev.smsforwarder.data.DeliveryEntity
import dev.smsforwarder.data.SmsEntity
import dev.smsforwarder.data.publicHeaderList
import dev.smsforwarder.sms.SimSlots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * One work item per SMS (unique name `send-<smsId>`, as in 1.1.x): it sends every QUEUED delivery of the
 * SMS in parallel. Each delivery records its own result, so one destination being down never blocks the
 * others, and deliveries already SENT are not repeated.
 */
class SendWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_SMS_ID, -1L)
        if (id < 0) return Result.failure()

        val graph = (applicationContext as SmsForwarderApp).graph
        // Safe mode: nothing is sent until the 1.1 settings are imported.
        if (!graph.legacyImport.run()) return Result.retry()
        val repo = graph.smsRepository
        val sms = repo.get(id) ?: return Result.success()
        if (sms.status == SmsStatus.SENT || sms.deletedAt != null) return Result.success()

        val pending = try {
            // Paused: leave the SMS untouched; resuming and every sweep queue it again.
            if (!graph.settings.isForwardingEnabled()) return Result.success()
            // The aggregate can read ERROR while another delivery is still QUEUED, so look at the deliveries.
            repo.pendingDeliveries(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.retry()
        }
        if (pending.isEmpty()) return Result.success()

        val retryNeeded = coroutineScope {
            pending.map { delivery -> async { sendOne(graph, sms, delivery) } }.awaitAll()
        }.any { it }
        return if (retryNeeded) Result.retry() else Result.success()
    }

    /** Returns true when this delivery should be tried again later. */
    private suspend fun sendOne(graph: AppGraph, sms: SmsEntity, delivery: DeliveryEntity): Boolean {
        val outcome = try {
            val destination = delivery.destinationId?.let { graph.destinationRepository.get(it) }
            // Paused or deleted destinations are skipped without an attempt.
            if (destination == null || destination.deletedAt != null || !destination.enabled) return false
            // No secrets yet (or a transient Keystore failure): keep it queued and back off.
            val secrets = graph.destinationRepository.secrets(destination.id) ?: return true
            val spec = DestinationSpec(
                method = destination.method,
                url = secrets.url,
                headers = destination.publicHeaderList() + secrets.headers,
                body = destination.bodyTemplate,
                bodyIsJson = destination.bodyIsJson,
            )
            val vars = SmsVars(sms.sender, sms.body, sms.receivedAt, SimSlots.slotOf(applicationContext, sms.subId), Build.MODEL)
            graph.deliveryClient.send(Templates.render(spec, vars), destination.successPolicy)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return true
        }
        graph.smsRepository.recordAttempt(delivery.id, outcome)
        if (outcome is SendOutcome.Rejected) notifyRejected(graph, sms.id, delivery, outcome)
        return outcome is SendOutcome.Retry
    }

    // A 401/403 means the token is wrong for every SMS to that destination, so one shared notification per
    // destination; a 400 is per SMS.
    private suspend fun notifyRejected(graph: AppGraph, smsId: Long, delivery: DeliveryEntity, outcome: SendOutcome.Rejected) {
        try {
            val repo = graph.smsRepository
            // A late result on an already-sent delivery was ignored by recordAttempt: stay silent.
            if (repo.delivery(delivery.id)?.status != DeliveryStatus.ERROR) return
            when {
                isAuthRejection(outcome.httpCode) -> {
                    val destinationId = delivery.destinationId ?: return
                    val waiting = repo.idsAuthRejected(destinationId).size
                    if (waiting > 0) graph.notifier.tokenRejected(destinationId, waiting)
                }
                outcome.httpCode == 400 -> repo.get(smsId)?.takeIf { it.status == SmsStatus.ERROR }?.let {
                    graph.notifier.rejected(it)
                    repo.markStuckNotified(smsId)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The result is already stored; a failed notification must not retry the send.
        }
    }

    companion object {
        const val KEY_SMS_ID = "smsId"
    }
}
