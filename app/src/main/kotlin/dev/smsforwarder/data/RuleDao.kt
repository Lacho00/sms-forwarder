package dev.smsforwarder.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface RuleDao {
    @Insert
    suspend fun insert(rule: RuleEntity): Long

    @Insert
    suspend fun insertLinks(links: List<RuleDestinationEntity>)

    /**
     * Callers must drop destinations with `deletedAt != null`; paused ones stay, since their
     * deliveries are queued without attempts.
     */
    @Transaction
    @Query("SELECT * FROM rule WHERE enabled = 1 ORDER BY id ASC")
    suspend fun activeWithDestinations(): List<RuleWithDestinations>

    /** Removes the sender-only exact rules for [pattern] that point at [destinationId]. */
    @Query(
        "DELETE FROM rule WHERE senderMode = 'EXACT' AND senderPattern = :pattern AND bodyMode IS NULL " +
            "AND id IN (SELECT ruleId FROM rule_destination WHERE destinationId = :destinationId)",
    )
    suspend fun deleteExactForDestination(pattern: String, destinationId: Long): Int
}
