package dev.smsforwarder.core

import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

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

    /**
     * Regex rules only look at the first this many chars of the sender or body. Bounds the work a
     * runaway match can do, since on Android the in-match deadline never fires.
     */
    const val MAX_REGEX_INPUT = 2000

    private val regexExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "rule-regex").apply { isDaemon = true }
    }

    /**
     * False when the pattern does not compile or has a nested unbounded quantifier, i.e. a group
     * whose body contains `*`, `+` or `{n,}` and that is itself followed by `*`, `+` or `{...}`
     * (`(a+)+`, `(\d+,)*`). Deliberately conservative: it also rejects some harmless patterns.
     */
    fun validatePattern(pattern: String): Boolean =
        runCatching { Regex(pattern) }.isSuccess && !hasNestedUnboundedQuantifier(pattern)

    private fun hasNestedUnboundedQuantifier(p: String): Boolean {
        // One flag per open group: does its body contain an unbounded quantifier?
        val stack = ArrayDeque<Boolean>()
        var i = 0
        while (i < p.length) {
            when (p[i]) {
                '\\' -> i++
                '[' -> {
                    i++
                    if (i < p.length && p[i] == '^') i++
                    if (i < p.length && p[i] == ']') i++
                    while (i < p.length && p[i] != ']') {
                        if (p[i] == '\\') i++
                        i++
                    }
                }
                '(' -> stack.addLast(false)
                ')' -> {
                    val inner = stack.removeLastOrNull() ?: false
                    val next = p.getOrNull(i + 1)
                    val quantified = next == '*' || next == '+' || next == '{'
                    if (inner && quantified) return true
                    if (inner || (quantified && (next != '{' || isUnboundedBrace(p, i + 1)))) {
                        if (stack.isNotEmpty()) stack[stack.lastIndex] = true
                    }
                }
                '*', '+' -> if (stack.isNotEmpty()) stack[stack.lastIndex] = true
                '{' -> if (isUnboundedBrace(p, i) && stack.isNotEmpty()) stack[stack.lastIndex] = true
            }
            i++
        }
        return false
    }

    /** True for `{n,}` starting at [start] (the `{`). */
    private fun isUnboundedBrace(p: String, start: Int): Boolean {
        val end = p.indexOf('}', start)
        return end > start && p.substring(start + 1, end).let { it.endsWith(",") && it.length > 1 && it.dropLast(1).all(Char::isDigit) }
    }

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
            BodyMode.NOT_CONTAINS -> pattern.isNotEmpty() && !sms.body.contains(pattern, ignoreCase = true)
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
        val input = if (text.length > MAX_REGEX_INPUT) text.substring(0, MAX_REGEX_INPUT) else text
        val deadline = System.nanoTime() + budgetMs * 1_000_000
        // On the JVM the deadline sequence stops the runaway thread itself. On ART (ICU copies the
        // input and never calls charAt) only the timeout below protects the caller: the thread runs
        // on until the match ends, which the length cap and the nested-quantifier check bound.
        val future = regexExecutor.submit<Boolean> { regex.find(DeadlineCharSequence(input, deadline)) != null }
        try {
            return future.get(budgetMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            throw RegexTimeout()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            future.cancel(true)
            throw RegexTimeout()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }
}
