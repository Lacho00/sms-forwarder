package dev.smsforwarder.diag

import dev.smsforwarder.core.EngineEvent
import dev.smsforwarder.core.EngineEventType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EngineLogTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun event(i: Int) = EngineEvent(i.toLong(), EngineEventType.RECEIVED, smsId = i.toLong(), code = "inserted")

    @Test fun keepsLastCapacity() = runBlocking {
        val log = EngineLog(File(tmp.root, "e.log"), capacity = 5)
        (1..12).forEach { log.add(event(it)) }
        assertEquals((8..12).map { event(it) }, log.recent(10))
        assertEquals((11..12).map { event(it) }, log.recent(2))
    }

    @Test fun survivesCorruptLine() = runBlocking {
        val f = File(tmp.root, "e.log")
        val log = EngineLog(f, capacity = 5)
        log.add(event(1))
        f.appendText("not an event\n")
        log.add(event(2))
        assertEquals(listOf(event(1), event(2)), log.recent(10))
    }

    @Test fun addNeverThrowsOnIoError() = runBlocking {
        // The log path is a directory, so every write fails.
        val dir = tmp.newFolder("blocked")
        val log = EngineLog(dir, capacity = 5)
        log.add(event(1))
        assertEquals(emptyList<EngineEvent>(), log.recent(10))
    }

    @Test fun concurrentAddsKeepAll() = runBlocking {
        val f = File(tmp.root, "e.log")
        // Small capacity so compaction runs during the concurrent adds.
        val log = EngineLog(f, capacity = 10)
        (1..100).map { i -> async(kotlinx.coroutines.Dispatchers.Default) { log.add(event(i)) } }.awaitAll()
        val recent = log.recent(10)
        assertEquals(10, recent.size)
        assertEquals(10, recent.map { it.at }.toSet().size)
        assertEquals(true, f.readLines().size <= 20)
    }

    @Test fun partialLineThenAddParsesCleanly() = runBlocking {
        val f = File(tmp.root, "e.log")
        f.writeText("123 SEND sms=")
        val log = EngineLog(f, capacity = 5)
        log.add(event(2))
        assertEquals(listOf(event(2)), log.recent(10))
    }
}
