package dev.smsforwarder.diag

import dev.smsforwarder.AppGraph
import dev.smsforwarder.core.BodyMode
import dev.smsforwarder.core.DeliveryStatus
import dev.smsforwarder.core.DestinationDraft
import dev.smsforwarder.core.DestinationPresets
import dev.smsforwarder.core.RuleDraft
import dev.smsforwarder.core.SenderMode
import dev.smsforwarder.core.SimMatch
import dev.smsforwarder.core.SmsStatus
import dev.smsforwarder.core.validateDestination
import dev.smsforwarder.core.validateRule

data class SeedResult(val ok: Boolean, val data: String)

/**
 * Debug-only seeding used by scripts/e2e-emulator.sh. Results carry ids and counts only: never a URL, token or SMS text.
 */
internal class SeedCommands(private val graph: AppGraph) {
    private class BadArg : Exception()

    suspend fun run(extras: Map<String, String>): SeedResult = try {
        when (extras["op"]) {
            "done" -> done()
            "destination" -> destination(extras)
            "rule" -> rule(extras)
            "status" -> status()
            else -> fail("bad_op")
        }
    } catch (e: BadArg) {
        fail("bad_arg")
    } catch (e: IllegalArgumentException) {
        fail("bad_arg")
    }

    private fun fail(name: String) = SeedResult(false, "error=$name")

    private suspend fun done(): SeedResult {
        val now = graph.clock.now()
        graph.settings.setSweepFloor(now)
        graph.settings.setSweepCursor(now)
        graph.settings.setOnboardingDone()
        return SeedResult(true, "ok")
    }

    private fun bool(v: String?, default: Boolean): Boolean = when (v) {
        null -> default
        "true" -> true
        "false" -> false
        else -> throw BadArg()
    }

    private suspend fun destination(x: Map<String, String>): SeedResult {
        val name = x["name"] ?: throw BadArg()
        val url = x["url"] ?: throw BadArg()
        val draft: DestinationDraft = when (x["template"]) {
            "standard" -> DestinationPresets.standardJson(name, url, x["token"] ?: throw BadArg())
            "ntfy" -> DestinationPresets.ntfy(name, url)
            "custom" -> DestinationPresets.custom(name).copy(
                url = url,
                body = x["body"] ?: throw BadArg(),
                bodyIsJson = bool(x["json"], true),
            )
            else -> throw BadArg()
        }.let { it.copy(enabled = bool(x["enabled"], true)) }
        val errors = validateDestination(draft, urlStored = false)
        if (errors.isNotEmpty()) return fail(errors.joinToString(",") { it.name })
        return SeedResult(true, "id=${graph.destinationRepository.create(draft)}")
    }

    private suspend fun rule(x: Map<String, String>): SeedResult {
        val draft = RuleDraft(
            name = x["name"] ?: throw BadArg(),
            enabled = true,
            senderMode = when (x["mode"] ?: "exact") {
                "exact" -> SenderMode.EXACT
                "contains" -> SenderMode.CONTAINS
                "regex" -> SenderMode.REGEX
                else -> throw BadArg()
            },
            senderPattern = x["sender"] ?: throw BadArg(),
            bodyMode = when (x["body_mode"]) {
                null -> null
                "contains" -> BodyMode.CONTAINS
                "not_contains" -> BodyMode.NOT_CONTAINS
                "regex" -> BodyMode.REGEX
                else -> throw BadArg()
            },
            bodyPattern = x["body"],
            sim = when (x["sim"] ?: "any") {
                "any" -> SimMatch.ANY
                "1" -> SimMatch.SIM1
                "2" -> SimMatch.SIM2
                else -> throw BadArg()
            },
            destinationIds = (x["destinations"] ?: throw BadArg()).split(',')
                .filter { it.isNotBlank() }.map { it.trim().toLong() }.toSet(),
        )
        val errors = validateRule(draft)
        if (errors.isNotEmpty()) return fail(errors.joinToString(",") { it.name })
        return SeedResult(true, "id=${graph.ruleRepository.create(draft)}")
    }

    private suspend fun status(): SeedResult {
        val sms = graph.smsRepository.countsByStatus().associate { it.status to it.count }
        val del = graph.smsRepository.deliveryCountsByStatus().associate { it.status to it.count }
        return SeedResult(
            true,
            "sms queued=${sms[SmsStatus.QUEUED] ?: 0} sent=${sms[SmsStatus.SENT] ?: 0} error=${sms[SmsStatus.ERROR] ?: 0} " +
                "deliveries queued=${del[DeliveryStatus.QUEUED] ?: 0} sent=${del[DeliveryStatus.SENT] ?: 0} " +
                "error=${del[DeliveryStatus.ERROR] ?: 0} canceled=${del[DeliveryStatus.CANCELED] ?: 0}",
        )
    }
}
