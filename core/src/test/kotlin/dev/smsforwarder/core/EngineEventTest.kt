package dev.smsforwarder.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EngineEventTest {
    @Test fun lineRoundTrip() {
        val full = EngineEvent(1_700_000_000_000L, EngineEventType.SEND, smsId = 3, destinationId = 2, ruleId = 9, httpCode = 403, code = "rejected", count = 7)
        assertEquals(full, parseEngineEvent(full.toLine()))
        val bare = EngineEvent(5L, EngineEventType.SAFE_MODE, code = "legacy_import")
        assertEquals("5 SAFE_MODE code=legacy_import", bare.toLine())
        assertEquals(bare, parseEngineEvent(bare.toLine()))
    }

    @Test fun unknownLineIsNull() {
        assertNull(parseEngineEvent(""))
        assertNull(parseEngineEvent("garbage"))
        assertNull(parseEngineEvent("12 NOPE sms=1"))
        assertNull(parseEngineEvent("x SEND sms=1"))
        assertNull(parseEngineEvent("12 SEND sms=abc"))
    }

    @Test fun safeCodeRejectsFreeText() {
        assertEquals("other", safeCode("https://h.example x"))
        assertEquals("config_template", safeCode("config_template"))
        assertEquals("other", safeCode(""))
        assertEquals("other", safeCode("a".repeat(41)))
        assertNull(safeCode(null))
    }

    @Test fun toLineSanitizesCode() {
        val line = EngineEvent(1L, EngineEventType.SEND, code = "https://h.example x\nfoo=bar").toLine()
        assertEquals("1 SEND code=other", line)
        assertEquals("other", parseEngineEvent(line)?.code)
    }
}
