package dev.smsforwarder.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SenderFilterTest {
    @Test fun normalizeRemovesAllWhitespaceAndUppercases() =
        assertEquals("BANCOX", SenderFilter.normalize(" Banco x\t"))

    @Test fun matchesIgnoringCaseAndSpaces() {
        val allowed = listOf("OPERADOR", "BANCO")
        assertTrue(SenderFilter.matches("operador ", allowed))
        assertTrue(SenderFilter.matches("BANCO", listOf(" banco")))
    }

    @Test fun doesNotMatchPrefixOrOtherSenders() {
        val allowed = listOf("OPERADOR", "BANCO")
        assertFalse(SenderFilter.matches("OPERADOR-PROMO", allowed))
        assertFalse(SenderFilter.matches("+10000000000", allowed))
        assertFalse(SenderFilter.matches("", allowed))
    }

    @Test fun emptyAllowedListMatchesNothing() =
        assertFalse(SenderFilter.matches("OPERADOR", emptyList()))
}
