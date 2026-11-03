package dev.smsforwarder.ui

import androidx.room.Room
import dev.smsforwarder.core.Clock
import dev.smsforwarder.core.DestinationPresets
import dev.smsforwarder.core.SendOutcome
import dev.smsforwarder.core.SmsOrigin
import dev.smsforwarder.core.SmsRecord
import dev.smsforwarder.core.TemplateKind
import dev.smsforwarder.data.AppDatabase
import dev.smsforwarder.data.IngestResult
import dev.smsforwarder.data.TestRepos
import dev.smsforwarder.ui.destinations.DestinationsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DestinationsViewModelTest {
    private val clock = Clock { 1_800_000_000_000L }
    private lateinit var db: AppDatabase
    private lateinit var repos: TestRepos
    private val enqueued = java.util.concurrent.CopyOnWriteArrayList<List<Long>>()

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repos = TestRepos(db, clock)
    }

    @After fun tearDown() {
        db.close()
    }

    private fun vm() = DestinationsViewModel(repos.destinations, { enqueued += it }, CoroutineScope(Dispatchers.Unconfined))

    private fun draft(name: String) = DestinationPresets.custom(name).copy(url = "https://h.example/$name", body = "{}")

    private var next = 0
    private suspend fun ingest(vararg destinations: Long): Long {
        next++
        val record = SmsRecord("AAA", "text $next", clock.now() - next * 10_000L, null, null)
        return (repos.sms.ingest(record, SmsOrigin.RECEIVER, destinations.toSet()) as IngestResult.Inserted).id
    }

    @Test fun listShowsCounts() = runBlocking {
        val a = repos.destinations.create(draft("One"))
        val b = repos.destinations.create(draft("Two"))
        ingest(a)
        ingest(a)
        val failed = ingest(a)
        repos.sms.recordAttempt(repos.sms.deliveriesOf(failed).first().id, SendOutcome.Rejected(400, null))
        val rows = withTimeout(5_000) { vm().rows.first { r -> r.size == 2 && r.first().queued == 2 } }
        assertEquals(listOf("One", "Two"), rows.map { it.name })
        assertEquals(TemplateKind.CUSTOM, rows[0].template)
        assertEquals(2, rows[0].queued)
        assertEquals(1, rows[0].errors)
        assertEquals(0, rows[1].queued)
        assertEquals(0, rows[1].errors)
        assertEquals(b, rows[1].id)
    }

    @Test fun resumeEnqueuesQueued() = runBlocking {
        val a = repos.destinations.create(draft("One"))
        val s1 = ingest(a)
        val s2 = ingest(a)
        val vm = vm()
        vm.setEnabled(a, false)
        assertFalse(vm.rows.first { it.isNotEmpty() && !it[0].enabled }[0].enabled)
        assertEquals(emptyList<List<Long>>(), enqueued)
        vm.setEnabled(a, true)
        val end = System.currentTimeMillis() + 5_000
        while (enqueued.isEmpty() && System.currentTimeMillis() < end) Thread.sleep(10)
        assertEquals(1, enqueued.size)
        assertEquals(setOf(s1, s2), enqueued[0].toSet())
    }
}
