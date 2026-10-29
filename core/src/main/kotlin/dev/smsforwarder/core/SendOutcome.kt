package dev.smsforwarder.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

sealed interface SendOutcome {
    data object Accepted : SendOutcome
    data class Retry(val httpCode: Int?, val reason: String) : SendOutcome
    data class Rejected(val httpCode: Int, val errorCode: String?) : SendOutcome
}

enum class ConnectionTestResult { OK, BAD_TOKEN, UNREACHABLE }

/** `httpCode == null` means a network error. A 2xx is accepted without reading the body. */
fun classify(httpCode: Int?, responseBody: String?): SendOutcome = when {
    httpCode == null -> SendOutcome.Retry(null, "network")
    httpCode in 200..299 -> SendOutcome.Accepted
    httpCode == 400 || httpCode == 401 -> SendOutcome.Rejected(httpCode, errorCodeOf(responseBody))
    else -> SendOutcome.Retry(httpCode, "http_$httpCode")
}

/** The test request is sent with an empty body: 400 proves url and token are good, 401 the token is not. */
fun interpretConnectionTest(httpCode: Int?): ConnectionTestResult = when (httpCode) {
    400 -> ConnectionTestResult.OK
    401 -> ConnectionTestResult.BAD_TOKEN
    else -> ConnectionTestResult.UNREACHABLE
}

private fun errorCodeOf(body: String?): String? {
    if (body.isNullOrBlank()) return null
    return runCatching {
        (Json.parseToJsonElement(body) as? JsonObject)?.get("error")?.jsonPrimitive?.contentOrNull
    }.getOrNull()
}
