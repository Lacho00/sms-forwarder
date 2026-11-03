package dev.smsforwarder.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DraftsTest {
    private fun custom(
        url: String? = "https://h.example",
        body: String = "",
        json: Boolean = true,
        method: HttpMethod = HttpMethod.POST,
        headers: List<HeaderDraft> = emptyList(),
    ) = DestinationPresets.custom("C").copy(url = url, body = body, bodyIsJson = json, method = method, headers = headers)

    @Test fun httpUrlRejected() {
        assertTrue(DestinationError.URL_NOT_HTTPS in validateDestination(custom(url = "http://h.example"), false))
        assertFalse(DestinationError.URL_MISSING in validateDestination(custom(url = null), true))
        assertTrue(DestinationError.URL_MISSING in validateDestination(custom(url = null), false))
    }

    @Test fun urlWithVariablesValidatedAfterSubstitution() {
        assertEquals(emptySet(), validateDestination(custom(url = "https://h.example/x?s={sender}"), false))
    }

    @Test fun unknownVariableInAnyField() {
        val u = validateDestination(custom(url = "https://h.example/{foo}"), false)
        val h = validateDestination(custom(headers = listOf(HeaderDraft("X-A", "{foo}", false))), false)
        val b = validateDestination(custom(body = "{\"a\":\"{foo}\"}"), false)
        for (r in listOf(u, h, b)) assertTrue(DestinationError.UNKNOWN_VARIABLE in r)
    }

    @Test fun jsonBodyRequiresQuotedTextVariables() {
        assertTrue(DestinationError.BODY_NOT_JSON in validateDestination(custom(body = "{\"s\":{sim}}"), false))
        assertTrue(DestinationError.BODY_NOT_JSON in validateDestination(custom(body = "{\"b\":{body}}"), false))
        assertEquals(
            emptySet(),
            validateDestination(custom(body = "{\"s\":\"{sim}\",\"b\":\"{body}\",\"t\":{received_at}}"), false),
        )
        assertEquals(emptySet(), validateDestination(custom(body = "{sim}", json = false), false))
    }

    @Test fun standardPresetIsValidAndMatchesV1() {
        val d = DestinationPresets.standardJson("Main", "https://h.example", "t")
        assertEquals(emptySet(), validateDestination(d, false))
        assertEquals(Templates.STANDARD_JSON_BODY, d.body)
        assertEquals(listOf(HeaderDraft("Authorization", "Bearer t", secret = true)), d.headers)
        assertEquals(SuccessPolicy.ANY_2XX, d.successPolicy)
    }

    @Test fun telegramPreset() {
        val d = DestinationPresets.telegram("T", "123:abc", "-100")
        assertEquals("https://api.telegram.org/bot123:abc/sendMessage", d.url)
        assertEquals("{\"chat_id\":\"-100\",\"text\":\"{sender}: {body}\"}", d.body)
        assertTrue(d.bodyIsJson)
        assertEquals(emptySet(), validateDestination(d, false))
        val q = DestinationPresets.telegram("T", "123:abc", "a\"b")
        assertEquals(emptySet(), validateDestination(q, false))
        assertEquals("a\"b", Json.parseToJsonElement(q.body).jsonObject["chat_id"]!!.jsonPrimitive.content)
    }

    @Test fun ntfyPreset() {
        val d = DestinationPresets.ntfy("N", "https://h.example/topic")
        assertEquals(HttpMethod.POST, d.method)
        assertFalse(d.bodyIsJson)
        assertEquals("{sender}: {body}", d.body)
        assertTrue(d.headers.isEmpty())
        assertEquals(emptySet(), validateDestination(d, false))
    }

    @Test fun getWithBodyRejected() {
        assertTrue(DestinationError.BODY_WITH_GET in validateDestination(custom(method = HttpMethod.GET, body = "x", json = false), false))
    }

    @Test fun headerChecks() {
        fun h(n: String, v: String) = validateDestination(custom(headers = listOf(HeaderDraft(n, v, false))), false)
        assertTrue(DestinationError.HEADER_NAME_INVALID in h("X Bad", "a"))
        assertTrue(DestinationError.HEADER_VALUE_INVALID in h("X-A", "a\nb"))
        assertTrue(DestinationError.HEADER_VALUE_INVALID in h("X-A", "ñ"))
        assertEquals(emptySet(), h("Authorization", "Bearer {sender}"))
    }

    @Test fun ruleValidation() {
        val ok = RuleDraft("R", true, SenderMode.EXACT, "AAA", null, null, SimMatch.ANY, setOf(1L))
        assertEquals(emptySet(), validateRule(ok))
        assertTrue(RuleError.SENDER_REGEX_INVALID in validateRule(ok.copy(senderMode = SenderMode.REGEX, senderPattern = "(")))
        assertTrue(RuleError.SENDER_EMPTY in validateRule(ok.copy(senderPattern = "  ")))
        assertTrue(RuleError.BODY_REGEX_INVALID in validateRule(ok.copy(bodyMode = BodyMode.REGEX, bodyPattern = "(a")))
        assertTrue(RuleError.BODY_EMPTY in validateRule(ok.copy(bodyMode = BodyMode.CONTAINS, bodyPattern = "")))
        assertTrue(RuleError.NO_DESTINATIONS in validateRule(ok.copy(destinationIds = emptySet())))
        assertTrue(RuleError.NAME_EMPTY in validateRule(ok.copy(name = " ")))
    }

    @Test fun draftToStringHidesSecrets() {
        val d = DestinationPresets.standardJson("Main", "https://h.example/path", "tok-123")
        val s = d.toString()
        assertFalse("h.example" in s)
        assertFalse("tok-123" in s)
        assertFalse("tok-123" in d.headers[0].toString())
    }

    @Test fun testSmsVarsShape() {
        val v = testSmsVars("b", 5L, "dev")
        assertEquals("TEST", v.sender)
        assertEquals(null, v.simSlot)
        assertEquals(5L, v.receivedAt)
    }
}
