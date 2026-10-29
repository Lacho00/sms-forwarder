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
import dev.smsforwarder.net.IngestClient
import dev.smsforwarder.net.defaultHttpClient
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

    // Seams for the health check and notifications (Task 12); no-ops until then.
    var onRecovered: (List<SmsEntity>) -> Unit = {}
    var afterSweep: suspend (SweepResult) -> Unit = {}
}
