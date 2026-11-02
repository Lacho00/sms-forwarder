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
        val r = rule(id = 7, bodyMode = BodyMode.REGEX, bodyPattern = "(a+)+\\1$")  // backreference defeats the JDK memoization of "(a+)+$"
        val start = System.nanoTime()
        val m = RuleEngine.destinationsFor(sms("abc", "a".repeat(40) + "!"), listOf(r))
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue(m.destinationIds.isEmpty())
        assertEquals(setOf(7L), m.timedOutRuleIds)
        assertTrue(ms < 1000, "took $ms ms")
    }

    @Test fun invalidPatternRejected() {
        assertFalse(RuleEngine.validatePattern("("))
        assertTrue(RuleEngine.validatePattern("^a.*"))
    }

    @Test fun invalidStoredPatternNeverMatches() {
        val r = rule(mode = SenderMode.REGEX, pattern = "(")
        assertEquals(emptySet(), dests(sms(), r))
    }
}
