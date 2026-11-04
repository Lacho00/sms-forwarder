package dev.smsforwarder.diag

import dev.smsforwarder.core.EngineEvent
import dev.smsforwarder.core.parseEngineEvent
import dev.smsforwarder.core.toLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The last engine events, one line each, in a plain file (no Room). Events carry ids and codes only: never SMS text,
 * senders, URLs, destination names or server error text. When the file passes 2 x [capacity] lines it is rewritten
 * with the last [capacity].
 */
class EngineLog(private val file: File, private val capacity: Int = 500) {
    private val mutex = Mutex()
    private var lines = -1

    /** Never throws except on cancellation: a failing log must not affect the engine. */
    suspend fun add(e: EngineEvent) {
        try {
            mutex.withLock {
                withContext(Dispatchers.IO) {
                    if (lines < 0) lines = readLines().size
                    file.appendText(e.toLine() + "\n")
                    lines++
                    if (lines > 2 * capacity) {
                        val keep = readLines().takeLast(capacity)
                        val tmp = File(file.path + ".tmp")
                        tmp.writeText(keep.joinToString("") { it + "\n" })
                        if (!tmp.renameTo(file)) {
                            file.writeText(keep.joinToString("") { it + "\n" })
                            tmp.delete()
                        }
                        lines = keep.size
                    }
                }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            lines = -1
        }
    }

    /** The newest [limit] events (at most [capacity]), oldest first; unreadable lines are skipped. */
    suspend fun recent(limit: Int): List<EngineEvent> = try {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                readLines().mapNotNull { parseEngineEvent(it) }.takeLast(minOf(limit, capacity))
            }
        }
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        emptyList()
    }

    private fun readLines(): List<String> = if (file.isFile) file.readLines() else emptyList()
}
