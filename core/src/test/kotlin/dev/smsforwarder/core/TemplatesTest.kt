package dev.smsforwarder.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class TemplatesTest {
    private fun vars(body: String, sender: String = "ACME", at: Long = 1730000000000L) =
        SmsVars(sender, body, at, null, "dev")

    @Test fun standardJsonIsByteIdenticalToV1() {
        val url = "https://h.example/ingest"
        val bodies = listOf("hola", "di \"sí\"", "línea1\nlínea2", "c:\\ruta", "{body} literal", "emoji 😀\t")
        for (b in bodies) {
            val v = vars(b)
            val r = Templates.render(Templates.standardJson(url, "t"), v)
            assertEquals(IngestPayload.encode(v.sender, b, v.receivedAt), r.body)
            assertEquals(HttpMethod.POST, r.method)
            assertEquals(listOf("Authorization" to "Bearer t"), r.headers)
            assertEquals("application/json; charset=utf-8", r.contentType)
            assertEquals(url, r.url)
        }
    }

    @Test fun bodyContainingVariableNameIsNotExpandedTwice() {
        val r = Templates.render(Templates.standardJson("https://h/x", "t"), vars("{sender}"))
        assertEquals(true, r.body!!.contains("\"body\":\"{sender}\""))
    }

    @Test fun jsonEscapeBlocksInjection() {
        val spec = DestinationSpec(HttpMethod.POST, "https://h/x", emptyList(), "{\"t\":\"{body}\"}", true)
        val r = Templates.render(spec, vars("\",\"x\":\"1"))
        val obj = Json.parseToJsonElement(r.body!!) as JsonObject
        assertEquals(setOf("t"), obj.keys)
        assertEquals("\",\"x\":\"1", obj["t"]!!.jsonPrimitive.content)
    }

    @Test fun urlVariablesAreEncoded() {
        val spec = DestinationSpec(HttpMethod.GET, "https://h/x?m={body}", emptyList(), null, false)
        assertEquals("https://h/x?m=a%20b%26c", Templates.render(spec, vars("a b&c")).url)
    }

    @Test fun headerVariablesDropLineBreaks() {
        val spec = DestinationSpec(HttpMethod.POST, "https://h/x", listOf("X" to "{body}"), null, false)
        val v = Templates.render(spec, vars("a\r\nb")).headers.single().second
        assertFalse(v.contains('\r') || v.contains('\n'))
    }

    @Test fun receivedAtIsMillisDigits() {
        val spec = DestinationSpec(HttpMethod.POST, "https://h/x", emptyList(), "{received_at} {received_at_ms}", false)
        assertEquals("1730000000000 1730000000000", Templates.render(spec, vars("x")).body)
    }

    @Test fun unknownVariablesListed() {
        assertEquals(setOf("foo"), Templates.unknownVariables("{sender} {foo} {body}"))
    }

    @Test fun toStringHidesSecrets() {
        val r = Templates.render(Templates.standardJson("https://secret.example/path", "tok-123"), vars("cuerpo-privado"))
        val s = r.toString()
        for (x in listOf("secret.example", "tok-123", "cuerpo-privado")) assertFalse(s.contains(x))
        assertNotNull(s)
    }
}
