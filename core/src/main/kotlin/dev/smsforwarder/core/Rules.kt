package dev.smsforwarder.core

enum class SenderMode { EXACT, CONTAINS, REGEX }

enum class BodyMode { CONTAINS, REGEX }

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

data class RuleMatch(val destinationIds: Set<Long>, val timedOutRuleIds: Set<Long>)

object RuleEngine {
    private class RegexTimeout : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    /** Throws [RegexTimeout] from charAt once the deadline passes, cutting off backtracking. */
    private class DeadlineCharSequence(
        private val inner: CharSequence,
        private val deadlineNanos: Long,
    ) : CharSequence {
        override val length: Int get() = inner.length
        override fun get(index: Int): Char {
            if (System.nanoTime() - deadlineNanos > 0) throw RegexTimeout()
            return inner[index]
        }
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
            DeadlineCharSequence(inner.subSequence(startIndex, endIndex), deadlineNanos)
        override fun toString(): String = inner.toString()
    }

    fun validatePattern(pattern: String): Boolean =
        runCatching { Regex(pattern) }.isSuccess

    fun destinationsFor(
        sms: IncomingSms,
        rules: List<RuleSpec>,
        regexBudgetMs: Long = 50,
    ): RuleMatch {
        val destinations = linkedSetOf<Long>()
        val timedOut = linkedSetOf<Long>()
        for (rule in rules) {
            if (!rule.enabled) continue
            try {
                if (matches(rule, sms, regexBudgetMs)) destinations += rule.destinationIds
            } catch (_: RegexTimeout) {
                timedOut += rule.id
            } catch (_: StackOverflowError) {
                // java.util.regex recurses per group iteration; treat like a timeout.
                timedOut += rule.id
            }
        }
        return RuleMatch(destinations, timedOut)
    }

    private fun matches(rule: RuleSpec, sms: IncomingSms, budgetMs: Long): Boolean {
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
            SenderMode.REGEX -> regexFind(rule.senderPattern, sms.sender, budgetMs)
        }
        if (!senderOk) return false
        val mode = rule.bodyMode ?: return true
        val pattern = rule.bodyPattern ?: return false
        return when (mode) {
            BodyMode.CONTAINS -> pattern.isNotEmpty() && sms.body.contains(pattern, ignoreCase = true)
            BodyMode.REGEX -> regexFind(pattern, sms.body, budgetMs)
        }
    }

    private fun simMatches(match: SimMatch, slot: Int?): Boolean = when (match) {
        SimMatch.ANY -> true
        SimMatch.SIM1 -> slot == 1
        SimMatch.SIM2 -> slot == 2
    }

    private fun regexFind(pattern: String, text: String, budgetMs: Long): Boolean {
        val regex = runCatching { Regex(pattern) }.getOrNull() ?: return false
        val deadline = System.nanoTime() + budgetMs * 1_000_000
        return regex.find(DeadlineCharSequence(text, deadline)) != null
    }
}
