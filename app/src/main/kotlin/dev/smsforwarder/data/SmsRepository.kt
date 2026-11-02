package dev.smsforwarder.data

import androidx.room.withTransaction
import dev.smsforwarder.core.Clock
import dev.smsforwarder.core.DeliveryStatus
import dev.smsforwarder.core.QueuePolicy
import dev.smsforwarder.core.Retention
import dev.smsforwarder.core.SendOutcome
import dev.smsforwarder.core.SenderFilter
import dev.smsforwarder.core.SmsIdentity
import dev.smsforwarder.core.SmsOrigin
import dev.smsforwarder.core.SmsRecord
import dev.smsforwarder.core.SmsStatus
import dev.smsforwarder.core.aggregateStatus
import dev.smsforwarder.core.isAuthRejection
import kotlinx.coroutines.flow.Flow

sealed interface IngestResult {
    data class Inserted(val id: Long) : IngestResult
    data class AlreadyKnown(val id: Long) : IngestResult
}

class SmsRepository(private val db: AppDatabase, private val clock: Clock) {
    private val dao = db.smsDao()
    private val deliveries = db.deliveryDao()
    private val destinations = db.destinationDao()

    suspend fun ingest(record: SmsRecord, origin: SmsOrigin, destinationIds: Set<Long>): IngestResult = db.withTransaction {
        val key = SmsIdentity.localKey(record)
        dao.findByLocalKey(key)?.let { return@withTransaction IngestResult.AlreadyKnown(it.id) }

        val normalized = SenderFilter.normalize(record.sender)
        val window = SmsIdentity.FALLBACK_WINDOW_MS
        val match = dao.candidates(normalized, record.receivedAt - window, record.receivedAt + window)
            .firstOrNull { SmsIdentity.isSameSms(it.toRecord(), record) }
        if (match != null) return@withTransaction IngestResult.AlreadyKnown(match.id)

        val id = dao.insert(
            SmsEntity(
                localKey = key,
                sender = record.sender,
                senderNormalized = normalized,
                body = record.body,
                receivedAt = record.receivedAt,
                phoneDate = record.phoneDate,
                subId = record.subId,
                origin = origin,
                status = SmsStatus.QUEUED,
                firstSeenAt = clock.now(),
            ),
        )
        deliveries.insertAll(
            destinationIds.sorted().mapNotNull { destinationId ->
                val destination = destinations.get(destinationId) ?: return@mapNotNull null
                DeliveryEntity(
                    smsId = id,
                    destinationId = destinationId,
                    destinationName = destination.name,
                    status = DeliveryStatus.QUEUED,
                )
            },
        )
        IngestResult.Inserted(id)
    }

    /** Same identity check as [ingest], without inserting: true if this SMS is already stored. */
    suspend fun isKnown(record: SmsRecord): Boolean {
        if (dao.findByLocalKey(SmsIdentity.localKey(record)) != null) return true
        val window = SmsIdentity.FALLBACK_WINDOW_MS
        return dao.candidates(
            SenderFilter.normalize(record.sender),
            record.receivedAt - window,
            record.receivedAt + window,
        ).any { SmsIdentity.isSameSms(it.toRecord(), record) }
    }

    suspend fun get(id: Long): SmsEntity? = dao.get(id)

    fun observeAll(): Flow<List<SmsEntity>> = dao.observeAll()

    fun observe(id: Long): Flow<SmsEntity?> = dao.observe(id)

    fun observeAttempts(smsId: Long): Flow<List<AttemptEntity>> = dao.observeAttempts(smsId)

