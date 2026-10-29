package dev.smsforwarder.core

import kotlin.math.max

enum class SmsStatus { QUEUED, SENT, ERROR }

enum class SmsOrigin { RECEIVER, SWEEP, MANUAL }

object QueuePolicy {
    const val STUCK_AFTER_MS = 6 * 3_600_000L
    const val RETENTION_MS = 90 * 86_400_000L
    const val SWEEP_OVERLAP_MS = 24 * 3_600_000L
    const val MANUAL_RECOVERY_MS = 7 * 86_400_000L

    fun isStuck(status: SmsStatus, lastHttpCode: Int?, firstSeenAt: Long, now: Long): Boolean = when (status) {
        SmsStatus.SENT -> false
        SmsStatus.ERROR -> lastHttpCode == 400 || lastHttpCode == 401
        SmsStatus.QUEUED -> now - firstSeenAt > STUCK_AFTER_MS
    }

    fun retentionCutoff(now: Long): Long = now - RETENTION_MS

    fun sweepFrom(cursor: Long, floor: Long): Long = max(cursor - SWEEP_OVERLAP_MS, floor)
}

fun statusLabel(status: SmsStatus, origin: SmsOrigin, attempts: Int, lastHttpCode: Int?): String {
    val code = lastHttpCode?.let { " $it" }.orEmpty()
    val dotCode = lastHttpCode?.let { " · $it" }.orEmpty()
    return when (status) {
        SmsStatus.QUEUED -> "En cola · $attempts ${if (attempts == 1) "intento" else "intentos"}"
        SmsStatus.ERROR -> "Error$code"
        SmsStatus.SENT -> when (origin) {
            SmsOrigin.RECEIVER -> "Enviado$code"
            SmsOrigin.SWEEP -> "Recuperado$dotCode"
            SmsOrigin.MANUAL -> "Manual$dotCode"
        }
    }
}
