package dev.smsforwarder.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QueuePolicyTest {
    @Test fun stuckAfterSixHoursQueued() {
        assertFalse(QueuePolicy.isStuck(SmsStatus.QUEUED, null, 0, QueuePolicy.STUCK_AFTER_MS))
        assertTrue(QueuePolicy.isStuck(SmsStatus.QUEUED, null, 0, QueuePolicy.STUCK_AFTER_MS + 1))
    }

    @Test fun error401IsStuckImmediately() = assertTrue(QueuePolicy.isStuck(SmsStatus.ERROR, 401, 1000, 1001))

    @Test fun error400IsStuckImmediately() = assertTrue(QueuePolicy.isStuck(SmsStatus.ERROR, 400, 1000, 1001))

    @Test fun sentIsNeverStuck() = assertFalse(QueuePolicy.isStuck(SmsStatus.SENT, 200, 0, Long.MAX_VALUE))

    @Test fun retentionCutoffSubtracts90Days() =
        assertEquals(1000L, QueuePolicy.retentionCutoff(1000L + QueuePolicy.RETENTION_MS))

    @Test fun sweepFromSubtracts24h() = assertEquals(100L, QueuePolicy.sweepFrom(100L + 86_400_000L, 0L))

    @Test fun sweepFromNeverBelowFloor() = assertEquals(500L, QueuePolicy.sweepFrom(100L + 86_400_000L, 500L))

    @Test fun labels() {
        assertEquals("En cola · 3 intentos", statusLabel(SmsStatus.QUEUED, SmsOrigin.RECEIVER, 3, null))
        assertEquals("Enviado 200", statusLabel(SmsStatus.SENT, SmsOrigin.RECEIVER, 1, 200))
        assertEquals("Recuperado · 200", statusLabel(SmsStatus.SENT, SmsOrigin.SWEEP, 1, 200))
        assertEquals("Manual · 200", statusLabel(SmsStatus.SENT, SmsOrigin.MANUAL, 1, 200))
        assertEquals("Error 401", statusLabel(SmsStatus.ERROR, SmsOrigin.RECEIVER, 2, 401))
        assertEquals("Error 400", statusLabel(SmsStatus.ERROR, SmsOrigin.SWEEP, 1, 400))
    }

    @Test fun labelsWithoutHttpCodeNeverShowNull() {
        assertEquals("Error", statusLabel(SmsStatus.ERROR, SmsOrigin.RECEIVER, 1, null))
        assertEquals("Enviado", statusLabel(SmsStatus.SENT, SmsOrigin.RECEIVER, 1, null))
        assertEquals("Recuperado", statusLabel(SmsStatus.SENT, SmsOrigin.SWEEP, 1, null))
        assertEquals("Manual", statusLabel(SmsStatus.SENT, SmsOrigin.MANUAL, 1, null))
    }
}
