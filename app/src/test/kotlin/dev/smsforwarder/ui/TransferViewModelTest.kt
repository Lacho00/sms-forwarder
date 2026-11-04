package dev.smsforwarder.ui

import android.net.Uri
import androidx.room.Room
import dev.smsforwarder.core.Clock
import dev.smsforwarder.core.ConfigError
import dev.smsforwarder.core.DestinationPresets
import dev.smsforwarder.core.RuleDraft
import dev.smsforwarder.core.SenderMode
import dev.smsforwarder.core.SimMatch
import dev.smsforwarder.data.AppDatabase
import dev.smsforwarder.data.ConfigTransfer
import dev.smsforwarder.data.ImportResult
import dev.smsforwarder.data.TestRepos
import dev.smsforwarder.ui.DocumentIo
import dev.smsforwarder.ui.transfer.TransferMessage
import dev.smsforwarder.ui.transfer.TransferUi
import dev.smsforwarder.ui.transfer.TransferViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.IOException
import java.time.LocalDate

class FakeDocumentIo : DocumentIo {
    val writes = mutableListOf<Pair<Uri, String>>()
    var content: String? = ""
    var failRead = false
    var failWrite = false
    var failDelete = false
    val deleted = mutableListOf<Uri>()
    var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    override suspend fun write(uri: Uri, text: String) {
        gate?.await()
        if (failWrite) throw IOException("write failed")
        writes += uri to text
    }

    override suspend fun delete(uri: Uri) {
        deleted += uri
        if (failDelete) throw IOException("delete failed")
    }

    override suspend fun read(uri: Uri, maxBytes: Int): String? {
        if (failRead) throw IOException("read failed")
        return content
    }
}

