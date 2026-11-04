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

    private fun vars(body: String, sender: String = "AAA") = SmsVars(sender, body, 1_700_000_000_000L, 1, "DEV")

    private fun render(d: DestinationDraft, body: String) = Templates.render(
        DestinationSpec(d.method, d.url.orEmpty(), d.headers.map { it.name to it.value.orEmpty() }, d.body, d.bodyIsJson),
        vars(body),
    )

    @Test fun jsonPresetsStayValid() {
        val custom = DestinationPresets.custom("C").copy(url = "https://h.example/c", body = "{\"t\":\"{body}\"}", bodyIsJson = true)
        for (b in bodies) {
            val std = Json.parseToJsonElement(render(DestinationPresets.standardJson("S", "https://h.example/s", "T"), b).body!!) as JsonObject
            assertEquals(b, std["body"]!!.jsonPrimitive.content)
            assertEquals("AAA", std["sender"]!!.jsonPrimitive.content)

            val tg = Json.parseToJsonElement(render(DestinationPresets.telegram("T", "BOT", "123"), b).body!!) as JsonObject
            assertEquals("AAA: $b", tg["text"]!!.jsonPrimitive.content)

            val cu = Json.parseToJsonElement(render(custom, b).body!!) as JsonObject
            assertEquals(b, cu["t"]!!.jsonPrimitive.content)
        }
    }

    @Test fun ntfyTextBodyIsLiteral() {
        val ntfy = DestinationPresets.ntfy("N", "https://h.example/t").copy(body = "TEST: {body}")
        for (b in bodies) assertEquals("TEST: $b", render(ntfy, b).body)
    }

    @Test fun urlVariableCannotChangeHost() {
        val d = DestinationPresets.custom("U").copy(url = "https://h.example/x?b={body}", body = "")
        val url = render(d, "x@evil.example/#").url
        assertEquals("h.example", URI(url).host)
    }

    @Test fun headerVariableCannotInject() {
        val d = DestinationPresets.custom("H").copy(
            url = "https://h.example/x", headers = listOf(HeaderDraft("X-S", "{body}", secret = false)),
        )
        val value = render(d, "a\r\nX-Injected: 1").headers.single().second
        assertFalse(value.contains('\r') || value.contains('\n'))
        assertTrue(value.all { it.code in 0x20..0x7E })
    }

    private fun file(vararg dests: String, rules: String = "[]") =
        "{\"format\":\"$CONFIG_FORMAT\",\"version\":1,\"destinations\":[${dests.joinToString(",")}],\"rules\":$rules}"

    private fun dest(url: String) =
        "{\"key\":\"d1\",\"name\":\"Main\",\"enabled\":true,\"template\":\"CUSTOM\",\"method\":\"POST\"," +
            "\"url\":\"$url\",\"headers\":[],\"body\":\"{}\",\"bodyIsJson\":true,\"successPolicy\":\"ANY_2XX\"}"

    @Test fun catastrophicRegexInImportedRule() {
        val rule = "{\"name\":\"R\",\"enabled\":true,\"senderMode\":\"EXACT\",\"senderPattern\":\"AAA\"," +
            "\"bodyMode\":\"REGEX\",\"bodyPattern\":\"(a+)+\\\\1$\",\"sim\":\"ANY\",\"destinations\":[\"d1\"]}"
        val parsed = assertIs<ConfigParse.Ok>(ConfigCodec.decode(file(dest("https://h.example/a"), rules = "[$rule]")))
        val r = parsed.file.rules.single().draft
        val spec = RuleSpec(1, true, r.senderMode, r.senderPattern, r.bodyMode, r.bodyPattern, r.sim, setOf(1L))
        val m = RuleEngine.destinationsFor(IncomingSms("AAA", "a".repeat(30) + "b", null), listOf(spec))
        assertEquals(setOf(1L), m.timedOutRuleIds)
        assertTrue(m.destinationIds.isEmpty())
    }

    @Test fun httpVariants() {
        for (u in listOf("HTTP://h.example", "http://h.example", "https://", "ftp://h.example")) {
            val draft = DestinationPresets.ntfy("N", u)
            assertTrue(validateDestination(draft, urlStored = false).isNotEmpty(), u)
            assertFalse(UrlValidator.isValid(u), u)
            assertIs<ConfigParse.Invalid>(ConfigCodec.decode(file(dest(u))), u)
        }
    }
}
