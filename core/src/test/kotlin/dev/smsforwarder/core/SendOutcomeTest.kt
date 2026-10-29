package dev.smsforwarder.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SendOutcomeTest {
    @Test fun classify200and2xxAccepted() {
        assertEquals(SendOutcome.Accepted, classify(200, ""))
        assertEquals(SendOutcome.Accepted, classify(204, null))
    }

    @Test fun classify401Rejected() =
        assertEquals(SendOutcome.Rejected(401, "unauthorized"), classify(401, """{"error":"unauthorized"}"""))

    @Test fun classify400ReadsErrorCode() =
        assertEquals(SendOutcome.Rejected(400, "invalid_request"), classify(400, """{"error":"invalid_request"}"""))

    @Test fun classify400WithGarbageBody() =
        assertEquals(SendOutcome.Rejected(400, null), classify(400, "<html>"))

    @Test fun networkAnd5xxAnd429Retry() {
        assertTrue(classify(null, null) is SendOutcome.Retry)
        assertTrue(classify(502, "<html>cloudflare</html>") is SendOutcome.Retry)
        assertTrue(classify(429, null) is SendOutcome.Retry)
        assertTrue(classify(404, null) is SendOutcome.Retry)
    }

    @Test fun connectionTest() {
        assertEquals(ConnectionTestResult.OK, interpretConnectionTest(400))
        assertEquals(ConnectionTestResult.BAD_TOKEN, interpretConnectionTest(401))
        assertEquals(ConnectionTestResult.UNREACHABLE, interpretConnectionTest(null))
        assertEquals(ConnectionTestResult.UNREACHABLE, interpretConnectionTest(200))
    }
}
