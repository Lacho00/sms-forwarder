package dev.smsforwarder.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

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

    /** Patterns of every sender-only exact rule, enabled or not; the 1.1 import uses it to skip the ones that exist. */
    @Query("SELECT senderPattern FROM rule WHERE senderMode = 'EXACT' AND bodyMode IS NULL")
    suspend fun exactSenderOnlyPatterns(): List<String>

    /** Removes the sender-only exact rules for [pattern] that point at [destinationId]. */
    @Query(
        "DELETE FROM rule WHERE senderMode = 'EXACT' AND senderPattern = :pattern AND bodyMode IS NULL " +
            "AND id IN (SELECT ruleId FROM rule_destination WHERE destinationId = :destinationId)",
    )
    suspend fun deleteExactForDestination(pattern: String, destinationId: Long): Int

    /** Sender-only exact patterns of the primary destination (lowest id not deleted), oldest rule first. */
    @Query(PRIMARY_EXACT_SENDERS)
    fun observePrimaryExactSenders(): Flow<List<String>>

    @Query(PRIMARY_EXACT_SENDERS)
    suspend fun primaryExactSenders(): List<String>
}

private const val PRIMARY_EXACT_SENDERS =
    "SELECT senderPattern FROM rule WHERE senderMode = 'EXACT' AND bodyMode IS NULL AND id IN " +
        "(SELECT ruleId FROM rule_destination WHERE destinationId = " +
        "(SELECT id FROM destination WHERE deletedAt IS NULL ORDER BY id ASC LIMIT 1)) ORDER BY id ASC"
