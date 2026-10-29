package dev.smsforwarder.core

import java.security.MessageDigest
import kotlin.math.abs

data class SmsRecord(
    val sender: String,
    val body: String,
    val receivedAt: Long,
    val phoneDate: Long?,
    val subId: Int?,
)

object SmsIdentity {
    const val FALLBACK_WINDOW_MS = 2_000L

    fun effectiveReceivedAt(dateSent: Long?, date: Long): Long =
        if (dateSent != null && dateSent > 0L) dateSent else date

    fun bodyHash(body: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(body.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun localKey(r: SmsRecord): String =
        SenderFilter.normalize(r.sender) + "|" + r.receivedAt + "|" + bodyHash(r.body)

    // An identical localKey is the special case of delta 0, so the window check covers it.
    fun isSameSms(a: SmsRecord, b: SmsRecord): Boolean =
        SenderFilter.normalize(a.sender) == SenderFilter.normalize(b.sender) &&
            a.body == b.body &&
            abs(a.receivedAt - b.receivedAt) <= FALLBACK_WINDOW_MS
}

fun joinParts(parts: List<String>): String = parts.joinToString("")
