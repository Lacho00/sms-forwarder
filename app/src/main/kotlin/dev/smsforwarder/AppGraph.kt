package dev.smsforwarder

import android.content.Context
import android.os.Build
import androidx.room.Room
import dev.smsforwarder.core.NotifyKind
import dev.smsforwarder.core.SystemClock
import dev.smsforwarder.data.AppDatabase
import dev.smsforwarder.data.ConfigTransfer
import dev.smsforwarder.data.DestinationRepository
import dev.smsforwarder.data.DestinationSecretStore
import dev.smsforwarder.data.DestinationSecrets
import dev.smsforwarder.data.KeystoreDestinationSecretStore
import dev.smsforwarder.data.KeystoreSecretStore
import dev.smsforwarder.data.LegacyImport
import dev.smsforwarder.data.MIGRATION_1_2
import dev.smsforwarder.data.RuleRepository
import dev.smsforwarder.data.SecretStore
import dev.smsforwarder.data.SettingsRepository
import dev.smsforwarder.data.SmsEntity
import dev.smsforwarder.data.SmsRepository
import dev.smsforwarder.data.migration2to3
import dev.smsforwarder.diag.EngineLog
import dev.smsforwarder.diag.DiagnosticsCollector
import dev.smsforwarder.health.HealthChecker
import dev.smsforwarder.health.HealthIssue
import dev.smsforwarder.net.DeliveryClient
import dev.smsforwarder.net.DestinationTester
import dev.smsforwarder.net.IngestClient
import dev.smsforwarder.net.defaultHttpClient
import dev.smsforwarder.notify.Notifier
import dev.smsforwarder.sms.SweepResult
import dev.smsforwarder.work.WorkScheduler
import java.io.File
import kotlinx.coroutines.runBlocking

class AppGraph(context: Context) {
    val db: AppDatabase =
        Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "sms-forwarder.db")
            .addMigrations(MIGRATION_1_2, migration2to3(context.getString(R.string.destination_primary_name)))
            .build()
    val clock = SystemClock
    /** Deliveries whose "no secrets" was logged; cleared when the delivery gets an outcome. */
    val noSecretsLogged: MutableSet<Long> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    val engineLog = EngineLog(File(context.noBackupFilesDir, "engine-events.log"))
    val smsRepository = SmsRepository(db, clock)

    /** `var` so tests can swap in a fake instead of the Android Keystore. */
    var secretStore: SecretStore = KeystoreSecretStore(context)
    /** `var` so tests can swap in a fake instead of the Android Keystore. */
    var destinationSecretStore: DestinationSecretStore = KeystoreDestinationSecretStore(context)
    private val httpClient = defaultHttpClient()
    val ingestClient = IngestClient(httpClient)
    val deliveryClient = DeliveryClient(httpClient)
    val destinationTester by lazy {
        DestinationTester(deliveryClient, secretsProxy, context.getString(R.string.destination_test_body), Build.MODEL, clock)
    }

    /** Forwards to whichever store is current, so a test swap after the first use still takes effect. */
    private val secretsProxy = object : DestinationSecretStore {
        override suspend fun get(destinationId: Long) = destinationSecretStore.get(destinationId)
        override suspend fun set(destinationId: Long, s: DestinationSecrets) =
            destinationSecretStore.set(destinationId, s)
        override suspend fun remove(destinationId: Long) = destinationSecretStore.remove(destinationId)
    }
    val destinationRepository by lazy {
        DestinationRepository(db, secretsProxy, clock, context.getString(R.string.destination_primary_name), smsRepository, notifier)
    }
    val configTransfer by lazy { ConfigTransfer(db, destinationRepository, ruleRepository, secretsProxy, engineLog = engineLog, clock = clock) }
    val ruleRepository by lazy { RuleRepository(db, destinationRepository, clock) }

    /** Lazy so it picks up the stores a test may have swapped in. */
    val settings by lazy {
        SettingsRepository(
            context.applicationContext,
            destinationRepository,
            ruleRepository,
            smsRepository,
            // New credentials replace a running send on purpose; resume must not (see SettingsRepository).
            scheduler = { id -> WorkScheduler.enqueueSend(context.applicationContext, id, replace = true) },
            safeScheduler = { id -> WorkScheduler.kickSend(context.applicationContext, id) },
        )
    }

    /** Whether forwarding is on; a seam so a test can make the read fail. */
    var forwardingEnabled: suspend () -> Boolean = { settings.isForwardingEnabled() }

    private var legacyImportOrNull: LegacyImport? = null

    /** Built on first use; a test can assign its own with fake stores. */
    var legacyImport: LegacyImport
        get() = synchronized(this) {
            legacyImportOrNull ?: LegacyImport(db, secretStore, destinationSecretStore, settings, onImported = { notifier.cancelLegacyTokenRejected() }, engineLog = engineLog)
                .also { legacyImportOrNull = it }
        }
        set(value) = synchronized(this) { legacyImportOrNull = value }

    val diagnostics by lazy { DiagnosticsCollector(context.applicationContext, this) }

    val notifier by lazy { Notifier(context.applicationContext) }
    val healthChecker by lazy {
        HealthChecker(context.applicationContext, smsRepository, destinationRepository, ruleRepository, settings)
    }

    // Seams so tests can replace the notifications; by default they use the real Notifier.
    var onRecovered: (List<SmsEntity>) -> Unit = {
        // The sweeper's callback is not suspend; the settings read is a single DataStore value.
        if (runBlocking { settings.isNotifyEnabled(NotifyKind.RECOVERED) }) notifier.recovered(it)
    }
    var afterSweep: suspend (SweepResult) -> Unit = { runSweepAlerts(it) }

    /** Runs after every sweep: stuck SMS first, then the health check (notified only when it changes). */
    private suspend fun runSweepAlerts(result: SweepResult) {
        // A paused SMS is not a stuck SMS.
        if (settings.isForwardingEnabled() && settings.isNotifyEnabled(NotifyKind.STUCK)) {
            for (item in smsRepository.stuckToNotify(settings.stuckAfterNow().millis)) {
                notifier.stuck(item)
                smsRepository.markStuckNotified(item.id)
            }
        }
        val issues = healthChecker.check(result.inboxUnreadable)
        val names = issues.map { it.name }.toSet()
        // The stored set stays unfiltered, so turning the permissions notice back on repeats nothing.
        if (names != settings.lastNotifiedIssues()) {
            val shown = if (settings.isNotifyEnabled(NotifyKind.PERMISSIONS)) issues else issues - PERMISSION_ISSUES
            notifier.health(shown)
        }
        settings.setLastNotifiedIssues(names)
    }

    private companion object {
        val PERMISSION_ISSUES = setOf(
            HealthIssue.SMS_PERMISSION,
            HealthIssue.NOTIFICATION_PERMISSION,
            HealthIssue.BATTERY_OPTIMIZED,
            HealthIssue.BACKGROUND_RESTRICTED,
            HealthIssue.AUTOSTART_OFF,
            HealthIssue.MIUI_SMS_BLOCKED,
        )
    }
}
