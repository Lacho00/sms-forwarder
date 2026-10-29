package dev.smsforwarder

import android.content.Context
import androidx.room.Room
import dev.smsforwarder.core.SystemClock
import dev.smsforwarder.data.AppDatabase
import dev.smsforwarder.data.KeystoreSecretStore
import dev.smsforwarder.data.SecretStore
import dev.smsforwarder.data.SettingsRepository
import dev.smsforwarder.data.SmsEntity
import dev.smsforwarder.data.SmsRepository
import dev.smsforwarder.health.HealthChecker
import dev.smsforwarder.net.IngestClient
import dev.smsforwarder.net.defaultHttpClient
import dev.smsforwarder.notify.Notifier
import dev.smsforwarder.sms.SweepResult
import dev.smsforwarder.work.WorkScheduler

class AppGraph(context: Context) {
    val db: AppDatabase =
        Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "sms-forwarder.db").build()
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
    var onRecovered: (List<SmsEntity>) -> Unit = { notifier.recovered(it) }
    var afterSweep: suspend (SweepResult) -> Unit = { runSweepAlerts(it) }

    /** Runs after every sweep: stuck SMS first, then the health check (notified only when it changes). */
    private suspend fun runSweepAlerts(result: SweepResult) {
        for (item in smsRepository.stuckToNotify()) {
            notifier.stuck(item)
            smsRepository.markStuckNotified(item.id)
        }
        val issues = healthChecker.check(result.inboxUnreadable)
        val names = issues.map { it.name }.toSet()
        if (names != settings.lastNotifiedIssues()) notifier.health(issues)
        settings.setLastNotifiedIssues(names)
    }
}
