package dev.smsforwarder.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DestinationDao {
    @Insert
    suspend fun insert(destination: DestinationEntity): Long

    @Update
    suspend fun update(destination: DestinationEntity)

    @Query("DELETE FROM destination WHERE id = :id")
    suspend fun hardDelete(id: Long)

    @Query("SELECT * FROM destination WHERE id = :id")
    suspend fun get(id: Long): DestinationEntity?

    /** The primary destination: lowest live standard-JSON one, paused or not. */
    @Query("SELECT * FROM destination WHERE deletedAt IS NULL AND template = 'STANDARD_JSON' ORDER BY id ASC LIMIT 1")
    suspend fun primary(): DestinationEntity?

    /** Ids of the destinations that can send now: not deleted and not paused. */
    @Query("SELECT id FROM destination WHERE deletedAt IS NULL AND enabled = 1")
    suspend fun activeIds(): List<Long>

    /** Destinations not deleted, paused ones included, by id. */
    @Query("SELECT * FROM destination WHERE deletedAt IS NULL ORDER BY id ASC")
    fun observeLive(): Flow<List<DestinationEntity>>
}
