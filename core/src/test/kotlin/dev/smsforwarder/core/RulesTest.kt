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

    @Test fun hostilePatternsRunInLinearTime() {
        val inputs = mapOf(
            "(a+)+$" to "a".repeat(100_000) + "!",
            "(a|aa)+$" to "a".repeat(100_000) + "!",
            "\\d+\\d+\\d+\\d+x" to "1".repeat(100_000),
            "(?x)(a+) +$" to "a".repeat(100_000) + "!",
        )
        val start = System.nanoTime()
        for ((p, text) in inputs) {
            val m = RuleEngine.destinationsFor(sms("abc", text), listOf(rule(id = 7, bodyMode = BodyMode.REGEX, bodyPattern = p)))
            assertTrue(m.destinationIds.isEmpty(), p)
            // RE2/J has no (?x) flag: that pattern is invalid and reported instead of run.
            assertEquals(p.startsWith("(?x)"), m.timedOutRuleIds.isNotEmpty(), p)
        }
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue(ms < 1000, "took $ms ms")
    }

    @Test fun invalidPatternRejected() {
        for (p in listOf("(", "(a)\\1", "a(?=b)", "(?<=a)b", "(?!a)b")) assertFalse(RuleEngine.validatePattern(p), p)
        assertTrue(RuleEngine.validatePattern("^a.*"))
    }

    @Test fun patternsAccepted() {
        for (p in listOf("(CUP|USD)+", ".*codigo.*\\d{6}.*", "^\\+?53\\d+", "(a|aa)+$", "(?i)saldo")) {
            assertTrue(RuleEngine.validatePattern(p), p)
        }
    }

    @Test fun storedUnrunnablePatternIsReportedAndLaterRulesStillMatch() {
        val bad = rule(id = 3, dest = setOf(9L), bodyMode = BodyMode.REGEX, bodyPattern = "(a)\\1")
        val ok = rule(id = 4, dest = setOf(5L), bodyMode = BodyMode.REGEX, bodyPattern = "aa")
        val m = RuleEngine.destinationsFor(sms("abc", "aa"), listOf(bad, ok))
        assertEquals(setOf(3L), m.timedOutRuleIds)
        assertEquals(setOf(5L), m.destinationIds)
    }

    @Test fun invalidStoredPatternNeverMatches() {
        val r = rule(mode = SenderMode.REGEX, pattern = "(")
        assertEquals(emptySet(), dests(sms(), r))
    }
}
