package dev.smsforwarder.core

enum class EngineEventType { RECEIVED, SWEEP, SEND, RULE_TIMEOUT, SAFE_MODE, IMPORT, EXPORT }

data class EngineEvent(
    val at: Long,
    val type: EngineEventType,
    val smsId: Long? = null,
    val destinationId: Long? = null,
    val ruleId: Long? = null,
    val httpCode: Int? = null,
    val code: String? = null,
    val count: Int? = null,
)

private val SAFE_CODE = Regex("[a-z0-9_]{1,40}")

/** Free text never enters the log: only a short lowercase code, or "other". */
fun safeCode(s: String?): String? = when {
    s == null -> null
    SAFE_CODE.matches(s) -> s
    else -> "other"
}

/** `<at> <TYPE> sms=.. dest=.. rule=.. http=.. code=.. n=..`; null fields are left out. */
fun EngineEvent.toLine(): String = buildString {
    append(at).append(' ').append(type.name)
    smsId?.let { append(" sms=").append(it) }
    destinationId?.let { append(" dest=").append(it) }
    ruleId?.let { append(" rule=").append(it) }
    httpCode?.let { append(" http=").append(it) }
    code?.let { append(" code=").append(it) }
    count?.let { append(" n=").append(it) }
}

/** The inverse of [toLine]; null for anything that is not a well-formed line. Unknown keys are ignored. */
fun parseEngineEvent(line: String): EngineEvent? {
    val parts = line.trim().split(' ')
    if (parts.size < 2) return null
    val at = parts[0].toLongOrNull() ?: return null
    val type = EngineEventType.entries.firstOrNull { it.name == parts[1] } ?: return null
    var smsId: Long? = null
    var dest: Long? = null
    var rule: Long? = null
    var http: Int? = null
    var code: String? = null
    var count: Int? = null
    for (kv in parts.drop(2)) {
        val i = kv.indexOf('=')
        if (i <= 0) return null
        val v = kv.substring(i + 1)
        when (kv.substring(0, i)) {
            "sms" -> smsId = v.toLongOrNull() ?: return null
            "dest" -> dest = v.toLongOrNull() ?: return null
            "rule" -> rule = v.toLongOrNull() ?: return null
            "http" -> http = v.toIntOrNull() ?: return null
            "code" -> code = v.takeIf { SAFE_CODE.matches(it) } ?: return null
            "n" -> count = v.toIntOrNull() ?: return null
        }
    }
    return EngineEvent(at, type, smsId, dest, rule, http, code, count)
}