@RunWith(RobolectricTestRunner::class)
class TransferViewModelTest {
    private val clock = Clock { 1_800_000_000_000L }
    private lateinit var db: AppDatabase
    private lateinit var repos: TestRepos
    private lateinit var transfer: ConfigTransfer
    private val io = FakeDocumentIo()
    private val uri = Uri.parse("content://test/doc/1")
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repos = TestRepos(db, clock)
        transfer = ConfigTransfer(db, repos.destinations, repos.rules, repos.secrets)
    }

    @After fun tearDown() {
        db.close()
    }

    private fun vm() = TransferViewModel(transfer, io, "2.0.0", { LocalDate.of(2026, 11, 3) }, scope)

    private suspend fun seed() {
        val d = repos.destinations.create(DestinationPresets.standardJson("Main", "https://h.example/in", "TOKEN"))
        repos.rules.create(RuleDraft("Rule", true, SenderMode.EXACT, "AAA", null, null, SimMatch.ANY, setOf(d)))
    }

    private suspend fun TransferViewModel.await(match: (TransferUi) -> Boolean): TransferUi =
        withTimeout(5_000) { state.first(match) }

    private suspend fun names() = db.destinationDao().liveNames().sorted()

    @Test fun exportDefaultsToNoSecrets() = runBlocking {
        seed()
        val vm = vm()
        vm.startExport()
        assertEquals(TransferUi.AskExport(false), vm.state.value)
        vm.confirmExport()
        assertEquals(TransferUi.PickExportFile("sms-forwarder-config-2026-11-03.json"), vm.await { it is TransferUi.PickExportFile })
        vm.pickerLaunched()
        vm.onExportFile(uri)
        assertEquals(TransferUi.Done(TransferMessage.Exported), vm.await { it is TransferUi.Done })
        assertEquals(1, io.writes.size)
        assertEquals(uri, io.writes[0].first)
        assertFalse(io.writes[0].second.contains("h.example"))
        assertFalse(io.writes[0].second.contains("TOKEN"))
    }

    @Test fun pickerUnavailableDropsExportText() = runBlocking {
        seed()
        val vm = vm()
        vm.startExport()
        vm.confirmExport()
        vm.await { it is TransferUi.PickExportFile }
        vm.pickerLaunched()
        vm.pickerUnavailable()
        assertEquals(TransferUi.Done(TransferMessage.PickerUnavailable), vm.state.value)
        // The text is gone: a late file would only be treated as interrupted.
        vm.onExportFile(uri)
        assertEquals(TransferUi.Done(TransferMessage.ExportInterrupted), vm.await { it is TransferUi.Done && it.message == TransferMessage.ExportInterrupted })
        assertTrue(io.writes.isEmpty())
    }

    @Test fun importPickerUnavailable() = runBlocking {
        val vm = vm()
        vm.startImport()
        vm.pickerLaunched()
        vm.pickerUnavailable()
        assertEquals(TransferUi.Done(TransferMessage.PickerUnavailable), vm.state.value)
    }

    @Test fun secondFileWhileWritingIsIgnoredAndCancelDeletes() = runBlocking {
        seed()
        io.gate = kotlinx.coroutines.CompletableDeferred()
        val own = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Unconfined)
        val vm = TransferViewModel(transfer, io, "2.0.0", { LocalDate.of(2026, 11, 3) }, own)
        vm.startExport()
        vm.confirmExport()
        vm.await { it is TransferUi.PickExportFile }
        vm.pickerLaunched()
        vm.onExportFile(uri)
        vm.onExportFile(Uri.parse("content://test/doc/other"))
        own.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
        kotlinx.coroutines.yield()
        assertEquals(listOf(uri), io.deleted)
        assertTrue(io.writes.isEmpty())
    }

    @Test fun cancelPickerWritesNothing() = runBlocking {
        seed()
        val vm = vm()
        vm.startExport()
        vm.confirmExport()
        vm.await { it is TransferUi.PickExportFile }
        vm.pickerLaunched()
        vm.onExportFile(null)
        assertEquals(TransferUi.Idle, vm.state.value)
        assertTrue(io.writes.isEmpty())
    }

    @Test fun writeFailureIsReported() = runBlocking {
        seed()
        io.failWrite = true
        val vm = vm()
        vm.startExport()
        vm.confirmExport()
        vm.await { it is TransferUi.PickExportFile }
        vm.pickerLaunched()
        vm.onExportFile(uri)
        assertEquals(TransferUi.Done(TransferMessage.WriteFailed), vm.await { it is TransferUi.Done })
        assertEquals(listOf(uri), io.deleted)
    }

    @Test fun failedDeleteAfterWriteFailureIsIgnored() = runBlocking {
        seed()
        io.failWrite = true
        io.failDelete = true
        val vm = vm()
        vm.startExport()
        vm.confirmExport()
        vm.await { it is TransferUi.PickExportFile }
        vm.pickerLaunched()
        vm.onExportFile(uri)
        assertEquals(TransferUi.Done(TransferMessage.WriteFailed), vm.await { it is TransferUi.Done })
    }

    @Test fun pickedFileWithoutTextIsDeletedAndReported() = runBlocking {
        io.failDelete = true
        val vm = vm()
        vm.onExportFile(uri)
        assertEquals(TransferUi.Done(TransferMessage.ExportInterrupted), vm.await { it is TransferUi.Done })
        assertEquals(listOf(uri), io.deleted)
        assertTrue(io.writes.isEmpty())
    }

    @Test fun exportEventLoggedOnlyAfterSuccessfulWrite() = runBlocking {
        seed()
        val log = dev.smsforwarder.diag.EngineLog(java.io.File.createTempFile("engine", ".log"))
        transfer = ConfigTransfer(db, repos.destinations, repos.rules, repos.secrets, engineLog = log, clock = clock)
        suspend fun run(uriOrNull: Uri?, fail: Boolean) {
            io.failWrite = fail
            val vm = vm()
            vm.startExport()
            vm.confirmExport()
            vm.await { it is TransferUi.PickExportFile }
            vm.pickerLaunched()
            vm.onExportFile(uriOrNull)
            if (uriOrNull != null) vm.await { it is TransferUi.Done }
        }
        run(null, false)
        run(uri, true)
        assertTrue(log.recent(10).isEmpty())
        run(uri, false)
        assertEquals(1, log.recent(10).count { it.type == dev.smsforwarder.core.EngineEventType.EXPORT })
    }

    @Test fun invalidImportAfterSummaryDropsThePendingFile() = runBlocking {
        seed()
        io.content = transfer.export(includeSecrets = false, appVersion = "2.0.0")
        val vm = vm()
        vm.startImport()
        vm.pickerLaunched()
        vm.onImportFile(uri)
        vm.await { it is TransferUi.ImportSummary }
        io.content = "not json"
        vm.dismiss()
        vm.startImport()
        vm.pickerLaunched()
        vm.onImportFile(uri)
        vm.await { it is TransferUi.ImportInvalid }
        vm.confirmImport()
        assertTrue(vm.state.value is TransferUi.ImportInvalid)
        assertEquals(listOf("Main"), names())
    }

    @Test fun keystoreFailureWithoutSecretsIsWriteFailed() = runBlocking {
        seed()
        repos.secrets.getFailure = dev.smsforwarder.data.TransientSecretException(java.security.GeneralSecurityException("x"))
        val vm = vm()
        vm.startExport()
        vm.confirmExport()
        assertEquals(TransferUi.Done(TransferMessage.WriteFailed), vm.await { it is TransferUi.Done })
    }

    @Test fun keystoreFailureOnExportIsReported() = runBlocking {
        seed()
        repos.secrets.getFailure = dev.smsforwarder.data.TransientSecretException(java.security.GeneralSecurityException("x"))
        val vm = vm()
        vm.startExport()
        vm.setIncludeSecrets(true)
        vm.confirmExport()
        assertEquals(TransferUi.Done(TransferMessage.SecretsUnavailable), vm.await { it is TransferUi.Done })
        assertTrue(io.writes.isEmpty())
    }

    @Test fun pickerLaunchedOnce() = runBlocking {
        val vm = vm()
        vm.startImport()
        assertEquals(TransferUi.PickImportFile, vm.state.value)
        vm.pickerLaunched()
        assertEquals(TransferUi.Waiting, vm.state.value)
        vm.pickerLaunched()
        assertEquals(TransferUi.Waiting, vm.state.value)
    }

    @Test fun tooLargeFileRejected() = runBlocking {
        io.content = null
        val vm = vm()
        vm.startImport()
        vm.pickerLaunched()
        vm.onImportFile(uri)
        assertEquals(TransferUi.ImportInvalid(ConfigError.TOO_LARGE, null), vm.await { it is TransferUi.ImportInvalid })
    }

    @Test fun readFailureIsReported() = runBlocking {
        io.failRead = true
        val vm = vm()
        vm.startImport()
        vm.pickerLaunched()
        vm.onImportFile(uri)
        assertEquals(TransferUi.Done(TransferMessage.ReadFailed), vm.await { it is TransferUi.Done })
    }

    @Test fun invalidFileChangesNothing() = runBlocking {
        seed()
        io.content = transfer.export(includeSecrets = true, appVersion = "2.0.0").replace("https://", "http://")
        val vm = vm()
        vm.startImport()
        vm.pickerLaunched()
        vm.onImportFile(uri)
        assertEquals(
            TransferUi.ImportInvalid(ConfigError.INVALID_DESTINATION, "Main"),
            vm.await { it is TransferUi.ImportInvalid },
        )
        assertEquals(listOf("Main"), names())
        assertEquals(1, db.ruleDao().allNames().size)
    }

    @Test fun summaryThenApply() = runBlocking {
        seed()
        io.content = transfer.export(includeSecrets = false, appVersion = "2.0.0")
        val vm = vm()
        vm.startImport()
        vm.pickerLaunched()
        vm.onImportFile(uri)
        val summary = vm.await { it is TransferUi.ImportSummary } as TransferUi.ImportSummary
        assertEquals(listOf("Main" to "Main (2)", "Rule" to "Rule (2)"), summary.plan.renamed)
        assertEquals(listOf("Main (2)"), summary.plan.withoutCredentials)
        assertEquals(listOf("Main"), names())
        vm.confirmImport()
        assertEquals(
            TransferUi.Done(TransferMessage.Imported(ImportResult(1, 1, 1))),
            vm.await { it is TransferUi.Done },
        )
        assertEquals(listOf("Main", "Main (2)"), names())
    }

    @Test fun dismissAfterSummaryImportsNothing() = runBlocking {
        seed()
        io.content = transfer.export(includeSecrets = false, appVersion = "2.0.0")
        val vm = vm()
        vm.startImport()
        vm.pickerLaunched()
        vm.onImportFile(uri)
        vm.await { it is TransferUi.ImportSummary }
        vm.dismiss()
        vm.confirmImport()
        assertEquals(TransferUi.Idle, vm.state.value)
        assertEquals(listOf("Main"), names())
    }

    @Test fun stateToStringHidesSecrets() = runBlocking {
        seed()
        val seen = mutableListOf<String>()
        val vm = vm()
        val job = vm.state.onEach { seen += it.toString() }.launchIn(scope)
        vm.startExport()
        vm.setIncludeSecrets(true)
        vm.confirmExport()
        vm.await { it is TransferUi.PickExportFile }
        vm.pickerLaunched()
        val text = transfer.export(includeSecrets = true, appVersion = "2.0.0")
        vm.onExportFile(uri)
        vm.await { it is TransferUi.Done }
        assertTrue(io.writes.single().second.contains("TOKEN"))
        io.content = text
        vm.dismiss()
        vm.startImport()
        vm.pickerLaunched()
        vm.onImportFile(uri)
        vm.await { it is TransferUi.ImportSummary }
        job.cancel()
        assertTrue(seen.size >= 6)
        seen.forEach {
            assertFalse(it, it.contains("h.example"))
            assertFalse(it, it.contains("TOKEN"))
        }
    }
}
