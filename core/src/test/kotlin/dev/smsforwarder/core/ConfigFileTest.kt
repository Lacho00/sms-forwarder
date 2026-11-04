package dev.smsforwarder.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ConfigFileTest {
    private fun dest(
        name: String,
        template: TemplateKind = TemplateKind.CUSTOM,
        url: String? = "https://h.example/a",
        headers: List<HeaderDraft> = emptyList(),
        body: String = "{\"t\":\"{body}\"}",
        bodyIsJson: Boolean = true,
        method: HttpMethod = HttpMethod.POST,
    ) = DestinationDraft(name, true, template, method, url, headers, body, bodyIsJson, SuccessPolicy.ANY_2XX)

    private fun rule(name: String = "R", pattern: String = "AAA", mode: SenderMode = SenderMode.EXACT) =
        RuleDraft(name, true, mode, pattern, null, null, SimMatch.ANY, emptySet())

    private fun full() = ConfigFile(
        destinations = listOf(
            ConfigDestination("d1", DestinationPresets.standardJson("Main", "https://h.example/in", "TOKEN")),
            ConfigDestination("d2", DestinationPresets.telegram("Tg", "BOT", "123")),
            ConfigDestination("d3", DestinationPresets.ntfy("Ntfy", "https://h.example/topic")),
        ),
        rules = listOf(
            ConfigRule(rule("One"), listOf("d1", "d2")),
            ConfigRule(
                RuleDraft("Two", false, SenderMode.REGEX, "A+", BodyMode.NOT_CONTAINS, "x", SimMatch.SIM2, emptySet()),
                listOf("d3"),
            ),
        ),
        includesSecrets = true,
    )

    private fun wrap(dests: String = "[]", rules: String = "[]", extra: String = "") =
        "{\"format\":\"$CONFIG_FORMAT\",\"version\":1,\"destinations\":$dests,\"rules\":$rules$extra}"

    private fun destJson(
        key: String = "d1",
        name: String = "Main",
        url: String = "\"https://h.example/a\"",
        headers: String = "[]",
        body: String = "\"{\\\"t\\\":1}\"",
        method: String = "POST",
        enabled: String = "true",
    ) = "{\"key\":\"$key\",\"name\":\"$name\",\"enabled\":$enabled,\"template\":\"CUSTOM\",\"method\":\"$method\"," +
        "\"url\":$url,\"headers\":$headers,\"body\":$body,\"bodyIsJson\":true,\"successPolicy\":\"ANY_2XX\"}"

    private fun ruleJson(name: String = "R", pattern: String = "AAA", mode: String = "EXACT", dests: String = "[\"d1\"]") =
        "{\"name\":\"$name\",\"enabled\":true,\"senderMode\":\"$mode\",\"senderPattern\":\"$pattern\"," +
            "\"bodyMode\":null,\"bodyPattern\":null,\"sim\":\"ANY\",\"destinations\":$dests}"

    private fun invalid(text: String): ConfigParse.Invalid = assertIs<ConfigParse.Invalid>(ConfigCodec.decode(text))

    @Test
    fun roundTripWithSecrets() {
        val f = full()
        val parsed = assertIs<ConfigParse.Ok>(ConfigCodec.decode(ConfigCodec.encode(f, "2.0.0")))
        assertEquals(f, parsed.file)
    }

    @Test
    fun withoutSecretsHasNoUrlNorSecretValues() {
        val f = ConfigFile(
            destinations = listOf(
                ConfigDestination(
                    "d1",
                    dest("Main", url = null, headers = listOf(HeaderDraft("Authorization", null, secret = true))),
                ),
            ),
            rules = emptyList(),
            includesSecrets = false,
        )
        val text = ConfigCodec.encode(f, "2.0.0")
        assertFalse(text.contains("https://h.example"))
        assertFalse(text.contains("Bearer"))
        assertTrue(text.contains("\"Authorization\""))
        assertTrue(text.contains("\"secrets\": false"))
        assertEquals(f, assertIs<ConfigParse.Ok>(ConfigCodec.decode(text)).file)
    }

    @Test
    fun versionAndFormatChecked() {
        assertEquals(ConfigError.NEWER_VERSION, invalid(wrap().replace("\"version\":1", "\"version\":2")).error)
        assertEquals(ConfigError.WRONG_FORMAT, invalid("{\"version\":1,\"destinations\":[],\"rules\":[]}").error)
        assertEquals(ConfigError.WRONG_FORMAT, invalid(wrap().replace(CONFIG_FORMAT, "other/thing")).error)
        assertIs<ConfigParse.Ok>(ConfigCodec.decode(wrap(extra = ",\"unknown\":{\"a\":1}")))
    }

    @Test
    fun everythingValidated() {
        val http = invalid(wrap(dests = "[${destJson(url = "\"http://h.example\"")}]"))
        assertEquals(ConfigError.INVALID_DESTINATION, http.error)
        assertEquals("Main", http.item)
        assertEquals(
            ConfigError.INVALID_DESTINATION,
            invalid(wrap(dests = "[${destJson(body = "\"{foo}\"")}]")).error,
        )
        assertEquals(
            ConfigError.INVALID_RULE,
            invalid(wrap(dests = "[${destJson()}]", rules = "[${ruleJson(pattern = "(", mode = "REGEX")}]")).error,
        )
        val pub = "[{\"name\":\"X\",\"value\":null,\"secret\":false}]"
        assertEquals(ConfigError.BAD_VALUE, invalid(wrap(dests = "[${destJson(headers = pub)}]")).error)
        assertEquals(ConfigError.BAD_VALUE, invalid(wrap(dests = "[${destJson(method = "PATCH")}]")).error)
    }

    @Test
    fun rulesPointAtKeys() {
        val unknown = invalid(wrap(dests = "[${destJson()}]", rules = "[${ruleJson(dests = "[\"zz\"]")}]"))
        assertEquals(ConfigError.UNKNOWN_DESTINATION, unknown.error)
        assertEquals("R", unknown.item)
        assertEquals(
            ConfigError.DUPLICATE_KEY,
            invalid(wrap(dests = "[${destJson()},${destJson(name = "B")}]")).error,
        )
        val ok = assertIs<ConfigParse.Ok>(ConfigCodec.decode(wrap(rules = "[${ruleJson(dests = "[]")}]")))
        assertEquals(emptyList(), ok.file.rules.single().destinationKeys)
    }

    @Test
    fun hostileFilesAreInvalid() {
        val deep = try {
            ConfigCodec.decode("[".repeat(10_000))
        } catch (e: StackOverflowError) {
            throw AssertionError("StackOverflowError escaped", e)
        }
        assertEquals(ConfigError.NOT_JSON, assertIs<ConfigParse.Invalid>(deep).error)
        assertEquals(ConfigError.TOO_LARGE, invalid(" ".repeat(CONFIG_MAX_BYTES + 1)).error)
        val many = (1..101).joinToString(",", "[", "]") { destJson(key = "d$it", name = "N$it") }
        assertEquals(ConfigError.TOO_MANY, invalid(wrap(dests = many)).error)
        assertEquals(ConfigError.BAD_VALUE, invalid(wrap(dests = "\"x\"")).error)
        assertEquals(ConfigError.BAD_VALUE, invalid(wrap(dests = "[${destJson(enabled = "\"yes\"")}]")).error)
        assertEquals(ConfigError.NOT_JSON, invalid("").error)
        assertIs<ConfigParse.Ok>(ConfigCodec.decode("\uFEFF" + wrap()))
    }

    private fun named(vararg names: String) =
        ConfigFile(names.mapIndexed { i, n -> ConfigDestination("d$i", dest(n)) }, emptyList(), true)

    @Test
    fun renamesCaseAndSpaceInsensitive() {
        val plan = planImport(named("Main "), listOf("main"), emptyList())
        assertEquals("Main (2)", plan.destinations.single().draft.name)
        assertEquals(listOf("Main " to "Main (2)"), plan.renamed)
        val plan2 = planImport(named("Main "), listOf("Main", "Main (2)"), emptyList())
        assertEquals("Main (3)", plan2.destinations.single().draft.name)
        assertEquals("Main ", plan2.destinations.single().originalName)
    }

    @Test
    fun renamesDuplicatesInsideFile() {
        val plan = planImport(named("A", "A"), emptyList(), emptyList())
        assertEquals(listOf("A", "A (2)"), plan.destinations.map { it.draft.name })
        val rules = ConfigFile(
            emptyList(),
            listOf(ConfigRule(rule("A"), emptyList()), ConfigRule(rule("A"), emptyList())),
            true,
        )
        val rp = planImport(rules, emptyList(), emptyList())
        assertEquals(listOf("A", "A (2)"), rp.rules.map { it.draft.name })
        assertEquals(listOf("A" to "A (2)"), rp.renamed)
    }

    @Test
    fun withoutCredentialsDropsSecretHeaders() {
        val headers = listOf(HeaderDraft("X-Key", null, secret = true), HeaderDraft("X-Pub", "v", secret = false))
        val plan = planImport(
            ConfigFile(listOf(ConfigDestination("d1", dest("Main", url = null, headers = headers))), emptyList(), false),
            emptyList(),
            emptyList(),
        )
        val d = plan.destinations.single()
        assertTrue(d.withoutCredentials)
        assertEquals(listOf("X-Key"), d.droppedSecretHeaders)
        assertEquals(listOf(HeaderDraft("X-Pub", "v", secret = false)), d.draft.headers)
        assertEquals(listOf("Main"), plan.withoutCredentials)
    }

    @Test
    fun fileToStringHidesSecrets() {
        val text = full().toString()
        assertFalse(text.contains("https://h.example"))
        assertFalse(text.contains("TOKEN"))
        assertFalse(text.contains("BOT"))
    }

    @Test
    fun ruleAndHeaderLimits() {
        val rules = (1..501).joinToString(",", "[", "]") { ruleJson(name = "R$it") }
        assertEquals(ConfigError.TOO_MANY, invalid(wrap(dests = "[${destJson()}]", rules = rules)).error)
        val headers = (1..51).joinToString(",", "[", "]") { "{\"name\":\"H$it\",\"value\":\"v\",\"secret\":false}" }
        val r = invalid(wrap(dests = "[${destJson(headers = headers)}]"))
        assertEquals(ConfigError.TOO_MANY, r.error)
        assertEquals("Main", r.item)
    }

    @Test
    fun byteSizeBranch() {
        assertEquals(ConfigError.TOO_LARGE, invalid("\u00e9".repeat(600_000)).error)
    }

    @Test
    fun duplicateKeysInRuleCollapse() {
        val ok = assertIs<ConfigParse.Ok>(
            ConfigCodec.decode(wrap(dests = "[${destJson()}]", rules = "[${ruleJson(dests = "[\"d1\",\"d1\"]")}]")),
        )
        assertEquals(listOf("d1"), planImport(ok.file, emptyList(), emptyList()).rules.single().destinationKeys)
        assertEquals(listOf("d1"), ok.file.rules.single().destinationKeys)
    }

    @Test
    fun hugeVersionIsNewer() {
        assertEquals(
            ConfigError.NEWER_VERSION,
            invalid(wrap().replace("\"version\":1", "\"version\":99999999999999999999999")).error,
        )
    }

    @Test
    fun bomEscape() {
        assertIs<ConfigParse.Ok>(ConfigCodec.decode("\uFEFF" + wrap()))
    }

    @Test
    fun rulesHidePatternsInToString() {
        val r = RuleDraft("N", true, SenderMode.EXACT, "SECRETSENDER", BodyMode.CONTAINS, "SECRETBODY", SimMatch.ANY, emptySet())
        val f = ConfigFile(emptyList(), listOf(ConfigRule(r, emptyList())), true)
        val plan = planImport(f, emptyList(), emptyList())
        for (t in listOf(f.rules.single().toString(), plan.rules.single().toString(), plan.toString(), f.toString())) {
            assertFalse(t.contains("SECRETSENDER"))
            assertFalse(t.contains("SECRETBODY"))
        }
    }

    @Test
    fun uniqueNameTrims() {
        assertEquals("Main", uniqueName("Main ", emptySet()))
        assertEquals("Main", planImport(named("Main "), emptyList(), emptyList()).destinations.single().draft.name)
    }

    @Test
    fun encodeIsDeterministic() {
        assertEquals(ConfigCodec.encode(full(), "2.0.0"), ConfigCodec.encode(full(), "2.0.0"))
    }

    @Test
    fun unknownFieldsInItemsIgnored() {
        val d = destJson().replace("{\"key\"", "{\"extra\":1,\"key\"")
        val r = ruleJson().replace("{\"name\"", "{\"extra\":[1],\"name\"")
        assertIs<ConfigParse.Ok>(ConfigCodec.decode(wrap(dests = "[$d]", rules = "[$r]")))
    }

    @Test
    fun takenRuleNameRenamed() {
        val f = ConfigFile(emptyList(), listOf(ConfigRule(rule("Alpha"), emptyList())), true)
        assertEquals("Alpha (2)", planImport(f, emptyList(), listOf("alpha")).rules.single().draft.name)
    }

    @Test
    fun destinationAndPlanToStringHideSecrets() {
        val c = ConfigDestination("d1", dest("Main", url = "https://h.example/TOKENURL", headers = listOf(HeaderDraft("A", "HEADERVAL", true))))
        val plan = planImport(ConfigFile(listOf(c), emptyList(), true), emptyList(), emptyList())
        for (t in listOf(c.toString(), plan.toString(), plan.destinations.single().toString())) {
            assertFalse(t.contains("TOKENURL"))
            assertFalse(t.contains("HEADERVAL"))
        }
    }
}
