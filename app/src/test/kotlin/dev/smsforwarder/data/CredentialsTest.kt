package dev.smsforwarder.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CredentialsTest {
    @Test fun toStringRedacts() {
        val c = Credentials("https://example.invalid/ingest", "test-token")
        val text = c.toString()
        assertEquals("Credentials(***)", text)
        assertFalse(text.contains("example.invalid"))
        assertFalse(text.contains("test-token"))
    }
}
