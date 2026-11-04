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
    val forwardingEnabled: Boolean?,
    val sms: Map<SmsStatus, Int>,
    val destinations: List<DestinationDiag>,
    val errors: Map<String, Int>,
    val rules: Int,
    val activeRules: Int,
    val events: List<EngineEvent>,
    /** Sections whose source failed; the report says so instead of showing a made-up value. */
    val unavailable: Set<String> = emptySet(),
) {
    override fun toString(): String = "DiagnosticInput(app=$appVersion)"
}

private val CONFIG_CODES = setOf("config_request", "config_template")

/** A bucket for an error: `http_<n>`, `network`, `destination_deleted`, `config_<reason>` or `other`. Server text never passes. */
fun errorKey(httpCode: Int?, lastError: String?): String = when {
    httpCode != null -> "http_$httpCode"
    lastError == "network" -> "network"
    lastError == "destination_deleted" -> "destination_deleted"
    lastError != null && lastError in CONFIG_CODES -> lastError
    lastError != null && lastError.startsWith("config_") && lastError.matches(Regex("config_[a-z0-9_]{1,40}")) -> "config_other"
    else -> "other"
}

private fun iso(ms: Long): String = Instant.ofEpochMilli(ms).toString()

/** Plain text with counters, ids and codes. Never SMS text, senders, URLs, names, headers or tokens. */
fun renderDiagnostic(i: DiagnosticInput): String = buildString {
    fun section(name: String, body: StringBuilder.() -> Unit) {
        appendLine("== $name ==")
        if (name in i.unavailable) appendLine("unavailable") else body()
        appendLine()
    }
    appendLine("SMS Forwarder diagnostic")
    appendLine("generated: ${iso(i.generatedAt)}")
    appendLine()
    section("App") {
        appendLine("version: ${i.appVersion} (${i.versionCode})")
        appendLine("forwarding: ${when (i.forwardingEnabled) { true -> "on"; false -> "off"; null -> "unknown" }}")
    }
    section("Device") {
        appendLine("android: ${i.androidRelease} (sdk ${i.sdkInt})")
        appendLine("device: ${i.device}")
        appendLine("miui: ${i.miui}")
        appendLine("autostart: ${i.autostart ?: "unknown"}")
    }
    section("Health") {
        if (i.issues.isEmpty()) appendLine("ok") else i.issues.forEach { appendLine(it) }
    }
    section("Queue") {
        SmsStatus.entries.forEach { appendLine("${it.name.lowercase()}: ${i.sms[it] ?: 0}") }
    }
    section("Destinations") {
        if (i.destinations.isEmpty()) appendLine("none")
        for (d in i.destinations) {
            append("id=${d.id} template=${d.template.name} enabled=${d.enabled} configured=${d.configured}")
            DeliveryStatus.entries.forEach { append(" ${it.name.lowercase()}=${d.counts[it] ?: 0}") }
            appendLine()
        }
    }
    section("Errors") {
        if (i.errors.isEmpty()) appendLine("none")
        i.errors.toSortedMap().forEach { (k, n) -> appendLine("$k: $n") }
    }
    section("Rules") {
        appendLine("total: ${i.rules}")
        appendLine("active: ${i.activeRules}")
    }
    section("Events") {
        if (i.events.isEmpty()) appendLine("none")
        for (e in i.events) appendLine("${iso(e.at)} ${e.toLine().substringAfter(' ')}")
    }
}
