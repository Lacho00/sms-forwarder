package dev.smsforwarder

import android.app.Application
import dev.smsforwarder.work.WorkScheduler

class SmsForwarderApp : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }

    override fun onCreate() {
        super.onCreate()
        WorkScheduler.schedulePeriodicSweep(this)
    }
}
