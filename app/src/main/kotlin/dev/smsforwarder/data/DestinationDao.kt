package dev.smsforwarder.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface DestinationDao {
    @Insert
    suspend fun insert(destination: DestinationEntity): Long

    @Update
    suspend fun update(destination: DestinationEntity)

    @Query("SELECT * FROM destination WHERE id = :id")
    suspend fun get(id: Long): DestinationEntity?

    @Query("SELECT * FROM destination WHERE enabled = 1 AND deletedAt IS NULL ORDER BY id ASC LIMIT 1")
    suspend fun firstActive(): DestinationEntity?
}
