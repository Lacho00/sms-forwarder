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
import dev.smsforwarder.data.DestinationEntity
import dev.smsforwarder.data.DestinationSecrets
import dev.smsforwarder.data.SmsEntity
import dev.smsforwarder.data.publicHeaderList
import dev.smsforwarder.sms.SimSlots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope

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
            if (!graph.forwardingEnabled()) return Result.success()
            // The aggregate can read ERROR while another delivery is still QUEUED, so look at the deliveries.
            repo.pendingDeliveries(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.retry()
        }
        if (pending.isEmpty()) return Result.success()

        var retryNeeded = false
        // Deliveries queued while this run was sending (a resend or retry finds the work RUNNING and cannot
        // replace it) are picked up here instead of waiting for the next sweep.
        var batch = pending
        val retried = mutableSetOf<Long>()
        for (pass in 0 until MAX_PASSES) {
            if (batch.isEmpty()) break
            // Forwarding paused while this run was sending: stop, resuming and every sweep queue it again.
            if (pass > 0) {
                val enabled = try {
                    graph.forwardingEnabled()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Cannot tell: try again later instead of stopping quietly with deliveries still queued.
                    retryNeeded = true
                    break
                }
                if (!enabled) break
            }
            val results = supervisorScope {
                batch.map { delivery -> async { sendOne(graph, sms, delivery) } }.awaitAll()
            }
            batch.forEachIndexed { i, delivery -> if (results[i]) retried += delivery.id }
            if (results.any { it }) retryNeeded = true
            batch = try {
                // Skip what is waiting for a retry and what belongs to a destination that is still paused or gone;
                // a destination resumed during the run is picked up here.
                val active = graph.destinationRepository.activeIds()
                repo.pendingDeliveries(id).filter { it.id !in retried && it.destinationId in active }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
        }
        return if (retryNeeded) Result.retry() else Result.success()
    }

    /** Returns true when this delivery should be tried again later. */
    private suspend fun sendOne(graph: AppGraph, sms: SmsEntity, delivery: DeliveryEntity): Boolean {
        var used: Pair<DestinationEntity, DestinationSecrets>? = null
        val outcome = try {
            val destination = delivery.destinationId?.let { graph.destinationRepository.get(it) }
            // Paused or deleted destinations are skipped without an attempt.
            if (destination == null || destination.deletedAt != null || !destination.enabled) return false
            // No secrets yet (or a transient Keystore failure): keep it queued and back off.
            val secrets = graph.destinationRepository.secrets(destination.id) ?: return true
            used = destination to secrets
            // A destination that cannot be rendered never will until it is edited: record it, do not retry.
            val rendered = try {
                val spec = DestinationSpec(
                    method = destination.method,
                    url = secrets.url,
                    headers = destination.publicHeaderList() + secrets.headers,
                    body = destination.bodyTemplate,
                    bodyIsJson = destination.bodyIsJson,
                )
                val vars = SmsVars(sms.sender, sms.body, sms.receivedAt, SimSlots.slotOf(applicationContext, sms.subId), Build.MODEL)
                Templates.render(spec, vars)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (rendered == null) SendOutcome.Invalid("template")
            else graph.deliveryClient.send(rendered, destination.successPolicy)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return true
        }
        // The destination or its token was edited while this request was in flight: a 401/403 or a config error
        // refers to the old values, so try again with the new ones instead of recording an error.
        if ((outcome is SendOutcome.Rejected && isAuthRejection(outcome.httpCode)) || outcome is SendOutcome.Invalid) {
            if (changedSince(graph, used)) return true
        }
        // A failing write must not cancel the sibling sends: count it as "retry" for this delivery only.
        try {
            graph.smsRepository.recordAttempt(delivery.id, outcome)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return true
        }
        if (outcome is SendOutcome.Rejected) notifyRejected(graph, sms.id, delivery, outcome)
        if (outcome is SendOutcome.Invalid) notifyConfigError(graph, sms.id, delivery)
        return outcome is SendOutcome.Retry
    }

    private suspend fun changedSince(graph: AppGraph, used: Pair<DestinationEntity, DestinationSecrets>?): Boolean {
        if (used == null) return false
        return try {
            val now = graph.destinationRepository.get(used.first.id)
            now != used.first || graph.destinationRepository.secrets(used.first.id) != used.second
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
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
                    if (waiting > 0) {
                        val name = graph.destinationRepository.get(destinationId)?.name ?: delivery.destinationName
                        graph.notifier.tokenRejected(destinationId, name, waiting)
                    }
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

    private suspend fun notifyConfigError(graph: AppGraph, smsId: Long, delivery: DeliveryEntity) {
        try {
            val repo = graph.smsRepository
            if (repo.delivery(delivery.id)?.status != DeliveryStatus.ERROR) return
            repo.get(smsId)?.takeIf { it.status == SmsStatus.ERROR }?.let {
                graph.notifier.configError(it, delivery.destinationName)
                repo.markStuckNotified(smsId)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The result is already stored; a failed notification must not retry the send.
        }
    }

    companion object {
        const val KEY_SMS_ID = "smsId"
        private const val MAX_PASSES = 3
    }
}
