package dev.smsforwarder.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HostileInputsTest {
    private val bodies = listOf("a\"b", "l1\nl2\r\n", "back\\slash", "{body}{sender}", "  ", "\u0000", "ñ€😀")

    private fun vars(body: String, sender: String = body, device: String = body) = SmsVars(sender, body, 1_700_000_000_000L, 1, device)

    private fun render(d: DestinationDraft, body: String) = Templates.render(
        DestinationSpec(d.method, d.url.orEmpty(), d.headers.map { it.name to it.value.orEmpty() }, d.body, d.bodyIsJson),
        vars(body),
    )

    @Test fun jsonPresetsStayValid() {
        val custom = DestinationPresets.custom("C").copy(url = "https://h.example/c", body = "{\"t\":\"{body}\"}", bodyIsJson = true)
        for (b in bodies) {
            val std = Json.parseToJsonElement(render(DestinationPresets.standardJson("S", "https://h.example/s", "T"), b).body!!) as JsonObject
            assertEquals(b, std["body"]!!.jsonPrimitive.content)
            assertEquals(b, std["sender"]!!.jsonPrimitive.content)

            val withDevice = custom.copy(body = "{\"t\":\"{device}\",\"s\":\"{sender}\"}")
            val dv = Json.parseToJsonElement(render(withDevice, b).body!!) as JsonObject
            assertEquals(b, dv["t"]!!.jsonPrimitive.content)
            assertEquals(b, dv["s"]!!.jsonPrimitive.content)

            val tg = Json.parseToJsonElement(render(DestinationPresets.telegram("T", "BOT", "123"), b).body!!) as JsonObject
            assertEquals("$b: $b", tg["text"]!!.jsonPrimitive.content)

            val cu = Json.parseToJsonElement(render(custom, b).body!!) as JsonObject
            assertEquals(b, cu["t"]!!.jsonPrimitive.content)
        }
    }

    @Test fun ntfyTextBodyIsLiteral() {
        val ntfy = DestinationPresets.ntfy("N", "https://h.example/t").copy(body = "TEST: {body}")
        for (b in bodies) assertEquals("TEST: $b", render(ntfy, b).body)
    }

    private fun encoded(s: String) = java.net.URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")

    @Test fun urlVariableCannotChangeHost() {
        val d = DestinationPresets.custom("U").copy(url = "https://h.example/x?b={body}", body = "")
        val hostile = "x@evil.example/#"
        val uri = URI(render(d, hostile).url)
        assertEquals("h.example", uri.host)
        assertEquals(encoded(hostile), uri.rawQuery.removePrefix("b="))
        assertEquals(null, uri.fragment)
        val spaced = URI(render(d, "a b").url)
        assertEquals("b=a%20b", spaced.rawQuery)
    }

    @Test fun urlWithSenderDeviceAndLineBreaksStaysOnHost() {
        val d = DestinationPresets.custom("U").copy(url = "https://h.example/{sender}/p?d={device}&b={body}", body = "")
        for (b in bodies + "x@evil.example/#\r\n") {
            val url = render(d, b).url
            assertFalse(url.contains('\r') || url.contains('\n') || url.contains(' '), url)
            val uri = URI(url)
            assertEquals("h.example", uri.host, url)
            assertEquals(null, uri.fragment, url)
            assertEquals("d=${encoded(b)}&b=${encoded(b)}", uri.rawQuery, url)
        }
    }

    // OkHttp accepts a header value only of tab and 0x20..0x7E.
    private fun legalHeaderValue(v: String) = v.all { it == '\t' || it.code in 0x20..0x7E }

    @Test fun headerVariableCannotInject() {
        val d = DestinationPresets.custom("H").copy(
            url = "https://h.example/x",
            headers = listOf(HeaderDraft("X-S", "{body}", secret = false), HeaderDraft("X-D", "{device}-{sender}", secret = false)),
        )
        for (b in bodies + "a\r\nX-Injected: 1") {
            val headers = render(d, b).headers
            for ((name, value) in headers) {
                assertFalse(name.contains('\r') || name.contains('\n'))
                assertTrue(legalHeaderValue(value), "illegal header value for ${b.length}-char body")
            }
            assertEquals(listOf("X-S", "X-D"), headers.map { it.first })
        }
    }

    private fun file(vararg dests: String, rules: String = "[]") =
        "{\"format\":\"$CONFIG_FORMAT\",\"version\":1,\"destinations\":[${dests.joinToString(",")}],\"rules\":$rules}"

    private fun dest(url: String) =
        "{\"key\":\"d1\",\"name\":\"Main\",\"enabled\":true,\"template\":\"CUSTOM\",\"method\":\"POST\"," +
            "\"url\":\"$url\",\"headers\":[],\"body\":\"{}\",\"bodyIsJson\":true,\"successPolicy\":\"ANY_2XX\"}"

    @Test fun catastrophicRegexInImportedRule() {
        val rule = "{\"name\":\"R\",\"enabled\":true,\"senderMode\":\"EXACT\",\"senderPattern\":\"AAA\"," +
            "\"bodyMode\":\"REGEX\",\"bodyPattern\":\"(a+)+\\\\1$\",\"sim\":\"ANY\",\"destinations\":[\"d1\"]}"
        // Nested unbounded quantifiers are refused at import time.
        val parsed = assertIs<ConfigParse.Invalid>(ConfigCodec.decode(file(dest("https://h.example/a"), rules = "[$rule]")))
        assertEquals(ConfigError.INVALID_RULE, parsed.error)
    }

    @Test fun httpVariants() {
        for (u in listOf("HTTP://h.example", "http://h.example", "https://", "ftp://h.example", "//h.example", "javascript:alert(1)")) {
            val draft = DestinationPresets.ntfy("N", u)
            assertTrue(validateDestination(draft, urlStored = false).isNotEmpty(), u)
            assertFalse(UrlValidator.isValid(u), u)
            assertIs<ConfigParse.Invalid>(ConfigCodec.decode(file(dest(u))), u)
        }
    }

    @Test fun userinfoAndLeadingSpaceUrls() {
        // Recorded behavior: userinfo is harmless (the host stays h.example) and a leading space is trimmed.
        val userinfo = DestinationPresets.ntfy("N", "https://a@h.example")
        assertTrue(validateDestination(userinfo, urlStored = false).isEmpty())
        assertEquals("h.example", URI("https://a@h.example").host)
        val spaced = DestinationPresets.ntfy("N", " https://h.example")
        assertTrue(validateDestination(spaced, urlStored = false).isEmpty())
        assertTrue(UrlValidator.isValid(" https://h.example"))
    }
}
