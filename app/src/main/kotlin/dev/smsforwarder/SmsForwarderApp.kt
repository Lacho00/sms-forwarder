package dev.smsforwarder

import android.app.Application

class SmsForwarderApp : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }
}
