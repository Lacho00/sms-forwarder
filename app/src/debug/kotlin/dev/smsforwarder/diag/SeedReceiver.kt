package dev.smsforwarder.diag

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.smsforwarder.SmsForwarderApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Debug-only: `am broadcast -a dev.smsforwarder.debug.SEED --es op ...` (see scripts/e2e-emulator.sh). The result code is
 * 0 on success and 1 on failure; the result data is a [SeedResult] line. Nothing sensitive is logged.
 */
class SeedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val extras = buildMap {
            intent.extras?.keySet()?.forEach { k -> intent.getStringExtra(k)?.let { put(k, it) } }
        }
        val graph = (context.applicationContext as SmsForwarderApp).graph
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            val result = try {
                withTimeoutOrNull(TIMEOUT_MS) { SeedCommands(graph).run(extras) } ?: SeedResult(false, "error=timeout")
            } catch (e: Exception) {
                SeedResult(false, "error=${e.javaClass.simpleName}")
            }
            try {
                pending.setResult(if (result.ok) 0 else 1, result.data, null)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION = "dev.smsforwarder.debug.SEED"
        private const val TIMEOUT_MS = 9_000L
    }
}
