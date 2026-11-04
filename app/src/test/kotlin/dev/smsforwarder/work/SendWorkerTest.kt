package dev.smsforwarder.work

import android.app.NotificationManager
import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import dev.smsforwarder.AppGraph
import dev.smsforwarder.SmsForwarderApp
import dev.smsforwarder.core.DeliveryStatus
import dev.smsforwarder.core.HttpMethod
import dev.smsforwarder.core.IngestPayload
import dev.smsforwarder.core.SendOutcome
import dev.smsforwarder.core.SmsOrigin
import dev.smsforwarder.core.SuccessPolicy
import dev.smsforwarder.core.TemplateKind
import dev.smsforwarder.data.DestinationEntity
import dev.smsforwarder.data.DestinationSecrets
import dev.smsforwarder.data.STANDARD_JSON_BODY
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
        graph.secretStore = FakeSecretStore(null)
        graph.destinationSecretStore = dev.smsforwarder.data.FakeDestinationSecretStore()
        runBlocking {
            graph.destinationRepository.savePrimaryCredentials(Credentials(server.url("/ingest").toString(), "test-token"))
        }
    }

    @After fun tearDown() {
        server.close()
        graph.db.close()
    }

    private suspend fun insertSms(receivedAt: Long = 1_800_000_000_000L): Long {
        val record = SmsRecord("OPERADOR", body, receivedAt, null, null)
        return (graph.smsRepository.ingest(record, SmsOrigin.RECEIVER, setOf(1L)) as IngestResult.Inserted).id
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

    @Test fun importFailedSendsNothing() = runBlocking {
        val failing = dev.smsforwarder.data.FakeDestinationSecretStore().apply { failOnSet = true }
        // The 1.1.x credentials are still in the old store, so the import has a secret to copy and fails on it.
        val legacy = FakeSecretStore(Credentials(server.url("/ingest").toString(), "test-token"))
        graph.destinationSecretStore = dev.smsforwarder.data.FakeDestinationSecretStore()
        graph.legacyImport = dev.smsforwarder.data.LegacyImport(
            graph.db, legacy, failing, freshSettings(),
        )
        val id = insertSms()
        assertEquals(ListenableWorker.Result.retry(), run(id))
        assertEquals(0, server.requestCount)
        assertEquals(SmsStatus.QUEUED, graph.smsRepository.get(id)!!.status)
    }

    @Test fun deliveryQueuedDuringTheRunIsSentInTheSameRun() = runBlocking {
        val id = insertSms()
        val other = graph.db.destinationDao().insert(
            DestinationEntity(
                name = "T2", enabled = true, template = TemplateKind.STANDARD_JSON, method = HttpMethod.POST,
                bodyTemplate = null, bodyIsJson = true, publicHeaders = "[]",
                successPolicy = SuccessPolicy.ANY_2XX, createdAt = 1L,
            ),
        )
        graph.destinationSecretStore.set(other, DestinationSecrets(server.url("/second").toString(), emptyList()))
        var queuedOnce = false
        server.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse {
                if (!queuedOnce) {
                    queuedOnce = true
                    // What a resend does while this work item is RUNNING.
                    runBlocking { graph.smsRepository.resendTo(id, other) }
                }
                return MockResponse.Builder().code(200).build()
            }
        }
        assertEquals(ListenableWorker.Result.success(), run(id))
        assertEquals(2, server.requestCount)
        assertEquals(
            listOf(DeliveryStatus.SENT, DeliveryStatus.SENT),
            graph.smsRepository.deliveriesOf(id).map { it.status },
        )
        assertEquals(SmsStatus.SENT, graph.smsRepository.get(id)!!.status)
    }

    @Test fun logsOutcomePerDelivery() = runBlocking {
        val other = graph.db.destinationDao().insert(
            DestinationEntity(
                name = "T2", enabled = true, template = TemplateKind.STANDARD_JSON, method = HttpMethod.POST,
                bodyTemplate = null, bodyIsJson = true, publicHeaders = "[]",
                successPolicy = SuccessPolicy.ANY_2XX, createdAt = 1L,
            ),
        )
        graph.destinationSecretStore.set(other, DestinationSecrets(server.url("/second").toString(), emptyList()))
        val record = SmsRecord("OPERADOR", body, 1_800_000_000_000L, null, null)
        val id = (graph.smsRepository.ingest(record, SmsOrigin.RECEIVER, setOf(1L, other)) as IngestResult.Inserted).id
        server.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse =
                MockResponse.Builder().code(if (request.url.encodedPath == "/second") 403 else 200).build()
        }
        run(id)
        val sends = graph.engineLog.recent(10).filter { it.type == dev.smsforwarder.core.EngineEventType.SEND }
        assertEquals(setOf(1L to 200, other to 403), sends.map { it.destinationId to it.httpCode }.toSet())
        assertEquals(2, sends.size)
        assertEquals(setOf("accepted", "rejected"), sends.map { it.code }.toSet())
        val raw = java.io.File(context.noBackupFilesDir, "engine-events.log").readText()
        assertEquals(false, raw.contains("OPERADOR"))
        assertEquals(false, raw.contains("://"))
        assertEquals(false, raw.contains("test-token"))
    }

    @Test fun noSecretsLoggedOncePerDelivery() = runBlocking {
        val id = insertSms()
        graph.destinationSecretStore.remove(1L)
        run(id)
        run(id)
        val events = graph.engineLog.recent(10).filter { it.code == "no_secrets" }
        assertEquals(1, events.size)
        assertEquals(1L, events.single().destinationId)
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
        assertNotNull(shadow.getNotification(Notifier.TOKEN_TAG, 1))
        assertNull(shadow.getNotification(Notifier.STUCK_TAG, first.toInt()))
        assertNull(shadow.getNotification(Notifier.STUCK_TAG, second.toInt()))
    }

    @Test fun `400NotifiesEvenWhenStuckAlertsAreOff`() = runBlocking {
        graph.settings.setNotifyEnabled(dev.smsforwarder.core.NotifyKind.STUCK, false)
        val id = insertSms()
        server.enqueue(MockResponse.Builder().code(400).body("{\"error\":\"bad\"}").build())
        run(id)
        val manager = context.getSystemService(NotificationManager::class.java)
        assertNotNull(shadowOf(manager).getNotification(Notifier.STUCK_TAG, id.toInt()))
    }

    @Test fun secretStoreFailureReturnsRetryAndStaysQueued() = runBlocking {
        val id = insertSms()
        graph.destinationSecretStore = object : dev.smsforwarder.data.DestinationSecretStore {
            override suspend fun get(destinationId: Long): dev.smsforwarder.data.DestinationSecrets? =
                throw java.security.ProviderException("keystore")
            override suspend fun set(destinationId: Long, s: dev.smsforwarder.data.DestinationSecrets) = Unit
            override suspend fun remove(destinationId: Long) = Unit
        }
        assertEquals(ListenableWorker.Result.retry(), run(id))
        assertEquals(SmsStatus.QUEUED, graph.smsRepository.get(id)!!.status)
    }

    @Test fun missingCredentialsReturnsRetry() = runBlocking {
        val id = insertSms()
        graph.destinationSecretStore = dev.smsforwarder.data.FakeDestinationSecretStore()
        assertEquals(ListenableWorker.Result.retry(), run(id))
        assertEquals(SmsStatus.QUEUED, graph.smsRepository.get(id)!!.status)
        assertEquals(0, server.requestCount)
    }

    @Test fun pausedForwardingKeepsSmsQueued() = runBlocking {
        val id = insertSms()
        graph.settings.setForwardingEnabled(false)
        assertEquals(ListenableWorker.Result.success(), run(id))
        val sms = graph.smsRepository.get(id)!!
        assertEquals(SmsStatus.QUEUED, sms.status)
        assertEquals(0, sms.attempts)
        assertEquals(0, server.requestCount)
    }


    private lateinit var second: MockWebServer

    /** Adds a standard-JSON destination pointing at [target]; returns its id. */
    private suspend fun addDestination(target: MockWebServer, enabled: Boolean = true, name: String = "Second"): Long {
        val id = graph.db.destinationDao().insert(
            DestinationEntity(
                name = name, enabled = enabled, template = TemplateKind.STANDARD_JSON, method = HttpMethod.POST,
                bodyTemplate = STANDARD_JSON_BODY, bodyIsJson = true, publicHeaders = "[]",
                successPolicy = SuccessPolicy.ANY_2XX, createdAt = 1L,
            ),
        )
        graph.destinationSecretStore.set(
            id, DestinationSecrets(target.url("/ingest").toString(), listOf("Authorization" to "Bearer b-token")),
        )
        return id
    }

    private suspend fun insertSmsTo(vararg destinations: Long): Long {
        val record = SmsRecord("OPERADOR", body, 1_800_000_000_000L, null, null)
        return (graph.smsRepository.ingest(record, SmsOrigin.RECEIVER, destinations.toSet()) as IngestResult.Inserted).id
    }

    @Test fun primarySendsV1Payload() = runBlocking {
        val id = insertSms()
        server.enqueue(MockResponse.Builder().code(200).build())
        assertEquals(ListenableWorker.Result.success(), run(id))
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("Bearer test-token", request.headers["Authorization"])
        assertEquals("application/json; charset=utf-8", request.headers["Content-Type"])
        assertEquals(IngestPayload.encode("OPERADOR", body, 1_800_000_000_000L), request.body!!.utf8())
    }

    @Test fun downDestinationDoesNotBlockOther() = runBlocking {
        second = MockWebServer().also { it.start() }
        try {
            val b = addDestination(second)
            val id = insertSmsTo(1L, b)
            server.enqueue(MockResponse.Builder().code(500).build())
            second.enqueue(MockResponse.Builder().code(200).build())

            assertEquals(ListenableWorker.Result.retry(), run(id))
            val byDestination = graph.smsRepository.deliveriesOf(id).associateBy { it.destinationId }
            assertEquals(DeliveryStatus.QUEUED, byDestination[1L]!!.status)
            assertEquals(1, byDestination[1L]!!.attempts)
            assertEquals(DeliveryStatus.SENT, byDestination[b]!!.status)
            assertEquals(SmsStatus.QUEUED, graph.smsRepository.get(id)!!.status)

            server.enqueue(MockResponse.Builder().code(200).build())
            assertEquals(ListenableWorker.Result.success(), run(id))
            assertEquals(SmsStatus.SENT, graph.smsRepository.get(id)!!.status)
            assertEquals(1, second.requestCount)
            assertEquals(2, server.requestCount)
        } finally {
            second.close()
        }
    }

    @Test fun pausedDestinationSkipped() = runBlocking {
        second = MockWebServer().also { it.start() }
        try {
            val b = addDestination(second)
            val id = insertSmsTo(1L, b)
            val a = graph.db.destinationDao().get(1L)!!
            graph.db.destinationDao().update(a.copy(enabled = false))
            second.enqueue(MockResponse.Builder().code(200).build())

            assertEquals(ListenableWorker.Result.success(), run(id))
            val byDestination = graph.smsRepository.deliveriesOf(id).associateBy { it.destinationId }
            assertEquals(DeliveryStatus.QUEUED, byDestination[1L]!!.status)
            assertEquals(0, byDestination[1L]!!.attempts)
            assertEquals(DeliveryStatus.SENT, byDestination[b]!!.status)
            assertEquals(0, server.requestCount)
        } finally {
            second.close()
        }
    }

    @Test fun deletedDestinationDoesNotKeepSmsFromSent() = runBlocking {
        second = MockWebServer().also { it.start() }
        try {
            val b = addDestination(second)
            val id = insertSmsTo(1L, b)
            val a = graph.db.destinationDao().get(1L)!!
            graph.db.destinationDao().update(a.copy(deletedAt = 5L))
            second.enqueue(MockResponse.Builder().code(200).build())

            assertEquals(ListenableWorker.Result.success(), run(id))
            assertEquals(0, server.requestCount)
            assertEquals(SmsStatus.SENT, graph.smsRepository.get(id)!!.status)
        } finally {
            second.close()
        }
    }

    @Test fun errorAggregateStillSendsQueuedDelivery() = runBlocking {
        second = MockWebServer().also { it.start() }
        try {
            val b = addDestination(second)
            val id = insertSmsTo(1L, b)
            val (da, _) = graph.smsRepository.deliveriesOf(id)
            graph.smsRepository.recordAttempt(da.id, SendOutcome.Rejected(401, null))
            assertEquals(SmsStatus.ERROR, graph.smsRepository.get(id)!!.status)
            second.enqueue(MockResponse.Builder().code(200).build())

            assertEquals(ListenableWorker.Result.success(), run(id))
            assertEquals(1, second.requestCount)
            assertEquals(0, server.requestCount)
        } finally {
            second.close()
        }
    }

    @Test fun legacyQueuedWorkStillSends() = runBlocking {
        val id = insertSms()
        server.enqueue(MockResponse.Builder().code(200).build())
        val result = TestListenableWorkerBuilder<SendWorker>(context)
            .setInputData(workDataOf("smsId" to id))
            .build()
            .doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, server.requestCount)
    }

    @Test fun deletedQueuedSmsReturnsSuccessWithoutRequest() = runBlocking {
        val id = insertSms()
        graph.smsRepository.softDelete(id)
        assertEquals(ListenableWorker.Result.success(), run(id))
        assertEquals(0, server.requestCount)
        val sms = graph.smsRepository.get(id)!!
        assertEquals(SmsStatus.QUEUED, sms.status)
        assertEquals(0, sms.attempts)
    }

    // The app's own import may already have marked the shared settings done; use an unmarked store.
    private fun freshSettings() = dev.smsforwarder.data.SettingsRepository(
        context, graph.destinationRepository, graph.ruleRepository, graph.smsRepository, { },
        java.io.File(context.cacheDir, "settings-${java.util.UUID.randomUUID()}.preferences_pb"),
    )

    @Test fun renderFailureRecordedOnce() = runBlocking {
        val id = insertSms()
        val destination = graph.destinationRepository.get(1L)!!
        graph.db.destinationDao().update(destination.copy(publicHeaders = "[[\"X-Bad\",\"caf\u00e9\"]]"))
        assertEquals(ListenableWorker.Result.success(), run(id))
        val delivery = graph.smsRepository.deliveriesOf(id).single()
        assertEquals(DeliveryStatus.ERROR, delivery.status)
        assertEquals("config_request", delivery.lastError)
        assertEquals(1, delivery.attempts)
        assertEquals(0, server.requestCount)
        val manager = context.getSystemService(NotificationManager::class.java)
        assertNotNull(shadowOf(manager).getNotification(Notifier.STUCK_TAG, id.toInt()))
    }

    @Test fun tokenEditedWhileSendingRetriesInsteadOfError() = runBlocking {
        val id = insertSms()
        server.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse {
                // The user fixes the token while the request is in flight.
                runBlocking {
                    graph.destinationSecretStore.set(
                        1L, DestinationSecrets(server.url("/ingest").toString(), listOf("Authorization" to "Bearer new-token")),
                    )
                }
                return MockResponse.Builder().code(403).build()
            }
        }
        assertEquals(ListenableWorker.Result.retry(), run(id))
        val delivery = graph.smsRepository.deliveriesOf(id).single()
        assertEquals(DeliveryStatus.QUEUED, delivery.status)
        assertEquals(0, delivery.attempts)
        val manager = context.getSystemService(NotificationManager::class.java)
        assertEquals(0, shadowOf(manager).allNotifications.size)
    }

    @Test fun destinationResumedDuringRunIsSentInTheSameRun() = runBlocking {
        second = MockWebServer().also { it.start() }
        try {
            val b = addDestination(second, enabled = false)
            val id = insertSmsTo(1L, b)
            second.enqueue(MockResponse.Builder().code(200).build())
            server.dispatcher = object : mockwebserver3.Dispatcher() {
                override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse {
                    runBlocking { graph.destinationRepository.setEnabled(b, true) }
                    return MockResponse.Builder().code(200).build()
                }
            }
            assertEquals(ListenableWorker.Result.success(), run(id))
            assertEquals(1, second.requestCount)
            assertEquals(SmsStatus.SENT, graph.smsRepository.get(id)!!.status)
        } finally {
            second.close()
        }
    }

    @Test fun forwardingPausedDuringRunStopsNextPass() = runBlocking {
        val id = insertSms()
        val other = graph.db.destinationDao().insert(
            DestinationEntity(
                name = "T2", enabled = true, template = TemplateKind.STANDARD_JSON, method = HttpMethod.POST,
                bodyTemplate = null, bodyIsJson = true, publicHeaders = "[]",
                successPolicy = SuccessPolicy.ANY_2XX, createdAt = 1L,
            ),
        )
        graph.destinationSecretStore.set(other, DestinationSecrets(server.url("/second").toString(), emptyList()))
        server.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse {
                runBlocking {
                    graph.smsRepository.resendTo(id, other)
                    graph.settings.setForwardingEnabled(false)
                }
                return MockResponse.Builder().code(200).build()
            }
        }
        assertEquals(ListenableWorker.Result.success(), run(id))
        assertEquals(1, server.requestCount)
        assertEquals(DeliveryStatus.QUEUED, graph.smsRepository.deliveriesOf(id).first { it.destinationId == other }.status)
    }

    @Test fun forwardingReadFailureBetweenPassesRetries() = runBlocking {
        val id = insertSms()
        val other = graph.db.destinationDao().insert(
            DestinationEntity(
                name = "T2", enabled = true, template = TemplateKind.STANDARD_JSON, method = HttpMethod.POST,
                bodyTemplate = null, bodyIsJson = true, publicHeaders = "[]",
                successPolicy = SuccessPolicy.ANY_2XX, createdAt = 1L,
            ),
        )
        graph.destinationSecretStore.set(other, DestinationSecrets(server.url("/second").toString(), emptyList()))
        var reads = 0
        graph.forwardingEnabled = {
            if (++reads > 1) throw java.io.IOException("datastore") else true
        }
        try {
            server.dispatcher = object : mockwebserver3.Dispatcher() {
                override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse {
                    runBlocking { graph.smsRepository.resendTo(id, other) }
                    return MockResponse.Builder().code(200).build()
                }
            }
            assertEquals(ListenableWorker.Result.retry(), run(id))
            assertEquals(1, server.requestCount)
        } finally {
            graph.forwardingEnabled = { graph.settings.isForwardingEnabled() }
        }
    }
}
