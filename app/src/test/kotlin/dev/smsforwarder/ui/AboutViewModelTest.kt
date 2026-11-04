package dev.smsforwarder.ui

import android.net.Uri
import androidx.work.testing.WorkManagerTestInitHelper
import dev.smsforwarder.SmsForwarderApp
import dev.smsforwarder.ui.about.AboutViewModel
import dev.smsforwarder.ui.about.DiagnosticMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class AboutViewModelTest {
    private val io = FakeDocumentIo()
    private val uri = Uri.parse("content://test/doc/2")
    private lateinit var vm: AboutViewModel

    @Before fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val graph = (context as SmsForwarderApp).graph
        vm = AboutViewModel(graph.diagnostics, io, { LocalDate.of(2026, 11, 3) }, CoroutineScope(Dispatchers.Unconfined))
    }

    /** The report is built on the IO dispatcher, so wait for the picker request. */
    private fun startAndAwait() {
        vm.startExport()
        runBlocking { withTimeout(20_000) { vm.state.first { it.pickFile != null || it.message != null } } }
    }

    @Test fun exportWritesReport() {
        startAndAwait()
        assertEquals("sms-forwarder-diagnostic-2026-11-03.txt", vm.state.value.pickFile)
        vm.pickerLaunched()
        assertNull(vm.state.value.pickFile)
        vm.onFile(uri)
        assertEquals(1, io.writes.size)
        assertTrue(io.writes.single().second.startsWith("SMS Forwarder diagnostic"))
        assertEquals(DiagnosticMessage.SAVED, vm.state.value.message)
        assertFalse(vm.state.value.busy)
    }

    @Test fun cancelWritesNothing() {
        startAndAwait()
        vm.pickerLaunched()
        vm.onFile(null)
        assertTrue(io.writes.isEmpty())
        assertNull(vm.state.value.message)
        assertFalse(vm.state.value.busy)
        // The report is dropped: a later file with no new export is treated as interrupted.
        vm.onFile(uri)
        assertTrue(io.writes.isEmpty())
        assertEquals(DiagnosticMessage.INTERRUPTED, vm.state.value.message)
    }

    @Test fun writeFailureReportsFailedAndDeletes() {
        io.failWrite = true
        startAndAwait()
        vm.pickerLaunched()
        vm.onFile(uri)
        assertEquals(DiagnosticMessage.FAILED, vm.state.value.message)
        assertEquals(listOf(uri), io.deleted)
    }

    @Test fun fileWithoutReportAfterProcessDeathIsInterrupted() {
        vm.onFile(uri)
        assertEquals(DiagnosticMessage.INTERRUPTED, vm.state.value.message)
        assertEquals(listOf(uri), io.deleted)
        assertTrue(io.writes.isEmpty())
    }

    @Test fun pickerUnavailableFailsAndDropsReport() {
        startAndAwait()
        vm.pickerLaunched()
        vm.pickerUnavailable()
        assertEquals(DiagnosticMessage.FAILED, vm.state.value.message)
        assertFalse(vm.state.value.busy)
        assertNull(vm.state.value.pickFile)
        vm.onFile(uri)
        assertTrue(io.writes.isEmpty())
    }

    @Test fun secondFileWhileWritingIsIgnoredAndCancelDeletes() {
        io.gate = kotlinx.coroutines.CompletableDeferred()
        val own = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Unconfined)
        val context = RuntimeEnvironment.getApplication()
        val graph = (context as SmsForwarderApp).graph
        val v = AboutViewModel(graph.diagnostics, io, { LocalDate.of(2026, 11, 3) }, own)
        v.startExport()
        runBlocking { withTimeout(20_000) { v.state.first { it.pickFile != null } } }
        v.pickerLaunched()
        v.onFile(uri)
        v.onFile(Uri.parse("content://test/doc/other"))
        own.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
        runBlocking { kotlinx.coroutines.yield() }
        assertEquals(listOf(uri), io.deleted)
        assertTrue(io.writes.isEmpty())
    }
}
