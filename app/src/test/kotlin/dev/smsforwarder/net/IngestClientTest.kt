package dev.smsforwarder.net

import dev.smsforwarder.core.ConnectionTestResult
import dev.smsforwarder.core.SendOutcome
import dev.smsforwarder.data.Credentials
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class IngestClientTest {
    private lateinit var server: MockWebServer
    private val client = IngestClient(defaultHttpClient())

    private val body = "OPERADOR: Se ha comprado un plan de datos de 2.5GB valido por 30 dias, " +
        "se activa con el primer uso. Se desconto 360.00. Saldo actual 140.25"

    private fun credentials() = Credentials(server.url("/ingest").toString(), "test-token")

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.close()
    }

    @Test fun sendsBearerJsonAndNumberReceivedAt() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).build())
        val outcome = client.send(credentials(), "OPERADOR", body, 1_800_000_000_000L)
        assertEquals(SendOutcome.Accepted, outcome)

        val request = server.takeRequest()
        assertEquals("Bearer test-token", request.headers["Authorization"])
        assertTrue(request.headers["Content-Type"]!!.startsWith("application/json"))
        val text = request.body!!.utf8()
        assertTrue(text, text.contains("\"received_at\":1800000000000"))
        assertTrue(text, text.contains("\"sender\":\"OPERADOR\""))
    }

    @Test fun maps401ToRejected() = runBlocking {
        server.enqueue(MockResponse.Builder().code(401).body("{\"error\":\"unauthorized\"}").build())
        val outcome = client.send(credentials(), "OPERADOR", body, 1_800_000_000_000L)
        assertEquals(SendOutcome.Rejected(401, "unauthorized"), outcome)
    }

    @Test fun disconnectIsRetry() = runBlocking {
        val creds = credentials()
        server.close() // nothing is listening any more: connection refused
        val outcome = client.send(creds, "OPERADOR", body, 1_800_000_000_000L)
        assertEquals(SendOutcome.Retry(null, "network"), outcome)
    }

    @Test fun testConnectionSendsEmptyObject() = runBlocking {
        server.enqueue(MockResponse.Builder().code(400).body("{\"error\":\"bad_request\"}").build())
        val result = client.testConnection(credentials())
        assertEquals(ConnectionTestResult.OK, result)
        assertEquals("{}", server.takeRequest().body!!.utf8())
    }
}
