package dev.smsforwarder

import android.content.Context
import androidx.room.Room
import dev.smsforwarder.core.SystemClock
import dev.smsforwarder.data.AppDatabase
import dev.smsforwarder.data.KeystoreSecretStore
import dev.smsforwarder.data.SecretStore
import dev.smsforwarder.data.SmsRepository
import dev.smsforwarder.net.IngestClient
import dev.smsforwarder.net.defaultHttpClient

class AppGraph(context: Context) {
    val db: AppDatabase =
        Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "sms-forwarder.db").build()
    val clock = SystemClock
    val smsRepository = SmsRepository(db, clock)

    /** `var` so tests can swap in a fake instead of the Android Keystore. */
    var secretStore: SecretStore = KeystoreSecretStore(context)
    val ingestClient = IngestClient(defaultHttpClient())
}
