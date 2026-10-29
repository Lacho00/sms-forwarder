package dev.smsforwarder.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.smsforwarder.work.WorkScheduler

/** After a reboot, sweeps right away so SMS that arrived while the phone was off are not missed. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        WorkScheduler.sweepNow(context.applicationContext)
    }
}
