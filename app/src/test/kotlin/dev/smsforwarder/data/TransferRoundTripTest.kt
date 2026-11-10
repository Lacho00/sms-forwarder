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
        // Export while the stored URL is still the original https one, so the file carries the real secrets.
        val text = graph.configTransfer.export(includeSecrets = true, appVersion = "2.0.0")
        // Creation only accepts https: point the stored URL at the test server for the first send.
        graph.destinationSecretStore.set(
            original,
            graph.destinationRepository.secrets(original)!!.copy(url = server.url("/hook").toString()),
        )
        server.enqueue(MockResponse.Builder().code(200).build())
        assertEquals(ListenableWorker.Result.success(), send(ingest(original, 1_800_000_000_000L)))
        val first = server.takeRequest()

        graph.destinationRepository.delete(original)
        val file = (ConfigCodec.decode(text) as ConfigParse.Ok).file
        graph.configTransfer.apply(file)
        val imported = graph.db.destinationDao().observeLive().first().single().id
        // Imports arrive disabled: turn it on so the worker sends.
        graph.destinationRepository.setEnabled(imported, true)
        val secrets = graph.destinationRepository.secrets(imported)!!
        // The secrets really made the round trip: the URL and the secret header value come from the file.
        assertEquals("https://h.example/hook", secrets.url)
        assertEquals("k1", secrets.headers.single { it.first == "X-Key" }.second)
        // Only now is the stored URL pointed at the test server, for the wire comparison.
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

    @Test fun importedRowsAreDisabled() = runBlocking {
        val d = DestinationPresets.standardJson("Main", "https://h.example/in", "T")
        val r = dev.smsforwarder.core.RuleDraft(
            "R", true, dev.smsforwarder.core.SenderMode.EXACT, "AAA", null, null,
            dev.smsforwarder.core.SimMatch.ANY, emptySet(),
        )
        val file = dev.smsforwarder.core.ConfigFile(
            listOf(dev.smsforwarder.core.ConfigDestination("d1", d)),
            listOf(dev.smsforwarder.core.ConfigRule(r, listOf("d1"))),
            true,
        )
        graph.configTransfer.apply(file)
        assertEquals(false, graph.db.destinationDao().observeLive().first().single().enabled)
        assertEquals(false, graph.db.ruleDao().observeAllWithDestinations().first().single().rule.enabled)
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
