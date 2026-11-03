package dev.smsforwarder.ui

import androidx.room.Room
import dev.smsforwarder.core.Clock
import dev.smsforwarder.core.DeliveryStatus
import dev.smsforwarder.core.HttpMethod
import dev.smsforwarder.core.SendOutcome
import dev.smsforwarder.core.SmsOrigin
import dev.smsforwarder.core.SmsRecord
import dev.smsforwarder.core.SmsStatus
import dev.smsforwarder.core.SuccessPolicy
import dev.smsforwarder.core.TemplateKind
import dev.smsforwarder.data.AppDatabase
import dev.smsforwarder.data.DestinationEntity
import dev.smsforwarder.data.IngestResult
import dev.smsforwarder.data.SmsRepository
import dev.smsforwarder.ui.detail.DetailUiState
import dev.smsforwarder.ui.detail.DetailViewModel
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DetailViewModelTest {
    private val now = 1_800_000_000_000L
    private lateinit var db: AppDatabase
    private lateinit var sms: SmsRepository
    private var smsId = 0L
    private val enqueued = kotlinx.coroutines.CompletableDeferred<Long>()
    private lateinit var vm: DetailViewModel

    @Before fun setUp() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        sms = SmsRepository(db, Clock { now })
        for (name in listOf("Main", "T2")) {
            db.destinationDao().insert(
                DestinationEntity(
                    name = name, enabled = true, template = TemplateKind.STANDARD_JSON, method = HttpMethod.POST,
                    bodyTemplate = null, bodyIsJson = true, publicHeaders = "[]",
                    successPolicy = SuccessPolicy.ANY_2XX, createdAt = 1L,
                ),
            )
        }
        smsId = (sms.ingest(SmsRecord("AAA", "TEST", now, null, null), SmsOrigin.RECEIVER, setOf(1L)) as IngestResult.Inserted).id
        vm = newVm()
    }

    private fun newVm() =
        DetailViewModel(
            smsId, sms, enqueueSend = { enqueued.complete(it) },
            destinations = db.destinationDao().observeLive(),
        )

    @After fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun await(cond: (DetailUiState) -> Boolean): DetailUiState =
        runBlocking { withTimeout(5_000) { vm.state.first(cond) } }

    @Test fun stateCarriesDeliveriesAndLiveDestinations() {
        val s = await { it.loaded && it.destinations.size == 2 && it.deliveries.size == 1 }
        assertEquals(listOf("Main"), s.deliveries.map { it.destinationName })
        assertEquals(listOf("Main", "T2"), s.destinations.map { it.name })
    }

    @Test fun resendEnqueues() {
        vm.resendTo(2L)
        assertEquals(smsId, runBlocking { withTimeout(5_000) { enqueued.await() } })
        val s = await { it.deliveries.size == 2 }
        assertEquals(listOf(DeliveryStatus.QUEUED, DeliveryStatus.QUEUED), s.deliveries.map { it.status })
    }

    @Test fun failingActionsDoNotCrash() {
        val broken = DetailViewModel(smsId, sms, enqueueSend = { throw IllegalStateException("boom") })
        broken.resendTo(2L)
        broken.retry()
        broken.delete()
        // Reaching here without an uncaught exception is the check; the stored state is still readable.
        assertEquals(smsId, runBlocking { sms.get(smsId)!!.id })
    }

    @Test fun errorCodeComesFromTheFailingDelivery() {
        runBlocking {
            sms.resendTo(smsId, 2L)
            val byDestination = sms.deliveriesOf(smsId).associateBy { it.destinationId }
            sms.recordAttempt(byDestination[1L]!!.id, SendOutcome.Rejected(403, null))
            // The latest attempt is the other destination's 200, which used to leak into the aggregate label.
            sms.recordAttempt(byDestination[2L]!!.id, SendOutcome.Accepted(200))
        }
        val s = await { it.deliveries.size == 2 && it.deliveries.all { d -> d.status != DeliveryStatus.QUEUED } }
        assertEquals(SmsStatus.ERROR, s.sms!!.status)
        assertEquals(403, s.label!!.httpCode)
    }

    @Test fun errorWithoutFailingCodeShowsNoCode() {
        runBlocking {
            sms.resendTo(smsId, 2L)
            val byDestination = sms.deliveriesOf(smsId).associateBy { it.destinationId }
            sms.recordAttempt(byDestination[1L]!!.id, SendOutcome.Invalid("template"))
            sms.recordAttempt(byDestination[2L]!!.id, SendOutcome.Accepted(200))
        }
        val s = await { it.deliveries.size == 2 && it.deliveries.all { d -> d.status != DeliveryStatus.QUEUED } }
        assertEquals(null, s.label!!.httpCode)
    }
}
