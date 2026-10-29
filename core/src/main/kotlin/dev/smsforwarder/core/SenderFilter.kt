package dev.smsforwarder.core

import java.util.Locale

object SenderFilter {
    fun normalize(sender: String): String =
        sender.filterNot { it.isWhitespace() }.uppercase(Locale.ROOT)

    fun matches(sender: String, allowed: Collection<String>): Boolean {
        val normalized = normalize(sender)
        if (normalized.isEmpty()) return false
        return allowed.any { normalize(it) == normalized }
    }
}
