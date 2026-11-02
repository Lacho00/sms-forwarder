package dev.smsforwarder.data

import androidx.room.Room
import dev.smsforwarder.core.Clock
import dev.smsforwarder.core.Language
import dev.smsforwarder.core.NotifyKind
import dev.smsforwarder.core.Retention
import dev.smsforwarder.core.StuckAfter
import dev.smsforwarder.core.SweepInterval
import dev.smsforwarder.core.SendOutcome
import dev.smsforwarder.core.SmsOrigin
import dev.smsforwarder.core.SmsRecord
import dev.smsforwarder.core.SmsStatus
import dev.smsforwarder.ui.theme.ThemeMode
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
import java.io.File
import java.util.UUID

private class FakeSecretStore : SecretStore {
    var saved: Credentials? = null
    override suspend fun get(): Credentials? = saved
    override suspend fun set(c: Credentials) {
        saved = c
    }
}

@RunWith(RobolectricTestRunner::class)
class SettingsRepositoryTest {
    private val clock = Clock { 1_800_000_000_000L }
    private lateinit var db: AppDatabase
    private lateinit var sms: SmsRepository
    private lateinit var secrets: FakeSecretStore
    private lateinit var settings: SettingsRepository
    private val kicked = mutableListOf<Long>()

    private fun record(text: String) = SmsRecord("OPERADOR", text, 1_799_999_999_000L, null, null)

    @Before fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        sms = SmsRepository(db, clock)
        secrets = FakeSecretStore()
        // A unique file per test keeps DataStore state from leaking between tests.
        val file = File(context.cacheDir, "settings-${UUID.randomUUID()}.preferences_pb")
        settings = SettingsRepository(context, secrets, sms, { kicked.add(it) }, file)
    }

    @After fun tearDown() {
        db.close()
    }

    @Test fun sendersStartEmpty() = runBlocking {
        assertTrue(settings.senders.first().isEmpty())
    }

    @Test fun addSenderIgnoresDuplicateAfterNormalization() = runBlocking {
        settings.addSender("OPERADOR")
        settings.addSender(" operador")
        assertEquals(listOf("OPERADOR"), settings.senders.first())
    }

    @Test fun saveCredentialsRequeues401AndKicksQueued() = runBlocking {
        val rejected = (sms.ingest(record("uno"), SmsOrigin.RECEIVER) as IngestResult.Inserted).id
        val queued = (sms.ingest(record("dos"), SmsOrigin.RECEIVER) as IngestResult.Inserted).id
        sms.recordAttempt(rejected, SendOutcome.Rejected(401, null))
        assertEquals(SmsStatus.ERROR, sms.get(rejected)!!.status)

        val credentials = Credentials("https://example.invalid/ingest", "test-token")
        settings.saveCredentials(credentials)

        assertEquals(credentials, secrets.saved)
        assertEquals(SmsStatus.QUEUED, sms.get(rejected)!!.status)
        assertEquals(setOf(rejected, queued), kicked.toSet())
        assertEquals(2, kicked.size)
    }

    @Test fun removeSenderMatchesNormalized() = runBlocking {
        settings.addSender("OPERADOR")
        settings.removeSender("operador ")
        assertTrue(settings.senders.first().isEmpty())
    }

    @Test fun themeDefaultsToSystem() = runBlocking {
        assertEquals(ThemeMode.SYSTEM, settings.themeMode.first())
    }

    @Test fun inboxUnreadableRoundTrips() = runBlocking {
        assertEquals(false, settings.inboxUnreadable())
        settings.setInboxUnreadable(true)
        assertEquals(true, settings.inboxUnreadable())
        assertEquals(true, settings.inboxUnreadableFlow.first())
        settings.setInboxUnreadable(false)
        assertEquals(false, settings.inboxUnreadable())
    }

    @Test fun lastSweepAtRoundTrips() = runBlocking {
        assertEquals(null, settings.lastSweepAt())
        settings.setLastSweepAt(1_800_000_000_000L)
        assertEquals(1_800_000_000_000L, settings.lastSweepAt())
        assertEquals(1_800_000_000_000L, settings.lastSweepAtFlow.first())
    }

    @Test fun defaultsMatchV1() = runBlocking {
        assertEquals(true, settings.forwardingEnabled.first())
        assertEquals(true, settings.isForwardingEnabled())
        assertEquals(SweepInterval.M15, settings.sweepInterval.first())
        assertEquals(SweepInterval.M15, settings.sweepIntervalNow())
        assertEquals(StuckAfter.H6, settings.stuckAfter.first())
        assertEquals(StuckAfter.H6, settings.stuckAfterNow())
        assertEquals(Retention.D90, settings.retention.first())
        assertEquals(Retention.D90, settings.retentionNow())
        assertEquals(false, settings.hideText.first())
        assertEquals(true, settings.dynamicColor.first())
        assertEquals(Language.SYSTEM, settings.language.first())
        assertEquals(true, settings.notifyEnabled(NotifyKind.STUCK).first())
        assertEquals(true, settings.isNotifyEnabled(NotifyKind.STUCK))
    }

    @Test fun newSettingsRoundTrip() = runBlocking {
        settings.setSweepInterval(SweepInterval.M60)
        settings.setStuckAfter(StuckAfter.H24)
        settings.setRetention(Retention.FOREVER)
        settings.setHideText(true)
        settings.setDynamicColor(false)
        settings.setLanguage(Language.EN)
        settings.setNotifyEnabled(NotifyKind.RECOVERED, false)
        assertEquals(SweepInterval.M60, settings.sweepIntervalNow())
        assertEquals(StuckAfter.H24, settings.stuckAfterNow())
        assertEquals(Retention.FOREVER, settings.retentionNow())
        assertEquals(true, settings.hideText.first())
        assertEquals(false, settings.dynamicColor.first())
        assertEquals(Language.EN, settings.language.first())
        assertEquals(false, settings.isNotifyEnabled(NotifyKind.RECOVERED))
        assertEquals(true, settings.isNotifyEnabled(NotifyKind.PERMISSIONS))
    }

    @Test fun resumingForwardingKicksQueuedIds() = runBlocking {
        val a = (sms.ingest(record("uno"), SmsOrigin.RECEIVER) as IngestResult.Inserted).id
        val b = (sms.ingest(record("dos"), SmsOrigin.RECEIVER) as IngestResult.Inserted).id
        settings.setForwardingEnabled(false)
        assertEquals(false, settings.isForwardingEnabled())
        assertTrue(kicked.isEmpty())

        settings.setForwardingEnabled(true)

        assertEquals(true, settings.isForwardingEnabled())
        assertEquals(setOf(a, b), kicked.toSet())
        assertEquals(2, kicked.size)
    }
}
