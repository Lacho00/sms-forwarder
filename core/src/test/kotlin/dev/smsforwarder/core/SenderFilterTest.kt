package dev.smsforwarder.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SenderFilterTest {
    @Test fun normalizeRemovesAllWhitespaceAndUppercases() =
        assertEquals("BANKX", SenderFilter.normalize(" Bank x\t"))

    @Test fun matchesIgnoringCaseAndSpaces() {
        val allowed = listOf("CARRIER", "BANK")
        assertTrue(SenderFilter.matches("carrier ", allowed))
        assertTrue(SenderFilter.matches("BANK", listOf(" bank")))
    }

    @Test fun doesNotMatchPrefixOrOtherSenders() {
        val allowed = listOf("CARRIER", "BANK")
        assertFalse(SenderFilter.matches("CARRIER-PROMO", allowed))
        assertFalse(SenderFilter.matches("+10000000000", allowed))
        assertFalse(SenderFilter.matches("", allowed))
    }

    @Test fun emptyAllowedListMatchesNothing() =
        assertFalse(SenderFilter.matches("CARRIER", emptyList()))
}
