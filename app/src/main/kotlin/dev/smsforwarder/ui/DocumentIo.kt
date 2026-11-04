package dev.smsforwarder.ui

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Reads and writes a document the user picked with the Storage Access Framework. */
interface DocumentIo {
    suspend fun write(uri: Uri, text: String)

    /** Removes a document created by the picker; callers ignore failures. */
    suspend fun delete(uri: Uri)

    /** The UTF-8 text of [uri], or null if it is larger than [maxBytes]. */
    suspend fun read(uri: Uri, maxBytes: Int): String?
}

class ContentResolverDocumentIo(private val resolver: ContentResolver) : DocumentIo {
    override suspend fun write(uri: Uri, text: String) = withContext(Dispatchers.IO) {
        val out = resolver.openOutputStream(uri, "wt") ?: throw IOException("no output stream")
        out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    override suspend fun delete(uri: Uri) {
        withContext(Dispatchers.IO) { DocumentsContract.deleteDocument(resolver, uri) }
    }

    override suspend fun read(uri: Uri, maxBytes: Int): String? = withContext(Dispatchers.IO) {
        val input = resolver.openInputStream(uri) ?: throw IOException("no input stream")
        input.use { stream ->
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            var total = 0
            while (true) {
                val n = stream.read(chunk)
                if (n < 0) break
                total += n
                // Stops one byte past the limit: enough to know the file is too large.
                if (total > maxBytes) return@withContext null
                buffer.write(chunk, 0, n)
            }
            String(buffer.toByteArray(), Charsets.UTF_8)
        }
    }
}
