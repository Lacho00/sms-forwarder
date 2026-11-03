package dev.smsforwarder.ui

import androidx.room.Room
import dev.smsforwarder.core.BodyMode
import dev.smsforwarder.core.Clock
import dev.smsforwarder.core.DestinationPresets
import dev.smsforwarder.core.RuleError
import dev.smsforwarder.core.SenderMode
import dev.smsforwarder.core.SimMatch
import dev.smsforwarder.data.AppDatabase
import dev.smsforwarder.data.TestRepos
import dev.smsforwarder.ui.rules.RuleEditorState
import dev.smsforwarder.ui.rules.RuleEditorViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class RuleEditorViewModelTest {
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

    private fun vm(id: Long? = null) = RuleEditorViewModel(
        id, repos.rules, repos.destinations, scope,
    )

    // Room runs its queries on its own executor, so the ViewModel finishes on another thread.
    private fun RuleEditorViewModel.await(cond: (RuleEditorState) -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!cond(state.value) && System.currentTimeMillis() < end) Thread.sleep(10)
        assertTrue("timed out waiting for the state", cond(state.value))
    }

    private fun dest(name: String) = runBlocking {
        repos.destinations.create(DestinationPresets.custom(name).copy(url = "https://h.example/$name", body = "{}"))
    }

    @Test fun invalidRegexBlocksSave() {
        val d = dest("One")
        val vm = vm()
        vm.await { it.loaded && it.destinations.size == 1 }
        vm.update { it.copy(name = "R", senderMode = SenderMode.REGEX, senderPattern = "(", destinationIds = setOf(d)) }
        vm.save()
        assertTrue(RuleError.SENDER_REGEX_INVALID in vm.state.value.errors)
        assertFalse(vm.state.value.done)
        assertEquals(0, runBlocking { repos.rules.observeAll().first() }.size)
    }

    @Test fun noDestinationBlocksSave() {
        val vm = vm()
        vm.await { it.loaded }
        vm.update { it.copy(name = "R", senderPattern = "AAA") }
        vm.save()
        assertEquals(setOf(RuleError.NO_DESTINATIONS), vm.state.value.errors)
        assertFalse(vm.state.value.done)
        assertEquals(0, runBlocking { repos.rules.observeAll().first() }.size)
    }

    @Test fun saveCreatesRule() {
        val d = dest("One")
        val vm = vm()
        vm.await { it.loaded && it.destinations.size == 1 }
        vm.update { it.copy(name = "R", senderPattern = " AAA ", destinationIds = setOf(d)) }
        vm.setBodyMode(BodyMode.NOT_CONTAINS)
        vm.update { it.copy(bodyPattern = "login", sim = SimMatch.SIM2) }
        vm.save()
        vm.await { it.done }
        val active = runBlocking { repos.rules.activeRules() }
        assertEquals(1, active.size)
        assertEquals("AAA", active[0].senderPattern)
        assertEquals(BodyMode.NOT_CONTAINS, active[0].bodyMode)
        assertEquals("login", active[0].bodyPattern)
        assertEquals(SimMatch.SIM2, active[0].sim)
        assertEquals(setOf(d), active[0].destinationIds)
    }

    @Test fun editLoadsExisting() {
        val d = dest("One")
        val id = runBlocking {
            repos.rules.create(
                dev.smsforwarder.core.RuleDraft("R", false, SenderMode.CONTAINS, "BANK", BodyMode.CONTAINS, "pay", SimMatch.SIM1, setOf(d)),
            )
        }
        val vm = vm(id)
        vm.await { it.loaded && it.destinations.isNotEmpty() }
        val s = vm.state.value
        assertFalse(s.isNew)
        assertEquals("R", s.draft.name)
        assertFalse(s.draft.enabled)
        assertEquals(BodyMode.CONTAINS, s.draft.bodyMode)
        assertEquals(setOf(d), s.draft.destinationIds)
        vm.update { it.copy(name = "R2") }
        vm.save()
        vm.await { it.done }
        assertEquals("R2", runBlocking { repos.rules.draft(id) }!!.name)
    }

    @Test fun pausedDestinationsListed() {
        val a = dest("One")
        val b = dest("Two")
        runBlocking { repos.destinations.setEnabled(b, false) }
        val vm = vm()
        vm.await { it.loaded && it.destinations.size == 2 }
        assertEquals(listOf(a, b), vm.state.value.destinations.map { it.id })
        assertFalse(vm.state.value.destinations[1].enabled)
    }

    @Test fun deleteAsksFirst() {
        val d = dest("One")
        val id = runBlocking {
            repos.rules.create(dev.smsforwarder.core.RuleDraft("R", true, SenderMode.EXACT, "AAA", null, null, SimMatch.ANY, setOf(d)))
        }
        val vm = vm(id)
        vm.await { it.loaded && it.draft.name == "R" }
        vm.confirmDelete()
        assertNotNull(runBlocking { repos.rules.draft(id) })
        vm.requestDelete()
        assertTrue(vm.state.value.deleteAsked)
        vm.dismissDelete()
        vm.confirmDelete()
        assertNotNull(runBlocking { repos.rules.draft(id) })
        vm.requestDelete()
        vm.confirmDelete()
        vm.await { it.done }
        assertNull(runBlocking { repos.rules.draft(id) })
    }

    @Test fun resetDropsEverythingTyped() {
        val d = dest("One")
        val vm = vm()
        vm.await { it.loaded && it.destinations.size == 1 }
        vm.update { it.copy(name = "Typed", senderPattern = "AAA", destinationIds = setOf(d)) }
        vm.save()
        vm.await { it.done }
        vm.reset()
        vm.await { it.loaded && it.destinations.size == 1 }
        assertFalse(vm.state.value.done)
        assertEquals("", vm.state.value.draft.name)
        assertEquals(emptySet<Long>(), vm.state.value.draft.destinationIds)
    }

    @Test fun switchingBodyModeToAnyDropsThePattern() {
        val vm = vm()
        vm.await { it.loaded }
        vm.setBodyMode(BodyMode.CONTAINS)
        vm.update { it.copy(bodyPattern = "x") }
        vm.setBodyMode(BodyMode.NOT_CONTAINS)
        assertEquals("x", vm.state.value.draft.bodyPattern)
        vm.setBodyMode(null)
        assertNull(vm.state.value.draft.bodyMode)
        assertNull(vm.state.value.draft.bodyPattern)
    }
}
