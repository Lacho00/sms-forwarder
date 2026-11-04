package dev.smsforwarder.data

import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import dev.smsforwarder.SmsForwarderApp
import dev.smsforwarder.core.ConfigCodec
import dev.smsforwarder.core.ConfigParse
import dev.smsforwarder.core.DestinationPresets
import dev.smsforwarder.core.HeaderDraft
import dev.smsforwarder.core.SmsOrigin
import dev.smsforwarder.core.SmsRecord
import dev.smsforwarder.work.SendWorker
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class TransferRoundTripTest {
    private lateinit var server: MockWebServer
    private val context = RuntimeEnvironment.getApplication()
    private val graph get() = (context as SmsForwarderApp).graph

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        graph.secretStore = object : SecretStore {
            override suspend fun get(): Credentials? = null
            override suspend fun set(c: Credentials) = Unit
        }
        graph.destinationSecretStore = FakeDestinationSecretStore()
    }

    @After fun tearDown() {
        server.close()
        graph.db.close()
    }

    private suspend fun ingest(destination: Long, receivedAt: Long): Long {
        val record = SmsRecord("AAA", "TEST body", receivedAt, null, null)
        return (graph.smsRepository.ingest(record, SmsOrigin.RECEIVER, setOf(destination)) as IngestResult.Inserted).id
    }

    private suspend fun send(smsId: Long) = TestListenableWorkerBuilder<SendWorker>(context)
        .setInputData(workDataOf(SendWorker.KEY_SMS_ID to smsId)).build().doWork()

    @Test fun exportThenImportSendsTheSameRequest() = runBlocking {
        val original = graph.destinationRepository.create(
            DestinationPresets.custom("Hook").copy(
                url = "https://h.example/hook",
                headers = listOf(HeaderDraft("X-Pub", "p", false), HeaderDraft("X-Key", "k1", true)),
                body = "{\"t\":\"{body}\",\"s\":\"{sender}\"}",
            ),
        )
        // Creation only accepts https: point the stored URL at the test server afterwards.
        graph.destinationSecretStore.set(
            original,
            graph.destinationRepository.secrets(original)!!.copy(url = server.url("/hook").toString()),
        )
        server.enqueue(MockResponse.Builder().code(200).build())
        assertEquals(ListenableWorker.Result.success(), send(ingest(original, 1_800_000_000_000L)))
        val first = server.takeRequest()

        // The exported file only accepts https, so the test host is swapped for a placeholder in the file and put back
        // on the imported destination's stored URL; path, headers and body still come from the import.
        val text = graph.configTransfer.export(includeSecrets = true, appVersion = "2.0.0")
            .replace(server.url("/hook").toString(), "https://h.example/hook")
        graph.destinationRepository.delete(original)
        val file = (ConfigCodec.decode(text) as ConfigParse.Ok).file
        graph.configTransfer.apply(file)
        val imported = graph.db.destinationDao().observeLive().first().single().id
        val secrets = graph.destinationRepository.secrets(imported)!!
        assertEquals("https://h.example/hook", secrets.url)
        graph.destinationSecretStore.set(imported, secrets.copy(url = server.url("/hook").toString()))

        server.enqueue(MockResponse.Builder().code(200).build())
        assertEquals(ListenableWorker.Result.success(), send(ingest(imported, 1_800_003_600_000L)))
        val second = server.takeRequest()

        assertEquals(first.url.encodedPath, second.url.encodedPath)
        assertEquals(first.method, second.method)
        assertEquals(first.body?.utf8(), second.body?.utf8())
        for (h in listOf("X-Pub", "X-Key", "Content-Type")) {
            assertNotNull(h, first.headers[h])
            assertEquals(h, first.headers[h], second.headers[h])
        }
        assertTrue(first.body!!.utf8().contains("TEST body"))
    }

    @Test fun unconfiguredImportedPrimaryGetsQueuedKickedOnSaveCredentials() = runBlocking {
        val standard = DestinationPresets.standardJson("Main", "https://h.example/in", "T")
        val file = (ConfigCodec.decode(
            ConfigCodec.encode(
                dev.smsforwarder.core.ConfigFile(
                    listOf(dev.smsforwarder.core.ConfigDestination("d1", standard.copy(url = null, headers = emptyList()))),
                    emptyList(),
                    false,
                ),
                "2.0.0",
            ),
        ) as ConfigParse.Ok).file
        graph.configTransfer.apply(file)
        val id = graph.db.destinationDao().observeLive().first().single().id
        val smsId = ingest(id, 1_800_000_000_000L)
        val kicked = mutableListOf<Long>()
        val settings = SettingsRepository(
            context, graph.destinationRepository, graph.ruleRepository, graph.smsRepository,
            scheduler = { kicked += it },
            storeFile = java.io.File(context.cacheDir, "round-trip-settings.preferences_pb"),
        )
        settings.saveCredentials(Credentials("https://h.example/in", "NEW"))
        assertEquals(id, graph.destinationRepository.primary()!!.id)
        assertEquals("https://h.example/in", graph.destinationRepository.secrets(id)!!.url)
        assertTrue(kicked.contains(smsId))
    }
}
