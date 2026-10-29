package dev.smsforwarder.work

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import dev.smsforwarder.AppGraph
import dev.smsforwarder.SmsForwarderApp
import dev.smsforwarder.core.SmsOrigin
import dev.smsforwarder.core.SmsRecord
import dev.smsforwarder.core.SmsStatus
import dev.smsforwarder.data.Credentials
import dev.smsforwarder.data.IngestResult
import dev.smsforwarder.data.SecretStore
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

private class FakeSecretStore(private var value: Credentials?) : SecretStore {
    override suspend fun get(): Credentials? = value
    override suspend fun set(c: Credentials) {
        value = c
    }
}

@RunWith(RobolectricTestRunner::class)
class SendWorkerTest {
    private lateinit var server: MockWebServer
    private lateinit var context: Context
    private lateinit var graph: AppGraph

    private val body = "OPERADOR: Se ha comprado un plan de datos de 2.5GB valido por 30 dias, " +
        "se activa con el primer uso. Se desconto 360.00. Saldo actual 140.25"

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        context = RuntimeEnvironment.getApplication()
        graph = (context as SmsForwarderApp).graph
        graph.secretStore = FakeSecretStore(Credentials(server.url("/ingest").toString(), "test-token"))
    }

    @After fun tearDown() {
        server.close()
        graph.db.close()
    }

    private suspend fun insertSms(): Long {
        val record = SmsRecord("OPERADOR", body, 1_800_000_000_000L, null, null)
        return (graph.smsRepository.ingest(record, SmsOrigin.RECEIVER) as IngestResult.Inserted).id
    }

    private suspend fun run(id: Long): ListenableWorker.Result =
        TestListenableWorkerBuilder<SendWorker>(context)
            .setInputData(workDataOf(SendWorker.KEY_SMS_ID to id))
            .build()
            .doWork()

    @Test fun ioErrorReturnsRetryAndStaysQueued() = runBlocking {
        val id = insertSms()
        server.close() // connection refused
        assertEquals(ListenableWorker.Result.retry(), run(id))
        val sms = graph.smsRepository.get(id)!!
        assertEquals(SmsStatus.QUEUED, sms.status)
        assertEquals(1, sms.attempts)
    }

    @Test fun `200MarksSent`() = runBlocking {
        val id = insertSms()
        server.enqueue(MockResponse.Builder().code(200).build())
        assertEquals(ListenableWorker.Result.success(), run(id))
        assertEquals(SmsStatus.SENT, graph.smsRepository.get(id)!!.status)
    }

    @Test fun `401ReturnsSuccessAndMarksError`() = runBlocking {
        val id = insertSms()
        server.enqueue(MockResponse.Builder().code(401).body("{\"error\":\"unauthorized\"}").build())
        assertEquals(ListenableWorker.Result.success(), run(id))
        val sms = graph.smsRepository.get(id)!!
        assertEquals(SmsStatus.ERROR, sms.status)
        assertEquals(401, sms.lastHttpCode)
    }

    @Test fun missingCredentialsReturnsRetry() = runBlocking {
        val id = insertSms()
        graph.secretStore = FakeSecretStore(null)
        assertEquals(ListenableWorker.Result.retry(), run(id))
        assertEquals(SmsStatus.QUEUED, graph.smsRepository.get(id)!!.status)
        assertEquals(0, server.requestCount)
    }
}
