package dev.smsforwarder.data

import androidx.room.Room
import dev.smsforwarder.core.SenderMode
import dev.smsforwarder.core.SuccessPolicy
import dev.smsforwarder.core.TemplateKind
import dev.smsforwarder.core.HttpMethod
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
        assertTrue(!rules.hasActiveRules())
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
}
