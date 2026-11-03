package dev.smsforwarder.ui

import androidx.room.Room
import dev.smsforwarder.core.Clock
import dev.smsforwarder.core.DeliveryStatus
import dev.smsforwarder.core.HttpMethod
import dev.smsforwarder.core.SmsOrigin
import dev.smsforwarder.core.SmsRecord
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
    private val enqueued = mutableListOf<Long>()
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
        vm = DetailViewModel(
            smsId, sms, enqueueSend = { enqueued += it },
            destinations = db.destinationDao().observeLive(),
        )
    }

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
        val s = await { it.deliveries.size == 2 }
        assertEquals(listOf(DeliveryStatus.QUEUED, DeliveryStatus.QUEUED), s.deliveries.map { it.status })
        assertEquals(listOf(smsId), enqueued)
    }
}
