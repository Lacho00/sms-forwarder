package dev.smsforwarder.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Room
import dev.smsforwarder.core.SenderMode
import dev.smsforwarder.core.SimMatch
import dev.smsforwarder.core.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

private class LegacyStore(var value: Credentials?) : SecretStore {
    override suspend fun get(): Credentials? = value
    override suspend fun set(c: Credentials) {
        value = c
    }
}

@RunWith(RobolectricTestRunner::class)
class LegacyImportTest {
    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var sms: SmsRepository
    private lateinit var repos: TestRepos
    private val creds = Credentials("https://example.invalid/ingest", "test-token")

    @Before fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        sms = SmsRepository(db, SystemClock)
        repos = TestRepos(db)
        settings = settingsOver(File(context.cacheDir, "settings-${UUID.randomUUID()}.preferences_pb"))
    }

    private fun settingsOver(file: File) =
        SettingsRepository(RuntimeEnvironment.getApplication(), repos.destinations, repos.rules, sms, { }, file)

    /** Writes the 1.1.x raw sender list, as the old app left it, and reopens the settings over it. */
    private suspend fun seedLegacySenders(vararg senders: String) {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.cacheDir, "settings-${UUID.randomUUID()}.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val seed = PreferenceDataStoreFactory.create(scope = scope) { file }
            seed.edit { it[stringPreferencesKey("senders")] = senders.joinToString("\n") }
        } finally {
            scope.cancel()
        }
        settings = settingsOver(file)
    }

    @After fun tearDown() {
        db.close()
    }

    private suspend fun withPrimary() {
        db.destinationDao().insert(
            DestinationEntity(
                id = PRIMARY_DESTINATION_ID, name = "Primary", enabled = true,
                template = dev.smsforwarder.core.TemplateKind.STANDARD_JSON,
                method = dev.smsforwarder.core.HttpMethod.POST, bodyTemplate = null, bodyIsJson = true,
                publicHeaders = "[]", successPolicy = dev.smsforwarder.core.SuccessPolicy.ANY_2XX, createdAt = 1L,
            ),
        )
    }

    private fun importer(legacy: Credentials?, secrets: DestinationSecretStore) =
        LegacyImport(db, LegacyStore(legacy), secrets, settings)

    @Test fun copiesCredentialsAndSenders() = runBlocking {
        withPrimary()
        seedLegacySenders("AAA", "B B")
        val secrets = FakeDestinationSecretStore()
        assertTrue(importer(creds, secrets).run())
        assertEquals(creds.toDestinationSecrets(), secrets.get(PRIMARY_DESTINATION_ID))
        val rules = db.ruleDao().activeWithDestinations()
        assertEquals(listOf("AAA", "B B"), rules.map { it.rule.senderPattern })
        for (r in rules) {
            assertEquals(r.rule.senderPattern, r.rule.name)
            assertEquals(SenderMode.EXACT, r.rule.senderMode)
            assertEquals(SimMatch.ANY, r.rule.sim)
            assertNull(r.rule.bodyMode)
            assertEquals(listOf(PRIMARY_DESTINATION_ID), r.destinations.map { it.id })
        }
        assertFalse(settings.isImportFailed())
    }

    @Test fun idempotent() = runBlocking {
        withPrimary()
        seedLegacySenders("AAA", "B B")
        val imp = importer(creds, FakeDestinationSecretStore())
        assertTrue(imp.run())
        assertTrue(imp.run())
        assertEquals(2, db.ruleDao().activeWithDestinations().size)
    }

    @Test fun noCredentialsStillImportsRules() = runBlocking {
        withPrimary()
        seedLegacySenders("AAA")
        val secrets = FakeDestinationSecretStore()
        assertTrue(importer(null, secrets).run())
        assertNull(secrets.get(PRIMARY_DESTINATION_ID))
        assertEquals(1, db.ruleDao().activeWithDestinations().size)
    }

    @Test fun existingSecretsAreNotOverwritten() = runBlocking {
        withPrimary()
        val secrets = FakeDestinationSecretStore()
        val existing = DestinationSecrets("https://other.invalid/x", emptyList())
        secrets.set(PRIMARY_DESTINATION_ID, existing)
        assertTrue(importer(creds, secrets).run())
        assertEquals(existing, secrets.get(PRIMARY_DESTINATION_ID))
    }

    @Test fun keystoreFailureLeavesNothingHalfDone() = runBlocking {
        withPrimary()
        seedLegacySenders("AAA")
        val failing = FakeDestinationSecretStore().apply { failOnSet = true }
        assertFalse(importer(creds, failing).run())
        assertEquals(0, db.ruleDao().activeWithDestinations().size)
        assertTrue(settings.isImportFailed())

        val working = FakeDestinationSecretStore()
        assertTrue(importer(creds, working).run())
        assertEquals(1, db.ruleDao().activeWithDestinations().size)
        assertEquals(creds.toDestinationSecrets(), working.get(PRIMARY_DESTINATION_ID))
        assertFalse(settings.isImportFailed())
    }

    @Test fun freshInstallSkips() = runBlocking {
        seedLegacySenders("AAA")
        val secrets = FakeDestinationSecretStore()
        assertTrue(importer(creds, secrets).run())
        assertEquals(0, db.ruleDao().activeWithDestinations().size)
        assertNull(secrets.get(PRIMARY_DESTINATION_ID))
        assertNull(db.destinationDao().primary())
        // Done is remembered: a destination created later is not imported into.
        withPrimary()
        assertTrue(importer(creds, secrets).run())
        assertEquals(0, db.ruleDao().activeWithDestinations().size)
    }

    @Test fun brokenSettingsStoreReturnsFalseWithoutThrowing() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.cacheDir, "settings-${UUID.randomUUID()}.preferences_pb")
        file.writeBytes(ByteArray(64) { 0x7f })
        val broken = SettingsRepository(context, repos.destinations, repos.rules, sms, { }, file)
        withPrimary()
        assertFalse(LegacyImport(db, LegacyStore(creds), FakeDestinationSecretStore(), broken).run())
    }

    @Test fun legacySendersReadsRawKey() = runBlocking {
        seedLegacySenders("AAA")
        assertEquals(listOf("AAA"), settings.legacySenders())
    }
}
