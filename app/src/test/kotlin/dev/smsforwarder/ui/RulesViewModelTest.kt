package dev.smsforwarder.ui

import androidx.room.Room
import dev.smsforwarder.core.BodyMode
import dev.smsforwarder.core.Clock
import dev.smsforwarder.core.DestinationPresets
import dev.smsforwarder.core.RuleDraft
import dev.smsforwarder.core.SenderMode
import dev.smsforwarder.core.SimMatch
import dev.smsforwarder.data.AppDatabase
import dev.smsforwarder.data.RuleEntity
import dev.smsforwarder.data.TestRepos
import dev.smsforwarder.ui.rules.RulesViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class RulesViewModelTest {
    private lateinit var db: AppDatabase
    private lateinit var repos: TestRepos

    // Cancelled before the database closes, so no collector outlives it.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repos = TestRepos(db, Clock { 1_800_000_000_000L })
    }

    @After fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun vm() = RulesViewModel(repos.rules, scope)

    private suspend fun dest(name: String) =
        repos.destinations.create(DestinationPresets.custom(name).copy(url = "https://h.example/$name", body = "{}"))

    private fun draft(name: String, vararg dest: Long) = RuleDraft(
        name, true, SenderMode.EXACT, "AAA", null, null, SimMatch.ANY, dest.toSet(),
    )

    @Test fun rowsShowDestinationNames() = runBlocking {
        val a = dest("One")
        val b = dest("Two")
        repos.rules.create(draft("R1", a, b))
        val rows = withTimeout(5_000) { vm().rows.first { it.size == 1 } }
        assertEquals("R1", rows[0].name)
        assertEquals(listOf("One", "Two"), rows[0].destinationNames)
        assertEquals("AAA", rows[0].senderPattern)
    }

    @Test fun deletedDestinationLeavesAnEmptyNameList() = runBlocking {
        val a = dest("One")
        repos.rules.create(draft("R1", a))
        repos.destinations.delete(a)
        val rows = withTimeout(5_000) { vm().rows.first { it.size == 1 && it[0].destinationNames.isEmpty() } }
        assertEquals(emptyList<String>(), rows[0].destinationNames)
    }

    @Test fun toggleDisables() = runBlocking {
        val a = dest("One")
        val id = repos.rules.create(draft("R1", a))
        val vm = vm()
        vm.setEnabled(id, false)
        val rows = withTimeout(5_000) { vm.rows.first { it.size == 1 && !it[0].enabled } }
        assertFalse(rows[0].enabled)
    }

    // Bypasses the validating repository: a rule saved before the RE2/J migration.
    private fun stored(senderMode: SenderMode, senderPattern: String, bodyMode: BodyMode?, bodyPattern: String?) = runBlocking {
        db.ruleDao().insert(
            RuleEntity(
                name = "R", enabled = true, senderMode = senderMode, senderPattern = senderPattern,
                bodyMode = bodyMode, bodyPattern = bodyPattern, sim = SimMatch.ANY, createdAt = 0,
            ),
        )
    }

    private fun onlyRow() = runBlocking { withTimeout(5_000) { vm().rows.first { it.size == 1 } }[0] }

    @Test fun invalidSenderRegexIsFlagged() {
        stored(SenderMode.REGEX, "(a)\\1", null, null)
        assertTrue(onlyRow().invalidRegex)
    }

    @Test fun invalidBodyRegexIsFlagged() {
        stored(SenderMode.EXACT, "AAA", BodyMode.REGEX, "a(?=b)")
        assertTrue(onlyRow().invalidRegex)
    }

    @Test fun validRegexIsNotFlagged() {
        stored(SenderMode.REGEX, "^a.*", BodyMode.REGEX, "^a.*")
        assertFalse(onlyRow().invalidRegex)
    }

    @Test fun containsModeNeverCountsAsRegex() {
        stored(SenderMode.CONTAINS, "(a)\\1", BodyMode.CONTAINS, "a(?=b)")
        assertFalse(onlyRow().invalidRegex)
    }
}
