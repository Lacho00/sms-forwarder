package dev.smsforwarder.work

import android.app.NotificationManager
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
import dev.smsforwarder.notify.Notifier
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

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

    private suspend fun insertSms(receivedAt: Long = 1_800_000_000_000L): Long {
        val record = SmsRecord("OPERADOR", body, receivedAt, null, null)
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

    @Test fun `401DoesNotNotifyPerSms`() = runBlocking {
        val first = insertSms(1_800_000_000_000L)
        val second = insertSms(1_800_000_100_000L)
        repeat(2) { server.enqueue(MockResponse.Builder().code(401).body("{\"error\":\"unauthorized\"}").build()) }
        run(first)
        run(second)
        val manager = context.getSystemService(NotificationManager::class.java)
        val shadow = shadowOf(manager)
        assertEquals(1, shadow.allNotifications.size)
        assertNotNull(shadow.getNotification(Notifier.ID_TOKEN_REJECTED))
        assertNull(shadow.getNotification(Notifier.STUCK_TAG, first.toInt()))
        assertNull(shadow.getNotification(Notifier.STUCK_TAG, second.toInt()))
    }

    @Test fun secretStoreFailureReturnsRetryAndStaysQueued() = runBlocking {
        val id = insertSms()
        graph.secretStore = object : SecretStore {
            override suspend fun get(): Credentials? = throw java.security.ProviderException("keystore")
            override suspend fun set(c: Credentials) = Unit
        }
        assertEquals(ListenableWorker.Result.retry(), run(id))
        assertEquals(SmsStatus.QUEUED, graph.smsRepository.get(id)!!.status)
    }

    @Test fun missingCredentialsReturnsRetry() = runBlocking {
        val id = insertSms()
        graph.secretStore = FakeSecretStore(null)
        assertEquals(ListenableWorker.Result.retry(), run(id))
        assertEquals(SmsStatus.QUEUED, graph.smsRepository.get(id)!!.status)
        assertEquals(0, server.requestCount)
    }
}
