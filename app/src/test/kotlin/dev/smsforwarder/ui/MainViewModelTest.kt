package dev.smsforwarder.ui

import androidx.room.Room
import dev.smsforwarder.core.Clock
import dev.smsforwarder.core.DeliveryStatus
import dev.smsforwarder.core.HttpMethod
import dev.smsforwarder.core.SuccessPolicy
import dev.smsforwarder.core.TemplateKind
import dev.smsforwarder.data.DestinationEntity
import dev.smsforwarder.core.SendOutcome
import dev.smsforwarder.core.SmsOrigin
import dev.smsforwarder.core.SmsRecord
import dev.smsforwarder.core.StatusFilter
import dev.smsforwarder.data.AppDatabase
import dev.smsforwarder.data.Credentials
import dev.smsforwarder.data.IngestResult
import dev.smsforwarder.data.recordAttemptForSms
import dev.smsforwarder.data.SecretStore
import dev.smsforwarder.data.TestRepos
import dev.smsforwarder.data.SettingsRepository
import dev.smsforwarder.data.SmsRepository
import dev.smsforwarder.health.HealthChecker
import dev.smsforwarder.ui.main.MainUiState
import dev.smsforwarder.ui.main.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MainViewModelTest {
    private class NoSecrets : SecretStore {
        override suspend fun get(): Credentials? = null
        override suspend fun set(c: Credentials) = Unit
    }

    private val now = 1_800_000_000_000L
    private lateinit var db: AppDatabase
    private lateinit var sms: SmsRepository
    private lateinit var repos: TestRepos
    private lateinit var settings: SettingsRepository
    private lateinit var vm: MainViewModel
    private var queuedId = 0L
    private var errorId = 0L
    private var otherSenderId = 0L

    private suspend fun add(sender: String, text: String, at: Long): Long =
        (sms.ingest(SmsRecord(sender, text, at, null, null), SmsOrigin.RECEIVER, setOf(1L)) as IngestResult.Inserted).id

    @Before fun setUp() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        sms = SmsRepository(db, Clock { now })
        val file = File(context.cacheDir, "main-vm-${UUID.randomUUID()}.preferences_pb")
        repos = TestRepos(db)
        settings = SettingsRepository(context, repos.destinations, repos.rules, sms, scheduler = {}, storeFile = file)
        repos.destinations.ensurePrimary()
        queuedId = add("SENDERONE", "Code 1111", now - 3_000)
        errorId = add("SENDERONE", "Code 2222", now - 2_000)
        otherSenderId = add("SENDERTWO", "Code 3333", now - 1_000)
        sms.recordAttemptForSms(errorId, SendOutcome.Rejected(400, null))
        vm = MainViewModel(sms, settings, HealthChecker(context, sms, repos.destinations, repos.rules, settings), repos.destinations, repos.rules)
    }

    @After fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun await(cond: (MainUiState) -> Boolean): MainUiState =
        runBlocking { withTimeout(5_000) { vm.state.first(cond) } }

    @Test fun queryKeepsOnlyThatSender() {
        vm.setQuery("senderone")
        val s = await { it.query == "senderone" && it.rows.size == 2 }
        assertEquals(setOf(queuedId, errorId), s.rows.map { it.id }.toSet())
    }

    @Test fun errorFilterKeepsOnlyErrors() {
        vm.setFilter(StatusFilter.ERROR)
        val s = await { it.filter == StatusFilter.ERROR && it.rows.size == 1 }
        assertEquals(listOf(errorId), s.rows.map { it.id })
    }

    @Test fun queryAndFilterCombineWithAnd() {
        vm.setQuery("SENDERONE")
        vm.setFilter(StatusFilter.QUEUED)
        val s = await { it.query == "SENDERONE" && it.filter == StatusFilter.QUEUED && it.rows.size == 1 }
        assertEquals(listOf(queuedId), s.rows.map { it.id })
        vm.setFilter(StatusFilter.ERROR)
        vm.setQuery("SENDERTWO")
        val none = await { it.query == "SENDERTWO" && it.filter == StatusFilter.ERROR }
        assertEquals(emptyList<Long>(), none.rows.map { it.id })
    }

    @Test fun countersIgnoreSearchAndDeletedRows() {
        vm.setQuery("SENDERTWO")
        val filtered = await { it.query == "SENDERTWO" }
        assertEquals(2, filtered.queued)
        assertEquals(1, filtered.errors)

        runBlocking { sms.softDelete(queuedId) }
        val s = await { it.queued == 1 }
        assertEquals(1, s.errors)
    }

    @Test fun hideTextFollowsTheSetting() {
        runBlocking { settings.setHideText(true) }
        assertEquals(true, await { it.hideText }.hideText)
    }

    private fun addDestination(name: String): Long = runBlocking {
        db.destinationDao().insert(
            DestinationEntity(
                name = name, enabled = true, template = TemplateKind.STANDARD_JSON, method = HttpMethod.POST,
                bodyTemplate = null, bodyIsJson = true, publicHeaders = "[]",
                successPolicy = SuccessPolicy.ANY_2XX, createdAt = 1L,
            ),
        )
    }

    @Test fun rowShowsPerDestinationStatus() {
        val second = addDestination("T2")
        val id = runBlocking {
            val sid = (sms.ingest(SmsRecord("SENDERFOUR", "Code 4444", now, null, null), SmsOrigin.RECEIVER, setOf(1L, second)) as IngestResult.Inserted).id
            sms.recordAttempt(sms.deliveriesOf(sid).first().id, SendOutcome.Accepted(204))
            sid
        }
        val s = await { st -> st.rows.firstOrNull { it.id == id }?.deliveries?.size == 2 }
        val chips = s.rows.first { it.id == id }.deliveries
        assertEquals(listOf("Primary", "T2"), chips.map { it.destinationName })
        assertEquals(listOf(DeliveryStatus.SENT, DeliveryStatus.QUEUED), chips.map { it.status })
        assertEquals(listOf<Int?>(204, null), chips.map { it.httpCode })
        assertEquals(listOf(false, false), chips.map { it.configError })
    }

    @Test fun configErrorFlagged() {
        val id = runBlocking {
            val sid = (sms.ingest(SmsRecord("SENDERFIVE", "Code 5555", now, null, null), SmsOrigin.RECEIVER, setOf(1L)) as IngestResult.Inserted).id
            sms.recordAttempt(sms.deliveriesOf(sid).first().id, SendOutcome.Invalid("url"))
            sid
        }
        val s = await { st -> st.rows.firstOrNull { it.id == id }?.deliveries?.firstOrNull()?.status == DeliveryStatus.ERROR }
        assertEquals(true, s.rows.first { it.id == id }.deliveries.single().configError)
    }

    @Test fun destinationFilterKeepsOnlyMatching() {
        val second = addDestination("T2")
        runBlocking {
            sms.resendTo(queuedId, second)
            sms.resendTo(errorId, second)
        }
        vm.setDestinationFilter(second)
        val s = await { it.destinationFilter == second && it.rows.size == 2 }
        assertEquals(setOf(queuedId, errorId), s.rows.map { it.id }.toSet())
        assertEquals(listOf("Primary", "T2"), s.destinations.map { it.second })
    }

    @Test fun destinationFilterLeavesCountersAlone() {
        val second = addDestination("T2")
        runBlocking { sms.resendTo(queuedId, second) }
        val before = await { it.destinations.size == 2 }
        vm.setDestinationFilter(second)
        val s = await { it.destinationFilter == second && it.rows.size == 1 }
        assertEquals(before.queued, s.queued)
        assertEquals(before.errors, s.errors)
    }

    @Test fun deletedDestinationFilterResets() {
        val second = addDestination("T2")
        runBlocking { sms.resendTo(queuedId, second) }
        vm.setDestinationFilter(second)
        await { it.destinationFilter == second && it.rows.size == 1 }
        runBlocking { repos.destinations.delete(second) }
        val s = await { it.destinationFilter == null && it.rows.size == 3 }
        assertEquals(null, s.destinationFilter)
    }

    @Test fun healthRefreshesWhenDestinationAndRuleAppearAfterCreation() = runBlocking {
        val id = repos.destinations.create(
            dev.smsforwarder.core.DestinationPresets.custom("Other").copy(url = "https://h.example/x"),
        )
        repos.secrets.map.remove(id)
        repos.rules.addSender("AAA")
        db.ruleDao().insertLinks(
            listOf(dev.smsforwarder.data.RuleDestinationEntity(db.ruleDao().activeWithDestinations().first().rule.id, id)),
        )
        val s = await { dev.smsforwarder.health.HealthIssue.NOT_CONFIGURED in it.issues }
        assertEquals(true, dev.smsforwarder.health.HealthIssue.NOT_CONFIGURED in s.issues)
    }
}
