package dev.smsforwarder.diag

import dev.smsforwarder.core.SenderFilter
import dev.smsforwarder.core.SmsIdentity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/** One SMS as seen by the broadcast receiver. Never holds the body, only a hash prefix and a length. */
data class DiagCapture(
    val sender: String,
    val pduTimestamp: Long,
    val bodyHash: String,
    val bodyLength: Int,
    val parts: Int,
    val subId: Int,
    val capturedAt: Long,
)

/** One row read from content://sms/inbox, reduced the same way as [DiagCapture]. */
data class DiagInboxRow(
    val address: String,
    val date: Long,
    val dateSent: Long,
    val subId: Int,
    val bodyHash: String,
    val bodyLength: Int,
)

enum class PairKind { EXACT, FALLBACK, NONE }

fun shortHash(body: String): String = SmsIdentity.bodyHash(body).take(12)

fun pairKind(capture: DiagCapture, row: DiagInboxRow): PairKind {
    val sameContent = SenderFilter.normalize(capture.sender) == SenderFilter.normalize(row.address) &&
        capture.bodyHash == row.bodyHash &&
        capture.bodyLength == row.bodyLength
    if (!sameContent) return PairKind.NONE
    val delta = abs(capture.pduTimestamp - SmsIdentity.effectiveReceivedAt(row.dateSent, row.date))
    return when {
        delta == 0L -> PairKind.EXACT
        delta <= SmsIdentity.FALLBACK_WINDOW_MS -> PairKind.FALLBACK
        else -> PairKind.NONE
    }
}

class DiagStore(private val file: File) {
    fun add(capture: DiagCapture) = synchronized(LOCK) {
        val updated = (listOf(capture) + readAll()).take(MAX_ENTRIES)
        val array = JSONArray()
        updated.forEach { array.put(it.toJson()) }
        val tmp = File(file.path + ".tmp")
        tmp.writeText(array.toString())
        check(tmp.renameTo(file)) { "diag store rename failed" }
    }

    fun all(): List<DiagCapture> = synchronized(LOCK) { readAll() }

    // A truncated or corrupt file reads as empty so the receiver and the screen keep working.
    private fun readAll(): List<DiagCapture> = runCatching {
        if (file.exists()) {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { array.getJSONObject(it).toCapture() }
        } else {
            emptyList()
        }
    }.getOrElse { emptyList() }

    private fun DiagCapture.toJson() = JSONObject()
        .put("sender", sender)
        .put("pduTimestamp", pduTimestamp)
        .put("bodyHash", bodyHash)
        .put("bodyLength", bodyLength)
        .put("parts", parts)
        .put("subId", subId)
        .put("capturedAt", capturedAt)

    private fun JSONObject.toCapture() = DiagCapture(
        sender = getString("sender"),
        pduTimestamp = getLong("pduTimestamp"),
        bodyHash = getString("bodyHash"),
        bodyLength = getInt("bodyLength"),
        parts = getInt("parts"),
        subId = getInt("subId"),
        capturedAt = getLong("capturedAt"),
    )

    companion object {
        const val MAX_ENTRIES = 20
        private val LOCK = Any()
        const val FILE_NAME = "diag.json"
    }
}
