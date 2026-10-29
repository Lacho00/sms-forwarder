package dev.smsforwarder.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class IngestPayloadTest {
    @Test fun encodesReceivedAtAsNumberAndEscapes() {
        val json = Json.parseToJsonElement(IngestPayload.encode("OPERADOR", "a \"b\"\nc", 1_792_260_900_000)).jsonObject
        assertEquals("OPERADOR", json["sender"]!!.jsonPrimitive.content)
        assertEquals("a \"b\"\nc", json["body"]!!.jsonPrimitive.content)
        assertFalse(json["received_at"]!!.jsonPrimitive.isString)
        assertEquals(1_792_260_900_000, json["received_at"]!!.jsonPrimitive.long)
    }
}
