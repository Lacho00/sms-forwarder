package dev.smsforwarder

import android.content.Context
import androidx.room.Room
import dev.smsforwarder.core.NotifyKind
import dev.smsforwarder.core.SystemClock
import dev.smsforwarder.data.AppDatabase
import dev.smsforwarder.data.KeystoreSecretStore
import dev.smsforwarder.data.MIGRATION_1_2
import dev.smsforwarder.data.SecretStore
import dev.smsforwarder.data.SettingsRepository
import dev.smsforwarder.data.SmsEntity
import dev.smsforwarder.data.SmsRepository
import dev.smsforwarder.health.HealthChecker
import dev.smsforwarder.health.HealthIssue
import dev.smsforwarder.net.IngestClient
import dev.smsforwarder.net.defaultHttpClient
import dev.smsforwarder.notify.Notifier
import dev.smsforwarder.sms.SweepResult
import dev.smsforwarder.work.WorkScheduler
import kotlinx.coroutines.runBlocking

class AppGraph(context: Context) {
    val db: AppDatabase =
        Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "sms-forwarder.db")
            .addMigrations(MIGRATION_1_2)
            .build()
    val clock = SystemClock
    val smsRepository = SmsRepository(db, clock)

    /** `var` so tests can swap in a fake instead of the Android Keystore. */
    var secretStore: SecretStore = KeystoreSecretStore(context)
    val ingestClient = IngestClient(defaultHttpClient())

    /** Lazy so it picks up the secretStore a test may have swapped in. */
    val settings by lazy {
        SettingsRepository(
            context.applicationContext,
            secretStore,
            smsRepository,
            scheduler = { id -> WorkScheduler.enqueueSend(context.applicationContext, id, replace = true) },
        )
    }

    val notifier by lazy { Notifier(context.applicationContext) }
    val healthChecker by lazy {
        HealthChecker(context.applicationContext, smsRepository, secretStore, settings)
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
