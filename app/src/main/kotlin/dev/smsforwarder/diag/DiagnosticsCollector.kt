package dev.smsforwarder.diag

import android.content.Context
import android.os.Build
import dev.smsforwarder.AppGraph
import dev.smsforwarder.core.DestinationDiag
import dev.smsforwarder.core.DiagnosticInput
import dev.smsforwarder.core.errorKey
import dev.smsforwarder.core.renderDiagnostic
import dev.smsforwarder.health.HealthIssue
import dev.smsforwarder.health.MiuiIntents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Gathers counters, ids and codes for the diagnostic report. It never reads SMS text, senders, destination or rule
 * names, URLs or secret values (only whether a destination has secrets). Every source is isolated: one that fails
 * leaves "unavailable" in its section and the rest of the report is still written.
 */
class DiagnosticsCollector(
    private val context: Context,
    private val graph: AppGraph,
    private val health: suspend () -> Set<HealthIssue> = { graph.healthChecker.check() },
) {
    suspend fun collect(): DiagnosticInput = withContext(Dispatchers.IO) {
        val db = graph.db
        val unavailable = linkedSetOf<String>()

        suspend fun <T> source(section: String, fallback: T, block: suspend () -> T): T = try {
            block()
        } catch (c: CancellationException) {
            throw c
        } catch (_: Throwable) {
            unavailable += section
            fallback
        }

        val info = source("App", null) { context.packageManager.getPackageInfo(context.packageName, 0) }
        val forwarding = source<Boolean?>("App", null) { graph.settings.isForwardingEnabled() }
        val autostart = source<Boolean?>("Device", null) { MiuiIntents.autostartState(context) }
        val issues = source("Health", emptyList<String>()) { health().map { it.name }.sorted() }
        val sms = source("Queue", emptyMap()) { db.smsDao().countsByStatus().associate { it.status to it.count } }
        val destinations = source("Destinations", emptyList<DestinationDiag>()) {
            val perDestination = db.deliveryDao().countsByDestination().groupBy { it.destinationId }
            graph.destinationRepository.observeLiveWithConfigured().first().map { d ->
                DestinationDiag(
                    id = d.entity.id,
                    template = d.entity.template,
                    enabled = d.entity.enabled,
                    configured = d.configured,
                    counts = perDestination[d.entity.id].orEmpty().associate { it.status to it.count },
                )
            }
        }
        val errors = source("Errors", emptyMap<String, Int>()) {
            val out = linkedMapOf<String, Int>()
            for (e in db.deliveryDao().errorCodes()) {
                val key = errorKey(e.lastHttpCode, e.lastError)
                out[key] = (out[key] ?: 0) + e.n
            }
            out
        }
        val rules = source("Rules", 0 to 0) {
            db.ruleDao().observeAllWithDestinations().first().size to graph.ruleRepository.activeRules().size
        }
        val events = source("Events", emptyList()) { graph.engineLog.recent(50) }

        DiagnosticInput(
            generatedAt = graph.clock.now(),
            appVersion = info?.versionName ?: "unknown",
            versionCode = info?.longVersionCode ?: 0,
            androidRelease = Build.VERSION.RELEASE.orEmpty(),
            sdkInt = Build.VERSION.SDK_INT,
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            miui = MiuiIntents.isMiui(),
            autostart = autostart,
            issues = issues,
            forwardingEnabled = forwarding,
            sms = sms,
            destinations = destinations,
            errors = errors,
            rules = rules.first,
            activeRules = rules.second,
            events = events,
            unavailable = unavailable,
        )
    }

    suspend fun report(): String = renderDiagnostic(collect())
}
