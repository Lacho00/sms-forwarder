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

    /** Deliveries of every SMS that is not deleted, by id (the home list groups them by SMS). */
    @Query(
        "SELECT d.* FROM delivery d JOIN sms s ON s.id = d.smsId WHERE s.deletedAt IS NULL ORDER BY d.id ASC",
    )
    fun observeAll(): Flow<List<DeliveryEntity>>

    @Query(
        "SELECT DISTINCT d.smsId FROM delivery d JOIN sms s ON s.id = d.smsId " +
            "JOIN destination t ON t.id = d.destinationId AND t.deletedAt IS NULL " +
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
            "JOIN destination t ON t.id = d.destinationId AND t.deletedAt IS NULL " +
            "WHERE d.status = 'ERROR' AND d.lastHttpCode IN (401, 403) " +
            "AND s.deletedAt IS NULL ORDER BY s.receivedAt ASC",
    )
    suspend fun idsAnyAuthRejected(): List<Long>

    /** SMS ids with a QUEUED delivery to [destinationId] (the SMS is not hidden). */
    @Query(
        "SELECT DISTINCT d.smsId FROM delivery d JOIN sms s ON s.id = d.smsId " +
            "WHERE d.destinationId = :destinationId AND d.status = 'QUEUED' AND s.deletedAt IS NULL " +
            "ORDER BY s.receivedAt ASC",
    )
    suspend fun smsIdsQueuedFor(destinationId: Long): List<Long>

    /** QUEUED plus ERROR deliveries to [destinationId] of visible SMS. */
    @Query(
        "SELECT COUNT(*) FROM delivery d JOIN sms s ON s.id = d.smsId " +
            "WHERE d.destinationId = :destinationId AND d.status IN ('QUEUED', 'ERROR') AND s.deletedAt IS NULL",
    )
    suspend fun pendingCountFor(destinationId: Long): Int

    @Query(
        "SELECT d.destinationId AS destinationId, " +
            "SUM(CASE WHEN d.status = 'QUEUED' THEN 1 ELSE 0 END) AS queued, " +
            "SUM(CASE WHEN d.status = 'ERROR' THEN 1 ELSE 0 END) AS errors " +
            "FROM delivery d JOIN sms s ON s.id = d.smsId " +
            "WHERE d.destinationId IS NOT NULL AND s.deletedAt IS NULL GROUP BY d.destinationId",
    )
    fun observeCounts(): Flow<List<DestinationCounts>>

    /** Every QUEUED or ERROR delivery to [destinationId], hidden SMS included (the delete cancels them all). */
    @Query("SELECT * FROM delivery WHERE destinationId = :destinationId AND status IN ('QUEUED', 'ERROR')")
    suspend fun unfinishedTo(destinationId: Long): List<DeliveryEntity>

    /** SMS ids with an ERROR delivery to [destinationId] that failed on its configuration. */
    @Query(
        "SELECT DISTINCT d.smsId FROM delivery d JOIN sms s ON s.id = d.smsId " +
            "WHERE d.destinationId = :destinationId AND d.status = 'ERROR' AND substr(d.lastError, 1, 7) = 'config_' " +
            "AND s.deletedAt IS NULL ORDER BY s.receivedAt ASC",
    )
    suspend fun idsConfigErrors(destinationId: Long): List<Long>

    /** Delivery counts per status, visible SMS only. */
    @Query(
        "SELECT d.status AS status, COUNT(*) AS count FROM delivery d JOIN sms s ON s.id = d.smsId " +
            "WHERE s.deletedAt IS NULL GROUP BY d.status",
    )
    suspend fun countsByStatus(): List<DeliveryStatusCount>

    /** ERROR deliveries of visible SMS grouped by their last HTTP code and error text (the text is bucketed by the caller). */
    @Query(
        "SELECT d.lastHttpCode AS lastHttpCode, d.lastError AS lastError, COUNT(*) AS n FROM delivery d " +
            "JOIN sms s ON s.id = d.smsId WHERE d.status = 'ERROR' AND s.deletedAt IS NULL " +
            "GROUP BY d.lastHttpCode, d.lastError",
    )
    suspend fun errorCodes(): List<ErrorCodeCount>

    /** Delivery counts per destination and status, visible SMS only. */
    @Query(
        "SELECT d.destinationId AS destinationId, d.status AS status, COUNT(*) AS count FROM delivery d " +
            "JOIN sms s ON s.id = d.smsId WHERE d.destinationId IS NOT NULL AND s.deletedAt IS NULL " +
            "GROUP BY d.destinationId, d.status",
    )
    suspend fun countsByDestination(): List<DestinationStatusCount>
}

data class ErrorCodeCount(val lastHttpCode: Int?, val lastError: String?, val n: Int)

data class DestinationStatusCount(val destinationId: Long, val status: DeliveryStatus, val count: Int)

data class DeliveryStatusCount(val status: DeliveryStatus, val count: Int)

data class DestinationCounts(val destinationId: Long, val queued: Int, val errors: Int)
