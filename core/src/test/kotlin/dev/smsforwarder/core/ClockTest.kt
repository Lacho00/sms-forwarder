package dev.smsforwarder.core

import kotlin.test.Test
import kotlin.test.assertTrue

class ClockTest {
    @Test fun systemClockIsEpochMillis() {
        val now = SystemClock.now()
        assertTrue(now > 1_700_000_000_000L)
    }
}
