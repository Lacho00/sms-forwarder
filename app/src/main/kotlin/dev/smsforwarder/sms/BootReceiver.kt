package dev.smsforwarder.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.smsforwarder.SmsForwarderApp
import dev.smsforwarder.work.WorkScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** After a reboot, sweeps right away so SMS that arrived while the phone was off are not missed. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val graph = (app as SmsForwarderApp).graph
                WorkScheduler.schedulePeriodicSweep(app, graph.settings.sweepIntervalNow())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Settings unreadable: fall back to the default period rather than lose the job.
                WorkScheduler.schedulePeriodicSweep(app)
            } finally {
                WorkScheduler.sweepNow(app)
                pending?.finish()
            }
        }
    }
}
