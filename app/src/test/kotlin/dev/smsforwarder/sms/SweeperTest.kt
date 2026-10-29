package dev.smsforwarder.sms

import androidx.room.Room
import dev.smsforwarder.core.Clock
import dev.smsforwarder.core.QueuePolicy
import dev.smsforwarder.core.SmsOrigin
import dev.smsforwarder.core.SmsRecord
import dev.smsforwarder.data.AppDatabase
import dev.smsforwarder.data.Credentials
import dev.smsforwarder.data.SecretStore
import dev.smsforwarder.data.SettingsRepository
import dev.smsforwarder.data.SmsEntity
import dev.smsforwarder.data.SmsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.UUID

private class FakeInboxReader : InboxReader {
    var rows: List<InboxRow>? = emptyList()
    var anyRow: Boolean? = true
    val requested = mutableListOf<Long>()

    override fun readSince(fromDate: Long): List<InboxRow>? {
        requested += fromDate
        return rows
    }

    override fun hasAnyRow(): Boolean? = anyRow
}

private class NoSecretStore : SecretStore {
    override suspend fun get(): Credentials? = null
    override suspend fun set(c: Credentials) = Unit
}

@RunWith(RobolectricTestRunner::class)
class SweeperTest {
    private val now = 1_800_000_000_000L
    private val clock = Clock { now }
    private val body = "OPERADOR: Se ha comprado un plan de datos de 2.5GB valido por 30 dias, " +
        "se activa con el primer uso. Se desconto 360.00. Saldo actual 140.25"

    private lateinit var db: AppDatabase
    private lateinit var sms: SmsRepository
    private lateinit var settings: SettingsRepository
    private val reader = FakeInboxReader()
    private val enqueued = mutableListOf<Long>()
    private val recovered = mutableListOf<List<SmsEntity>>()
    private lateinit var sweeper: Sweeper

    @Before fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        sms = SmsRepository(db, clock)
        val file = File(context.cacheDir, "sweep-${UUID.randomUUID()}.preferences_pb")
        settings = SettingsRepository(context, NoSecretStore(), sms, scheduler = {}, storeFile = file)
        sweeper = Sweeper(reader, sms, settings, clock, { enqueued += it }, { recovered += it })
    }

    @After fun tearDown() {
        db.close()
    }

    private suspend fun configure(floor: Long? = now - 100 * 86_400_000L, cursor: Long? = null) {
        settings.setOnboardingDone()
        settings.addSender("OPERADOR")
        floor?.let { settings.setSweepFloor(it) }
        cursor?.let { settings.setSweepCursor(it) }
    }

    private fun row(date: Long, dateSent: Long = 0L, address: String = "OPERADOR") =
        InboxRow(address, body, date, dateSent, null)

    @Test fun sweepBeforeOnboardingDoesNothing() = runBlocking {
        settings.addSender("OPERADOR")
        reader.rows = listOf(row(now - 1_000))
        val result = sweeper.sweep()
        assertEquals(SweepResult(0, false), result)
        assertTrue(reader.requested.isEmpty())
        assertTrue(sms.queuedIds().isEmpty())
    }

    @Test fun sweepNeverReadsBeforeFloor() = runBlocking {
        val floor = now - 5 * 3_600_000L
        configure(floor = floor, cursor = floor + 3_600_000L)
        sweeper.sweep()
        assertEquals(listOf(floor), reader.requested)
    }

    @Test fun sweepWithoutCursorReadsFromFloor() = runBlocking {
        val floor = now - 5 * 3_600_000L
        configure(floor = floor)
        sweeper.sweep()
        assertEquals(listOf(floor), reader.requested)
    }

    @Test fun sweepIngestsMatchingSendersSinceCursorMinus24h() = runBlocking {
        val cursor = now - 3_600_000L
        configure(cursor = cursor)
        reader.rows = listOf(row(now - 2_000), row(now - 1_000, address = "OTRO"))
        val result = sweeper.sweep()
        assertEquals(listOf(cursor - QueuePolicy.SWEEP_OVERLAP_MS), reader.requested)
        assertEquals(SweepResult(1, false), result)
        assertEquals(1, sms.queuedIds().size)
    }

    @Test fun sweepSkipsSmsAlreadyFromReceiverAndDoesNotNotify() = runBlocking {
        configure()
        val t = now - 10_000
        sms.ingest(SmsRecord("OPERADOR", body, t, null, null), SmsOrigin.RECEIVER)
        reader.rows = listOf(row(date = t + 1_500))
        val result = sweeper.sweep()
        assertEquals(0, result.recovered)
        assertTrue(enqueued.isEmpty())
        assertTrue(recovered.isEmpty())
        assertEquals(1, sms.queuedIds().size)
    }

    @Test fun sweepRecoversMissingAndCallsOnRecovered() = runBlocking {
        configure()
        reader.rows = listOf(row(now - 5_000))
        val result = sweeper.sweep()
        assertEquals(1, result.recovered)
        assertEquals(1, enqueued.size)
        val saved = sms.get(enqueued.single())!!
        assertEquals(SmsOrigin.SWEEP, saved.origin)
        assertEquals(listOf(saved.id), recovered.single().map { it.id })
    }

    @Test fun sweepUsesDateWhenDateSentIsZero() = runBlocking {
        configure()
        reader.rows = listOf(
            row(date = now - 9_000, dateSent = 0L),
            row(date = now - 5_000, dateSent = now - 7_000).copy(body = "$body bis"),
        )
        sweeper.sweep()
        val saved = enqueued.map { sms.get(it)!! }.sortedBy { it.receivedAt }
        assertEquals(listOf(now - 9_000, now - 7_000), saved.map { it.receivedAt })
    }

    @Test fun sweepAdvancesCursorToMaxDateOrNow() = runBlocking {
        configure()
        reader.rows = listOf(row(now - 9_000), row(now - 5_000))
        sweeper.sweep()
        assertEquals(now - 5_000, settings.sweepCursor())

        reader.rows = emptyList()
        sweeper.sweep()
        assertEquals(now, settings.sweepCursor())
    }

    @Test fun nullInboxReportsUnreadable() = runBlocking {
        configure()
        reader.rows = null
        val result = sweeper.sweep()
        assertTrue(result.inboxUnreadable)
        assertNull(settings.sweepCursor())
    }

    @Test fun emptyWholeInboxWithPermissionIsUnreadable() = runBlocking {
        configure()
        reader.rows = emptyList()
        reader.anyRow = false
        assertTrue(sweeper.sweep().inboxUnreadable)
    }

    @Test fun emptyWindowButNonEmptyInboxIsReadable() = runBlocking {
        configure()
        reader.rows = emptyList()
        reader.anyRow = true
        assertFalse(sweeper.sweep().inboxUnreadable)
        assertTrue(settings.senders.first().isNotEmpty())
    }
}
