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
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

object WorkScheduler {

    fun enqueueSend(context: Context, smsId: Long, replace: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<SendWorker>()
            .setInputData(workDataOf(SendWorker.KEY_SMS_ID to smsId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .apply {
                if (!replace) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "send-$smsId",
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun kickQueued(context: Context, ids: List<Long>) {
        ids.forEach { enqueueSend(context, it, replace = true) }
    }

    fun schedulePeriodicSweep(context: Context) {
        val request = PeriodicWorkRequestBuilder<SweepWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "sweep-periodic",
            ExistingPeriodicWorkPolicy.KEEP,
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
