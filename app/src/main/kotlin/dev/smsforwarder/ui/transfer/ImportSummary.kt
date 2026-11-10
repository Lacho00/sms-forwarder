package dev.smsforwarder.ui.transfer

import dev.smsforwarder.core.PlannedDestination
import dev.smsforwarder.core.PlannedRule

private const val MAX_PART = 60

internal fun truncate(s: String, max: Int = MAX_PART): String =
    if (s.length <= max) s else s.take(max) + "…"

/**
 * The host of [url]; when `java.net.URI` finds no host (e.g. an underscore in the name) the raw authority
 * with any userinfo removed; [noUrl] when there is no url or nothing safe to show. Never returns the path,
 * query, fragment or userinfo, which may carry secrets.
 */
internal fun hostOf(url: String?, noUrl: String): String {
    if (url == null) return noUrl
    val shown = runCatching {
        val uri = java.net.URI(url)
        uri.host?.takeIf { it.isNotEmpty() }
            ?: uri.rawAuthority?.substringAfterLast('@')?.takeIf { it.isNotEmpty() }
    }.getOrNull() ?: return noUrl
    // The authority fallback keeps bidi/format characters that java.net.URI accepts, and has no length bound.
    return truncate(flatten(shown))
}

/** Imported text is untrusted: line breaks, control and bidi/format characters could fake extra lines. */
private fun flatten(s: String): String = buildString(s.length) {
    for (c in s) {
        val type = Character.getType(c)
        val bad = Character.isISOControl(c) ||
            type == Character.LINE_SEPARATOR.toInt() ||
            type == Character.PARAGRAPH_SEPARATOR.toInt() ||
            type == Character.FORMAT.toInt() ||
            type == Character.CONTROL.toInt()
        append(if (bad) ' ' else c)
    }
}

internal fun destinationLine(d: PlannedDestination, noUrl: String): String =
    "• ${flatten(d.draft.name)} — ${hostOf(d.draft.url, noUrl)}"

internal fun ruleLine(r: PlannedRule): String {
    val body = if (r.draft.bodyMode != null && r.draft.bodyPattern != null) " / ${flatten(r.draft.bodyPattern!!)}" else ""
    return "• ${flatten(r.draft.name)}: ${truncate(flatten(r.draft.senderPattern) + body)}"
}