    suspend fun recordAttempt(id: Long, outcome: SendOutcome) {
        db.withTransaction {
            val sms = dao.get(id) ?: return@withTransaction
            val now = clock.now()
            // A late result from an overlapping worker must not undo a delivered SMS.
            if (sms.status == SmsStatus.SENT) {
                if (outcome !is SendOutcome.Accepted) return@withTransaction
            }
            val (httpCode, error) = when (outcome) {
                SendOutcome.Accepted -> 200 to null
                is SendOutcome.Retry -> outcome.httpCode to outcome.reason
                is SendOutcome.Rejected -> outcome.httpCode to outcome.errorCode
            }
            val status = when (outcome) {
                SendOutcome.Accepted -> SmsStatus.SENT
                is SendOutcome.Retry -> SmsStatus.QUEUED
                is SendOutcome.Rejected -> SmsStatus.ERROR
            }
            dao.insertAttempt(AttemptEntity(smsId = id, at = now, httpCode = httpCode, error = error))
            // Until the per-destination send path lands, the single request is tracked on the primary delivery.
            destinations.primary()?.let { primary ->
                deliveries.forSms(id).firstOrNull { it.destinationId == primary.id }?.let { d ->
                    val deliveryStatus = when (status) {
                        SmsStatus.SENT -> DeliveryStatus.SENT
                        SmsStatus.QUEUED -> DeliveryStatus.QUEUED
                        SmsStatus.ERROR -> DeliveryStatus.ERROR
                    }
                    deliveries.update(
                        d.copy(
                            status = deliveryStatus,
                            attempts = d.attempts + 1,
                            lastHttpCode = httpCode,
                            lastError = error,
                            sentAt = if (deliveryStatus == DeliveryStatus.SENT) now else d.sentAt,
                        ),
                    )
                }
            }
            dao.update(
                sms.copy(
                    status = status,
                    attempts = sms.attempts + 1,
                    lastHttpCode = httpCode,
                    lastError = error,
                    sentAt = if (status == SmsStatus.SENT) now else sms.sentAt,
                ),
            )
        }
    }

    /** Puts every unsent delivery of the SMS back to QUEUED; delivered ones stay. */
    suspend fun requeue(id: Long) {
        db.withTransaction {
            val sms = dao.get(id) ?: return@withTransaction
            if (sms.status == SmsStatus.SENT || sms.deletedAt != null) return@withTransaction
            val all = deliveries.forSms(id)
            all.filter { it.status != DeliveryStatus.SENT }.forEach {
                deliveries.update(it.copy(status = DeliveryStatus.QUEUED))
            }
            resetSms(sms, all.map { if (it.status == DeliveryStatus.SENT) it.status else DeliveryStatus.QUEUED })
        }
    }

    /** Requeues only the 401/403 deliveries to [destinationId]; returns the affected SMS ids. */
    suspend fun requeueAuthErrors(destinationId: Long): List<Long> = db.withTransaction {
        val ids = deliveries.idsAuthRejected(destinationId)
        for (id in ids) {
            val sms = dao.get(id) ?: continue
            val all = deliveries.forSms(id)
            all.filter {
                it.destinationId == destinationId && it.status == DeliveryStatus.ERROR && isAuthRejection(it.lastHttpCode)
            }.forEach { deliveries.update(it.copy(status = DeliveryStatus.QUEUED)) }
            val after = deliveries.forSms(id).map { it.status }
            resetSms(sms, after)
        }
        ids
    }

    // The stuck clock restarts: firstSeenAt only feeds the stuck check.
    private suspend fun resetSms(sms: SmsEntity, statuses: List<DeliveryStatus>) {
        dao.update(
            sms.copy(status = aggregateStatus(statuses), stuckNotified = false, firstSeenAt = clock.now()),
        )
    }

    /** Hides the row from the app. It is kept so ingest still finds it and the sweep does not re-forward the SMS. */
    suspend fun softDelete(id: Long) = dao.softDelete(id, clock.now())

    suspend fun queuedIds(): List<Long> = dao.idsWithStatus(SmsStatus.QUEUED)

    suspend fun idsWithAuthError(): List<Long> = deliveries.idsAnyAuthRejected()

    suspend fun purgeOld(retention: Retention = Retention.D90) {
        val cutoff = retention.cutoff(clock.now()) ?: return
        dao.deleteOlderThan(SmsStatus.SENT, cutoff)
    }

    // 401 rows are covered by the token notification, so they are left out here.
    suspend fun stuckToNotify(stuckAfterMs: Long = QueuePolicy.STUCK_AFTER_MS): List<SmsEntity> {
        val now = clock.now()
        return dao.unsentNotNotified().filter {
            !(it.status == SmsStatus.ERROR && it.lastHttpCode == 401) &&
                QueuePolicy.isStuck(it.status, it.lastHttpCode, it.firstSeenAt, now, stuckAfterMs)
        }
    }

    suspend fun markStuckNotified(id: Long) = dao.markStuckNotified(id)
}
