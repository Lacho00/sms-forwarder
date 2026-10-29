package dev.smsforwarder.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.smsforwarder.SmsForwarderApp
import dev.smsforwarder.sms.ContentInboxReader
import dev.smsforwarder.sms.Sweeper

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
        val result = sweeper.sweep()
        repo.purgeOld()
        WorkScheduler.kickQueued(applicationContext, repo.queuedIds())
        graph.afterSweep(result)
        return Result.success()
    }
}
