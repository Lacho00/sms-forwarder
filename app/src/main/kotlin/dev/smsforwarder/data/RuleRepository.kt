package dev.smsforwarder.data

import androidx.room.withTransaction
import dev.smsforwarder.core.Clock
import dev.smsforwarder.core.RuleSpec
import dev.smsforwarder.core.SenderFilter
import dev.smsforwarder.core.SenderMode
import dev.smsforwarder.core.SimMatch
import kotlinx.coroutines.flow.Flow

/** Rules as the engine sees them, plus the sender list Settings shows (EXACT, sender-only rules to the primary). */
class RuleRepository(
    private val db: AppDatabase,
    private val destinations: DestinationRepository,
    private val clock: Clock,
) {
    private val dao = db.ruleDao()

    val senders: Flow<List<String>> = dao.observePrimaryExactSenders()

    suspend fun addSender(s: String) {
        val clean = s.trim()
        val normalized = SenderFilter.normalize(clean)
        if (normalized.isEmpty()) return
        db.withTransaction {
            val primary = destinations.ensurePrimary()
            if (dao.primaryExactSenders().any { SenderFilter.normalize(it) == normalized }) return@withTransaction
            val id = dao.insert(
                RuleEntity(
                    name = clean,
                    enabled = true,
                    senderMode = SenderMode.EXACT,
                    senderPattern = clean,
                    bodyMode = null,
                    bodyPattern = null,
                    sim = SimMatch.ANY,
                    createdAt = clock.now(),
                ),
            )
            dao.insertLinks(listOf(RuleDestinationEntity(id, primary)))
        }
    }

    suspend fun removeSender(s: String) {
        val normalized = SenderFilter.normalize(s)
        db.withTransaction {
            val primary = destinations.primary()?.id ?: return@withTransaction
            for (pattern in dao.primaryExactSenders()) {
                if (SenderFilter.normalize(pattern) == normalized) dao.deleteExactForDestination(pattern, primary)
            }
        }
    }

    /** Enabled rules whose destinations still exist; a rule left with none is dropped. Paused destinations stay. */
    suspend fun activeRules(): List<RuleSpec> = dao.activeWithDestinations().mapNotNull { r ->
        val ids = r.destinations.filter { it.deletedAt == null }.map { it.id }.toSet()
        if (ids.isEmpty()) return@mapNotNull null
        RuleSpec(
            id = r.rule.id,
            enabled = r.rule.enabled,
            senderMode = r.rule.senderMode,
            senderPattern = r.rule.senderPattern,
            bodyMode = r.rule.bodyMode,
            bodyPattern = r.rule.bodyPattern,
            sim = r.rule.sim,
            destinationIds = ids,
        )
    }

    suspend fun hasActiveRules(): Boolean = activeRules().isNotEmpty()
}
