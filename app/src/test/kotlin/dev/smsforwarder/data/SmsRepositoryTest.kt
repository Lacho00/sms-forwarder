package dev.smsforwarder.data

import androidx.room.Room
import dev.smsforwarder.core.Clock
import dev.smsforwarder.core.QueuePolicy
import dev.smsforwarder.core.SendOutcome
import dev.smsforwarder.core.SmsOrigin
import dev.smsforwarder.core.SmsRecord
import dev.smsforwarder.core.SmsStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
class SmsRepositoryTest {
    private var now = 1_800_000_000_000L
    private val clock = Clock { now }
    private lateinit var db: AppDatabase
    private lateinit var repo: SmsRepository

    private val body = "OPERADOR: Se ha comprado un plan de datos de 2.5GB valido por 30 dias, " +
        "se activa con el primer uso. Se desconto 360.00. Saldo actual 140.25"

    private fun record(receivedAt: Long = now - 1_000L, text: String = body) =
        SmsRecord("OPERADOR", text, receivedAt, null, null)

    @Before fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = SmsRepository(db, clock)
    }

    @After fun tearDown() {
        db.close()
    }

    @Test fun observeEmitsRowAndNullWhenMissing() = runBlocking {
        val id = (repo.ingest(record(), SmsOrigin.RECEIVER) as IngestResult.Inserted).id
        assertEquals(id, repo.observe(id).first()!!.id)
        assertEquals(null, repo.observe(id + 100).first())
    }

    @Test fun ingestTwiceSameRecordIsAlreadyKnown() = runBlocking {
        val first = repo.ingest(record(), SmsOrigin.RECEIVER)
        val second = repo.ingest(record(), SmsOrigin.SWEEP)
        assertTrue(first is IngestResult.Inserted)
        assertEquals(IngestResult.AlreadyKnown((first as IngestResult.Inserted).id), second)
    }

    @Test fun ingestWithin2sSameBodyIsAlreadyKnownAndKeepsFirstReceivedAt() = runBlocking {
        val t = now - 10_000L
        val first = repo.ingest(record(receivedAt = t), SmsOrigin.RECEIVER) as IngestResult.Inserted
        val second = repo.ingest(record(receivedAt = t + 1_500L), SmsOrigin.SWEEP)
        assertEquals(IngestResult.AlreadyKnown(first.id), second)
        assertEquals(t, repo.get(first.id)!!.receivedAt)
    }

    @Test fun recordAttemptRetryKeepsQueued() = runBlocking {
        val id = (repo.ingest(record(), SmsOrigin.RECEIVER) as IngestResult.Inserted).id
        repo.recordAttempt(id, SendOutcome.Retry(null, "io"))
        val sms = repo.get(id)!!
        assertEquals(SmsStatus.QUEUED, sms.status)
        assertEquals(1, sms.attempts)
    }

    @Test fun recordAttemptAcceptedSetsSent() = runBlocking {
        val id = (repo.ingest(record(), SmsOrigin.RECEIVER) as IngestResult.Inserted).id
        repo.recordAttempt(id, SendOutcome.Accepted)
        val sms = repo.get(id)!!
        assertEquals(SmsStatus.SENT, sms.status)
        assertEquals(200, sms.lastHttpCode)
        assertNotNull(sms.sentAt)
    }

    @Test fun recordAttempt401SetsErrorAndIsListedIn401() = runBlocking {
        val id = (repo.ingest(record(), SmsOrigin.RECEIVER) as IngestResult.Inserted).id
        repo.recordAttempt(id, SendOutcome.Rejected(401, null))
        assertEquals(SmsStatus.ERROR, repo.get(id)!!.status)
        assertEquals(listOf(id), repo.idsWithError401())
    }

    @Test fun purgeOldKeepsQueuedAndErrorEvenIfOld() = runBlocking {
        val old = now - QueuePolicy.RETENTION_MS - 86_400_000L
        val queued = (repo.ingest(record(old, "queued $body"), SmsOrigin.RECEIVER) as IngestResult.Inserted).id
        val error = (repo.ingest(record(old, "error $body"), SmsOrigin.RECEIVER) as IngestResult.Inserted).id
        val sent = (repo.ingest(record(old, "sent $body"), SmsOrigin.RECEIVER) as IngestResult.Inserted).id
        repo.recordAttempt(error, SendOutcome.Rejected(400, null))
        repo.recordAttempt(sent, SendOutcome.Accepted)

        repo.purgeOld()

        assertNotNull(repo.get(queued))
        assertNotNull(repo.get(error))
        assertEquals(null, repo.get(sent))
    }

    @Test fun requeueRestartsStuckClock() = runBlocking {
        val id = (repo.ingest(record(), SmsOrigin.RECEIVER) as IngestResult.Inserted).id
        repo.recordAttempt(id, SendOutcome.Rejected(400, null))
        now += QueuePolicy.STUCK_AFTER_MS + 1L
        assertEquals(listOf(id), repo.stuckToNotify().map { it.id })

        repo.requeue(id)

        assertEquals(SmsStatus.QUEUED, repo.get(id)!!.status)
        assertTrue(repo.stuckToNotify().isEmpty())
    }

    @Test fun lateRetryDoesNotUndoSent() = runBlocking {
        val id = (repo.ingest(record(), SmsOrigin.RECEIVER) as IngestResult.Inserted).id
        repo.recordAttempt(id, SendOutcome.Accepted)
        repo.recordAttempt(id, SendOutcome.Retry(null, "network"))
        repo.requeue(id)
        assertEquals(SmsStatus.SENT, repo.get(id)!!.status)
        assertTrue(repo.queuedIds().isEmpty())
    }
}
