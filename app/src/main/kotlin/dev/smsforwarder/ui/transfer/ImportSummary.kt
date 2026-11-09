package dev.smsforwarder.ui.transfer

import dev.smsforwarder.core.PlannedDestination
import dev.smsforwarder.core.PlannedRule

private const val MAX_PART = 60

internal fun truncate(s: String, max: Int = MAX_PART): String =
    if (s.length <= max) s else s.take(max) + "…"

/** The host of [url], or the raw url truncated when it has none, or [noUrl] when there is no url. */
internal fun hostOf(url: String?, noUrl: String): String {
    if (url == null) return noUrl
    val host = runCatching { java.net.URI(url).host }.getOrNull()
    return if (host.isNullOrEmpty()) truncate(url) else host
}

internal fun destinationLine(d: PlannedDestination, noUrl: String): String =
    "• ${d.draft.name} — ${hostOf(d.draft.url, noUrl)}"

internal fun ruleLine(r: PlannedRule): String {
    val body = if (r.draft.bodyMode != null && r.draft.bodyPattern != null) " / ${r.draft.bodyPattern}" else ""
    return "• ${r.draft.name}: ${truncate(r.draft.senderPattern + body)}"
}
