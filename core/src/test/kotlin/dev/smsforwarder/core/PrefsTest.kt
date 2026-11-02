package dev.smsforwarder.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PrefsTest {
    private val now = 1_000_000_000_000L

    @Test fun retentionCutoffs() {
        assertEquals(now - 30 * 86_400_000L, Retention.D30.cutoff(now))
        assertNull(Retention.FOREVER.cutoff(now))
        assertEquals(now - QueuePolicy.RETENTION_MS, Retention.D90.cutoff(now))
    }

    @Test fun stuckAfterMillis() {
        assertEquals(QueuePolicy.STUCK_AFTER_MS, StuckAfter.H6.millis)
    }

    @Test fun languageTags() {
        assertNull(Language.SYSTEM.tag)
        assertEquals("es", Language.ES.tag)
    }
}
