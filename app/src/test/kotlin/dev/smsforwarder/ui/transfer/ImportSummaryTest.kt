package dev.smsforwarder.ui.transfer

import dev.smsforwarder.core.BodyMode
import dev.smsforwarder.core.DestinationPresets
import dev.smsforwarder.core.PlannedDestination
import dev.smsforwarder.core.PlannedRule
import dev.smsforwarder.core.RuleDraft
import dev.smsforwarder.core.SenderMode
import dev.smsforwarder.core.SimMatch
import org.junit.Assert.assertEquals
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

    @Test fun unparsableUrlFallsBackToTruncatedRaw() {
        val line = destinationLine(dest("Main", "not a url " + "x".repeat(100)), "no URL")
        assertEquals("• Main — " + ("not a url " + "x".repeat(100)).take(60) + "…", line)
    }

    @Test fun ruleShowsSenderAndBody() {
        assertEquals("• R: BANK", ruleLine(rule("BANK")))
        assertEquals("• R: BANK / code", ruleLine(rule("BANK", BodyMode.CONTAINS, "code")))
    }

    @Test fun rulePatternIsTruncatedTo60() {
        val line = ruleLine(rule("A".repeat(50), BodyMode.REGEX, "b".repeat(50)))
        assertEquals("• R: " + ("A".repeat(50) + " / " + "b".repeat(50)).take(60) + "…", line)
    }
}
