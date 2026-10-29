package dev.smsforwarder.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class DiagStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun capture(i: Int) = DiagCapture(
        sender = "SENDER",
        pduTimestamp = 1_000L * i,
        bodyHash = shortHash("body-$i"),
        bodyLength = 10 + i,
        parts = 1,
        subId = 1,
        capturedAt = 5_000L * i,
    )

    private fun row(c: DiagCapture, dateSent: Long, date: Long = c.pduTimestamp, address: String = c.sender) =
        DiagInboxRow(address, date, dateSent, c.subId, c.bodyHash, c.bodyLength)

    @Test fun keepsNewestTwentyNewestFirst() {
        val store = DiagStore(File(tmp.root, DiagStore.FILE_NAME))
        (1..21).forEach { store.add(capture(it)) }
        val all = store.all()
        assertEquals(20, all.size)
        assertEquals(capture(21), all.first())
        assertEquals(capture(2), all.last())
    }

    @Test fun emptyWhenNoFile() {
        assertEquals(emptyList<DiagCapture>(), DiagStore(File(tmp.root, "none.json")).all())
    }

    @Test fun corruptFileReadsEmptyAndRecoversOnNextAdd() {
        val file = File(tmp.root, DiagStore.FILE_NAME).apply { writeText("[{\"sender\":\"X") }
        val store = DiagStore(file)
        assertEquals(emptyList<DiagCapture>(), store.all())
        store.add(capture(1))
        assertEquals(listOf(capture(1)), store.all())
    }

    @Test fun neverWritesTheBody() {
        val file = File(tmp.root, DiagStore.FILE_NAME)
        val body = "OPERADOR: saldo actual 140.25"
        DiagStore(file).add(capture(1).copy(bodyHash = shortHash(body), bodyLength = body.length))
        assertFalse(file.readText().contains("OPERADOR"))
        assertEquals(12, shortHash(body).length)
    }

    @Test fun exactPairWhenDateSentMatchesPduTimestamp() {
        val c = capture(3)
        assertEquals(PairKind.EXACT, pairKind(c, row(c, dateSent = c.pduTimestamp, date = c.pduTimestamp + 800)))
    }

    @Test fun fallbackPairWhenOnlyWithinWindow() {
        val c = capture(3)
        assertEquals(PairKind.FALLBACK, pairKind(c, row(c, dateSent = 0, date = c.pduTimestamp + 1_500)))
    }

    @Test fun noPairOutsideWindowOrOnOtherSender() {
        val c = capture(3)
        assertEquals(PairKind.NONE, pairKind(c, row(c, dateSent = 0, date = c.pduTimestamp + 2_500)))
        assertEquals(PairKind.NONE, pairKind(c, row(c, dateSent = c.pduTimestamp, address = "OTHER")))
    }
}
