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
    fun validatePattern(pattern: String): Boolean = compiled(pattern) is Compiled.Ok

    /** Longest accepted pattern, in characters. */
    const val MAX_PATTERN_LENGTH = 500

    /** Deepest accepted group nesting. RE2/J parses recursively, so this also bounds stack use. */
    const val MAX_GROUP_DEPTH = 20

    /**
     * Largest accepted product of repetition bounds along a nesting, e.g. `(?:a{30}){30}` is 900.
     * RE2/J expands counted repetitions into the program, so this caps compiled size and match cost.
     */
    const val MAX_REPEAT_PRODUCT = 1000L

    private const val CACHE_SIZE = 256

    private sealed interface Compiled {
        class Ok(val pattern: Pattern) : Compiled
        object Invalid : Compiled
    }

    /** Bounded LRU of compile results (invalid ones included), keyed by pattern text. */
    private val cache = object : LinkedHashMap<String, Compiled>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Compiled>): Boolean =
            size > CACHE_SIZE
    }

    private fun compiled(pattern: String): Compiled {
        synchronized(cache) { cache[pattern] }?.let { return it }
        val result = compile(pattern)
        synchronized(cache) { cache[pattern] = result }
        return result
    }

    private fun compile(pattern: String): Compiled {
        if (!isWithinLimits(pattern)) return Compiled.Invalid
        return try {
            Compiled.Ok(Pattern.compile(pattern))
        } catch (_: RuntimeException) {
            Compiled.Invalid
        } catch (_: StackOverflowError) {
            Compiled.Invalid
        }
    }

    /**
     * Structural size cap, run before compiling. Not a ReDoS detector (RE2 is already linear): it only
     * keeps compiled program size, memory and recursion depth bounded. Checks length, group depth and
     * the product of `{n}`, `{n,}`, `{n,m}` bounds along the nesting (`*`, `+`, `?` count as 1).
     * Cost of a quantified atom = bound x (for a group, the largest cost found inside it).
     */
    private fun isWithinLimits(pattern: String): Boolean {
        if (pattern.length > MAX_PATTERN_LENGTH) return false
        // One entry per open group: the largest cost seen so far at that level (alternatives and
        // concatenation both take the max, since only nesting multiplies).
        val levels = ArrayList<Long>().apply { add(1L) }
        var pending = 1L // cost of the last complete atom, which a following quantifier multiplies
        fun note(cost: Long): Boolean {
            pending = cost
            if (cost > levels[levels.size - 1]) levels[levels.size - 1] = cost
            return cost <= MAX_REPEAT_PRODUCT
        }
        var i = 0
        val n = pattern.length
        while (i < n) {
            when (pattern[i]) {
                '\\' -> {
                    val c = if (i + 1 < n) pattern[i + 1] else ' '
                    if (c == 'Q') {
                        val end = pattern.indexOf("\\E", i + 2)
                        i = if (end < 0) n else end + 2
                    } else if ((c == 'p' || c == 'P' || c == 'x') && i + 2 < n && pattern[i + 2] == '{') {
                        val end = pattern.indexOf('}', i + 3)
                        i = if (end < 0) n else end + 1 // \p{Greek}, \x{10FFFF}: not a quantifier
                    } else {
                        i += 2
                    }
                    note(1)
                }
                '[' -> {
                    i = skipClass(pattern, i)
                    note(1)
                }
                '(' -> {
                    if (levels.size > MAX_GROUP_DEPTH) return false
                    levels.add(1L)
                    i++
                }
                ')' -> {
                    i++
                    if (levels.size > 1) {
                        val inner = levels.removeAt(levels.size - 1)
                        if (!note(inner)) return false
                    } else {
                        note(1)
                    }
                }
                '{' -> {
                    val bound = quantifierBound(pattern, i)
                    if (bound == null) {
                        i++
                        note(1) // literal brace
                    } else {
                        i = bound.second
                        if (!note(pending * bound.first.coerceAtMost(MAX_REPEAT_PRODUCT + 1))) return false
                    }
                }
                '*', '+', '?' -> i++ // bound 1: cost unchanged
                else -> {
                    i++
                    note(1)
                }
            }
        }
        return true
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

    /** Upper bound and end index of a valid `{n}`, `{n,}` or `{n,m}` at [start]; null if it is a literal brace. */
    private fun quantifierBound(p: String, start: Int): Pair<Long, Int>? {
        val m = QUANTIFIER.matchAt(p, start) ?: return null
        val lo = m.groupValues[1]
        val hi = m.groupValues[3]
        val digits = if (m.groupValues[2].isNotEmpty() && hi.isNotEmpty()) hi else lo
        val value = if (digits.length > 7) MAX_REPEAT_PRODUCT + 1 else digits.toLong()
        return value to m.range.last + 1
    }

    private val QUANTIFIER = Regex("""\{(\d+)(,(\d*))?}""")

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
        val compiled = compiled(pattern) as? Compiled.Ok ?: throw InvalidRegex()
        return compiled.pattern.matcher(text).find()
    }
}
