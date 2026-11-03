package dev.smsforwarder.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/** `value == null` keeps the stored value. `toString()` hides a secret value. */
data class HeaderDraft(val name: String, val value: String?, val secret: Boolean) {
    override fun toString(): String =
        "HeaderDraft(name=$name, value=${if (secret) "<hidden>" else value}, secret=$secret)"
}

/** `url == null` keeps the stored URL. `toString()` leaves out url, headers and body. */
data class DestinationDraft(
    val name: String,
    val enabled: Boolean,
    val template: TemplateKind,
    val method: HttpMethod,
    val url: String?,
    val headers: List<HeaderDraft>,
    val body: String,
    val bodyIsJson: Boolean,
    val successPolicy: SuccessPolicy,
) {
    override fun toString(): String =
        "DestinationDraft(name=$name, enabled=$enabled, template=$template, method=$method, headers=${headers.size})"
}

enum class DestinationError {
    NAME_EMPTY, URL_MISSING, URL_NOT_HTTPS, UNKNOWN_VARIABLE,
    HEADER_NAME_INVALID, HEADER_VALUE_INVALID, BODY_NOT_JSON, BODY_WITH_GET,
}

private val PLACEHOLDER = Regex("\\{([a-z_]+)\\}")
private val HEADER_NAME = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")
private val TEXT_VARS = setOf("sender", "body", "device", "sim")

private fun fill(text: String, replace: (String) -> String): String =
    PLACEHOLDER.replace(text) { m ->
        if (m.groupValues[1] in Templates.VARIABLES) replace(m.groupValues[1]) else "x"
    }

fun validateDestination(d: DestinationDraft, urlStored: Boolean): Set<DestinationError> {
    val errors = linkedSetOf<DestinationError>()
    if (d.name.isBlank()) errors += DestinationError.NAME_EMPTY

    val url = d.url
    if (url == null) {
        if (!urlStored) errors += DestinationError.URL_MISSING
    } else if (url.isBlank()) {
        errors += DestinationError.URL_MISSING
    } else {
        if (Templates.unknownVariables(url).isNotEmpty()) errors += DestinationError.UNKNOWN_VARIABLE
        val probe = fill(url) { if (it.startsWith("received_at")) "0" else "x" }
        if (!UrlValidator.isValid(probe)) errors += DestinationError.URL_NOT_HTTPS
    }

    for (h in d.headers) {
        if (!HEADER_NAME.matches(h.name)) errors += DestinationError.HEADER_NAME_INVALID
        val v = h.value ?: continue
        if (Templates.unknownVariables(v).isNotEmpty()) errors += DestinationError.UNKNOWN_VARIABLE
        val literal = PLACEHOLDER.replace(v, "")
        if (literal.any { it.code !in 0x20..0x7E }) errors += DestinationError.HEADER_VALUE_INVALID
    }

    if (Templates.unknownVariables(d.body).isNotEmpty()) errors += DestinationError.UNKNOWN_VARIABLE
    if (d.method == HttpMethod.GET && d.body.isNotBlank()) errors += DestinationError.BODY_WITH_GET
    if (d.bodyIsJson && d.body.isNotBlank()) {
        val probe = fill(d.body) { if (it in TEXT_VARS) "" else "0" }
        if (runCatching { Json.parseToJsonElement(probe) }.isFailure) errors += DestinationError.BODY_NOT_JSON
    }
    return errors
}

object DestinationPresets {
    fun standardJson(name: String, url: String, token: String) = DestinationDraft(
        name = name, enabled = true, template = TemplateKind.STANDARD_JSON, method = HttpMethod.POST,
        url = url, headers = listOf(HeaderDraft("Authorization", "Bearer $token", secret = true)),
        body = Templates.STANDARD_JSON_BODY, bodyIsJson = true, successPolicy = SuccessPolicy.ANY_2XX,
    )

    fun telegram(name: String, botToken: String, chatId: String): DestinationDraft {
        val chat = JsonPrimitive(chatId).toString()
        return DestinationDraft(
            name = name, enabled = true, template = TemplateKind.TELEGRAM, method = HttpMethod.POST,
            url = "https://api.telegram.org/bot$botToken/sendMessage", headers = emptyList(),
            body = "{\"chat_id\":$chat,\"text\":\"{sender}: {body}\"}", bodyIsJson = true,
            successPolicy = SuccessPolicy.ANY_2XX,
        )
    }

    fun ntfy(name: String, topicUrl: String) = DestinationDraft(
        name = name, enabled = true, template = TemplateKind.NTFY, method = HttpMethod.POST,
        url = topicUrl, headers = emptyList(), body = "{sender}: {body}", bodyIsJson = false,
        successPolicy = SuccessPolicy.ANY_2XX,
    )

    fun custom(name: String) = DestinationDraft(
        name = name, enabled = true, template = TemplateKind.CUSTOM, method = HttpMethod.POST,
        url = "", headers = emptyList(), body = "", bodyIsJson = true, successPolicy = SuccessPolicy.ANY_2XX,
    )
}

data class RuleDraft(
    val name: String,
    val enabled: Boolean,
    val senderMode: SenderMode,
    val senderPattern: String,
    val bodyMode: BodyMode?,
    val bodyPattern: String?,
    val sim: SimMatch,
    val destinationIds: Set<Long>,
)

enum class RuleError { NAME_EMPTY, SENDER_EMPTY, SENDER_REGEX_INVALID, BODY_EMPTY, BODY_REGEX_INVALID, NO_DESTINATIONS }

fun validateRule(r: RuleDraft): Set<RuleError> {
    val errors = linkedSetOf<RuleError>()
    if (r.name.isBlank()) errors += RuleError.NAME_EMPTY
    when (r.senderMode) {
        SenderMode.EXACT, SenderMode.CONTAINS ->
            if (SenderFilter.normalize(r.senderPattern).isEmpty()) errors += RuleError.SENDER_EMPTY
        SenderMode.REGEX -> when {
            r.senderPattern.isBlank() -> errors += RuleError.SENDER_EMPTY
            !RuleEngine.validatePattern(r.senderPattern) -> errors += RuleError.SENDER_REGEX_INVALID
        }
    }
    if (r.bodyMode != null) {
        val p = r.bodyPattern.orEmpty()
        when {
            p.isEmpty() -> errors += RuleError.BODY_EMPTY
            r.bodyMode == BodyMode.REGEX && !RuleEngine.validatePattern(p) -> errors += RuleError.BODY_REGEX_INVALID
        }
    }
    if (r.destinationIds.isEmpty()) errors += RuleError.NO_DESTINATIONS
    return errors
}

fun testSmsVars(body: String, now: Long, device: String): SmsVars =
    SmsVars(sender = "TEST", body = body, receivedAt = now, simSlot = null, device = device)
