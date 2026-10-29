package dev.smsforwarder.data

import androidx.room.withTransaction
import dev.smsforwarder.core.Clock
import dev.smsforwarder.core.QueuePolicy
import dev.smsforwarder.core.SendOutcome
import dev.smsforwarder.core.SenderFilter
import dev.smsforwarder.core.SmsIdentity
import dev.smsforwarder.core.SmsOrigin
import dev.smsforwarder.core.SmsRecord
import dev.smsforwarder.core.SmsStatus
import kotlinx.coroutines.flow.Flow

sealed interface IngestResult {
    data class Inserted(val id: Long) : IngestResult
    data class AlreadyKnown(val id: Long) : IngestResult
}

class SmsRepository(private val db: AppDatabase, private val clock: Clock) {
    private val dao = db.smsDao()

    suspend fun ingest(record: SmsRecord, origin: SmsOrigin): IngestResult = db.withTransaction {
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
        IngestResult.Inserted(id)
    }

    suspend fun get(id: Long): SmsEntity? = dao.get(id)

    fun observeAll(): Flow<List<SmsEntity>> = dao.observeAll()

    fun observeAttempts(smsId: Long): Flow<List<AttemptEntity>> = dao.observeAttempts(smsId)

    suspend fun recordAttempt(id: Long, outcome: SendOutcome) {
        db.withTransaction {
            val sms = dao.get(id) ?: return@withTransaction
            val now = clock.now()
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

    suspend fun requeue(id: Long) {
        db.withTransaction {
            val sms = dao.get(id) ?: return@withTransaction
            dao.update(sms.copy(status = SmsStatus.QUEUED, stuckNotified = false))
        }
    }

    suspend fun queuedIds(): List<Long> = dao.idsWithStatus(SmsStatus.QUEUED)

    suspend fun idsWithError401(): List<Long> = dao.idsWithStatusAndCode(SmsStatus.ERROR, 401)

    suspend fun purgeOld() {
        dao.deleteOlderThan(SmsStatus.SENT, QueuePolicy.retentionCutoff(clock.now()))
    }

    // 401 rows are covered by the token notification, so they are left out here.
    suspend fun stuckToNotify(): List<SmsEntity> {
        val now = clock.now()
        return dao.unsentNotNotified().filter {
            !(it.status == SmsStatus.ERROR && it.lastHttpCode == 401) &&
                QueuePolicy.isStuck(it.status, it.lastHttpCode, it.firstSeenAt, now)
        }
    }

    suspend fun markStuckNotified(id: Long) = dao.markStuckNotified(id)
}
