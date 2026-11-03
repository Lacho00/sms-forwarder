package dev.smsforwarder.data

import androidx.room.withTransaction
import dev.smsforwarder.core.Clock
import dev.smsforwarder.core.RuleSpec
import dev.smsforwarder.core.SenderFilter
import dev.smsforwarder.core.SenderMode
import dev.smsforwarder.core.SimMatch
import dev.smsforwarder.core.RuleDraft
import dev.smsforwarder.core.validateRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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
                if (SenderFilter.normalize(pattern) != normalized) continue
                for (ruleId in dao.exactIdsForDestination(pattern, primary)) {
                    dao.deleteLink(ruleId, primary)
                    if (dao.linkCount(ruleId) == 0) dao.delete(ruleId)
                }
            }
        }
    }

    /** Every rule with its live destinations; deleted ones are left out. */
    fun observeAll(): Flow<List<RuleWithDestinations>> = dao.observeAllWithDestinations().map { list ->
        list.map { r -> r.copy(destinations = r.destinations.filter { it.deletedAt == null }) }
    }

    suspend fun draft(id: Long): RuleDraft? {
        val r = dao.getWithDestinations(id) ?: return null
        return RuleDraft(
            name = r.rule.name,
            enabled = r.rule.enabled,
            senderMode = r.rule.senderMode,
            senderPattern = r.rule.senderPattern,
            bodyMode = r.rule.bodyMode,
            bodyPattern = r.rule.bodyPattern,
            sim = r.rule.sim,
            destinationIds = r.destinations.filter { it.deletedAt == null }.map { it.id }.toSet(),
        )
    }

    suspend fun create(r: RuleDraft): Long {
        require(validateRule(r).isEmpty()) { "invalid rule" }
        return db.withTransaction {
            val id = dao.insert(entity(0, r, clock.now()))
            dao.insertLinks(r.destinationIds.sorted().map { RuleDestinationEntity(id, it) })
            id
        }
    }

    suspend fun update(id: Long, r: RuleDraft) {
        require(validateRule(r).isEmpty()) { "invalid rule" }
        db.withTransaction {
            val current = dao.getWithDestinations(id)?.rule ?: return@withTransaction
            dao.update(entity(id, r, current.createdAt))
            dao.deleteLinks(id)
            dao.insertLinks(r.destinationIds.sorted().map { RuleDestinationEntity(id, it) })
        }
    }

    suspend fun setEnabled(id: Long, enabled: Boolean) {
        db.withTransaction {
            val current = dao.getWithDestinations(id)?.rule ?: return@withTransaction
            dao.update(current.copy(enabled = enabled))
        }
    }

    /** Deliveries already created by the rule are untouched: they hang from the SMS and the destination, not the rule. */
    suspend fun delete(id: Long) = dao.delete(id)

    private fun entity(id: Long, r: RuleDraft, createdAt: Long) = RuleEntity(
        id = id,
        name = r.name.trim(),
        enabled = r.enabled,
        senderMode = r.senderMode,
        senderPattern = r.senderPattern.trim(),
        bodyMode = r.bodyMode,
        bodyPattern = if (r.bodyMode == null) null else r.bodyPattern,
        sim = r.sim,
        createdAt = createdAt,
    )

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
