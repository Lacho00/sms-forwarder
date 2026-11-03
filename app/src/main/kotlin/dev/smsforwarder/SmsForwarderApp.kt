package dev.smsforwarder

import android.app.Application
import dev.smsforwarder.notify.Notifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

open class SmsForwarderApp : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val graph: AppGraph by lazy { AppGraph(this) }

    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannel(this)
        startLegacyImport()
    }

    /** Overridable so Robolectric tests can keep the import from racing their own setup. */
    protected open fun startLegacyImport() {
        // Failures are stored by run() itself and surfaced by the health check.
        applicationScope.launch {
            try {
                graph.legacyImport.run()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // run() reports failures itself; nothing here may crash the process.
            }
        }
    }
}
