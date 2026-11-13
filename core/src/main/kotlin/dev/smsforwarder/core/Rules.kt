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
     * True when [pattern] compiles under RE2 syntax (RE2/J) and fits the size limits. Matching is linear
     * in the input size, so no pattern can backtrack catastrophically. The price: no backreferences
     * (`\1`), no lookarounds (`(?=..)`, `(?!..)`, `(?<=..)`, `(?<!..)`) and no possessive or atomic
     * constructs; those are invalid. Does not touch the cache, so editor drafts cannot evict real rules.
     */
    fun validatePattern(pattern: String): Boolean = compile(pattern) is Compiled.Ok

    /**
     * True only when RE2/J or the size limits reject [pattern]. A transient failure (stack or heap
     * exhausted while compiling) is not a rejection. Does not touch the cache.
     */
    fun isRejectedPattern(pattern: String): Boolean = compile(pattern) === Compiled.Invalid

    /** Longest accepted pattern, in characters. */
    const val MAX_PATTERN_LENGTH = 500

    /** Deepest accepted group nesting. RE2/J parses recursively, so this also bounds stack use. */
    const val MAX_GROUP_DEPTH = 20

    /**
     * Largest accepted estimated program size (RE2 instructions, see [estimatedSize]).
     *
     * Why 2000. RE2/J expands counted repetitions into the program, and both matching and the
     * compiler recurse per instruction. Measured with re2j 1.8: about 3000 consecutive nullable
     * instructions are fine with a 1 MB thread stack and 5000 overflow it in `Machine.add`;
     * `(?:` + 489 literals + `){1000}` expands to ~489k instructions (~30 MB retained), so a few
     * of them in the cache cause OutOfMemoryError. 2000 keeps a stack margin under the 3000 mark
     * (Android threads can have smaller stacks than a desktop JVM) and bounds one compiled pattern
     * to well under 1 MB. `a{1000}` and `.{0,1000}` (the largest plain bounds) still fit.
     */
    const val MAX_PROGRAM_SIZE = 2000

    /** RE2's own cap on a repetition count. */
    private const val MAX_REPEAT = 1000

    private const val CACHE_SIZE = 256

    private sealed interface Compiled {
        class Ok(val pattern: Pattern) : Compiled
        object Invalid : Compiled
        /**
         * Resource exhaustion while compiling (heap or stack): it depends on the machine and the calling
         * thread's stack depth, not on the pattern, so it is never cached.
         */
        object Transient : Compiled
    }

    /** Bounded LRU of compile results (invalid ones included), keyed by pattern text. */
    private val cache = object : LinkedHashMap<String, Compiled>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Compiled>): Boolean =
            size > CACHE_SIZE
    }

    internal fun cacheSizeForTest(): Int = synchronized(cache) { cache.size }
    internal fun quantifierPatternForTest(): String = QUANTIFIER.pattern

    private fun compiled(pattern: String): Compiled {
        synchronized(cache) { cache[pattern] }?.let { return it }
        val result = compile(pattern)
        if (result !== Compiled.Transient) synchronized(cache) { cache[pattern] = result }
        return result
    }

    /** Limit check plus compile, no cache. Shared by [validatePattern] and the cache-miss path. */
    private fun compile(pattern: String): Compiled {
        if (!isWithinLimits(pattern)) return Compiled.Invalid
        return try {
            Compiled.Ok(Pattern.compile(pattern))
        } catch (_: RuntimeException) {
            Compiled.Invalid
        } catch (_: StackOverflowError) {
            // Depends on this thread's stack depth, not on the pattern: retry on the next SMS.
            Compiled.Transient
        } catch (_: OutOfMemoryError) {
            // Safety net only: the size estimate keeps programs small, so this means memory pressure
            // elsewhere. The allocation is freed on unwind; this SMS treats the rule as unrunnable.
            Compiled.Transient
        }
    }

    /**
     * One open group (or the top level) while scanning. All sizes are estimated instruction counts.
     * [capturing]: RE2 emits two Capture instructions around the body of a capturing group.
     */
    private class Frame(val capturing: Boolean = false) {
        var alternatives = 0L // finished alternatives, plus one per `|`
        var sequence = 0L // concatenation so far in the current alternative
        var last = 0L // size of the last complete atom, which a quantifier would multiply
        var lastQuantified = false // a following `?` is then the non-greedy suffix, size unchanged
        fun total() = alternatives + sequence
    }

    /**
     * Size cap, run before compiling. Not a ReDoS detector (RE2 is already linear): it keeps the
     * compiled program, and with it memory and recursion depth, bounded.
     *
     * It is an upper-bound estimate of RE2's instruction count, not exact:
     * - atom (literal, escape, `.`, class, anchor) = 1; concatenation = sum; `|` adds 1;
     * - group = size of its body; `x?`, `x*`, `x+` = size(x) + 1;
     * - `x{n}` = n * size(x); `x{n,}` = (n + 1) * size(x) + 1; `x{n,m}` = m * size(x) + (m - n);
     * - a non-greedy `?` suffix on a quantifier does not change the size.
     * Rejects as soon as a running total passes [MAX_PROGRAM_SIZE]; counts above 1000 are rejected.
     * Every value stays at or below 2000 before a multiply by at most 1000, so Long never overflows.
     */
    private fun isWithinLimits(pattern: String): Boolean {
        if (pattern.length > MAX_PATTERN_LENGTH) return false
        val stack = ArrayList<Frame>().apply { add(Frame()) }
        var i = 0
        val n = pattern.length

        fun atom(size: Long): Boolean {
            val f = stack[stack.size - 1]
            f.sequence += size
            f.last = size
            f.lastQuantified = false
            return f.total() <= MAX_PROGRAM_SIZE
        }

        // Replace the last atom's size by newSize in the current concatenation.
        fun requantify(newSize: Long): Boolean {
            val f = stack[stack.size - 1]
            f.sequence += newSize - f.last
            f.last = newSize
            f.lastQuantified = true
            return f.total() <= MAX_PROGRAM_SIZE
        }

        while (i < n) {
            val f = stack[stack.size - 1]
            when (pattern[i]) {
                '\\' -> {
                    val c = if (i + 1 < n) pattern[i + 1] else ' '
                    if (c == 'Q') {
                        // \Q..\E: every character is a literal atom
                        val end = pattern.indexOf("\\E", i + 2)
                        val stop = if (end < 0) n else end
                        if (!atom(0L)) return false
                        for (k in i + 2 until stop) if (!atom(1L)) return false
                        i = if (end < 0) n else end + 2
                    } else if ((c == 'p' || c == 'P' || c == 'x') && i + 2 < n && pattern[i + 2] == '{') {
                        val end = pattern.indexOf('}', i + 3)
                        i = if (end < 0) n else end + 1 // \p{Greek}, \x{10FFFF}: not a quantifier
                        if (!atom(1L)) return false
                    } else {
                        i += 2
                        if (!atom(1L)) return false
                    }
                }
                '[' -> {
                    i = skipClass(pattern, i)
                    if (!atom(1L)) return false
                }
                '(' -> {
                    if (stack.size > MAX_GROUP_DEPTH) return false
                    i++
                    if (i < n && pattern[i] == '?') {
                        val named = pattern.startsWith("?P<", i) || pattern.startsWith("?<", i)
                        if (named) {
                            val end = pattern.indexOf('>', i)
                            if (end < 0) return false
                            i = end + 1
                            stack.add(Frame(capturing = true))
                        } else {
                            var k = i + 1
                            while (k < n && (pattern[k] in "imsU-")) k++
                            if (k < n && pattern[k] == ')') {
                                i = k + 1 // (?i): flags only, no instruction, no atom
                                f.last = 0L
                                f.lastQuantified = false
                            } else if (k < n && pattern[k] == ':') {
                                i = k + 1
                                stack.add(Frame())
                            } else {
                                stack.add(Frame()) // unknown construct; compile rejects it
                            }
                        }
                    } else {
                        stack.add(Frame(capturing = true))
                    }
                }
                ')' -> {
                    i++
                    if (stack.size > 1) {
                        val inner = stack.removeAt(stack.size - 1)
                        // An empty group still counts as 1, or `(?:(?:()){1000}){1000}` would estimate 0.
                        val body = maxOf(inner.total(), 1L)
                        if (!atom(if (inner.capturing) body + 2 else body)) return false
                    } else {
                        if (!atom(1L)) return false
                    }
                }
                '|' -> {
                    i++
                    f.alternatives += f.sequence + 1
                    f.sequence = 0L
                    f.last = 0L
                    f.lastQuantified = false
                    if (f.total() > MAX_PROGRAM_SIZE) return false
                }
                '{' -> {
                    val q = QUANTIFIER.matchAt(pattern, i)
                    if (q == null) {
                        i++
                        if (!atom(1L)) return false // literal brace
                    } else {
                        i = q.range.last + 1
                        val loText = q.groupValues[1]
                        val hasComma = q.groupValues[2].isNotEmpty()
                        val hiText = q.groupValues[3]
                        if (loText.length > 4 || hiText.length > 4) return false
                        val lo = loText.toLong()
                        val hi = if (hiText.isEmpty()) -1L else hiText.toLong()
                        if (lo > MAX_REPEAT || hi > MAX_REPEAT) return false
                        val s = f.last
                        val size = when {
                            !hasComma -> lo * s
                            hi < 0 -> (lo + 1) * s + 1
                            else -> maxOf(lo, hi).let { m -> m * s + (m - lo) }
                        }
                        if (size > MAX_PROGRAM_SIZE) return false
                        if (!requantify(size)) return false
                    }
                }
                '*', '+' -> {
                    i++
                    if (!requantify(f.last + 1)) return false
                }
                '?' -> {
                    i++
                    if (!f.lastQuantified) {
                        if (!requantify(f.last + 1)) return false
                    } else {
                        f.lastQuantified = false // non-greedy suffix: size unchanged
                    }
                }
                else -> {
                    i++
                    if (!atom(1L)) return false
                }
            }
        }
        return stack.sumOf { it.total() } <= MAX_PROGRAM_SIZE
    }

    /** Index just past the class starting at [start] (`[`), or the end of input when unterminated. */
    private fun skipClass(p: String, start: Int): Int {
        var i = start + 1
        if (i < p.length && p[i] == '^') i++
        if (i < p.length && p[i] == ']') i++ // leading ] is a literal
        while (i < p.length) {
            when {
                p[i] == '\\' -> i += 2
                p.startsWith("[:", i) -> {
                    val end = p.indexOf(":]", i + 2)
                    i = if (end < 0) i + 2 else end + 2
                }
                p[i] == ']' -> return i + 1
                else -> i++
            }
        }
        return p.length
    }

    private val QUANTIFIER = Regex("""\{(\d+)(,(\d*))?\}""")

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

    private fun markInvalid(pattern: String): Nothing {
        synchronized(cache) { cache[pattern] = Compiled.Invalid }
        throw InvalidRegex()
    }

    /** Unanchored, case-sensitive search. A pattern RE2/J rejects counts as a rule that could not run. */
    private fun regexFind(pattern: String, text: String): Boolean {
        val compiled = compiled(pattern) as? Compiled.Ok ?: throw InvalidRegex()
        return try {
            compiled.pattern.matcher(text).find()
        } catch (_: StackOverflowError) {
            // Stack depth belongs to the calling thread, not the pattern (the size cap bounds the
            // program): fail this SMS only, and do not cache the rule as invalid.
            throw InvalidRegex()
        } catch (_: RuntimeException) {
            markInvalid(pattern)
        }
        // OutOfMemoryError and other Errors are deliberately not caught: the program size cap keeps a
        // compiled pattern small, so they would signal a real fault, not a hostile pattern.
    }
}
