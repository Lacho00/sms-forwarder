package dev.smsforwarder.core

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

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

    private const val MAX_REGEX_THREADS = 4

    /**
     * Bounded pool with direct hand-off: at most [MAX_REGEX_THREADS] regex threads exist, so at most
     * that many runaway threads can leak, and thanks to [runningAway] at most one per distinct
     * pattern. When all threads are busy the submit is rejected and the rule counts as timed out.
     */
    private val regexExecutor = ThreadPoolExecutor(
        0, MAX_REGEX_THREADS, 30, TimeUnit.SECONDS, SynchronousQueue(),
        { r -> Thread(r, "rule-regex").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    /** Patterns whose match timed out and whose thread has not finished yet; they are not run again. */
    private val runningAway = ConcurrentHashMap.newKeySet<String>()

    /**
     * False when the pattern does not compile, has a nested unbounded quantifier (a group whose body
     * contains `*`, `+` or `{n,}` and that is itself followed by `*`, `+` or `{...}`: `(a+)+`,
     * `(\d+,)*`), has an unbounded-quantified group with alternation unless every alternative is a
     * distinct single literal char (`(a|aa)+`, `(\w|\d)+`, `(a|a)*`; `(a|b)+` is fine), or has more
     * than two `.*`/`.+`/`.{n,}` (`.*.*.*x`). `\Q..\E` blocks and character classes are skipped.
     * Deliberately conservative: it also rejects some harmless patterns.
     */
    fun validatePattern(pattern: String): Boolean =
        runCatching { Regex(pattern) }.isSuccess && !hasNestedUnboundedQuantifier(pattern)

    private class Frame {
        var hasUnbounded = false
        var hasAlt = false
        var allSingle = true
        var curLen = 0
        var curLiteral = false
        var curChar = ' '
        val chars = HashSet<Char>()

        fun atom(literal: Boolean, c: Char = ' ') {
            curLen++
            curLiteral = literal
            curChar = c
        }

        fun endAlternative() {
            if (curLen == 1 && curLiteral && chars.add(curChar)) Unit else allSingle = false
            curLen = 0
            curLiteral = false
        }
    }

    private const val META = "\\[()|*+?{}.^$"

    private fun hasNestedUnboundedQuantifier(p: String): Boolean {
        val stack = ArrayDeque<Frame>()
        var dotRuns = 0
        var i = 0
        while (i < p.length) {
            val c = p[i]
            when (c) {
                '\\' -> {
                    if (p.getOrNull(i + 1) == 'Q') {
                        val end = p.indexOf("\\E", i + 2)
                        i = if (end < 0) p.length else end + 1
                    } else {
                        i++
                    }
                    stack.lastOrNull()?.atom(false)
                }
                '[' -> {
                    i++
                    if (i < p.length && p[i] == '^') i++
                    if (i < p.length && p[i] == ']') i++
                    while (i < p.length && p[i] != ']') {
                        if (p[i] == '\\') i++
                        i++
                    }
                    stack.lastOrNull()?.atom(false)
                }
                '(' -> {
                    stack.addLast(Frame())
                    if (p.getOrNull(i + 1) == '?') {
                        // Skip the group prefix: (?:  (?=  (?!  (?<=  (?<!  (?<name>  (?i:  (?i)
                        var j = i + 2
                        if (p.getOrNull(j) == '<' && p.getOrNull(j + 1) != '=' && p.getOrNull(j + 1) != '!') {
                            val gt = p.indexOf('>', j)
                            j = if (gt < 0) p.length else gt + 1
                        } else {
                            while (j < p.length) {
                                val d = p[j]
                                if (d == ')') break
                                j++
                                if (d == ':' || d == '=' || d == '!' || d == '>') break
                            }
                        }
                        i = j - 1
                    }
                }
                '|' -> stack.lastOrNull()?.let { it.hasAlt = true; it.endAlternative() }
                ')' -> {
                    val frame = stack.removeLastOrNull() ?: Frame()
                    if (frame.hasAlt) frame.endAlternative()
                    val next = p.getOrNull(i + 1)
                    val unbounded = next == '*' || next == '+' || (next == '{' && isUnboundedBrace(p, i + 1))
                    val quantified = next == '*' || next == '+' || next == '{'
                    if (frame.hasUnbounded && quantified) return true
                    if (frame.hasAlt && unbounded && !frame.allSingle) return true
                    if (frame.hasUnbounded || unbounded) stack.lastOrNull()?.hasUnbounded = true
                    stack.lastOrNull()?.atom(false)
                }
                '*', '+' -> {
                    stack.lastOrNull()?.let { it.hasUnbounded = true; it.curLiteral = false }
                }
                '?' -> stack.lastOrNull()?.curLiteral = false
                '{' -> stack.lastOrNull()?.let {
                    it.curLiteral = false
                    if (isUnboundedBrace(p, i)) it.hasUnbounded = true
                }
                '.' -> {
                    val next = p.getOrNull(i + 1)
                    if (next == '*' || next == '+' || (next == '{' && isUnboundedBrace(p, i + 1))) {
                        if (++dotRuns > 2) return true
                    }
                    stack.lastOrNull()?.atom(false)
                }
                '^', '$', '}' -> Unit
                else -> stack.lastOrNull()?.atom(c !in META, c)
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
        // Rules stored before the validator existed are never re-validated elsewhere: an unsafe one
        // would leak a spinning thread per SMS, so it counts as timed out and never runs.
        if (hasNestedUnboundedQuantifier(pattern)) throw RegexTimeout()
        // A previous run of this pattern is still spinning: do not start another thread for it.
        if (pattern in runningAway) throw RegexTimeout()
        val input = if (text.length > MAX_REGEX_INPUT) text.substring(0, MAX_REGEX_INPUT) else text
        val deadline = System.nanoTime() + budgetMs * 1_000_000
        // On the JVM the deadline sequence stops the runaway thread itself. On ART (ICU copies the
        // input and never calls charAt) only the timeout below protects the caller: the thread runs
        // on until the match ends, which the length cap, the validator and the quarantine bound.
        val started = AtomicBoolean(false)
        val done = AtomicBoolean(false)
        val future = try {
            regexExecutor.submit<Boolean> {
                started.set(true)
                try {
                    regex.find(DeadlineCharSequence(input, deadline)) != null
                } finally {
                    // done is set before the removal so a late quarantine add below can tell.
                    done.set(true)
                    runningAway.remove(pattern)
                }
            }
        } catch (_: RejectedExecutionException) {
            throw RegexTimeout()
        }
        try {
            return future.get(budgetMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            quarantine(pattern, started, done)
            throw RegexTimeout()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            future.cancel(true)
            quarantine(pattern, started, done)
            throw RegexTimeout()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    /**
     * Marks [pattern] as running away until its task ends. Skipped when the task never started
     * (cancelled before running, so its finally would never clear the mark). If the task already
     * finished, its removal may have happened before our add, so undo the add: [done] is set before
     * the task's removal, hence either it removes after our add or we see done and remove here.
     */
    private fun quarantine(pattern: String, started: AtomicBoolean, done: AtomicBoolean) {
        if (!started.get()) return
        runningAway.add(pattern)
        if (done.get()) runningAway.remove(pattern)
    }
}
