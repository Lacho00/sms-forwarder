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
    /** Every requested destination is missing or deleted, so nothing was stored. */
    data object NoDestination : IngestResult
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

        val targets = destinationIds.sorted().mapNotNull { destinationId ->
            destinations.get(destinationId)?.takeIf { it.deletedAt == null }
        }
        if (targets.isEmpty()) return@withTransaction IngestResult.NoDestination

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
            targets.map { destination ->
                DeliveryEntity(
                    smsId = id,
                    destinationId = destination.id,
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

    /**
     * Records one attempt for a delivery and recomputes the SMS aggregate in the same transaction.
     * A delivery that is already SENT is never undone, and deliveries to deleted destinations do not count
     * toward the aggregate, so they cannot keep the SMS from reaching SENT.
     */
    suspend fun recordAttempt(deliveryId: Long, outcome: SendOutcome) {
        db.withTransaction {
            val delivery = deliveries.get(deliveryId) ?: return@withTransaction
            if (delivery.status == DeliveryStatus.SENT) return@withTransaction
            val sms = dao.get(delivery.smsId) ?: return@withTransaction
            val now = clock.now()
            val (httpCode, error) = when (outcome) {
                SendOutcome.Accepted -> 200 to null
                is SendOutcome.Retry -> outcome.httpCode to outcome.reason
                is SendOutcome.Rejected -> outcome.httpCode to outcome.errorCode
            }
            val status = when (outcome) {
                SendOutcome.Accepted -> DeliveryStatus.SENT
                is SendOutcome.Retry -> DeliveryStatus.QUEUED
                is SendOutcome.Rejected -> DeliveryStatus.ERROR
            }
            dao.insertAttempt(
                AttemptEntity(smsId = sms.id, at = now, httpCode = httpCode, error = error, deliveryId = deliveryId),
            )
            deliveries.update(
                delivery.copy(
                    status = status,
                    attempts = delivery.attempts + 1,
                    lastHttpCode = httpCode,
                    lastError = error,
                    sentAt = if (status == DeliveryStatus.SENT) now else delivery.sentAt,
                ),
            )
            val all = deliveries.forSms(sms.id)
            val counted = all.filter { d ->
                d.destinationId == null || destinations.get(d.destinationId)?.deletedAt == null
            }
            val aggregate = aggregateStatus(counted.map { it.status })
            dao.update(
                sms.copy(
                    status = aggregate,
                    attempts = all.sumOf { it.attempts },
                    lastHttpCode = httpCode,
                    lastError = error,
                    sentAt = if (aggregate == SmsStatus.SENT) sms.sentAt ?: now else sms.sentAt,
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

    /** QUEUED deliveries of the SMS, whatever the SMS aggregate says. */
    suspend fun pendingDeliveries(smsId: Long): List<DeliveryEntity> = deliveries.pendingForSms(smsId)

    suspend fun deliveriesOf(smsId: Long): List<DeliveryEntity> = deliveries.forSms(smsId)

    suspend fun delivery(id: Long): DeliveryEntity? = deliveries.get(id)

    /** SMS ids waiting on a rejected token for [destinationId]. */
    suspend fun idsAuthRejected(destinationId: Long): List<Long> = deliveries.idsAuthRejected(destinationId)

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
