package dev.smsforwarder.diag

import android.content.Context
import androidx.work.testing.WorkManagerTestInitHelper
import dev.smsforwarder.AppGraph
import dev.smsforwarder.SmsForwarderApp
import dev.smsforwarder.core.BodyMode
import dev.smsforwarder.core.DeliveryStatus
import dev.smsforwarder.core.DestinationPresets
import dev.smsforwarder.core.EngineEvent
import dev.smsforwarder.core.EngineEventType
import dev.smsforwarder.core.HeaderDraft
import dev.smsforwarder.core.RuleDraft
import dev.smsforwarder.core.SenderMode
import dev.smsforwarder.core.SimMatch
import dev.smsforwarder.core.SmsOrigin
import dev.smsforwarder.core.SmsRecord
import dev.smsforwarder.core.SmsStatus
import dev.smsforwarder.data.FakeDestinationSecretStore
import dev.smsforwarder.data.IngestResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DiagnosticsCollectorTest {
    private lateinit var context: Context
    private lateinit var graph: AppGraph

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        graph = (context as SmsForwarderApp).graph
        graph.destinationSecretStore = FakeDestinationSecretStore()
        graph.engineLog // created lazily by the graph
    }

    private suspend fun seed(): Pair<Long, Long> {
        val dest = graph.destinationRepository.create(
            DestinationPresets.custom("NAME-7f3a").copy(
                url = "https://h.example/tok-7f3a",
                headers = listOf(HeaderDraft("X-Key", "key-7f3a", secret = true)),
                body = "{}",
            ),
        )
        graph.ruleRepository.create(
            RuleDraft("RULE-7f3a", true, SenderMode.EXACT, "AAA-7f3a", BodyMode.CONTAINS, "BODY-7f3a", SimMatch.ANY, setOf(dest)),
        )
        val sms = (graph.smsRepository.ingest(
            SmsRecord("AAA-7f3a", "BODY-7f3a", 1_800_000_000_000L, null, null), SmsOrigin.RECEIVER, setOf(dest),
        ) as IngestResult.Inserted).id
        return dest to sms
    }

    @Test fun reportLeaksNothing() = runBlocking {
        val (dest, sms) = seed()
        val dao = graph.db.deliveryDao()
        val d = dao.forSms(sms).single()
        dao.update(d.copy(status = DeliveryStatus.ERROR, lastHttpCode = null, lastError = "srv https://h.example/tok-7f3a"))
        for (t in EngineEventType.entries) {
            graph.engineLog.add(EngineEvent(1_800_000_000_000L, t, smsId = sms, destinationId = dest, code = "other"))
        }
        graph.engineLog.add(EngineEvent(1_800_000_000_000L, EngineEventType.SEND, code = "srv https://h.example/tok-7f3a"))
        android.provider.Settings.Global.putString(context.contentResolver, "device_name", "DEV-7f3a")
        val report = DiagnosticsCollector(context, graph).report()
        assertFalse(report, report.contains("7f3a"))
        assertFalse(report, report.contains("h.example"))
        val lines = report.lines()
        assertTrue(report, lines.contains("other: 1"))
        assertTrue(report, lines.contains("queued: 1"))
        assertTrue(report, lines.any { it.contains(" SEND sms=") })
        assertTrue(report.contains(DiagnosticsCollector(context, graph).collect().appVersion))
    }

    @Test fun failingSourcesGiveUnavailableLines() = runBlocking {
        seed()
        (graph.destinationSecretStore as FakeDestinationSecretStore).getFailure = RuntimeException("boom")
        val collector = DiagnosticsCollector(context, graph, health = { throw IllegalStateException("health boom") })
        val report = collector.report()
        assertTrue(report, report.contains("== Health ==\nunavailable\n"))
        assertTrue(report, report.contains("== Destinations ==\nunavailable\n"))
        assertTrue(report, report.contains("== Queue ==\nqueued: 1"))
        assertTrue(report, report.contains("== Events =="))
        assertFalse(report.contains("boom"))
    }

    @Test fun countsMatchDatabase() = runBlocking {
        val (dest, sms) = seed()
        val input = DiagnosticsCollector(context, graph).collect()
        assertEquals(mapOf(SmsStatus.QUEUED to 1), input.sms)
        val diag = input.destinations.single()
        assertEquals(dest, diag.id)
        assertTrue(diag.configured)
        assertEquals(mapOf(DeliveryStatus.QUEUED to 1), diag.counts)
        assertEquals(1, input.rules)
        assertEquals(1, input.activeRules)
        assertTrue(sms > 0)
    }
}
