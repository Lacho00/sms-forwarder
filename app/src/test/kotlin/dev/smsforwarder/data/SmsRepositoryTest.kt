package dev.smsforwarder.data

import androidx.room.Room
import dev.smsforwarder.core.Clock
import dev.smsforwarder.core.DeliveryStatus
import dev.smsforwarder.core.HttpMethod
import dev.smsforwarder.core.QueuePolicy
import dev.smsforwarder.core.SuccessPolicy
import dev.smsforwarder.core.TemplateKind
import dev.smsforwarder.core.Retention
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
        runBlocking {
            for (name in listOf("Primary", "Second")) {
                db.destinationDao().insert(
                    DestinationEntity(
                        name = name, enabled = true, template = TemplateKind.STANDARD_JSON, method = HttpMethod.POST,
                        bodyTemplate = null, bodyIsJson = true, publicHeaders = "[]",
                        successPolicy = SuccessPolicy.ANY_2XX, createdAt = 1L,
                    ),
                )
            }
        }
    }

    @After fun tearDown() {
        db.close()
    }

    @Test fun observeEmitsRowAndNullWhenMissing() = runBlocking {
        val id = (repo.ingest(record(), SmsOrigin.RECEIVER, setOf(1L)) as IngestResult.Inserted).id
        assertEquals(id, repo.observe(id).first()!!.id)
        assertEquals(null, repo.observe(id + 100).first())
    }

    @Test fun ingestTwiceSameRecordIsAlreadyKnown() = runBlocking {
        val first = repo.ingest(record(), SmsOrigin.RECEIVER, setOf(1L))
        val second = repo.ingest(record(), SmsOrigin.SWEEP, setOf(1L))
        assertTrue(first is IngestResult.Inserted)
        assertEquals(IngestResult.AlreadyKnown((first as IngestResult.Inserted).id), second)
    }

    @Test fun ingestWithin2sSameBodyIsAlreadyKnownAndKeepsFirstReceivedAt() = runBlocking {
        val t = now - 10_000L
        val first = repo.ingest(record(receivedAt = t), SmsOrigin.RECEIVER, setOf(1L)) as IngestResult.Inserted
        val second = repo.ingest(record(receivedAt = t + 1_500L), SmsOrigin.SWEEP, setOf(1L))
        assertEquals(IngestResult.AlreadyKnown(first.id), second)
        assertEquals(t, repo.get(first.id)!!.receivedAt)
    }

    @Test fun recordAttemptRetryKeepsQueued() = runBlocking {
        val id = (repo.ingest(record(), SmsOrigin.RECEIVER, setOf(1L)) as IngestResult.Inserted).id
        repo.recordAttemptForSms(id, SendOutcome.Retry(null, "io"))
        val sms = repo.get(id)!!
        assertEquals(SmsStatus.QUEUED, sms.status)
        assertEquals(1, sms.attempts)
    }

    @Test fun recordAttemptAcceptedSetsSent() = runBlocking {
        val id = (repo.ingest(record(), SmsOrigin.RECEIVER, setOf(1L)) as IngestResult.Inserted).id
        repo.recordAttemptForSms(id, SendOutcome.Accepted(200))
        val sms = repo.get(id)!!
        assertEquals(SmsStatus.SENT, sms.status)
        assertEquals(200, sms.lastHttpCode)
        assertNotNull(sms.sentAt)
    }

    @Test fun recordAttempt401SetsErrorAndIsListedIn401() = runBlocking {
        val id = (repo.ingest(record(), SmsOrigin.RECEIVER, setOf(1L)) as IngestResult.Inserted).id
        repo.recordAttemptForSms(id, SendOutcome.Rejected(401, null))
        assertEquals(SmsStatus.ERROR, repo.get(id)!!.status)
        assertEquals(listOf(id), repo.idsWithAuthError())
    }

    @Test fun ingestCreatesOneDeliveryPerDestination() = runBlocking {
        val id = (repo.ingest(record(), SmsOrigin.RECEIVER, setOf(1L, 2L)) as IngestResult.Inserted).id
        val deliveries = db.deliveryDao().forSms(id)
        assertEquals(listOf(1L, 2L), deliveries.map { it.destinationId })
        assertEquals(listOf("Primary", "Second"), deliveries.map { it.destinationName })
        assertTrue(deliveries.all { it.status == DeliveryStatus.QUEUED })
    }

    @Test fun alreadyKnownAddsNoDelivery() = runBlocking {
        val id = (repo.ingest(record(), SmsOrigin.RECEIVER, setOf(1L)) as IngestResult.Inserted).id
        val again = repo.ingest(record(), SmsOrigin.SWEEP, setOf(1L, 2L))
        assertEquals(IngestResult.AlreadyKnown(id), again)
        assertEquals(1, db.deliveryDao().forSms(id).size)
    }

    @Test fun requeueResetsUnsentDeliveriesOnly() = runBlocking {
        val id = (repo.ingest(record(), SmsOrigin.RECEIVER, setOf(1L, 2L)) as IngestResult.Inserted).id
        val (a, b) = db.deliveryDao().forSms(id)
        db.deliveryDao().update(a.copy(status = DeliveryStatus.SENT))
        db.deliveryDao().update(b.copy(status = DeliveryStatus.ERROR, lastHttpCode = 500))
        db.smsDao().update(db.smsDao().get(id)!!.copy(status = SmsStatus.ERROR))

        repo.requeue(id)

        assertEquals(listOf(DeliveryStatus.SENT, DeliveryStatus.QUEUED), db.deliveryDao().forSms(id).map { it.status })
        assertEquals(SmsStatus.QUEUED, repo.get(id)!!.status)
    }

    // Runs [block] with the clock moved back, so firstSeenAt/deletedAt are old too, then restores it.
    private suspend fun <T> inThePast(days: Long, block: suspend () -> T): T {
        val real = now
        now = real - days * 86_400_000L
        try {
            return block()
        } finally {
            now = real
        }
    }

    private suspend fun oldRows(): Triple<Long, Long, Long> = inThePast(40) {
        val old = now
        val queued = (repo.ingest(record(old, "queued $body"), SmsOrigin.RECEIVER, setOf(1L)) as IngestResult.Inserted).id
        val error = (repo.ingest(record(old, "error $body"), SmsOrigin.RECEIVER, setOf(1L)) as IngestResult.Inserted).id
        val sent = (repo.ingest(record(old, "sent $body"), SmsOrigin.RECEIVER, setOf(1L)) as IngestResult.Inserted).id
        repo.recordAttemptForSms(error, SendOutcome.Rejected(400, null))
        repo.recordAttemptForSms(sent, SendOutcome.Accepted(200))
        Triple(queued, error, sent)
    }

    @Test fun purgeOldForeverKeepsEverything() = runBlocking {
        val (queued, error, sent) = oldRows()
        repo.purgeOld(Retention.FOREVER)
        assertNotNull(repo.get(queued))
        assertNotNull(repo.get(error))
        assertNotNull(repo.get(sent))
    }

    @Test fun purgeOld30DaysDeletesOnlySentOlder() = runBlocking {
        val (queued, error, sent) = oldRows()
        repo.purgeOld(Retention.D30)
        assertNotNull(repo.get(queued))
        assertNotNull(repo.get(error))
        assertEquals(null, repo.get(sent))
    }

    @Test fun stuckToNotifyHonoursWindow() = runBlocking {
        val id = (repo.ingest(record(), SmsOrigin.RECEIVER, setOf(1L)) as IngestResult.Inserted).id
        now += 2 * 3_600_000L
        assertTrue(repo.stuckToNotify(3_600_000L).any { it.id == id })
        assertTrue(repo.stuckToNotify(6 * 3_600_000L).isEmpty())
    }

    @Test fun purgeOldKeepsQueuedAndErrorEvenIfOld() = runBlocking {
        val (queued, error, sent) = inThePast(QueuePolicy.RETENTION_MS / 86_400_000L + 1) {
            val old = now
            val q = (repo.ingest(record(old, "queued $body"), SmsOrigin.RECEIVER, setOf(1L)) as IngestResult.Inserted).id
            val e = (repo.ingest(record(old, "error $body"), SmsOrigin.RECEIVER, setOf(1L)) as IngestResult.Inserted).id
            val s = (repo.ingest(record(old, "sent $body"), SmsOrigin.RECEIVER, setOf(1L)) as IngestResult.Inserted).id
            repo.recordAttemptForSms(e, SendOutcome.Rejected(400, null))
            repo.recordAttemptForSms(s, SendOutcome.Accepted(200))
            Triple(q, e, s)
        }

        repo.purgeOld()

        assertNotNull(repo.get(queued))
        assertNotNull(repo.get(error))
        assertEquals(null, repo.get(sent))
    }

    @Test fun requeueRestartsStuckClock() = runBlocking {
        val id = (repo.ingest(record(), SmsOrigin.RECEIVER, setOf(1L)) as IngestResult.Inserted).id
        repo.recordAttemptForSms(id, SendOutcome.Rejected(400, null))
        now += QueuePolicy.STUCK_AFTER_MS + 1L
        assertEquals(listOf(id), repo.stuckToNotify().map { it.id })

        repo.requeue(id)

        assertEquals(SmsStatus.QUEUED, repo.get(id)!!.status)
        assertTrue(repo.stuckToNotify().isEmpty())
    }

    @Test fun lateResultDoesNotUndoSent() = runBlocking {
        val id = (repo.ingest(record(), SmsOrigin.RECEIVER, setOf(1L, 2L)) as IngestResult.Inserted).id
        val (a, b) = db.deliveryDao().forSms(id)
        repo.recordAttempt(a.id, SendOutcome.Accepted(200))
        repo.recordAttempt(a.id, SendOutcome.Retry(null, "network"))
        repo.recordAttempt(a.id, SendOutcome.Rejected(401, null))
        assertEquals(DeliveryStatus.SENT, db.deliveryDao().get(a.id)!!.status)
        assertEquals(1, db.deliveryDao().get(a.id)!!.attempts)
        assertEquals(SmsStatus.QUEUED, repo.get(id)!!.status)
        repo.recordAttempt(b.id, SendOutcome.Accepted(200))
        assertEquals(SmsStatus.SENT, repo.get(id)!!.status)
        assertNotNull(repo.get(id)!!.sentAt)
        assertEquals(2, repo.get(id)!!.attempts)
    }

    @Test fun attemptsVisibleOnSms() = runBlocking {
        val id = (repo.ingest(record(), SmsOrigin.RECEIVER, setOf(1L, 2L)) as IngestResult.Inserted).id
        val (a, b) = db.deliveryDao().forSms(id)
        repo.recordAttempt(a.id, SendOutcome.Retry(500, "http_500"))
        repo.recordAttempt(b.id, SendOutcome.Accepted(200))
        repo.recordAttempt(a.id, SendOutcome.Rejected(400, "bad"))
        val attempts = repo.observeAttempts(id).first()
        assertEquals(3, attempts.size)
        assertEquals(listOf(a.id, b.id, a.id), attempts.map { it.deliveryId })
        val sms = repo.get(id)!!
        assertEquals(3, sms.attempts)
        assertEquals(SmsStatus.ERROR, sms.status)
        assertEquals(400, sms.lastHttpCode)
        assertEquals("bad", sms.lastError)
    }

    @Test fun deletedDestinationIsIgnoredByAggregate() = runBlocking {
        val id = (repo.ingest(record(), SmsOrigin.RECEIVER, setOf(1L, 2L)) as IngestResult.Inserted).id
        val (a, b) = db.deliveryDao().forSms(id)
        db.destinationDao().update(db.destinationDao().get(1L)!!.copy(deletedAt = now))
        repo.recordAttempt(b.id, SendOutcome.Accepted(200))
        assertEquals(SmsStatus.SENT, repo.get(id)!!.status)
        assertEquals(DeliveryStatus.QUEUED, db.deliveryDao().get(a.id)!!.status)
    }

    @Test fun lateRetryDoesNotUndoSent() = runBlocking {
        val id = (repo.ingest(record(), SmsOrigin.RECEIVER, setOf(1L)) as IngestResult.Inserted).id
        repo.recordAttemptForSms(id, SendOutcome.Accepted(200))
        repo.recordAttemptForSms(id, SendOutcome.Retry(null, "network"))
        repo.requeue(id)
        assertEquals(SmsStatus.SENT, repo.get(id)!!.status)
        assertTrue(repo.queuedIds().isEmpty())
    }


    @Test fun queuedIdsIncludesErrorSmsWithQueuedDelivery() = runBlocking {
        val id = (repo.ingest(record(), SmsOrigin.RECEIVER, setOf(1L, 2L)) as IngestResult.Inserted).id
        val (a, _) = db.deliveryDao().forSms(id)
        repo.recordAttempt(a.id, SendOutcome.Rejected(400, null))
        assertEquals(SmsStatus.ERROR, repo.get(id)!!.status)
        assertEquals(listOf(id), repo.queuedIds())
        db.destinationDao().update(db.destinationDao().get(2L)!!.copy(deletedAt = now))
        assertTrue(repo.queuedIds().isEmpty())
    }

    @Test fun softDeleteHidesRowFromEveryListButKeepsItForDedup() = runBlocking {
        val t = now - 10_000L
        val queued = (repo.ingest(record(t, "queued $body"), SmsOrigin.RECEIVER, setOf(1L)) as IngestResult.Inserted).id
        val rejected = (repo.ingest(record(t - 5_000, "rejected $body"), SmsOrigin.RECEIVER, setOf(1L)) as IngestResult.Inserted).id
        repo.recordAttemptForSms(rejected, SendOutcome.Rejected(401, null))
        now += QueuePolicy.STUCK_AFTER_MS + 1L
        assertEquals(listOf(queued), repo.queuedIds())
        assertEquals(listOf(rejected), repo.idsWithAuthError())
        // The 401 row is covered by the token notification, so only the queued one is stuck.
        assertEquals(listOf(queued), repo.stuckToNotify().map { it.id })

        repo.softDelete(queued)
        repo.softDelete(rejected)

        assertTrue(repo.queuedIds().isEmpty())
        assertTrue(repo.idsWithAuthError().isEmpty())
        assertTrue(repo.stuckToNotify().isEmpty())
        assertTrue(repo.observeAll().first().isEmpty())
        assertEquals(null, repo.observe(queued).first())
        // The row is still there, with its key, so ingest recognises the SMS.
        assertEquals(now, repo.get(queued)!!.deletedAt)
        assertEquals(
            IngestResult.AlreadyKnown(queued),
            repo.ingest(record(t, "queued $body"), SmsOrigin.SWEEP, setOf(1L)),
        )
        // Same SMS seen through the 2 s fallback window (different timestamp, so another localKey).
        assertEquals(
            IngestResult.AlreadyKnown(queued),
            repo.ingest(record(t + 1_500, "queued $body"), SmsOrigin.SWEEP, setOf(1L)),
        )
        assertTrue(repo.isKnown(record(t, "queued $body")))
    }

    @Test fun deletedStuckRowIsNotNotified() = runBlocking {
        val id = (repo.ingest(record(), SmsOrigin.RECEIVER, setOf(1L)) as IngestResult.Inserted).id
        now += 2 * 3_600_000L
        assertEquals(listOf(id), repo.stuckToNotify(3_600_000L).map { it.id })
        repo.softDelete(id)
        assertTrue(repo.stuckToNotify(3_600_000L).isEmpty())
    }

    @Test fun requeueIgnoresDeletedRow() = runBlocking {
        val id = (repo.ingest(record(), SmsOrigin.RECEIVER, setOf(1L)) as IngestResult.Inserted).id
        repo.recordAttemptForSms(id, SendOutcome.Rejected(400, null))
        repo.softDelete(id)
        repo.requeue(id)
        assertEquals(SmsStatus.ERROR, repo.get(id)!!.status)
        assertTrue(repo.queuedIds().isEmpty())
    }

    @Test fun purgeOldRemovesDeletedRowsOlderThanCutoffWhateverTheirStatus() = runBlocking {
        val (queued, error, _) = oldRows()
        val recentQueued = (repo.ingest(record(now - 1_000, "recent $body"), SmsOrigin.RECEIVER, setOf(1L)) as IngestResult.Inserted).id
        inThePast(40) {
            repo.softDelete(queued)
            repo.softDelete(error)
        }
        repo.softDelete(recentQueued)

        repo.purgeOld(Retention.D30)

        assertEquals(null, repo.get(queued))
        assertEquals(null, repo.get(error))
        assertNotNull(repo.get(recentQueued))
    }

    @Test fun purgeOldForeverKeepsDeletedRows() = runBlocking {
        val (queued, _, _) = oldRows()
        inThePast(40) { repo.softDelete(queued) }
        repo.purgeOld(Retention.FOREVER)
        assertNotNull(repo.get(queued))
    }

    // receivedAt is the carrier time; the sweep window uses the phone date. A row the app saw
    // recently must survive the purge however old the carrier stamp is, or the next sweep
    // would ingest the SMS again and forward it twice.
    @Test fun purgeOldKeepsDeletedRowSeenRecentlyEvenWithOldCarrierTime() = runBlocking {
        val old = now - 40 * 86_400_000L
        val id = (repo.ingest(record(old, "deleted $body"), SmsOrigin.SWEEP, setOf(1L)) as IngestResult.Inserted).id
        repo.softDelete(id)
        repo.purgeOld(Retention.D30)
        assertNotNull(repo.get(id))
    }

    @Test fun purgeOldKeepsSentRowSeenRecentlyEvenWithOldCarrierTime() = runBlocking {
        val old = now - 40 * 86_400_000L
        val id = (repo.ingest(record(old, "sent $body"), SmsOrigin.SWEEP, setOf(1L)) as IngestResult.Inserted).id
        repo.recordAttemptForSms(id, SendOutcome.Accepted(200))
        repo.purgeOld(Retention.D30)
        assertNotNull(repo.get(id))
    }

    @Test fun purgeOldKeepsRowDeletedRecentlyEvenIfSeenLongAgo() = runBlocking {
        val id = inThePast(40) {
            (repo.ingest(record(now, "deleted $body"), SmsOrigin.SWEEP, setOf(1L)) as IngestResult.Inserted).id
        }
        repo.softDelete(id)
        repo.purgeOld(Retention.D30)
        assertNotNull(repo.get(id))
    }

    @Test fun idsWithAuthErrorIgnoresDeletedDestinations() = runBlocking {
        val id = (repo.ingest(record(), SmsOrigin.RECEIVER, setOf(1L)) as IngestResult.Inserted).id
        repo.recordAttemptForSms(id, SendOutcome.Rejected(401, null))
        assertEquals(listOf(id), repo.idsWithAuthError())
        db.destinationDao().update(db.destinationDao().get(1L)!!.copy(deletedAt = now))
        assertEquals(emptyList<Long>(), repo.idsWithAuthError())
    }

    @Test fun ingestWithOnlyDeletedDestinationStoresNothing() = runBlocking {
        db.destinationDao().update(db.destinationDao().get(1L)!!.copy(deletedAt = now))
        val result = repo.ingest(record(), SmsOrigin.RECEIVER, setOf(1L, 99L))
        assertEquals(IngestResult.NoDestination, result)
        assertEquals(false, repo.isKnown(record()))
    }

    @Test fun ingestSkipsDeletedDestinationButKeepsLiveOnes() = runBlocking {
        db.destinationDao().update(db.destinationDao().get(1L)!!.copy(deletedAt = now))
        val id = (repo.ingest(record(), SmsOrigin.RECEIVER, setOf(1L, 2L)) as IngestResult.Inserted).id
        assertEquals(listOf(2L), db.deliveryDao().forSms(id).map { it.destinationId })
    }

    @Test fun acceptedStoresRealCode() = runBlocking {
        val id = (repo.ingest(record(), SmsOrigin.RECEIVER, setOf(1L)) as IngestResult.Inserted).id
        repo.recordAttemptForSms(id, SendOutcome.Accepted(204))
        assertEquals(204, repo.deliveriesOf(id).single().lastHttpCode)
        assertEquals(204, repo.get(id)!!.lastHttpCode)
    }

    @Test fun invalidIsTerminalWithAttempt() = runBlocking {
        val id = (repo.ingest(record(), SmsOrigin.RECEIVER, setOf(1L)) as IngestResult.Inserted).id
        repo.recordAttemptForSms(id, SendOutcome.Invalid("template"))
        val delivery = repo.deliveriesOf(id).single()
        assertEquals(DeliveryStatus.ERROR, delivery.status)
        assertEquals("config_template", delivery.lastError)
        assertEquals(null, delivery.lastHttpCode)
        assertEquals(SmsStatus.ERROR, repo.get(id)!!.status)
        val attempts = repo.observeAttempts(id).first()
        assertEquals(1, attempts.size)
        assertEquals(null, attempts.single().httpCode)
    }

    @Test fun stuckSeesQueuedDeliveryUnderError() = runBlocking {
        val id = (repo.ingest(record(), SmsOrigin.RECEIVER, setOf(1L, 2L)) as IngestResult.Inserted).id
        val (a, _) = repo.deliveriesOf(id)
        repo.recordAttempt(a.id, SendOutcome.Rejected(401, null))
        assertTrue(repo.stuckToNotify(6 * 3_600_000L).isEmpty())
        now += 7 * 3_600_000L
        assertEquals(listOf(id), repo.stuckToNotify(6 * 3_600_000L).map { it.id })
    }
}
