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

    @Test fun stuckWindowIsConfigurable() {
        assertTrue(QueuePolicy.isStuck(SmsStatus.QUEUED, null, 0, 2 * 3_600_000, StuckAfter.H1.millis))
        assertFalse(QueuePolicy.isStuck(SmsStatus.QUEUED, null, 0, 2 * 3_600_000))
    }

    @Test fun structuredStatusLabel() {
        assertEquals(StatusLabel(StatusKind.QUEUED, 2, null), statusLabelOf(SmsStatus.QUEUED, SmsOrigin.RECEIVER, 2, null))
        assertEquals(StatusKind.RECOVERED, statusLabelOf(SmsStatus.SENT, SmsOrigin.SWEEP, 1, 200).kind)
        assertEquals(StatusKind.MANUAL, statusLabelOf(SmsStatus.SENT, SmsOrigin.MANUAL, 1, 200).kind)
        assertEquals(StatusKind.SENT, statusLabelOf(SmsStatus.SENT, SmsOrigin.RECEIVER, 1, 200).kind)
        assertEquals(StatusLabel(StatusKind.ERROR, 3, 401), statusLabelOf(SmsStatus.ERROR, SmsOrigin.RECEIVER, 3, 401))
    }

    private val h6 = 6 * 3_600_000L

    @Test fun queuedDeliveryUnderErrorAggregateIsStuck() {
        val ds = listOf(
            DeliveryState(DeliveryStatus.ERROR, 401, null, true),
            DeliveryState(DeliveryStatus.QUEUED, null, null, true),
        )
        assertTrue(QueuePolicy.isStuck(ds, 0, 7 * 3_600_000L, h6))
    }

    @Test fun pausedOrCanceledNotStuck() {
        val late = 7 * 3_600_000L
        assertFalse(QueuePolicy.isStuck(listOf(DeliveryState(DeliveryStatus.QUEUED, null, null, false)), 0, late, h6))
        assertFalse(QueuePolicy.isStuck(listOf(DeliveryState(DeliveryStatus.CANCELED, null, null, true)), 0, late, h6))
        assertFalse(QueuePolicy.isStuck(listOf(DeliveryState(DeliveryStatus.ERROR, 403, null, true)), 0, late, h6))
        assertTrue(QueuePolicy.isStuck(listOf(DeliveryState(DeliveryStatus.ERROR, 400, null, true)), 0, 1, h6))
        assertTrue(QueuePolicy.isStuck(listOf(DeliveryState(DeliveryStatus.ERROR, null, "config_template", true)), 0, 1, h6))
    }
}
