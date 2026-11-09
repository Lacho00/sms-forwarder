package dev.smsforwarder.core

enum class DeliveryStatus { QUEUED, SENT, ERROR, CANCELED }

/**
 * `httpCode == null` means a network error. A 3xx is never followed, so it is a config error: the stored URL
 * should point at the final endpoint. Under [SuccessPolicy.STATUS_200] any other 2xx is retried.
 */
fun classifyDelivery(httpCode: Int?, responseBody: String?, policy: SuccessPolicy): SendOutcome = when {
    httpCode == null -> SendOutcome.Retry(null, "network")
    httpCode == 200 || (policy == SuccessPolicy.ANY_2XX && httpCode in 200..299) -> SendOutcome.Accepted(httpCode)
    httpCode in 300..399 -> SendOutcome.Invalid("redirect")
    httpCode == 400 || isAuthRejection(httpCode) -> SendOutcome.Rejected(httpCode, errorCodeOf(responseBody))
    else -> SendOutcome.Retry(httpCode, "http_$httpCode")
}

fun isAuthRejection(httpCode: Int?): Boolean = httpCode == 401 || httpCode == 403

/**
 * Canceled deliveries (destination deleted) are ignored. Any error wins, then any pending delivery keeps the
 * sms queued; an empty set is still queued, and a set of only canceled deliveries is an error.
 */
fun aggregateStatus(statuses: Collection<DeliveryStatus>): SmsStatus {
    if (statuses.isEmpty()) return SmsStatus.QUEUED
    val live = statuses.filter { it != DeliveryStatus.CANCELED }
    return when {
        live.isEmpty() -> SmsStatus.ERROR
        DeliveryStatus.ERROR in live -> SmsStatus.ERROR
        DeliveryStatus.QUEUED in live -> SmsStatus.QUEUED
        else -> SmsStatus.SENT
    }
}
