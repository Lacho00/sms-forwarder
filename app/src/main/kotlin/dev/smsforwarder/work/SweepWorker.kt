package dev.smsforwarder.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.smsforwarder.SmsForwarderApp
import dev.smsforwarder.sms.ContentInboxReader
import dev.smsforwarder.sms.SweepResult
import dev.smsforwarder.sms.Sweeper
import kotlin.coroutines.cancellation.CancellationException

class SweepWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val graph = (applicationContext as SmsForwarderApp).graph
        val repo = graph.smsRepository
        val sweeper = Sweeper(
            reader = ContentInboxReader(applicationContext),
            smsRepository = repo,
            settings = graph.settings,
            clock = graph.clock,
            enqueue = { id -> WorkScheduler.enqueueSend(applicationContext, id) },
            onRecovered = graph.onRecovered,
        )
        val result = try {
            sweeper.sweep()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SweepResult(recovered = 0, inboxUnreadable = true)
        }
        // The flag goes first so a collector woken by lastSweepAt already sees the fresh value.
        graph.settings.setInboxUnreadable(result.inboxUnreadable)
        if (!result.inboxUnreadable) graph.settings.setLastSweepAt(graph.clock.now())
        repo.purgeOld()
        WorkScheduler.kickQueued(applicationContext, repo.queuedIds())
        try {
            graph.afterSweep(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Alerts are best effort: a failed health check must not fail the sweep.
        }
        return Result.success()
    }
}
