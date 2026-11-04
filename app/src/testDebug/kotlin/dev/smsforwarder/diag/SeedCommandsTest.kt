package dev.smsforwarder.diag

import dev.smsforwarder.SmsForwarderApp
import dev.smsforwarder.core.SmsOrigin
import dev.smsforwarder.core.SmsRecord
import dev.smsforwarder.data.FakeDestinationSecretStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SeedCommandsTest {
    private lateinit var graph: dev.smsforwarder.AppGraph
    private lateinit var secrets: FakeDestinationSecretStore
    private lateinit var seed: SeedCommands

    @Before fun setUp() {
        graph = (RuntimeEnvironment.getApplication() as SmsForwarderApp).graph
        secrets = FakeDestinationSecretStore()
        graph.destinationSecretStore = secrets
        seed = SeedCommands(graph)
    }

    @Test fun seedsStandardDestinationAndRule() = runBlocking {
        val d = seed.run(mapOf("op" to "destination", "template" to "standard", "name" to "Main", "url" to "https://h.example/x", "token" to "t"))
        assertTrue(d.data, d.ok)
        assertEquals("id=1", d.data)
        val r = seed.run(mapOf("op" to "rule", "name" to "R", "sender" to "AAA", "destinations" to "1"))
        assertTrue(r.data, r.ok)
        assertTrue(r.data.startsWith("id="))
        val rules = graph.ruleRepository.activeRules()
        assertEquals(1, rules.size)
        assertEquals(setOf(1L), rules.single().destinationIds)
        assertEquals("https://h.example/x", secrets.get(1)?.url)
    }

    @Test fun httpUrlRejected() = runBlocking {
        val d = seed.run(mapOf("op" to "destination", "template" to "ntfy", "name" to "N", "url" to "http://h.example"))
        assertFalse(d.ok)
        assertTrue(d.data, d.data.contains("URL_NOT_HTTPS"))
        assertTrue(graph.destinationRepository.observeLive().first().isEmpty())
    }

    @Test fun invalidRegexRejected() = runBlocking {
        seed.run(mapOf("op" to "destination", "template" to "ntfy", "name" to "N", "url" to "https://h.example/t"))
        val r = seed.run(mapOf("op" to "rule", "name" to "R", "sender" to "(", "mode" to "regex", "destinations" to "1"))
        assertFalse(r.ok)
        assertTrue(r.data, r.data.contains("SENDER_REGEX_INVALID"))
    }

    @Test fun unknownOpFails() = runBlocking {
        val r = seed.run(mapOf("op" to "x"))
        assertFalse(r.ok)
        assertEquals("error=bad_op", r.data)
    }

    @Test fun statusCountsOnly() = runBlocking {
        seed.run(mapOf("op" to "destination", "template" to "custom", "name" to "C", "url" to "https://h.example/c", "body" to "{body}", "json" to "false"))
        graph.smsRepository.ingest(SmsRecord("AAA", "SECRET-BODY", System.currentTimeMillis(), null, null), SmsOrigin.RECEIVER, setOf(1L))
        val s = seed.run(mapOf("op" to "status"))
        assertTrue(s.data, s.ok)
        assertFalse(s.data.contains("SECRET-BODY"))
        assertFalse(s.data.contains("AAA"))
        assertTrue(s.data, s.data.startsWith("sms queued=1 "))
        assertTrue(s.data, s.data.contains("deliveries queued=1 "))
    }

    @Test fun doneMarksOnboarding() = runBlocking {
        val r = seed.run(mapOf("op" to "done"))
        assertEquals("ok", r.data)
        assertTrue(graph.settings.onboardingDone.first())
        assertNotNull(graph.settings.sweepFloor())
        assertEquals(graph.settings.sweepFloor(), graph.settings.sweepCursor())
    }

    private suspend fun seedTwoDestinations() {
        for (n in 1..2) seed.run(mapOf("op" to "destination", "template" to "ntfy", "name" to "N$n", "url" to "https://h.example/t$n"))
    }

    @Test fun softDeletedSmsLeftOutOfStatus() = runBlocking {
        seedTwoDestinations()
        val t = System.currentTimeMillis()
        val id = (graph.smsRepository.ingest(SmsRecord("AAA", "one", t, null, null), SmsOrigin.RECEIVER, setOf(1L)) as dev.smsforwarder.data.IngestResult.Inserted).id
        graph.smsRepository.ingest(SmsRecord("AAA", "two", t + 10_000, null, null), SmsOrigin.RECEIVER, setOf(1L))
        graph.smsRepository.softDelete(id)
        val s = seed.run(mapOf("op" to "status")).data
        assertTrue(s, s.startsWith("sms queued=1 "))
        assertTrue(s, s.contains("deliveries queued=1 "))
    }

    @Test fun badArgForNonNumericDestinationAndMissingExtra() = runBlocking {
        seedTwoDestinations()
        val a = seed.run(mapOf("op" to "rule", "name" to "R", "sender" to "AAA", "destinations" to "x"))
        assertFalse(a.ok)
        assertEquals("error=bad_arg", a.data)
        val b = seed.run(mapOf("op" to "destination", "template" to "ntfy", "name" to "N"))
        assertFalse(b.ok)
        assertEquals("error=bad_arg", b.data)
        val c = seed.run(mapOf("op" to "rule", "name" to "R", "destinations" to "1"))
        assertEquals("error=bad_arg", c.data)
    }

    @Test fun statusCountsSentAndErrorDeliveries() = runBlocking {
        seedTwoDestinations()
        val t = System.currentTimeMillis()
        val id = (graph.smsRepository.ingest(SmsRecord("AAA", "one", t, null, null), SmsOrigin.RECEIVER, setOf(1L, 2L)) as dev.smsforwarder.data.IngestResult.Inserted).id
        val ds = graph.smsRepository.deliveriesOf(id)
        graph.smsRepository.recordAttempt(ds[0].id, dev.smsforwarder.core.SendOutcome.Accepted(200))
        graph.smsRepository.recordAttempt(ds[1].id, dev.smsforwarder.core.SendOutcome.Rejected(403, null))
        val s = seed.run(mapOf("op" to "status")).data
        assertTrue(s, s.contains("deliveries queued=0 sent=1 error=1 canceled=0"))
        assertTrue(s, s.startsWith("sms queued=0 sent=0 error=1 "))
    }
}
