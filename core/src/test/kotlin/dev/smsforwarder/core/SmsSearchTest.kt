package dev.smsforwarder.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SmsSearchTest {
    @Test fun senderCaseInsensitive() = assertTrue(SmsSearch.matches("SENDER", "Balance 10", "send"))
    @Test fun senderIgnoresSpaces() = assertTrue(SmsSearch.matches("A B", "x", "ab"))
    @Test fun regexCharsAreLiteral() = assertTrue(SmsSearch.matches("A", "Balance (10)", "(10)"))
    @Test fun lonelyParenDoesNotThrow() = assertFalse(SmsSearch.matches("A", "x", "("))
    @Test fun starIsLiteral() = assertFalse(SmsSearch.matches("A", "abc", "*"))
    @Test fun emptyQueryMatches() = assertTrue(SmsSearch.matches("A", "x", ""))
    @Test fun bodyMatchIgnoresCase() = assertTrue(SmsSearch.matches("A", "Your CODE is 1", "code"))
}
