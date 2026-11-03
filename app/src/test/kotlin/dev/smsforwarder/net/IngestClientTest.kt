package dev.smsforwarder.net

import dev.smsforwarder.core.ConnectionTestResult
import dev.smsforwarder.data.Credentials
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class IngestClientTest {
    private lateinit var server: MockWebServer
    private val client = IngestClient(defaultHttpClient())

    private fun credentials() = Credentials(server.url("/ingest").toString(), "test-token")

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.close()
    }

    @Test fun testConnectionSendsEmptyObject() = runBlocking {
        server.enqueue(MockResponse.Builder().code(400).body("{\"error\":\"bad_request\"}").build())
        val result = client.testConnection(credentials())
        assertEquals(ConnectionTestResult.OK, result)
        assertEquals("{}", server.takeRequest().body!!.utf8())
    }
}
