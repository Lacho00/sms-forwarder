package dev.smsforwarder.ui

import androidx.room.Room
import dev.smsforwarder.R
import dev.smsforwarder.core.Clock
import dev.smsforwarder.core.DeliveryStatus
import dev.smsforwarder.core.DestinationError
import dev.smsforwarder.core.DestinationPresets
import dev.smsforwarder.core.HeaderDraft
import dev.smsforwarder.core.SendOutcome
import dev.smsforwarder.core.TestVerdict
import dev.smsforwarder.core.SmsOrigin
import dev.smsforwarder.core.SmsRecord
import dev.smsforwarder.core.TemplateKind
import dev.smsforwarder.data.AppDatabase
import dev.smsforwarder.data.IngestResult
import dev.smsforwarder.data.TestRepos
import dev.smsforwarder.net.DeliveryClient
import dev.smsforwarder.net.DestinationTester
import okhttp3.ResponseBody.Companion.toResponseBody
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
    private val enqueued = java.util.concurrent.CopyOnWriteArrayList<List<Long>>()

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

    private var gate: java.util.concurrent.CountDownLatch? = null
    private val canned = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
        gate?.await(5, java.util.concurrent.TimeUnit.SECONDS)
        okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
            .code(200).message("OK").body("".toResponseBody(null)).build()
    }.build()

    private fun vm(id: Long? = null) = DestinationEditorViewModel(
        id, repos.destinations,
        DestinationTester(
            DeliveryClient(canned), repos.secrets, app.getString(R.string.destination_test_body),
            "device-x", clock,
        ),
        { enqueued += it }, ::defaultName, CoroutineScope(Dispatchers.Unconfined),
    )

    // Room runs its queries on its own executor, so the ViewModel finishes on another thread.
    private fun DestinationEditorViewModel.await(cond: (dev.smsforwarder.ui.destinations.DestinationEditorState) -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!cond(state.value) && System.currentTimeMillis() < end) Thread.sleep(10)
        assertTrue("timed out waiting for the state", cond(state.value))
    }

    private fun loaded(id: Long) = vm(id).also { it.await { s -> s.loaded } }

    private fun draft(name: String, vararg headers: HeaderDraft) =
        DestinationPresets.custom(name).copy(url = "https://h.example/$name", headers = headers.toList(), body = "{}")

    private var next = 0
    private suspend fun ingest(vararg destinations: Long): Long {
        next++
        val record = SmsRecord("AAA", "text $next", clock.now() - next * 10_000L, null, null)
        return (repos.sms.ingest(record, SmsOrigin.RECEIVER, destinations.toSet()) as IngestResult.Inserted).id
    }

    private fun liveCount() = runBlocking { repos.destinations.observeLive().first().size }

    @Test fun resetAfterDoneMakesTheEditorUsableAgain() = runBlocking {
        val id = repos.destinations.create(draft("One", HeaderDraft("X-Key", "hush", secret = true)))
        val edit = loaded(id)
        edit.setName("Renamed")
        edit.save()
        edit.await { it.done }
        edit.reset()
        edit.await { it.loaded }
        assertFalse(edit.state.value.done)
        assertEquals("Renamed", edit.state.value.name)
        assertEquals("", edit.state.value.url)

        val create = vm()
        create.chooseTemplate(TemplateKind.CUSTOM)
        create.setUrl("https://h.example/typed-secret")
        create.setName("Two")
        create.save()
        create.await { it.done }
        create.reset()
        create.await { it.loaded }
        assertFalse(create.state.value.done)
        assertEquals("", create.state.value.url)
        assertEquals(defaultName(TemplateKind.STANDARD_JSON), create.state.value.name)
        assertEquals(PresetFields(), create.state.value.preset)
    }

    @Test fun newDestinationDefaultsToStandardJson() {
        val vm = vm()
        vm.reset()
        vm.await { it.loaded }
        assertEquals(TemplateKind.STANDARD_JSON, vm.state.value.template)
        assertEquals(defaultName(TemplateKind.STANDARD_JSON), vm.state.value.name)
    }

    @Test fun editingKeepsSavedTemplate() = runBlocking {
        val id = repos.destinations.create(draft("One"))
        val vm = loaded(id)
        assertEquals(TemplateKind.CUSTOM, vm.state.value.template)
        vm.reset()
        vm.await { it.loaded }
        assertEquals(TemplateKind.CUSTOM, vm.state.value.template)
    }

    @Test fun savingWithAnEmptyNameReportsNameEmpty() = runBlocking {
        val vm = vm()
        vm.chooseTemplate(TemplateKind.CUSTOM)
        vm.setName("  ")
        vm.setUrl("https://h.example/x")
        vm.setBody("{}")
        vm.save()
        assertTrue(DestinationError.NAME_EMPTY in vm.state.value.errors)
        assertFalse(vm.state.value.done)
        assertEquals(0, liveCount())
    }

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

    @Test fun staleSaveAfterResetDoesNotCloseTheFreshEditor() {
        val held = java.util.concurrent.CountDownLatch(1)
        val started = java.util.concurrent.CountDownLatch(1)
        val t = Thread { db.runInTransaction { started.countDown(); held.await(5, java.util.concurrent.TimeUnit.SECONDS) } }
        t.start()
        started.await()
        val vm = vm()
        vm.chooseTemplate(TemplateKind.CUSTOM)
        vm.setUrl("https://h.example/x")
        vm.setBody("{\"s\":\"{sim}\"}")
        vm.save()
        assertTrue(vm.state.value.busy)
        vm.reset()
        held.countDown()
        t.join()
        Thread.sleep(300)
        assertFalse(vm.state.value.done)
        assertFalse(vm.state.value.busy)
    }

    @Test fun savesValidNewDestination() = runBlocking {
        val vm = vm()
        vm.chooseTemplate(TemplateKind.CUSTOM)
        vm.setUrl("https://h.example/x")
        vm.setBody("{\"s\":\"{sim}\"}")
        vm.save()
        vm.await { it.done }
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
        val vm = loaded(id)
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
        val vm = loaded(id)
        vm.setName("Renamed")
        vm.save()
        vm.await { it.done }
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
        val vm = loaded(id)
        vm.setName("Again")
        vm.save()
        vm.await { it.done }
        assertEquals(listOf(listOf(smsId)), enqueued)
    }

    @Test fun deleteAsksWithPendingCount() = runBlocking {
        val id = repos.destinations.create(draft("One"))
        val smsIds = List(3) { ingest(id) }
        val vm = loaded(id)
        vm.requestDelete()
        vm.await { it.deletePending != null }
        assertEquals(3, vm.state.value.deletePending)
        vm.dismissDelete()
        assertNull(vm.state.value.deletePending)
        assertEquals(1, liveCount())
        assertFalse(vm.state.value.done)
        vm.requestDelete()
        vm.await { it.deletePending != null }
        vm.confirmDelete()
        vm.confirmDelete()
        vm.await { it.done }
        assertTrue(repos.sms.deliveriesOf(smsIds[0]).all { it.status == DeliveryStatus.CANCELED })
        assertEquals(0, liveCount())
    }

    @Test fun validFormTestIsOk() {
        val vm = vm()
        vm.chooseTemplate(TemplateKind.CUSTOM)
        vm.setUrl("https://h.example/t")
        vm.test()
        vm.await { it.test != null }
        assertEquals(TestVerdict.OK, vm.state.value.test!!.verdict)
        assertFalse(vm.state.value.busy)
    }

    @Test fun staleTestResultIsDiscarded() {
        gate = java.util.concurrent.CountDownLatch(1)
        val vm = vm()
        vm.chooseTemplate(TemplateKind.CUSTOM)
        vm.setUrl("https://h.example/t")
        vm.test()
        assertTrue(vm.state.value.busy)
        vm.setName("Changed")
        assertFalse(vm.state.value.busy)
        gate!!.countDown()
        Thread.sleep(500)
        assertNull(vm.state.value.test)
        assertFalse(vm.state.value.busy)
    }

    @Test fun keystoreFailureInTestIsFailedNotCrash() = runBlocking {
        val id = repos.destinations.create(draft("One"))
        val vm = loaded(id)
        repos.secrets.failOnGet = true
        vm.test()
        vm.await { it.test != null }
        assertEquals(TestVerdict.FAILED, vm.state.value.test!!.verdict)
        assertFalse(vm.state.value.busy)
    }

    @Test fun loadFailureIsNotDeleted() = runBlocking {
        val id = repos.destinations.create(draft("One"))
        repos.secrets.failOnGet = true
        val vm = vm(id)
        vm.await { it.loaded }
        assertTrue(vm.state.value.loadFailed)
        assertFalse(vm.state.value.done)
    }

    @Test fun renamedStoredSecretHeaderNeedsAValue() = runBlocking {
        val id = repos.destinations.create(draft("One", HeaderDraft("X-Key", "tok-secret-1", true)))
        val vm = loaded(id)
        val row = vm.state.value.headers.first { it.secret }
        vm.setHeaders(listOf(row.copy(name = "X-Other")))
        vm.save()
        assertTrue(DestinationError.HEADER_VALUE_INVALID in vm.state.value.errors)
        assertFalse(vm.state.value.done)
        vm.test()
        assertTrue(DestinationError.HEADER_VALUE_INVALID in vm.state.value.errors)
        assertEquals(listOf("X-Key" to "tok-secret-1"), repos.secrets.map.getValue(id).headers)
    }

    @Test fun newSecretHeaderWithoutValueIsRejected() {
        val vm = vm()
        vm.chooseTemplate(TemplateKind.CUSTOM)
        vm.setUrl("https://h.example/x")
        vm.setHeaders(listOf(HeaderRowUi("X-Key", "", secret = true, stored = false)))
        vm.save()
        assertTrue(DestinationError.HEADER_VALUE_INVALID in vm.state.value.errors)
        assertFalse(vm.state.value.done)
    }
}
