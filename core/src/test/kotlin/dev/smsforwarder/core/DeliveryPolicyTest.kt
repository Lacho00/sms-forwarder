package dev.smsforwarder.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeliveryPolicyTest {
    @Test fun any2xxAccepts() {
        for (c in listOf(200, 201, 204)) assertEquals(SendOutcome.Accepted, classifyDelivery(c, null, SuccessPolicy.ANY_2XX))
        assertEquals(SendOutcome.Accepted, classifyDelivery(200, null, SuccessPolicy.STATUS_200))
        assertEquals(SendOutcome.Retry(201, "http_201"), classifyDelivery(201, null, SuccessPolicy.STATUS_200))
    }

    @Test fun authRejections() {
        for (c in listOf(401, 403))
            assertEquals(SendOutcome.Rejected(c, "x"), classifyDelivery(c, """{"error":"x"}""", SuccessPolicy.ANY_2XX))
        assertTrue(isAuthRejection(401) && isAuthRejection(403))
        assertTrue(!isAuthRejection(400) && !isAuthRejection(null))
    }

    @Test fun badRequest() =
        assertEquals(SendOutcome.Rejected(400, null), classifyDelivery(400, "<html>", SuccessPolicy.ANY_2XX))

    @Test fun transient() {
        assertEquals(SendOutcome.Retry(null, "network"), classifyDelivery(null, null, SuccessPolicy.ANY_2XX))
        for (c in listOf(429, 500, 302)) assertTrue(classifyDelivery(c, null, SuccessPolicy.ANY_2XX) is SendOutcome.Retry)
    }

    @Test fun aggregate() {
        val s = DeliveryStatus.SENT; val q = DeliveryStatus.QUEUED; val e = DeliveryStatus.ERROR
        assertEquals(SmsStatus.QUEUED, aggregateStatus(listOf(s, q)))
        assertEquals(SmsStatus.ERROR, aggregateStatus(listOf(s, e, q)))
        assertEquals(SmsStatus.SENT, aggregateStatus(listOf(s, s)))
        assertEquals(SmsStatus.QUEUED, aggregateStatus(emptyList()))
    }
}
