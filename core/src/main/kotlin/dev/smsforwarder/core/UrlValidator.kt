package dev.smsforwarder.core

import java.net.URI

object UrlValidator {
    fun isValid(url: String): Boolean {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrEmpty()
    }
}
