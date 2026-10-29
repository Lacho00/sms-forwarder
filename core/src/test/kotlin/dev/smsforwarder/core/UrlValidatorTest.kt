package dev.smsforwarder.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UrlValidatorTest {
    @Test fun urlOnlyHttpsWithHost() {
        assertTrue(UrlValidator.isValid("https://worker.example.dev/ingest/sms"))
        assertTrue(UrlValidator.isValid("HTTPS://worker.example.dev/ingest/sms"))
        assertFalse(UrlValidator.isValid("http://worker.example.dev/ingest/sms"))
        assertFalse(UrlValidator.isValid("https://"))
        assertFalse(UrlValidator.isValid("worker.example.dev"))
    }
}
