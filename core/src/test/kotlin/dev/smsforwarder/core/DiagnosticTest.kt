package dev.smsforwarder.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticTest {
    @Test
    fun errorKeys() {
        assertEquals("http_403", errorKey(403, "invalid_token"))
        assertEquals("network", errorKey(null, "network"))
        assertEquals("config_template", errorKey(null, "config_template"))
        assertEquals("destination_deleted", errorKey(null, "destination_deleted"))
        assertEquals("other", errorKey(null, "leak https://h.example"))
        assertEquals("other", errorKey(null, null))
        assertEquals("other", errorKey(null, "config_Bad Value"))
    }

    private fun input() = DiagnosticInput(
        generatedAt = 1_800_000_000_000L,
        appVersion = "2.0.0",
        versionCode = 20,
        androidRelease = "15",
        sdkInt = 35,
        device = "Acme Phone",
        miui = false,
        autostart = null,
        issues = listOf("NO_RULES"),
        forwardingEnabled = true,
        sms = mapOf(SmsStatus.QUEUED to 3, SmsStatus.SENT to 5),
        destinations = listOf(
            DestinationDiag(7, TemplateKind.TELEGRAM, enabled = true, configured = false, counts = mapOf(DeliveryStatus.QUEUED to 2)),
        ),
        errors = mapOf("http_403" to 2),
        rules = 4,
        activeRules = 1,
        events = listOf(EngineEvent(1_800_000_000_000L, EngineEventType.SEND, smsId = 9, destinationId = 7, httpCode = 200)),
    )

    @Test
    fun rendersAllSections() {
        val text = renderDiagnostic(input())
        for (h in listOf("App", "Device", "Health", "Queue", "Destinations", "Errors", "Rules", "Events")) {
            assertTrue(text.contains("== $h =="), h)
        }
        assertTrue(text.contains("2.0.0"))
        assertTrue(text.contains("NO_RULES"))
        assertTrue(text.contains("queued: 3"))
        assertTrue(text.contains("id=7"))
        assertTrue(text.contains("TELEGRAM"))
        assertTrue(text.contains("http_403: 2"))
        assertTrue(text.contains("active: 1"))
        assertTrue(text.contains("SEND sms=9 dest=7 http=200"))
        assertTrue(text.contains("2027-01-15T08:00:00Z"))
        assertFalse(text.contains("1800000000000"))
    }
}
