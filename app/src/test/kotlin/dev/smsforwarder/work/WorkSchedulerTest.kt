package dev.smsforwarder.work

import android.content.Context
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class WorkSchedulerTest {
    @Test fun kickSkipsRunning() = assertNull(WorkScheduler.kickPolicy(listOf(WorkInfo.State.RUNNING)))

    @Test fun kickReplacesWaiting() =
        assertEquals(ExistingWorkPolicy.REPLACE, WorkScheduler.kickPolicy(listOf(WorkInfo.State.ENQUEUED)))

    @Test fun kickWithoutWork() = assertEquals(ExistingWorkPolicy.REPLACE, WorkScheduler.kickPolicy(emptyList()))

    @Test fun kickAfterFinished() =
        assertEquals(ExistingWorkPolicy.REPLACE, WorkScheduler.kickPolicy(listOf(WorkInfo.State.SUCCEEDED)))

    @Test fun kickSkipsWhenReadFailed() = assertNull(WorkScheduler.kickPolicy(null))

    // Editor requeues and resume go through kickSends: a job waiting in backoff is replaced, not kept.
    @Test fun kickSendsReplacesWaitingJob() {
        val context: Context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        val manager = WorkManager.getInstance(context)
        WorkScheduler.enqueueSend(context, 7L)
        val before = manager.getWorkInfosForUniqueWork("send-7").get().single().id
        WorkScheduler.kickSends(context, listOf(7L))
        val after = manager.getWorkInfosForUniqueWork("send-7").get().filter { it.state == WorkInfo.State.ENQUEUED }
        assertEquals(1, after.size)
        assertNotEquals(before, after.single().id)
    }
}
