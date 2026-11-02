package dev.smsforwarder.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import dev.smsforwarder.core.SmsStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface SmsDao {
    @Insert
    suspend fun insert(sms: SmsEntity): Long

    @Insert
    suspend fun insertAttempt(attempt: AttemptEntity): Long

    @Update
    suspend fun update(sms: SmsEntity)

    @Query("SELECT * FROM sms WHERE id = :id")
    suspend fun get(id: Long): SmsEntity?

    @Query("SELECT * FROM sms WHERE localKey = :localKey LIMIT 1")
    suspend fun findByLocalKey(localKey: String): SmsEntity?

    @Query(
        "SELECT * FROM sms WHERE senderNormalized = :senderNormalized " +
            "AND receivedAt BETWEEN :from AND :to",
    )
    suspend fun candidates(senderNormalized: String, from: Long, to: Long): List<SmsEntity>

    @Query("SELECT * FROM sms WHERE deletedAt IS NULL ORDER BY receivedAt DESC, id DESC")
    fun observeAll(): Flow<List<SmsEntity>>

    @Query("SELECT * FROM sms WHERE id = :id AND deletedAt IS NULL")
    fun observe(id: Long): Flow<SmsEntity?>

    @Query("SELECT * FROM attempt WHERE smsId = :smsId ORDER BY at DESC, id DESC")
    fun observeAttempts(smsId: Long): Flow<List<AttemptEntity>>

    @Query("SELECT id FROM sms WHERE status = :status AND deletedAt IS NULL ORDER BY receivedAt ASC")
    suspend fun idsWithStatus(status: SmsStatus): List<Long>

    @Query("SELECT id FROM sms WHERE status = :status AND lastHttpCode = :code AND deletedAt IS NULL ORDER BY receivedAt ASC")
    suspend fun idsWithStatusAndCode(status: SmsStatus, code: Int): List<Long>

    @Query("SELECT * FROM sms WHERE status != :sent AND stuckNotified = 0 AND deletedAt IS NULL")
    suspend fun unsentNotNotified(sent: SmsStatus = SmsStatus.SENT): List<SmsEntity>

    @Query("UPDATE sms SET stuckNotified = 1 WHERE id = :id")
    suspend fun markStuckNotified(id: Long)

    @Query("UPDATE sms SET deletedAt = :at WHERE id = :id AND deletedAt IS NULL")
    suspend fun softDelete(id: Long, at: Long)

    // Purged by when the app saw the SMS, not by the carrier stamp: the sweep window uses the phone
    // date, so an old receivedAt on a row still inside that window would let the sweep re-ingest it.
    // Rows the user deleted go at the cutoff whatever their status; the rest only when already sent.
    @Query(
        "DELETE FROM sms WHERE firstSeenAt < :cutoff AND " +
            "(status = :status OR (deletedAt IS NOT NULL AND deletedAt < :cutoff))",
    )
    suspend fun deleteOlderThan(status: SmsStatus, cutoff: Long): Int
}
