package dev.smsforwarder.diag

import android.content.Context
import android.os.Build
import dev.smsforwarder.AppGraph
import dev.smsforwarder.core.DeliveryStatus
import dev.smsforwarder.core.DestinationDiag
import dev.smsforwarder.core.DiagnosticInput
import dev.smsforwarder.core.errorKey
import dev.smsforwarder.core.renderDiagnostic
import dev.smsforwarder.health.MiuiIntents
import kotlinx.coroutines.flow.first

/**
 * Gathers counters, ids and codes for the diagnostic report. It never reads SMS text, senders, destination or rule
 * names, URLs or secret values (only whether a destination has secrets).
 */
class DiagnosticsCollector(private val context: Context, private val graph: AppGraph) {
    suspend fun collect(): DiagnosticInput {
        val db = graph.db
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val perDestination = db.deliveryDao().countsByDestination().groupBy { it.destinationId }
        val destinations = graph.destinationRepository.observeLiveWithConfigured().first().map { d ->
            DestinationDiag(
                id = d.entity.id,
                template = d.entity.template,
                enabled = d.entity.enabled,
                configured = d.configured,
                counts = perDestination[d.entity.id].orEmpty().associate { it.status to it.count }
                    .filterKeys { it in DeliveryStatus.entries },
            )
        }
        val errors = linkedMapOf<String, Int>()
        for (e in db.deliveryDao().errorCodes()) {
            val key = errorKey(e.lastHttpCode, e.lastError)
            errors[key] = (errors[key] ?: 0) + e.n
        }
        return DiagnosticInput(
            generatedAt = graph.clock.now(),
            appVersion = info.versionName ?: "unknown",
            versionCode = info.longVersionCode,
            androidRelease = Build.VERSION.RELEASE.orEmpty(),
            sdkInt = Build.VERSION.SDK_INT,
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            miui = Build.MANUFACTURER.lowercase() in setOf("xiaomi", "redmi", "poco"),
            autostart = MiuiIntents.autostartState(context),
            issues = graph.healthChecker.check().map { it.name }.sorted(),
            forwardingEnabled = graph.settings.isForwardingEnabled(),
            sms = db.smsDao().countsByStatus().associate { it.status to it.count },
            destinations = destinations,
            errors = errors,
            rules = db.ruleDao().observeAllWithDestinations().first().size,
            activeRules = graph.ruleRepository.activeRules().size,
            events = graph.engineLog.recent(50),
        )
    }

    suspend fun report(): String = renderDiagnostic(collect())
}
