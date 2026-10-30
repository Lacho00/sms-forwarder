package dev.smsforwarder.ui

import androidx.room.Room
import dev.smsforwarder.core.Clock
import dev.smsforwarder.core.QueuePolicy
import dev.smsforwarder.core.SmsOrigin
import dev.smsforwarder.core.SmsRecord
import dev.smsforwarder.data.AppDatabase
import dev.smsforwarder.data.Credentials
import dev.smsforwarder.data.SecretStore
import dev.smsforwarder.data.SettingsRepository
import dev.smsforwarder.data.SmsRepository
import dev.smsforwarder.sms.InboxReader
import dev.smsforwarder.sms.InboxRow
import dev.smsforwarder.ui.recovery.RecoveryViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class RecoveryViewModelTest {
    private class FakeReader : InboxReader {
        var rows: List<InboxRow>? = emptyList()
        val requested = mutableListOf<Long>()

        override fun readSince(fromDate: Long): List<InboxRow>? {
            requested += fromDate
            return rows
        }

        override fun hasAnyRow(): Boolean? = true
    }

    private class NoSecrets : SecretStore {
        override suspend fun get(): Credentials? = null
        override suspend fun set(c: Credentials) = Unit
    }

    private val now = 1_800_000_000_000L
    private val clock = Clock { now }
    private val body = "OPERADOR: Se ha comprado un plan de datos de 2.5GB valido por 30 dias, " +
        "se activa con el primer uso. Se desconto 360.00. Saldo actual 140.25"

    private lateinit var db: AppDatabase
    private lateinit var sms: SmsRepository
    private lateinit var settings: SettingsRepository
    private val reader = FakeReader()
    private val enqueued = mutableListOf<Long>()
    private lateinit var vm: RecoveryViewModel

    @Before fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        sms = SmsRepository(db, clock)
        val file = File(context.cacheDir, "recovery-${UUID.randomUUID()}.preferences_pb")
        settings = SettingsRepository(context, NoSecrets(), sms, scheduler = {}, storeFile = file)
        vm = RecoveryViewModel(reader, sms, settings, clock) { enqueued += it }
    }

    @After fun tearDown() {
        db.close()
    }

    private fun row(date: Long, text: String = body, address: String = "OPERADOR", dateSent: Long = 0L) =
        InboxRow(address, text, date, dateSent, null)

    @Test fun recoveryListsLast7DaysOfAllowedSendersNoneSelected() = runBlocking {
        settings.addSender("OPERADOR")
        reader.rows = listOf(
            row(now - 2 * 86_400_000L, "$body uno"),
            row(now - 1_000, "$body dos"),
            row(now - 500, "$body tres", address = "OTRO"),
        )
        vm.load()

        assertEquals(listOf(now - QueuePolicy.MANUAL_RECOVERY_MS), reader.requested)
        val items = vm.candidates.value
        assertEquals(2, items.size)
        assertEquals(listOf(now - 1_000, now - 2 * 86_400_000L), items.map { it.receivedAt })
        assertTrue(items.none { it.selected })
        assertTrue(items.none { it.alreadyKnown })
    }

    @Test fun recoveryMarksAlreadyKnownAndDisablesThem() = runBlocking {
        settings.addSender("OPERADOR")
        val t = now - 10_000
        sms.ingest(SmsRecord("OPERADOR", body, t, null, null), SmsOrigin.RECEIVER)
        reader.rows = listOf(
            row(date = t + 400, dateSent = t + 1_500),
            row(date = now - 5_000, text = "$body otro"),
        )
        vm.load()

        val known = vm.candidates.value.single { it.alreadyKnown }
        val fresh = vm.candidates.value.single { !it.alreadyKnown }
        vm.toggle(known.key)
        vm.toggle(fresh.key)

        assertFalse(vm.candidates.value.single { it.key == known.key }.selected)
        assertTrue(vm.candidates.value.single { it.key == fresh.key }.selected)
    }

    @Test fun sendSelectedIngestsAsManualAndEnqueues() = runBlocking {
        settings.addSender("OPERADOR")
        reader.rows = listOf(row(now - 5_000, "$body uno"), row(now - 6_000, "$body dos"))
        vm.load()

        vm.toggle(vm.candidates.value.first().key)
        val sent = vm.sendSelected()

        assertEquals(1, sent)
        val saved = sms.get(enqueued.single())!!
        assertEquals(SmsOrigin.MANUAL, saved.origin)
        assertEquals(1, vm.candidates.value.count { it.alreadyKnown })
        assertEquals(0, vm.sendSelected())
    }

    @Test fun recoveryLoadedAtIsTheInstantOfTheInboxRead() = runBlocking {
        var t = now
        val moving = RecoveryViewModel(reader, sms, settings, Clock { t }) { enqueued += it }
        assertEquals(now, moving.loadedAt) // never loaded: falls back to the clock
        moving.load()
        t = now + 60_000L
        assertEquals(now, moving.loadedAt)
    }
}
