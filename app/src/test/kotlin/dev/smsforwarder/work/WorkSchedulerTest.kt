package dev.smsforwarder.work

import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkSchedulerTest {
    @Test fun kickSkipsRunning() = assertNull(WorkScheduler.kickPolicy(listOf(WorkInfo.State.RUNNING)))

    @Test fun kickReplacesWaiting() =
        assertEquals(ExistingWorkPolicy.REPLACE, WorkScheduler.kickPolicy(listOf(WorkInfo.State.ENQUEUED)))

    @Test fun kickWithoutWork() = assertEquals(ExistingWorkPolicy.REPLACE, WorkScheduler.kickPolicy(emptyList()))

    @Test fun kickAfterFinished() =
        assertEquals(ExistingWorkPolicy.REPLACE, WorkScheduler.kickPolicy(listOf(WorkInfo.State.SUCCEEDED)))
}
