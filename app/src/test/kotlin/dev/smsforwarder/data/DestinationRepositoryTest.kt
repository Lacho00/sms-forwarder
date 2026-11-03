package dev.smsforwarder.data

import androidx.room.Room
import dev.smsforwarder.core.Clock
import dev.smsforwarder.core.DeliveryStatus
import dev.smsforwarder.core.DestinationDraft
import dev.smsforwarder.core.DestinationPresets
import dev.smsforwarder.core.HeaderDraft
import dev.smsforwarder.core.SendOutcome
import dev.smsforwarder.core.SmsOrigin
import dev.smsforwarder.core.SmsRecord
import dev.smsforwarder.core.SmsStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
class DestinationRepositoryTest {
    private val clock = Clock { 1_800_000_000_000L }
    private lateinit var db: AppDatabase
    private lateinit var repos: TestRepos
    private lateinit var repo: DestinationRepository
    private lateinit var sms: SmsRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repos = TestRepos(db, clock)
        repo = repos.destinations
        sms = repos.sms
    }

    @After fun tearDown() {
        db.close()
    }

    private fun custom(name: String, vararg headers: HeaderDraft): DestinationDraft =
        DestinationPresets.custom(name).copy(url = "https://h.example/$name", headers = headers.toList(), body = "{}")

    private var next = 0
    private suspend fun ingest(vararg destinations: Long): Long {
        next++
        val record = SmsRecord("AAA", "text $next", clock.now() - next * 10_000L, null, null)
        return (sms.ingest(record, SmsOrigin.RECEIVER, destinations.toSet()) as IngestResult.Inserted).id
    }

    private suspend fun statuses(smsId: Long) = sms.deliveriesOf(smsId).map { it.destinationId to it.status }

    @Test fun createStoresSecretsApart() = runBlocking {
        val id = repo.create(custom("One", HeaderDraft("X-A", "pub", false), HeaderDraft("X-Key", "k1", true)))
        assertEquals(listOf("X-A" to "pub"), repo.get(id)!!.publicHeaderList())
        assertEquals(DestinationSecrets("https://h.example/One", listOf("X-Key" to "k1")), repo.secrets(id))
    }

    @Test fun createRejectsInvalid() = runBlocking {
        val bad = custom("One").copy(url = "http://h.example/x")
        try {
            repo.create(bad)
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        assertNull(db.destinationDao().get(1L))
    }

    @Test fun editableHidesSecrets() = runBlocking {
        val id = repo.create(custom("One", HeaderDraft("X-A", "pub", false), HeaderDraft("X-Key", "k1", true)))
        val e = repo.editable(id)!!
        assertNull(e.draft.url)
        assertTrue(e.urlStored)
        assertEquals(null, e.draft.headers.first { it.name == "X-Key" }.value)
        assertEquals("pub", e.draft.headers.first { it.name == "X-A" }.value)
    }

    @Test fun updateKeepsBlankSecrets() = runBlocking {
        val id = repo.create(custom("One", HeaderDraft("X-Key", "k1", true)))
        val blank = repo.editable(id)!!.draft.copy(name = "Renamed")
        repo.update(id, blank)
        assertEquals(DestinationSecrets("https://h.example/One", listOf("X-Key" to "k1")), repo.secrets(id))
        assertEquals("Renamed", repo.get(id)!!.name)

        repo.update(id, blank.copy(url = "https://h.example/new"))
        assertEquals(DestinationSecrets("https://h.example/new", listOf("X-Key" to "k1")), repo.secrets(id))
    }

    @Test fun updateRequeuesAuthAndConfigErrors() = runBlocking {
        val id = repo.create(custom("One"))
        val a = ingest(id)
        val b = ingest(id)
        val c = ingest(id)
        sms.recordAttempt(sms.deliveriesOf(a).single().id, SendOutcome.Rejected(403, null))
        sms.recordAttempt(sms.deliveriesOf(b).single().id, SendOutcome.Invalid("template"))
        sms.recordAttempt(sms.deliveriesOf(c).single().id, SendOutcome.Rejected(400, null))

        val ids = repo.update(id, repo.editable(id)!!.draft)

        assertEquals(setOf(a, b), ids.toSet())
        assertEquals(DeliveryStatus.QUEUED, sms.deliveriesOf(a).single().status)
        assertEquals(DeliveryStatus.QUEUED, sms.deliveriesOf(b).single().status)
        assertEquals(DeliveryStatus.ERROR, sms.deliveriesOf(c).single().status)
    }

    private suspend fun twoDestinations(): Pair<Long, Long> = repo.create(custom("One")) to repo.create(custom("Two"))

    @Test fun deleteCancelsPendingKeepsFinished() = runBlocking {
        val (d1, d2) = twoDestinations()
        val a = ingest(d1, d2)
        val b = ingest(d2)
        sms.recordAttempt(sms.deliveriesOf(a).first { it.destinationId == d1 }.id, SendOutcome.Accepted(200))
        db.ruleDao().insert(
            RuleEntity(
                name = "r", enabled = true, senderMode = dev.smsforwarder.core.SenderMode.EXACT, senderPattern = "AAA",
                bodyMode = null, bodyPattern = null, sim = dev.smsforwarder.core.SimMatch.ANY, createdAt = 1L,
            ),
        ).also { db.ruleDao().insertLinks(listOf(RuleDestinationEntity(it, d1), RuleDestinationEntity(it, d2))) }

        repo.delete(d2)

        val aDeliveries = sms.deliveriesOf(a)
        assertEquals(DeliveryStatus.SENT, aDeliveries.first { it.destinationId == d1 }.status)
        val canceled = aDeliveries.first { it.destinationId == d2 }
        assertEquals(DeliveryStatus.CANCELED, canceled.status)
        assertEquals("Two", canceled.destinationName)
        assertEquals(SmsStatus.SENT, sms.get(a)!!.status)
        assertEquals(SmsStatus.ERROR, sms.get(b)!!.status)
        assertEquals("destination_deleted", sms.get(b)!!.lastError)
        assertNull(repo.secrets(d2))
        assertEquals(
            listOf(d1),
            db.ruleDao().activeWithDestinations().single().destinations.map { it.id },
        )
        assertFalse(b in sms.queuedIds())
        assertFalse(sms.stuckToNotify(stuckAfterMs = 0L).any { it.id == b })
        assertEquals(listOf(d1), repo.observeLive().first().map { it.id })
    }

    @Test fun lateResultOnCanceledIgnored() = runBlocking {
        val (_, d2) = twoDestinations()
        val b = ingest(d2)
        val delivery = sms.deliveriesOf(b).single()
        repo.delete(d2)
        sms.recordAttempt(delivery.id, SendOutcome.Accepted(200))
        assertEquals(DeliveryStatus.CANCELED, sms.deliveriesOf(b).single().status)
        assertEquals(SmsStatus.ERROR, sms.get(b)!!.status)
    }

    @Test fun requeueSkipsCanceled() = runBlocking {
        val (_, d2) = twoDestinations()
        val b = ingest(d2)
        repo.delete(d2)
        sms.requeue(b)
        assertEquals(DeliveryStatus.CANCELED, sms.deliveriesOf(b).single().status)
    }

    @Test fun resumeReturnsQueuedSms() = runBlocking {
        val (d1, d2) = twoDestinations()
        assertEquals(emptyList<Long>(), repo.setEnabled(d2, false))
        val id = ingest(d1, d2)
        assertEquals(listOf(id), repo.setEnabled(d2, true))
        assertEquals(1, repo.pendingCount(d2))
    }
}
