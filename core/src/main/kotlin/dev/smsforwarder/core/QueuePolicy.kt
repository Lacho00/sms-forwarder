package dev.smsforwarder.core

import kotlin.math.max

enum class SmsStatus { QUEUED, SENT, ERROR }

/** What the stuck check needs of one delivery; [live] = its destination exists and is enabled. */
data class DeliveryState(val status: DeliveryStatus, val lastHttpCode: Int?, val lastError: String?, val live: Boolean)

enum class SmsOrigin { RECEIVER, SWEEP, MANUAL }

object QueuePolicy {
    const val STUCK_AFTER_MS = 6 * 3_600_000L
    const val RETENTION_MS = 90 * 86_400_000L
    const val SWEEP_OVERLAP_MS = 24 * 3_600_000L
    const val MANUAL_RECOVERY_MS = 7 * 86_400_000L

    fun isStuck(
        status: SmsStatus,
        lastHttpCode: Int?,
        firstSeenAt: Long,
        now: Long,
        stuckAfterMs: Long = STUCK_AFTER_MS,
    ): Boolean = when (status) {
        SmsStatus.SENT -> false
        SmsStatus.ERROR -> lastHttpCode == 400 || lastHttpCode == 401
        SmsStatus.QUEUED -> now - firstSeenAt > stuckAfterMs
    }

    /**
     * Stuck by deliveries: a live destination still QUEUED past the deadline, or an ERROR 400 / configuration
     * error. 401/403 are covered by the token alert; paused, deleted and canceled deliveries do not count.
     */
    fun isStuck(deliveries: List<DeliveryState>, firstSeenAt: Long, now: Long, stuckAfterMs: Long): Boolean =
        deliveries.any {
            it.live && when (it.status) {
                DeliveryStatus.QUEUED -> now - firstSeenAt > stuckAfterMs
                DeliveryStatus.ERROR -> it.lastHttpCode == 400 || isConfigError(it.lastError)
                DeliveryStatus.SENT, DeliveryStatus.CANCELED -> false
            }
        }

    fun retentionCutoff(now: Long): Long = now - RETENTION_MS

    fun sweepFrom(cursor: Long, floor: Long): Long = max(cursor - SWEEP_OVERLAP_MS, floor)
}

enum class StatusKind { QUEUED, ERROR, SENT, RECOVERED, MANUAL }

data class StatusLabel(val kind: StatusKind, val attempts: Int, val httpCode: Int?)

fun statusLabelOf(status: SmsStatus, origin: SmsOrigin, attempts: Int, lastHttpCode: Int?): StatusLabel {
    val kind = when (status) {
        SmsStatus.QUEUED -> StatusKind.QUEUED
        SmsStatus.ERROR -> StatusKind.ERROR
        SmsStatus.SENT -> when (origin) {
            SmsOrigin.RECEIVER -> StatusKind.SENT
            SmsOrigin.SWEEP -> StatusKind.RECOVERED
            SmsOrigin.MANUAL -> StatusKind.MANUAL
        }
    }
    return StatusLabel(kind, attempts, lastHttpCode)
}
