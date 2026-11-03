package dev.smsforwarder.ui

import androidx.room.Room
import dev.smsforwarder.R
import dev.smsforwarder.core.Clock
import dev.smsforwarder.core.DeliveryStatus
import dev.smsforwarder.core.DestinationError
import dev.smsforwarder.core.DestinationPresets
import dev.smsforwarder.core.HeaderDraft
import dev.smsforwarder.core.SendOutcome
import dev.smsforwarder.core.SmsOrigin
import dev.smsforwarder.core.SmsRecord
import dev.smsforwarder.core.TemplateKind
import dev.smsforwarder.data.AppDatabase
import dev.smsforwarder.data.IngestResult
import dev.smsforwarder.data.TestRepos
import dev.smsforwarder.net.DeliveryClient
import dev.smsforwarder.net.DestinationTester
import dev.smsforwarder.net.defaultHttpClient
import dev.smsforwarder.ui.destinations.DestinationEditorViewModel
import dev.smsforwarder.ui.destinations.HeaderRowUi
import dev.smsforwarder.ui.destinations.PresetFields
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DestinationEditorViewModelTest {
    private val app = RuntimeEnvironment.getApplication()
    private val clock = Clock { 1_800_000_000_000L }
    private lateinit var db: AppDatabase
    private lateinit var repos: TestRepos
    private lateinit var server: MockWebServer
    private val enqueued = mutableListOf<List<Long>>()

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
        repos = TestRepos(db, clock)
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.close()
        db.close()
    }

    private fun defaultName(k: TemplateKind) = "Name-$k"

    private fun vm(id: Long? = null) = DestinationEditorViewModel(
        id, repos.destinations,
        DestinationTester(
            DeliveryClient(defaultHttpClient()), repos.secrets, app.getString(R.string.destination_test_body),
            "device-x", clock,
        ),
        { enqueued += it }, ::defaultName, CoroutineScope(Dispatchers.Unconfined),
    )

    private fun draft(name: String, vararg headers: HeaderDraft) =
        DestinationPresets.custom(name).copy(url = "https://h.example/$name", headers = headers.toList(), body = "{}")

    private var next = 0
    private suspend fun ingest(vararg destinations: Long): Long {
        next++
        val record = SmsRecord("AAA", "text $next", clock.now() - next * 10_000L, null, null)
        return (repos.sms.ingest(record, SmsOrigin.RECEIVER, destinations.toSet()) as IngestResult.Inserted).id
    }

    private fun liveCount() = runBlocking { repos.destinations.observeLive().first().size }

    @Test fun telegramPresetFillsForm() {
        val vm = vm()
        vm.chooseTemplate(TemplateKind.TELEGRAM)
        vm.setPreset(PresetFields(token = "123:ABC", chatId = "42"))
        vm.applyPreset()
        val expected = DestinationPresets.telegram(defaultName(TemplateKind.TELEGRAM), "123:ABC", "42")
        val s = vm.state.value
        assertEquals(expected.url, s.url)
        assertEquals(expected.body, s.body)
        assertEquals(expected.bodyIsJson, s.bodyIsJson)
        assertEquals(defaultName(TemplateKind.TELEGRAM), s.name)
    }

    @Test fun saveRejectsUnquotedSim() = runBlocking {
        val vm = vm()
        vm.chooseTemplate(TemplateKind.CUSTOM)
        vm.setUrl("https://h.example/x")
        vm.setBody("{\"s\":{sim}}")
        vm.save()
        assertTrue(DestinationError.BODY_NOT_JSON in vm.state.value.errors)
        assertFalse(vm.state.value.done)
        assertEquals(0, liveCount())
    }

    @Test fun saveRejectsHttpAndUnknownVariable() = runBlocking {
        val vm = vm()
        vm.chooseTemplate(TemplateKind.CUSTOM)
        vm.setUrl("http://h.example/x")
        vm.setBody("{\"s\":\"{nope}\"}")
        vm.save()
        val errors = vm.state.value.errors
        assertTrue(DestinationError.URL_NOT_HTTPS in errors)
        assertTrue(DestinationError.UNKNOWN_VARIABLE in errors)
        assertFalse(vm.state.value.done)
        assertEquals(0, liveCount())
    }

    @Test fun savesValidNewDestination() = runBlocking {
        val vm = vm()
        vm.chooseTemplate(TemplateKind.CUSTOM)
        vm.setUrl("https://h.example/x")
        vm.setBody("{\"s\":\"{sim}\"}")
        vm.save()
        assertTrue(vm.state.value.done)
        assertEquals(1, liveCount())
    }

    @Test fun testBlockedWhenInvalid() {
        val vm = vm()
        vm.chooseTemplate(TemplateKind.CUSTOM)
        vm.setUrl("http://h.example/x")
        vm.test()
        assertTrue(DestinationError.URL_NOT_HTTPS in vm.state.value.errors)
        assertNull(vm.state.value.test)
        assertEquals(0, server.requestCount)
    }

    @Test fun editLoadsWithoutSecrets() = runBlocking {
        val id = repos.destinations.create(
            draft("One", HeaderDraft("X-A", "pub", false), HeaderDraft("X-Key", "tok-secret-1", true))
                .copy(url = "https://h.example/secret-path"),
        )
        val vm = vm(id)
        val s = vm.state.value
        assertTrue(s.loaded)
        assertFalse(s.isNew)
        assertEquals("", s.url)
        assertTrue(s.urlStored)
        val secret = s.headers.first { it.secret }
        assertEquals("", secret.value)
        assertTrue(secret.stored)
        assertEquals("pub", s.headers.first { !it.secret }.value)
        val text = s.toString() + s.headers.toString()
        assertFalse(text.contains("secret-path"))
        assertFalse(text.contains("tok-secret-1"))
    }

    @Test fun stateToStringHidesPresetAndFormSecrets() {
        val vm = vm()
        vm.chooseTemplate(TemplateKind.TELEGRAM)
        vm.setPreset(PresetFields(token = "123:ABC", chatId = "4242"))
        vm.setUrl("https://h.example/hidden-url")
        vm.setBody("hidden-body")
        vm.setHeaders(listOf(HeaderRowUi("X-Key", "hidden-header", secret = true, stored = false)))
        val text = vm.state.value.toString()
        listOf("123:ABC", "4242", "hidden-url", "hidden-body", "hidden-header").forEach { assertFalse(text.contains(it)) }
        assertFalse(vm.state.value.preset.toString().contains("123:ABC"))
    }

    @Test fun editKeepsStoredSecretsWhenUntouched() = runBlocking {
        val id = repos.destinations.create(
            draft("One", HeaderDraft("X-Key", "tok-secret-1", true)).copy(url = "https://h.example/secret-path"),
        )
        val vm = vm(id)
        vm.setName("Renamed")
        vm.save()
        assertTrue(vm.state.value.done)
        val stored = repos.secrets.map.getValue(id)
        assertEquals("https://h.example/secret-path", stored.url)
        assertEquals(listOf("X-Key" to "tok-secret-1"), stored.headers)
        assertEquals("Renamed", repos.destinations.get(id)!!.name)
    }

    @Test fun saveEnqueuesRequeued() = runBlocking {
        val id = repos.destinations.create(draft("One"))
        val smsId = ingest(id)
        repos.sms.recordAttempt(repos.sms.deliveriesOf(smsId).first().id, SendOutcome.Rejected(401, null))
        assertEquals(DeliveryStatus.ERROR, repos.sms.deliveriesOf(smsId).first().status)
        val vm = vm(id)
        vm.setName("Again")
        vm.save()
        assertEquals(listOf(listOf(smsId)), enqueued)
        assertTrue(vm.state.value.done)
    }

    @Test fun deleteAsksWithPendingCount() = runBlocking {
        val id = repos.destinations.create(draft("One"))
        repeat(3) { ingest(id) }
        val vm = vm(id)
        vm.requestDelete()
        assertEquals(3, vm.state.value.deletePending)
        vm.dismissDelete()
        assertNull(vm.state.value.deletePending)
        assertEquals(1, liveCount())
        assertFalse(vm.state.value.done)
        vm.requestDelete()
        vm.confirmDelete()
        assertTrue(vm.state.value.done)
        assertEquals(0, liveCount())
    }
}
