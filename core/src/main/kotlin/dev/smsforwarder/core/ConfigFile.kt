package dev.smsforwarder.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

const val CONFIG_FORMAT = "sms-forwarder/config"
const val CONFIG_VERSION = 1
const val CONFIG_MAX_BYTES = 1_048_576
const val CONFIG_MAX_DESTINATIONS = 100
const val CONFIG_MAX_RULES = 500
const val CONFIG_MAX_HEADERS = 50

/** `draft.url == null` means no credentials; a secret header with `value == null` has no value. */
data class ConfigDestination(val key: String, val draft: DestinationDraft) {
    override fun toString(): String = "ConfigDestination(key=$key, draft=$draft)"
}

/** `draft.destinationIds` is empty: the links are [destinationKeys]. */
data class ConfigRule(val draft: RuleDraft, val destinationKeys: List<String>) {
    override fun toString(): String =
        "ConfigRule(name=${draft.name}, enabled=${draft.enabled}, destinations=${destinationKeys.size})"
}

data class ConfigFile(
    val destinations: List<ConfigDestination>,
    val rules: List<ConfigRule>,
    val includesSecrets: Boolean,
) {
    override fun toString(): String =
        "ConfigFile(destinations=${destinations.size}, rules=${rules.size}, includesSecrets=$includesSecrets)"
}

enum class ConfigError {
    TOO_LARGE, NOT_JSON, WRONG_FORMAT, NEWER_VERSION, BAD_VALUE, TOO_MANY,
    DUPLICATE_KEY, UNKNOWN_DESTINATION, INVALID_DESTINATION, INVALID_RULE,
}

sealed interface ConfigParse {
    data class Ok(val file: ConfigFile) : ConfigParse

    /** [item] is the name of the destination or rule at fault, when it could be read. */
    data class Invalid(val error: ConfigError, val item: String?) : ConfigParse
}

private class Bad(val error: ConfigError, val item: String? = null) : Exception() {
    override fun fillInStackTrace(): Throwable = this
}

object ConfigCodec {
    private val pretty = Json { prettyPrint = true }

    fun encode(file: ConfigFile, appVersion: String): String {
        val root = buildJsonObject {
            put("format", CONFIG_FORMAT)
            put("version", CONFIG_VERSION)
            put("app", appVersion)
            put("secrets", file.includesSecrets)
            put("destinations", buildJsonArray { file.destinations.forEach { add(destinationJson(it)) } })
            put("rules", buildJsonArray { file.rules.forEach { add(ruleJson(it)) } })
        }
        return pretty.encodeToString(JsonElement.serializer(), root)
    }

    private fun destinationJson(c: ConfigDestination): JsonObject {
        val d = c.draft
        return buildJsonObject {
            put("key", c.key)
            put("name", d.name)
            put("enabled", d.enabled)
            put("template", d.template.name)
            put("method", d.method.name)
            put("url", d.url?.let { JsonPrimitive(it) } ?: JsonNull)
            put(
                "headers",
                buildJsonArray {
                    d.headers.forEach { h ->
                        add(
                            buildJsonObject {
                                put("name", h.name)
                                put("value", h.value?.let { JsonPrimitive(it) } ?: JsonNull)
                                put("secret", h.secret)
                            },
                        )
                    }
                },
            )
            put("body", d.body)
            put("bodyIsJson", d.bodyIsJson)
            put("successPolicy", d.successPolicy.name)
        }
    }

    private fun ruleJson(c: ConfigRule): JsonObject {
        val r = c.draft
        return buildJsonObject {
            put("name", r.name)
            put("enabled", r.enabled)
            put("senderMode", r.senderMode.name)
            put("senderPattern", r.senderPattern)
            put("bodyMode", r.bodyMode?.let { JsonPrimitive(it.name) } ?: JsonNull)
            put("bodyPattern", r.bodyPattern?.let { JsonPrimitive(it) } ?: JsonNull)
            put("sim", r.sim.name)
            put("destinations", buildJsonArray { c.destinationKeys.forEach { add(JsonPrimitive(it)) } })
        }
    }

