package dev.smsforwarder.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RulesTest {
    private fun rule(
        id: Long = 1,
        enabled: Boolean = true,
        mode: SenderMode = SenderMode.EXACT,
        pattern: String = "ABC",
        bodyMode: BodyMode? = null,
        bodyPattern: String? = null,
        sim: SimMatch = SimMatch.ANY,
        dest: Set<Long> = setOf(1L),
    ) = RuleSpec(id, enabled, mode, pattern, bodyMode, bodyPattern, sim, dest)

    private fun sms(sender: String = "abc", body: String = "hello", slot: Int? = 1) =
        IncomingSms(sender, body, slot)

    private fun dests(sms: IncomingSms, vararg rules: RuleSpec) =
        RuleEngine.destinationsFor(sms, rules.toList()).destinationIds

    @Test fun exactIgnoresSpacesAndCase() {
        val r = rule(pattern = "AB C")
        assertEquals(setOf(1L), dests(sms("abc"), r))
        assertEquals(emptySet(), dests(sms("ABCD"), r))
    }

    @Test fun notContainsExcludesMatchingBody() {
        val r = rule(bodyMode = BodyMode.NOT_CONTAINS, bodyPattern = "login")
        assertEquals(emptySet(), dests(sms(body = "New LOGIN from device"), r))
    }

    @Test fun notContainsPassesOtherBody() {
        val r = rule(bodyMode = BodyMode.NOT_CONTAINS, bodyPattern = "login")
        assertEquals(setOf(1L), dests(sms(body = "Payment of 5"), r))
    }

    @Test fun notContainsEmptyPatternNeverMatches() {
        assertEquals(emptySet(), dests(sms(), rule(bodyMode = BodyMode.NOT_CONTAINS, bodyPattern = "")))
    }

    @Test fun containsNormalizes() {
        assertEquals(setOf(1L), dests(sms("MY BANK"), rule(mode = SenderMode.CONTAINS, pattern = "bank")))
    }

    @Test fun regexUsesRawSenderAndFind() {
        val strict = rule(mode = SenderMode.REGEX, pattern = "^Ab")
        assertEquals(setOf(1L), dests(sms("Abc"), strict))
        assertEquals(emptySet(), dests(sms("abc"), strict))
        assertEquals(setOf(1L), dests(sms("abc"), rule(mode = SenderMode.REGEX, pattern = "(?i)^ab")))
    }

    @Test fun bodyFilterMustAlsoMatch() {
        val r = rule(bodyMode = BodyMode.CONTAINS, bodyPattern = "code")
        assertEquals(emptySet(), dests(sms("abc", "nothing here"), r))
        assertEquals(setOf(1L), dests(sms("abc", "your CODE is 1"), r))
    }

    @Test fun simFilter() {
        val r = rule(sim = SimMatch.SIM2)
        assertEquals(emptySet(), dests(sms(slot = 1), r))
        assertEquals(emptySet(), dests(sms(slot = null), r))
        assertEquals(setOf(1L), dests(sms(slot = 2), r))
        assertEquals(setOf(1L), dests(sms(slot = null), rule(sim = SimMatch.ANY)))
    }

    @Test fun disabledRuleIgnored() =
        assertEquals(emptySet(), dests(sms(), rule(enabled = false)))

    @Test fun unionWithoutDuplicates() {
        val a = rule(id = 1, dest = setOf(1L, 2L))
        val b = rule(id = 2, dest = setOf(2L, 3L))
        assertEquals(setOf(1L, 2L, 3L), dests(sms(), a, b))
    }

    @Test fun catastrophicRegexTimesOut() {
        val r = rule(id = 7, bodyMode = BodyMode.REGEX, bodyPattern = SLOW)  // passes the validator, polynomial backtracking
        assertTrue(RuleEngine.validatePattern(SLOW))
        val start = System.nanoTime()
        val m = RuleEngine.destinationsFor(sms("abc", "1".repeat(2000)), listOf(r))
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue(m.destinationIds.isEmpty())
        assertEquals(setOf(7L), m.timedOutRuleIds)
        assertTrue(ms < 1000, "took $ms ms")
    }

    @Test fun storedUnsafePatternNeverRuns() {
        val r = rule(id = 3, dest = setOf(9L), bodyMode = BodyMode.REGEX, bodyPattern = "(a+)+")
        val m = RuleEngine.destinationsFor(sms("abc", "aaa"), listOf(r))
        assertEquals(setOf(3L), m.timedOutRuleIds)
        assertTrue(m.destinationIds.isEmpty())
    }

    @Test fun invalidPatternRejected() {
        assertFalse(RuleEngine.validatePattern("("))
        assertTrue(RuleEngine.validatePattern("^a.*"))
    }

    @Test fun nestedUnboundedQuantifiersRejected() {
        for (p in listOf("(a+)+", "(a*)*", "(a+)+\\1$", "((ab)+c)*", "(a{2,})+", "(?:x+y?)+", "(,\\d+)*",
            "(a|aa)+$", "(\\w|\\d)+$", "(a|a)*b", "\\Q[\\E(a+)+$", ".*.*.*x", "(ab|cd)+")) {
            assertFalse(RuleEngine.validatePattern(p), p)
        }
    }

    @Test fun safePatternsAccepted() {
        for (p in listOf("^a.*", "(ab)+", "(a+)?", "[+*]+", "(\\+)+", "a+b+", "(a|b)+", "(\\d{3})+", "([)+]+)",
            "(a|b)+", "(a|b|c)*x", "(ab|cd)?", "(ab|cd){2}", "\\Q(a+)+\\E", ".*foo.*", "[.*]+")) {
            assertTrue(RuleEngine.validatePattern(p), p)
        }
    }

    @Test fun regexOnlySeesFirstChars() {
        val r = rule(bodyMode = BodyMode.REGEX, bodyPattern = "x")
        assertEquals(setOf(1L), dests(sms("abc", "a".repeat(10) + "x"), r))
        assertEquals(emptySet(), dests(sms("abc", "a".repeat(2500) + "x"), r))
    }

    @Test fun timeoutDoesNotBlockLaterRules() {
        val slow = rule(id = 7, bodyMode = BodyMode.REGEX, bodyPattern = "\\d*\\d*\\d*\\d*\\d*\\d*\\d*!")
        val ok = rule(id = 8, dest = setOf(5L))
        val start = System.nanoTime()
        val m = RuleEngine.destinationsFor(sms("abc", "1".repeat(2000)), listOf(slow, ok))
        val ms = (System.nanoTime() - start) / 1_000_000
        assertEquals(setOf(7L), m.timedOutRuleIds)
        assertEquals(setOf(5L), m.destinationIds)
        assertTrue(ms < 2000, "took $ms ms")
    }

    @Test fun invalidStoredPatternNeverMatches() {
        val r = rule(mode = SenderMode.REGEX, pattern = "(")
        assertEquals(emptySet(), dests(sms(), r))
    }

    @Test fun stackOverflowInRegexIsContained() {
        val overflow = rule(id = 9, bodyMode = BodyMode.REGEX, bodyPattern = "(?:a|b)*x")
        val ok = rule(id = 10, dest = setOf(5L))
        val m = RuleEngine.destinationsFor(sms("abc", "a".repeat(100_000)), listOf(overflow, ok))
        assertEquals(setOf(5L), m.destinationIds)
        // Input is capped at MAX_REGEX_INPUT, so the overflow may no longer trigger; either way it is contained.
        assertTrue(m.timedOutRuleIds.all { it == 9L })
    }

    private companion object {
        const val SLOW = "\\d*\\d*\\d*\\d*\\d*\\d*!"
    }
}
