package dev.smsforwarder

import android.content.Context
import androidx.room.Room
import dev.smsforwarder.core.SystemClock
import dev.smsforwarder.data.AppDatabase
import dev.smsforwarder.data.SmsRepository

class AppGraph(context: Context) {
    val db: AppDatabase =
        Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "sms-forwarder.db").build()
    val clock = SystemClock
    val smsRepository = SmsRepository(db, clock)
}
