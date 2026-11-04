package dev.smsforwarder.core

import java.time.Instant

/** One destination, by id and template only: its name is user text and stays out of the report. */
data class DestinationDiag(
    val id: Long,
    val template: TemplateKind,
    val enabled: Boolean,
    val configured: Boolean,
    val counts: Map<DeliveryStatus, Int>,
)

data class DiagnosticInput(
    val generatedAt: Long,
    val appVersion: String,
    val versionCode: Long,
    val androidRelease: String,
    val sdkInt: Int,
    val device: String,
    val miui: Boolean,
    val autostart: Boolean?,
    val issues: List<String>,
    val forwardingEnabled: Boolean,
    val sms: Map<SmsStatus, Int>,
    val destinations: List<DestinationDiag>,
    val errors: Map<String, Int>,
    val rules: Int,
    val activeRules: Int,
    val events: List<EngineEvent>,
) {
    override fun toString(): String = "DiagnosticInput(app=$appVersion)"
}

private val CONFIG_CODE = Regex("config_[a-z_]{1,40}")

/** A bucket for an error: `http_<n>`, `network`, `destination_deleted`, `config_<reason>` or `other`. Server text never passes. */
fun errorKey(httpCode: Int?, lastError: String?): String = when {
    httpCode != null -> "http_$httpCode"
    lastError == "network" -> "network"
    lastError == "destination_deleted" -> "destination_deleted"
    lastError != null && CONFIG_CODE.matches(lastError) -> lastError
    else -> "other"
}

private fun iso(ms: Long): String = Instant.ofEpochMilli(ms).toString()

/** Plain text with counters, ids and codes. Never SMS text, senders, URLs, names, headers or tokens. */
fun renderDiagnostic(i: DiagnosticInput): String = buildString {
    appendLine("SMS Forwarder diagnostic")
    appendLine("generated: ${iso(i.generatedAt)}")
    appendLine()
    appendLine("== App ==")
    appendLine("version: ${i.appVersion} (${i.versionCode})")
    appendLine("forwarding: ${if (i.forwardingEnabled) "on" else "off"}")
    appendLine()
    appendLine("== Device ==")
    appendLine("android: ${i.androidRelease} (sdk ${i.sdkInt})")
    appendLine("device: ${i.device}")
    appendLine("miui: ${i.miui}")
    appendLine("autostart: ${i.autostart ?: "unknown"}")
    appendLine()
    appendLine("== Health ==")
    if (i.issues.isEmpty()) appendLine("ok") else i.issues.forEach { appendLine(it) }
    appendLine()
    appendLine("== Queue ==")
    SmsStatus.entries.forEach { appendLine("${it.name.lowercase()}: ${i.sms[it] ?: 0}") }
    appendLine()
    appendLine("== Destinations ==")
    if (i.destinations.isEmpty()) appendLine("none")
    for (d in i.destinations) {
        append("id=${d.id} template=${d.template.name} enabled=${d.enabled} configured=${d.configured}")
        DeliveryStatus.entries.forEach { append(" ${it.name.lowercase()}=${d.counts[it] ?: 0}") }
        appendLine()
    }
    appendLine()
    appendLine("== Errors ==")
    if (i.errors.isEmpty()) appendLine("none")
    i.errors.toSortedMap().forEach { (k, n) -> appendLine("$k: $n") }
    appendLine()
    appendLine("== Rules ==")
    appendLine("total: ${i.rules}")
    appendLine("active: ${i.activeRules}")
    appendLine()
    appendLine("== Events ==")
    if (i.events.isEmpty()) appendLine("none")
    for (e in i.events) appendLine("${iso(e.at)} ${e.toLine().substringAfter(' ')}")
}
