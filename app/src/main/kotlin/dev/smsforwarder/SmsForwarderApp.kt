package dev.smsforwarder

import android.app.Application
import dev.smsforwarder.notify.Notifier

class SmsForwarderApp : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }

    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannel(this)
    }
}
