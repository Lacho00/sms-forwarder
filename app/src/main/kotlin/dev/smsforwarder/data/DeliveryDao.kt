package dev.smsforwarder.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import dev.smsforwarder.core.DeliveryStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface DeliveryDao {
    @Insert
    suspend fun insertAll(deliveries: List<DeliveryEntity>): List<Long>

    @Update
    suspend fun update(delivery: DeliveryEntity)

    @Query("SELECT * FROM delivery WHERE id = :id")
    suspend fun get(id: Long): DeliveryEntity?

    @Query("SELECT * FROM delivery WHERE smsId = :smsId ORDER BY id ASC")
    suspend fun forSms(smsId: Long): List<DeliveryEntity>

    @Query("SELECT * FROM delivery WHERE smsId = :smsId AND status = 'QUEUED' ORDER BY id ASC")
    suspend fun pendingForSms(smsId: Long): List<DeliveryEntity>

    @Query("SELECT * FROM delivery WHERE smsId = :smsId ORDER BY id ASC")
    fun observeForSms(smsId: Long): Flow<List<DeliveryEntity>>

    @Query(
        "SELECT DISTINCT d.smsId FROM delivery d JOIN sms s ON s.id = d.smsId " +
            "WHERE d.status = :status AND s.deletedAt IS NULL ORDER BY s.receivedAt ASC",
    )
    suspend fun smsIdsWithStatus(status: DeliveryStatus): List<Long>

    /** SMS ids with an ERROR delivery to [destinationId] that the endpoint rejected with 401 or 403 (token rejection). */
    @Query(
        "SELECT DISTINCT d.smsId FROM delivery d JOIN sms s ON s.id = d.smsId " +
            "WHERE d.destinationId = :destinationId AND d.status = 'ERROR' AND d.lastHttpCode IN (401, 403) " +
            "AND s.deletedAt IS NULL ORDER BY s.receivedAt ASC",
    )
    suspend fun idsAuthRejected(destinationId: Long): List<Long>

    /** SMS ids with an ERROR delivery to any destination that the endpoint rejected with 401 or 403. */
    @Query(
        "SELECT DISTINCT d.smsId FROM delivery d JOIN sms s ON s.id = d.smsId " +
            "WHERE d.status = 'ERROR' AND d.lastHttpCode IN (401, 403) " +
            "AND s.deletedAt IS NULL ORDER BY s.receivedAt ASC",
    )
    suspend fun idsAnyAuthRejected(): List<Long>
}
