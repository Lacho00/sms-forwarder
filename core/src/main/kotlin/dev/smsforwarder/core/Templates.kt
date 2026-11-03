package dev.smsforwarder.core

import kotlinx.serialization.json.JsonPrimitive
import java.net.URLEncoder

data class SmsVars(val sender: String, val body: String, val receivedAt: Long, val simSlot: Int?, val device: String) {
    override fun toString(): String = "SmsVars(receivedAt=$receivedAt, simSlot=$simSlot)"
}

enum class HttpMethod { POST, PUT, GET }

enum class SuccessPolicy { STATUS_200, ANY_2XX }

enum class TemplateKind { STANDARD_JSON, TELEGRAM, NTFY, CUSTOM }

data class DestinationSpec(
    val method: HttpMethod,
    val url: String,
    val headers: List<Pair<String, String>>,
    val body: String?,
    val bodyIsJson: Boolean,
) {
    override fun toString(): String =
        "DestinationSpec(method=$method, headers=${headers.size}, hasBody=${body != null}, bodyIsJson=$bodyIsJson)"
}

/** `toString()` leaves out url, headers and body: they carry tokens and message text. */
data class RenderedRequest(
    val method: HttpMethod,
    val url: String,
    val headers: List<Pair<String, String>>,
    val body: String?,
    val contentType: String?,
) {
    override fun toString(): String = "RenderedRequest(method=$method, headers=${headers.size}, hasBody=${body != null})"
}

object Templates {
    val VARIABLES: Set<String> = setOf("sender", "body", "received_at", "received_at_ms", "sim", "device")

    const val STANDARD_JSON_BODY = "{\"sender\":\"{sender}\",\"body\":\"{body}\",\"received_at\":{received_at}}"

    private val PLACEHOLDER = Regex("\\{([a-z_]+)\\}")
    private const val JSON_CONTENT_TYPE = "application/json; charset=utf-8"
    private const val TEXT_CONTENT_TYPE = "text/plain; charset=utf-8"

    fun unknownVariables(text: String): Set<String> =
        PLACEHOLDER.findAll(text).map { it.groupValues[1] }.filter { it !in VARIABLES }.toSet()

    fun render(spec: DestinationSpec, vars: SmsVars): RenderedRequest {
        val url = substitute(spec.url, vars) { urlEncode(it) }
        val headers = spec.headers.map { (k, v) -> k to substitute(v, vars) { urlEncode(it) } }
        val body = spec.body?.let { b ->
            substitute(b, vars) { if (spec.bodyIsJson) jsonEscape(it) else it }
        }
        val contentType = when {
            body == null -> null
            spec.bodyIsJson -> JSON_CONTENT_TYPE
            else -> TEXT_CONTENT_TYPE
        }
        return RenderedRequest(spec.method, url, headers, body, contentType)
    }

    fun standardJson(url: String, token: String): DestinationSpec = DestinationSpec(
        method = HttpMethod.POST,
        url = url,
        headers = listOf("Authorization" to "Bearer $token"),
        body = STANDARD_JSON_BODY,
        bodyIsJson = true,
    )

    /** One pass over the template: inserted text is never scanned again. Unknown names stay literal. */
    private fun substitute(template: String, vars: SmsVars, escape: (String) -> String): String =
        PLACEHOLDER.replace(template) { m ->
            when (m.groupValues[1]) {
                "sender" -> escape(vars.sender)
                "body" -> escape(vars.body)
                "device" -> escape(vars.device)
                "received_at", "received_at_ms" -> vars.receivedAt.toString()
                "sim" -> vars.simSlot?.toString() ?: ""
                else -> m.value
            }
        }

    private fun jsonEscape(s: String): String = JsonPrimitive(s).toString().removeSurrounding("\"")

    private fun urlEncode(s: String): String = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
}
