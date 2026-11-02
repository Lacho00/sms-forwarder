package dev.smsforwarder.core

enum class DeliveryStatus { QUEUED, SENT, ERROR }

/** `httpCode == null` means a network error. Under [SuccessPolicy.STATUS_200] any other 2xx is retried. */
fun classifyDelivery(httpCode: Int?, responseBody: String?, policy: SuccessPolicy): SendOutcome = when {
    httpCode == null -> SendOutcome.Retry(null, "network")
    httpCode == 200 || (policy == SuccessPolicy.ANY_2XX && httpCode in 200..299) -> SendOutcome.Accepted
    httpCode == 400 || isAuthRejection(httpCode) -> SendOutcome.Rejected(httpCode, errorCodeOf(responseBody))
    else -> SendOutcome.Retry(httpCode, "http_$httpCode")
}

fun isAuthRejection(httpCode: Int?): Boolean = httpCode == 401 || httpCode == 403

/** Any error wins, then any pending delivery keeps the sms queued; an empty set is still queued. */
fun aggregateStatus(statuses: Collection<DeliveryStatus>): SmsStatus = when {
    statuses.isEmpty() -> SmsStatus.QUEUED
    DeliveryStatus.ERROR in statuses -> SmsStatus.ERROR
    DeliveryStatus.QUEUED in statuses -> SmsStatus.QUEUED
    else -> SmsStatus.SENT
}
