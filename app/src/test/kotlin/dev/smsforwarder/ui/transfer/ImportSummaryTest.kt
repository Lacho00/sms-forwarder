package dev.smsforwarder.ui.transfer

import dev.smsforwarder.core.BodyMode
import dev.smsforwarder.core.DestinationPresets
import dev.smsforwarder.core.PlannedDestination
import dev.smsforwarder.core.PlannedRule
import dev.smsforwarder.core.RuleDraft
import dev.smsforwarder.core.SenderMode
import dev.smsforwarder.core.SimMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ImportSummaryTest {
    private fun dest(name: String, url: String?) = PlannedDestination(
        key = "d", draft = DestinationPresets.custom(name).copy(url = url), originalName = name,
        withoutCredentials = url == null, droppedSecretHeaders = emptyList(),
    )

    private fun rule(sender: String, bodyMode: BodyMode? = null, body: String? = null) = PlannedRule(
        RuleDraft("R", false, SenderMode.CONTAINS, sender, bodyMode, body, SimMatch.ANY, emptySet()), "R", emptyList(),
    )

    @Test fun destinationShowsHostOnly() =
        assertEquals("• Main — h.example", destinationLine(dest("Main", "https://h.example:8443/in?token=secret"), "no URL"))

    @Test fun destinationWithoutUrl() =
        assertEquals("• Main — no URL", destinationLine(dest("Main", null), "no URL"))

    @Test fun unparsableUrlShowsNoUrlLabelNeverTheRawUrl() {
        val line = destinationLine(dest("Main", "https://exa mple.com/?k=secret"), "no URL")
        assertEquals("• Main — no URL", line)
    }

    @Test fun hostWithUnderscoreShowsAuthorityWithoutUserinfo() {
        val line = destinationLine(dest("Main", "https://user:pass@my_host.example.com/p?token=x"), "no URL")
        assertEquals("• Main — my_host.example.com", line)
        listOf("user", "pass", "token", "/p").forEach { assertFalse(it in line) }
    }

    @Test fun ruleShowsSenderAndBody() {
        assertEquals("• R: BANK", ruleLine(rule("BANK")))
        assertEquals("• R: BANK / code", ruleLine(rule("BANK", BodyMode.CONTAINS, "code")))
    }

    @Test fun rulePatternIsTruncatedTo60() {
        val line = ruleLine(rule("A".repeat(50), BodyMode.REGEX, "b".repeat(50)))
        assertEquals("• R: " + ("A".repeat(50) + " / " + "b".repeat(50)).take(60) + "…", line)
    }

    @Test fun importedNameAndPatternsCannotSpoofExtraLines() {
        val r = PlannedRule(
            RuleDraft("Evil\n• fake — legit.host", false, SenderMode.CONTAINS, "A\u202EB", BodyMode.CONTAINS, "x\u2028y\u2066z",
                SimMatch.ANY, emptySet()), "R", emptyList(),
        )
        val line = ruleLine(r)
        assertEquals("• Evil • fake — legit.host: A B / x y z", line)
        assertFalse(line.any { it == '\n' || it == '\u202E' || it == '\u2028' || it == '\u2066' })
        val d = destinationLine(dest("Evil\n• fake — legit.host", "https://h.example"), "no URL")
        assertFalse('\n' in d)
    }
}
