package dev.smsforwarder.data

import androidx.room.withTransaction
import dev.smsforwarder.core.Clock
import dev.smsforwarder.core.ConfigCodec
import dev.smsforwarder.core.EngineEvent
import dev.smsforwarder.core.EngineEventType
import dev.smsforwarder.core.SystemClock
import dev.smsforwarder.diag.EngineLog
import dev.smsforwarder.core.ConfigDestination
import dev.smsforwarder.core.ConfigError
import dev.smsforwarder.core.ConfigFile
import dev.smsforwarder.core.ConfigParse
import dev.smsforwarder.core.ConfigRule
import dev.smsforwarder.core.DestinationDraft
import dev.smsforwarder.core.HeaderDraft
import dev.smsforwarder.core.ImportPlan
import dev.smsforwarder.core.RuleDraft
import dev.smsforwarder.core.planImport
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

sealed interface ImportPreview {
    data class Ready(val file: ConfigFile, val plan: ImportPlan) : ImportPreview
    data class Invalid(val error: ConfigError, val item: String?) : ImportPreview
}

data class ImportResult(val destinations: Int, val rules: Int, val withoutCredentials: Int)

/**
 * Exports destinations and rules to the config file text and imports one back. Import only adds: nothing existing is
 * changed. The text, URLs and secrets are never logged.
 */
class ConfigTransfer(
    private val db: AppDatabase,
    private val destinations: DestinationRepository,
    private val rules: RuleRepository,
    private val secretStore: DestinationSecretStore,
    private val engineLog: EngineLog? = null,
    private val clock: Clock = SystemClock,
) {
    /** Throws (including [TransientSecretException]) when the secret store cannot be read; nothing is written. */
    suspend fun export(includeSecrets: Boolean, appVersion: String): String {
        val live = destinations.observeLive().first()
        val exported = live.map { e ->
            val stored = secretStore.get(e.id)
            val pub = e.publicHeaderList().map { (n, v) -> HeaderDraft(n, v, secret = false) }
            val secret = stored?.headers.orEmpty().map { (n, v) -> HeaderDraft(n, if (includeSecrets) v else null, secret = true) }
            ConfigDestination(
                keyOf(e.id),
                DestinationDraft(
                    name = e.name, enabled = e.enabled, template = e.template, method = e.method,
                    url = if (includeSecrets) stored?.url else null,
                    headers = pub + secret, body = e.bodyTemplate.orEmpty(), bodyIsJson = e.bodyIsJson,
                    successPolicy = e.successPolicy,
                ),
            )
        }
        val exportedRules = rules.observeAll().first().map { r ->
            ConfigRule(
                RuleDraft(
                    name = r.rule.name, enabled = r.rule.enabled, senderMode = r.rule.senderMode,
                    senderPattern = r.rule.senderPattern, bodyMode = r.rule.bodyMode, bodyPattern = r.rule.bodyPattern,
                    sim = r.rule.sim, destinationIds = emptySet(),
                ),
                r.destinations.sortedBy { it.id }.map { keyOf(it.id) },
            )
        }
        val text = ConfigCodec.encode(ConfigFile(exported, exportedRules, includeSecrets), appVersion)
        log(EngineEventType.EXPORT, includeSecrets, exported.size)
        return text
    }

    suspend fun preview(text: String): ImportPreview = when (val parsed = ConfigCodec.decode(text)) {
        is ConfigParse.Invalid -> ImportPreview.Invalid(parsed.error, parsed.item)
        is ConfigParse.Ok -> ImportPreview.Ready(
            parsed.file,
            planImport(parsed.file, destinations.liveNames(), db.ruleDao().allNames()),
        )
    }

    /**
     * Adds everything in one transaction, naming against the destinations and rules that exist now. If anything fails,
     * including saving a secret, no row stays and the secrets already saved are removed; the error is rethrown.
     */
    suspend fun apply(file: ConfigFile): ImportResult {
        val inserted = mutableListOf<Long>()
        try {
            return db.withTransaction {
                val plan = planImport(file, destinations.liveNames(), db.ruleDao().allNames())
                val ids = HashMap<String, Long>()
                for (p in plan.destinations) {
                    val id = destinations.insertRow(p.draft)
                    inserted += id
                    ids[p.key] = id
                }
                for (r in plan.rules) {
                    rules.createImported(r.draft.copy(destinationIds = r.destinationKeys.mapNotNull { ids[it] }.toSet()))
                }
                for (p in plan.destinations) {
                    val secrets = destinations.secretsOf(p.draft) ?: continue
                    secretStore.set(ids.getValue(p.key), secrets)
                }
                ImportResult(plan.destinations.size, plan.rules.size, plan.withoutCredentials.size)
            }.also { log(EngineEventType.IMPORT, file.includesSecrets, it.destinations) }
        } catch (e: Throwable) {
            withContext(NonCancellable) { inserted.forEach { secretStore.remove(it) } }
            throw e
        }
    }

    private suspend fun log(type: EngineEventType, withSecrets: Boolean, destinations: Int) {
        engineLog?.add(EngineEvent(clock.now(), type, code = if (withSecrets) "with_secrets" else "no_secrets", count = destinations))
    }

    private fun keyOf(id: Long) = "d$id"
}
