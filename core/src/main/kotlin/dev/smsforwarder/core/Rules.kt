package dev.smsforwarder.core

import com.google.re2j.Pattern

enum class SenderMode { EXACT, CONTAINS, REGEX }

enum class BodyMode { CONTAINS, NOT_CONTAINS, REGEX }

enum class SimMatch { ANY, SIM1, SIM2 }

data class RuleSpec(
    val id: Long,
    val enabled: Boolean,
    val senderMode: SenderMode,
    val senderPattern: String,
    val bodyMode: BodyMode?,
    val bodyPattern: String?,
    val sim: SimMatch,
    val destinationIds: Set<Long>,
)

data class IncomingSms(val sender: String, val body: String, val simSlot: Int?)

/**
 * [timedOutRuleIds]: rules whose regex could not run (invalid under the current engine, e.g. a
 * pattern stored earlier with a backreference or lookaround). Name kept from the old timeout design.
 */
data class RuleMatch(val destinationIds: Set<Long>, val timedOutRuleIds: Set<Long>)

object RuleEngine {
    /**
     * True when [pattern] compiles under RE2 syntax (RE2/J). Matching is linear in the input size, so
     * no pattern can backtrack catastrophically. The price: no backreferences (`\1`), no lookarounds
     * (`(?=..)`, `(?!..)`, `(?<=..)`, `(?<!..)`) and no possessive or atomic constructs; those are invalid.
     */
    fun validatePattern(pattern: String): Boolean =
        runCatching { Pattern.compile(pattern) }.isSuccess

    fun destinationsFor(
        sms: IncomingSms,
        rules: List<RuleSpec>,
    ): RuleMatch {
        val destinations = linkedSetOf<Long>()
        val timedOut = linkedSetOf<Long>()
        for (rule in rules) {
            if (!rule.enabled) continue
            try {
                if (matches(rule, sms)) destinations += rule.destinationIds
            } catch (_: InvalidRegex) {
                timedOut += rule.id
            }
        }
        return RuleMatch(destinations, timedOut)
    }

    private fun matches(rule: RuleSpec, sms: IncomingSms): Boolean {
        if (!simMatches(rule.sim, sms.simSlot)) return false
        val senderOk = when (rule.senderMode) {
            SenderMode.EXACT -> {
                val p = SenderFilter.normalize(rule.senderPattern)
                p.isNotEmpty() && p == SenderFilter.normalize(sms.sender)
            }
            SenderMode.CONTAINS -> {
                val p = SenderFilter.normalize(rule.senderPattern)
                p.isNotEmpty() && SenderFilter.normalize(sms.sender).contains(p)
            }
            SenderMode.REGEX -> regexFind(rule.senderPattern, sms.sender)
        }
        if (!senderOk) return false
        val mode = rule.bodyMode ?: return true
        val pattern = rule.bodyPattern ?: return false
        return when (mode) {
            BodyMode.CONTAINS -> pattern.isNotEmpty() && sms.body.contains(pattern, ignoreCase = true)
            BodyMode.NOT_CONTAINS -> pattern.isNotEmpty() && !sms.body.contains(pattern, ignoreCase = true)
            BodyMode.REGEX -> regexFind(pattern, sms.body)
        }
    }

    private fun simMatches(match: SimMatch, slot: Int?): Boolean = when (match) {
        SimMatch.ANY -> true
        SimMatch.SIM1 -> slot == 1
        SimMatch.SIM2 -> slot == 2
    }

    private class InvalidRegex : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    /** Unanchored, case-sensitive search. A pattern RE2/J rejects counts as a rule that could not run. */
    private fun regexFind(pattern: String, text: String): Boolean {
        val compiled = try {
            Pattern.compile(pattern)
        } catch (_: RuntimeException) {
            throw InvalidRegex()
        }
        return compiled.matcher(text).find()
    }
}
