package dev.smsforwarder.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.smsforwarder.core.SweepInterval
import java.util.concurrent.TimeUnit

object WorkScheduler {

    fun enqueueSend(context: Context, smsId: Long, replace: Boolean = false) {
        enqueueSend(context, smsId, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP)
    }

    private fun enqueueSend(context: Context, smsId: Long, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<SendWorker>()
            .setInputData(workDataOf(SendWorker.KEY_SMS_ID to smsId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .apply {
                if (policy == ExistingWorkPolicy.KEEP) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "send-$smsId",
            policy,
            request,
        )
    }

    /** Cancels the unique send job of a deleted SMS; SendWorker also ignores deleted rows if it still runs. */
    fun cancelSend(context: Context, smsId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork("send-$smsId")
    }

    /**
     * `null` = leave the job alone. A RUNNING send is never replaced: REPLACE would cancel it half way and could
     * repeat an SMS to a destination that already answered. A waiting (backoff) or finished job is replaced
     * so the SMS goes out now. [states] `null` = the read failed: do not touch, the next sweep retries.
     */
    internal fun kickPolicy(states: List<WorkInfo.State>?): ExistingWorkPolicy? = when {
        states == null -> null
        WorkInfo.State.RUNNING in states -> null
        else -> ExistingWorkPolicy.REPLACE
    }

    fun kickSend(context: Context, smsId: Long) = kickSends(context, listOf(smsId))

    /** Sends now without ever cancelling a send that is running (see [kickPolicy]). */
    fun kickSends(context: Context, ids: List<Long>) {
        val manager = WorkManager.getInstance(context)
        ids.forEach { id ->
            val infos = manager.getWorkInfosForUniqueWork("send-$id")
            infos.addListener({
                val states = try {
                    infos.get().map { it.state }
                } catch (e: Exception) {
                    null
                }
                kickPolicy(states)?.let { enqueueSend(context, id, it) }
            }, Runnable::run)
        }
    }

    fun kickQueued(context: Context, ids: List<Long>) = kickSends(context, ids)

    /** [update] swaps the period of the existing job (a live change); otherwise an existing job is kept. */
    fun schedulePeriodicSweep(context: Context, interval: SweepInterval = SweepInterval.M15, update: Boolean = false) {
        val request = PeriodicWorkRequestBuilder<SweepWorker>(interval.minutes, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "sweep-periodic",
            if (update) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun sweepNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "sweep-now",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SweepWorker>().build(),
        )
    }
}
