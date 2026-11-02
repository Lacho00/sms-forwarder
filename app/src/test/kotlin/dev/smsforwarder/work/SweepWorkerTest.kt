package dev.smsforwarder.work

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import dev.smsforwarder.AppGraph
import dev.smsforwarder.SmsForwarderApp
import dev.smsforwarder.core.SmsOrigin
import dev.smsforwarder.core.SmsRecord
import dev.smsforwarder.data.Credentials
import dev.smsforwarder.data.IngestResult
import dev.smsforwarder.data.SecretStore
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

private class StaticSecretStore(private var value: Credentials?) : SecretStore {
    override suspend fun get(): Credentials? = value
    override suspend fun set(c: Credentials) {
        value = c
    }
}

@RunWith(RobolectricTestRunner::class)
class SweepWorkerTest {
    private lateinit var server: MockWebServer
    private lateinit var context: Context
    private lateinit var graph: AppGraph

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        graph = (context as SmsForwarderApp).graph
        graph.secretStore = StaticSecretStore(Credentials(server.url("/ingest").toString(), "test-token"))
        graph.onRecovered = {}
        graph.afterSweep = {}
    }

    @After fun tearDown() {
        server.close()
        graph.db.close()
    }

    // Review Focus 1: after a sweep, a deleted queued SMS has no send job and nothing reaches the server.
    @Test fun sweepAfterDeletingQueuedSmsMakesNoRequestAndSchedulesNoSend() = runBlocking {
        val record = SmsRecord("OPERADOR", "Code 1234", 1_800_000_000_000L, null, null)
        val id = (graph.smsRepository.ingest(record, SmsOrigin.RECEIVER) as IngestResult.Inserted).id
        graph.smsRepository.softDelete(id)

        val result = TestListenableWorkerBuilder<SweepWorker>(context).build().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(0, server.requestCount)
        assertTrue(WorkManager.getInstance(context).getWorkInfosForUniqueWork("send-$id").get().isEmpty())
    }
}
