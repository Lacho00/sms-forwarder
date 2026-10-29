package dev.smsforwarder.core

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object IngestPayload {
    fun encode(sender: String, body: String, receivedAt: Long): String =
        buildJsonObject {
            put("sender", sender)
            put("body", body)
            put("received_at", receivedAt)
        }.toString()
}
