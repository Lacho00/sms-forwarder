package dev.smsforwarder.data

import androidx.room.Room
import dev.smsforwarder.core.Clock
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

    @Test fun lastSweepAtRoundTrips() = runBlocking {
        assertEquals(null, settings.lastSweepAt())
        settings.setLastSweepAt(1_800_000_000_000L)
        assertEquals(1_800_000_000_000L, settings.lastSweepAt())
        assertEquals(1_800_000_000_000L, settings.lastSweepAtFlow.first())
    }
}
