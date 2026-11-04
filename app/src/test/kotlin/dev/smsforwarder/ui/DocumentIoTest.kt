package dev.smsforwarder.ui

import android.net.Uri

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class DocumentIoTest {
    private val io = ContentResolverDocumentIo(RuntimeEnvironment.getApplication().contentResolver)

    @Test fun writesAndReadsUtf8() = runBlocking {
        val f = File.createTempFile("doc", ".json")
        val uri = Uri.fromFile(f)
        io.write(uri, "{\"a\":\"ñ\"}")
        assertEquals("{\"a\":\"ñ\"}", io.read(uri, 100))
        f.delete()
        Unit
    }

    @Test fun readStopsAboveTheLimit() = runBlocking {
        val f = File.createTempFile("doc", ".json")
        f.writeText("x".repeat(11))
        assertNull(io.read(Uri.fromFile(f), 10))
        assertEquals("x".repeat(11), io.read(Uri.fromFile(f), 11))
        f.delete()
        Unit
    }
}