    fun decode(text: String): ConfigParse {
        if (text.length > CONFIG_MAX_BYTES ||
            text.toByteArray(Charsets.UTF_8).size > CONFIG_MAX_BYTES
        ) {
            return ConfigParse.Invalid(ConfigError.TOO_LARGE, null)
        }
        val root = try {
            Json.parseToJsonElement(text.removePrefix("\uFEFF"))
        } catch (_: Exception) {
            return ConfigParse.Invalid(ConfigError.NOT_JSON, null)
        } catch (_: StackOverflowError) {
            return ConfigParse.Invalid(ConfigError.NOT_JSON, null)
        }
        return try {
            ConfigParse.Ok(read(root))
        } catch (b: Bad) {
            ConfigParse.Invalid(b.error, b.item)
        }
    }

    private fun read(root: JsonElement): ConfigFile {
        val obj = root as? JsonObject ?: throw Bad(ConfigError.WRONG_FORMAT)
        if (str(obj["format"]) != CONFIG_FORMAT) throw Bad(ConfigError.WRONG_FORMAT)
        val version = (obj["version"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBigIntegerOrNull()
            ?: throw Bad(ConfigError.BAD_VALUE)
        if (version > CONFIG_VERSION.toBigInteger()) throw Bad(ConfigError.NEWER_VERSION)
        if (version < java.math.BigInteger.ONE) throw Bad(ConfigError.BAD_VALUE)

        val destArray = array(obj["destinations"])
        val ruleArray = array(obj["rules"])
        if (destArray.size > CONFIG_MAX_DESTINATIONS || ruleArray.size > CONFIG_MAX_RULES) {
            throw Bad(ConfigError.TOO_MANY)
        }
        val destinations = destArray.map { readDestination(it) }
        val rules = ruleArray.map { readRule(it) }

        val keys = HashSet<String>()
        for (d in destinations) if (!keys.add(d.key)) throw Bad(ConfigError.DUPLICATE_KEY, d.draft.name)
        for (r in rules) {
            if (r.destinationKeys.any { it !in keys }) throw Bad(ConfigError.UNKNOWN_DESTINATION, r.draft.name)
        }
        for (d in destinations) {
            if (validateDestination(d.draft, urlStored = d.draft.url == null).isNotEmpty()) {
                throw Bad(ConfigError.INVALID_DESTINATION, d.draft.name)
            }
        }
        for (r in rules) {
            if ((validateRule(r.draft) - RuleError.NO_DESTINATIONS).isNotEmpty()) {
                throw Bad(ConfigError.INVALID_RULE, r.draft.name)
            }
        }
        val declared = (obj["secrets"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()
        return ConfigFile(destinations, rules, declared ?: destinations.any { it.draft.url != null })
    }

    private fun readDestination(e: JsonElement): ConfigDestination {
        val o = e as? JsonObject ?: throw Bad(ConfigError.BAD_VALUE)
        val name = str(o["name"])
        try {
            val headerArray = array(o["headers"])
            if (headerArray.size > CONFIG_MAX_HEADERS) throw Bad(ConfigError.TOO_MANY, name)
            val headers = headerArray.map { h ->
                val ho = h as? JsonObject ?: throw Bad(ConfigError.BAD_VALUE)
                val secret = bool(ho["secret"])
                val value = nullableStr(ho["value"])
                if (!secret && value == null) throw Bad(ConfigError.BAD_VALUE)
                HeaderDraft(req(str(ho["name"])), value, secret)
            }
            val draft = DestinationDraft(
                name = req(name),
                enabled = bool(o["enabled"]),
                template = enum<TemplateKind>(o["template"]),
                method = enum<HttpMethod>(o["method"]),
                url = if (o.containsKey("url")) nullableStr(o["url"]) else throw Bad(ConfigError.BAD_VALUE),
                headers = headers,
                body = req(str(o["body"])),
                bodyIsJson = bool(o["bodyIsJson"]),
                successPolicy = enum<SuccessPolicy>(o["successPolicy"]),
            )
            return ConfigDestination(req(str(o["key"])), draft)
        } catch (b: Bad) {
            throw if (b.item == null) Bad(b.error, name) else b
        }
    }

    private fun readRule(e: JsonElement): ConfigRule {
        val o = e as? JsonObject ?: throw Bad(ConfigError.BAD_VALUE)
        val name = str(o["name"])
        try {
            val keys = array(o["destinations"]).map { req(str(it)) }.distinct()
            val draft = RuleDraft(
                name = req(name),
                enabled = bool(o["enabled"]),
                senderMode = enum<SenderMode>(o["senderMode"]),
                senderPattern = req(str(o["senderPattern"])),
                bodyMode = nullableEnum<BodyMode>(o["bodyMode"]),
                bodyPattern = nullableStr(o["bodyPattern"]),
                sim = enum<SimMatch>(o["sim"]),
                destinationIds = emptySet(),
            )
            return ConfigRule(draft, keys)
        } catch (b: Bad) {
            throw if (b.item == null) Bad(b.error, name) else b
        }
    }

    private fun array(e: JsonElement?): JsonArray =
        if (e == null) JsonArray(emptyList()) else e as? JsonArray ?: throw Bad(ConfigError.BAD_VALUE)

    private fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun req(s: String?): String = s ?: throw Bad(ConfigError.BAD_VALUE)

    private fun nullableStr(e: JsonElement?): String? = when (e) {
        null, JsonNull -> null
        else -> str(e) ?: throw Bad(ConfigError.BAD_VALUE)
    }

    private fun bool(e: JsonElement?): Boolean =
        (e as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()
            ?: throw Bad(ConfigError.BAD_VALUE)

    private inline fun <reified T : Enum<T>> enum(e: JsonElement?): T {
        val s = str(e) ?: throw Bad(ConfigError.BAD_VALUE)
        return enumValues<T>().firstOrNull { it.name == s } ?: throw Bad(ConfigError.BAD_VALUE)
    }

    private inline fun <reified T : Enum<T>> nullableEnum(e: JsonElement?): T? =
        if (e == null || e == JsonNull) null else enum<T>(e)
}

data class PlannedDestination(
    val key: String,
    val draft: DestinationDraft,
    val originalName: String,
    val withoutCredentials: Boolean,
    val droppedSecretHeaders: List<String>,
)

data class PlannedRule(val draft: RuleDraft, val originalName: String, val destinationKeys: List<String>) {
    override fun toString(): String = "PlannedRule(name=${draft.name}, destinations=${destinationKeys.size})"
}

data class ImportPlan(val destinations: List<PlannedDestination>, val rules: List<PlannedRule>) {
    /** Pairs of (name in the file, name it will get), destinations first. */
    val renamed: List<Pair<String, String>>
        get() = destinations.filter { it.originalName.trim() != it.draft.name }.map { it.originalName to it.draft.name } +
            rules.filter { it.originalName.trim() != it.draft.name }.map { it.originalName to it.draft.name }

    /** Final names of the destinations that will be created without a URL. */
    val withoutCredentials: List<String>
        get() = destinations.filter { it.withoutCredentials }.map { it.draft.name }
}

private fun nameKey(name: String) = name.trim().lowercase()

/** Compares without surrounding spaces and ignoring case; appends " (2)", " (3)"... until free. */
fun uniqueName(name: String, taken: Set<String>): String {
    val used = taken.mapTo(HashSet()) { nameKey(it) }
    if (nameKey(name) !in used) return name.trim()
    var n = 2
    while (true) {
        val candidate = "${name.trim()} ($n)"
        if (nameKey(candidate) !in used) return candidate
        n++
    }
}

fun planImport(
    file: ConfigFile,
    takenDestinationNames: Collection<String>,
    takenRuleNames: Collection<String>,
): ImportPlan {
    val destTaken = takenDestinationNames.toMutableSet()
    val destinations = file.destinations.map { c ->
        val name = uniqueName(c.draft.name, destTaken)
        destTaken += name
        val noUrl = c.draft.url == null
        val (dropped, kept) = c.draft.headers.partition { it.secret && (noUrl || it.value == null) }
        PlannedDestination(
            key = c.key,
            // Imported items start disabled so nothing forwards before the user reviews it.
            draft = c.draft.copy(name = name, enabled = false, headers = kept),
            originalName = c.draft.name,
            withoutCredentials = noUrl,
            droppedSecretHeaders = dropped.map { it.name },
        )
    }
    val ruleTaken = takenRuleNames.toMutableSet()
    val rules = file.rules.map { r ->
        val name = uniqueName(r.draft.name, ruleTaken)
        ruleTaken += name
        PlannedRule(r.draft.copy(name = name, enabled = false), r.draft.name, r.destinationKeys)
    }
    return ImportPlan(destinations, rules)
}
