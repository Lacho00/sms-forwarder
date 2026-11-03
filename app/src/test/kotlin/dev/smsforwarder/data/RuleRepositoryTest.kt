package dev.smsforwarder.data

import androidx.room.Room
import dev.smsforwarder.core.DeliveryStatus
import dev.smsforwarder.core.BodyMode
import dev.smsforwarder.core.DestinationPresets
import dev.smsforwarder.core.HttpMethod
import dev.smsforwarder.core.RuleDraft
import dev.smsforwarder.core.SenderMode
import dev.smsforwarder.core.SimMatch
import dev.smsforwarder.core.SmsOrigin
import dev.smsforwarder.core.SmsRecord
import dev.smsforwarder.core.SuccessPolicy
import dev.smsforwarder.core.TemplateKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class RuleRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repos: TestRepos
    private val rules get() = repos.rules

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repos = TestRepos(db)
    }

    @After fun tearDown() {
        db.close()
    }

    @Test fun addSenderCreatesExactRuleToPrimary() = runBlocking {
        rules.addSender(" OPERADOR ")

        val primary = repos.destinations.primary()!!
        assertEquals("Primary", primary.name)
        assertEquals(TemplateKind.STANDARD_JSON, primary.template)
        assertEquals(HttpMethod.POST, primary.method)
        assertEquals(SuccessPolicy.ANY_2XX, primary.successPolicy)
        val active = rules.activeRules().single()
        assertEquals(SenderMode.EXACT, active.senderMode)
        assertEquals("OPERADOR", active.senderPattern)
        assertEquals(setOf(primary.id), active.destinationIds)
    }

    @Test fun addSenderTwiceNormalizedIsOne() = runBlocking {
        rules.addSender("OPERADOR")
        rules.addSender(" operador")
        assertEquals(1, rules.activeRules().size)
        assertEquals(listOf("OPERADOR"), rules.senders.first())
    }

    @Test fun removeSenderDeletesRule() = runBlocking {
        rules.addSender("OPERADOR")
        rules.removeSender("operador ")
        assertTrue(rules.activeRules().isEmpty())
        assertFalse(rules.hasActiveRules())
        assertTrue(rules.senders.first().isEmpty())
    }

    @Test fun sendersFlowListsPatterns() = runBlocking {
        rules.addSender("UNO")
        rules.addSender("DOS")
        assertEquals(listOf("UNO", "DOS"), rules.senders.first())
    }

    @Test fun ruleToDeletedDestinationContributesNothing() = runBlocking {
        rules.addSender("OPERADOR")
        val primary = repos.destinations.primary()!!
        db.destinationDao().update(primary.copy(deletedAt = 5L))
        assertTrue(rules.activeRules().isEmpty())
    }

    @Test fun pausedDestinationKeepsItsRules() = runBlocking {
        rules.addSender("OPERADOR")
        val primary = repos.destinations.primary()!!
        db.destinationDao().update(primary.copy(enabled = false))
        assertEquals(1, rules.activeRules().size)
    }

    private fun draft(vararg dest: Long, sender: String = "AAA", mode: SenderMode = SenderMode.EXACT) = RuleDraft(
        name = "R", enabled = true, senderMode = mode, senderPattern = sender,
        bodyMode = null, bodyPattern = null, sim = SimMatch.ANY, destinationIds = dest.toSet(),
    )

    private suspend fun second(): Long = repos.destinations.create(DestinationPresets.standardJson("Two", "https://h.example/2", "tok"))

    @Test fun createAndReadBack() = runBlocking {
        val p = repos.destinations.ensurePrimary()
        val d = draft(p)
        val id = rules.create(d)
        assertEquals(d, rules.draft(id))
    }

    @Test fun notContainsPatternIsTrimmed() = runBlocking {
        val p = repos.destinations.ensurePrimary()
        val id = rules.create(draft(p).copy(bodyMode = BodyMode.NOT_CONTAINS, bodyPattern = "  login "))
        assertEquals("login", rules.draft(id)!!.bodyPattern)
        assertEquals(BodyMode.NOT_CONTAINS, rules.draft(id)!!.bodyMode)
    }

    @Test fun invalidRuleRejected() = runBlocking {
        val p = repos.destinations.ensurePrimary()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { rules.create(draft(p, sender = "(", mode = SenderMode.REGEX)) }
        }
        assertTrue(rules.observeAll().first().isEmpty())
        assertEquals(0, db.query("SELECT COUNT(*) FROM rule_destination", null).use { it.moveToFirst(); it.getInt(0) })
    }

    @Test fun updateReplacesDestinations() = runBlocking {
        val p = repos.destinations.ensurePrimary()
        val two = second()
        val id = rules.create(draft(p))
        rules.update(id, draft(two))
        assertEquals(setOf(two), rules.activeRules().single().destinationIds)
    }

    @Test fun disabledRuleNotActive() = runBlocking {
        val p = repos.destinations.ensurePrimary()
        val id = rules.create(draft(p))
        rules.setEnabled(id, false)
        assertTrue(rules.activeRules().isEmpty())
        assertFalse(rules.hasActiveRules())
    }

    @Test fun deleteRemovesRuleAndLinks() = runBlocking {
        val p = repos.destinations.ensurePrimary()
        val id = rules.create(draft(p))
        rules.delete(id)
        assertNull(rules.draft(id))
        assertEquals(0, db.ruleDao().linkCount(id))
        assertTrue(rules.observeAll().first().isEmpty())
    }

    @Test fun editingKeepsQueuedDeliveries() = runBlocking {
        val p = repos.destinations.ensurePrimary()
        val two = second()
        val id = rules.create(draft(p))
        val smsId = (repos.sms.ingest(SmsRecord("AAA", "x", 1L, null, null), SmsOrigin.RECEIVER, setOf(p)) as IngestResult.Inserted).id
        rules.update(id, draft(two))
        assertEquals(DeliveryStatus.QUEUED, repos.sms.deliveriesOf(smsId).single().status)
        rules.delete(id)
        assertEquals(DeliveryStatus.QUEUED, repos.sms.deliveriesOf(smsId).single().status)
    }

    @Test fun removeSenderKeepsMultiDestinationRule() = runBlocking {
        val p = repos.destinations.ensurePrimary()
        val two = second()
        val id = rules.create(draft(p, two))
        rules.removeSender("AAA")
        assertEquals(setOf(two), rules.draft(id)!!.destinationIds)
        assertTrue(rules.senders.first().isEmpty())
        val solo = rules.create(draft(p, sender = "BBB"))
        rules.removeSender("BBB")
        assertNull(rules.draft(solo))
    }

    @Test fun createDropsDeletedAndUnknownDestinations() = runBlocking {
        val p = repos.destinations.ensurePrimary()
        val gone = second()
        db.destinationDao().update(db.destinationDao().get(gone)!!.copy(deletedAt = 5L))
        val id = rules.create(draft(p, gone, 9999L))
        assertEquals(setOf(p), rules.draft(id)!!.destinationIds)
        assertEquals(1, db.ruleDao().linkCount(id))
    }

    @Test fun updateDropsDeletedAndUnknownDestinations() = runBlocking {
        val p = repos.destinations.ensurePrimary()
        val gone = second()
        db.destinationDao().update(db.destinationDao().get(gone)!!.copy(deletedAt = 5L))
        val id = rules.create(draft(p))
        assertTrue(rules.update(id, draft(gone, 9999L)))
        assertEquals(emptySet<Long>(), rules.draft(id)!!.destinationIds)
        assertEquals(0, db.ruleDao().linkCount(id))
    }

    @Test fun regexPatternIsNotTrimmed() = runBlocking {
        val p = repos.destinations.ensurePrimary()
        val id = rules.create(draft(p, sender = "AAA\\ ", mode = SenderMode.REGEX))
        assertEquals("AAA\\ ", rules.draft(id)!!.senderPattern)
    }

    @Test fun nonRegexPatternsAreTrimmed() = runBlocking {
        val p = repos.destinations.ensurePrimary()
        val id = rules.create(draft(p, sender = " AAA "))
        assertEquals("AAA", rules.draft(id)!!.senderPattern)
    }

    @Test fun updateAndSetEnabledReportUnknownRule() = runBlocking {
        val p = repos.destinations.ensurePrimary()
        assertFalse(rules.update(4242L, draft(p)))
        assertFalse(rules.setEnabled(4242L, false))
        val id = rules.create(draft(p))
        assertTrue(rules.setEnabled(id, false))
        assertTrue(rules.update(id, draft(p)))
    }
}
