package dev.smsforwarder.health

import android.Manifest
import android.content.Context
import androidx.room.Room
import dev.smsforwarder.core.Clock
import dev.smsforwarder.core.SmsOrigin
import dev.smsforwarder.core.SmsRecord
import dev.smsforwarder.data.AppDatabase
import dev.smsforwarder.data.Credentials
import dev.smsforwarder.data.IngestResult
import dev.smsforwarder.data.SecretStore
import dev.smsforwarder.data.SettingsRepository
import dev.smsforwarder.data.SmsRepository
import dev.smsforwarder.core.SendOutcome
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.util.UUID

private class FakeSecrets(var value: Credentials?) : SecretStore {
    override suspend fun get(): Credentials? = value
    override suspend fun set(c: Credentials) {
        value = c
    }
}

@RunWith(RobolectricTestRunner::class)
class HealthCheckerTest {
    private val clock = Clock { 1_800_000_000_000L }
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var sms: SmsRepository
    private lateinit var secrets: FakeSecrets
    private lateinit var settings: SettingsRepository
    private lateinit var checker: HealthChecker

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        sms = SmsRepository(db, clock)
        secrets = FakeSecrets(Credentials("https://example.invalid/ingest", "test-token"))
        val file = File(context.cacheDir, "settings-${UUID.randomUUID()}.preferences_pb")
        settings = SettingsRepository(context, secrets, sms, { }, file)
        checker = HealthChecker(context, sms, secrets, settings)
    }

    @After fun tearDown() {
        db.close()
    }

    @Test fun emptySendersIsReportedAsNoSenders() = runBlocking {
        assertTrue(HealthIssue.NO_SENDERS in checker.check())
        settings.addSender("OPERADOR")
        assertFalse(HealthIssue.NO_SENDERS in checker.check())
    }

    @Test fun smsPermissionDeniedIsReported() = runBlocking {
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
        )
        assertTrue(HealthIssue.SMS_PERMISSION in checker.check())
    }

    @Test fun token401PresentIsReported() = runBlocking {
        val record = SmsRecord("OPERADOR", "uno", 1_799_999_999_000L, null, null)
        val id = (sms.ingest(record, SmsOrigin.RECEIVER) as IngestResult.Inserted).id
        assertFalse(HealthIssue.TOKEN_REJECTED in checker.check())
        sms.recordAttempt(id, SendOutcome.Rejected(401, "unauthorized"))
        assertTrue(HealthIssue.TOKEN_REJECTED in checker.check())
    }

    @Test fun inboxUnreadableWithPermissionIsMiuiSmsBlocked() = runBlocking {
        assertTrue(HealthIssue.MIUI_SMS_BLOCKED in checker.check(inboxUnreadable = true))
        assertFalse(HealthIssue.MIUI_SMS_BLOCKED in checker.check(inboxUnreadable = false))
    }

    @Test fun nonMiuiAutostartIsUnknownNotOff() = runBlocking {
        val issues = checker.check()
        assertTrue(HealthIssue.AUTOSTART_UNKNOWN in issues)
        assertFalse(HealthIssue.AUTOSTART_OFF in issues)
    }

    @Test fun missingCredentialsIsNotConfigured() = runBlocking {
        secrets.value = null
        assertTrue(HealthIssue.NOT_CONFIGURED in checker.check())
    }
}
