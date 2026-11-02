package dev.smsforwarder.core

object SmsSearch {
    fun matches(sender: String, body: String, query: String): Boolean {
        if (query.isBlank()) return true
        val q = query.trim()
        return body.contains(q, ignoreCase = true) ||
            SenderFilter.normalize(sender).contains(SenderFilter.normalize(q))
    }
}
