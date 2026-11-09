package dev.smsforwarder.net

import dev.smsforwarder.core.HttpMethod
import dev.smsforwarder.core.RenderedRequest
import dev.smsforwarder.core.SendOutcome
import dev.smsforwarder.core.SuccessPolicy
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class DeliveryClientTest {
    private lateinit var server: MockWebServer
    private val client = DeliveryClient(defaultHttpClient())

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.close()
    }

    private fun request(method: HttpMethod = HttpMethod.POST, body: String? = "{\"a\":1}") = RenderedRequest(
        method = method,
        url = server.url("/hook").toString(),
        headers = listOf("Authorization" to "Bearer t", "X-Extra" to "v"),
        body = body,
        contentType = "application/json; charset=utf-8",
    )

    @Test fun postsRenderedRequest() = runBlocking {
        server.enqueue(MockResponse.Builder().code(204).build())
        val outcome = client.send(request(), SuccessPolicy.ANY_2XX)
        assertEquals(SendOutcome.Accepted(204), outcome)

        val received = server.takeRequest()
        assertEquals("POST", received.method)
        assertEquals("/hook", received.url.encodedPath)
        assertEquals("Bearer t", received.headers["Authorization"])
        assertEquals("v", received.headers["X-Extra"])
        assertEquals("application/json; charset=utf-8", received.headers["Content-Type"])
        assertEquals("{\"a\":1}", received.body!!.utf8())
    }

    @Test fun status200PolicyRetriesOther2xx() = runBlocking {
        server.enqueue(MockResponse.Builder().code(204).build())
        assertEquals(SendOutcome.Retry(204, "http_204"), client.send(request(), SuccessPolicy.STATUS_200))
    }

    @Test fun getHasNoBody() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).build())
        assertEquals(SendOutcome.Accepted(200), client.send(request(HttpMethod.GET, null), SuccessPolicy.ANY_2XX))
        assertEquals("GET", server.takeRequest().method)
    }

    @Test fun rejectedCarriesErrorCode() = runBlocking {
        server.enqueue(MockResponse.Builder().code(403).body("{\"error\":\"forbidden\"}").build())
        assertEquals(SendOutcome.Rejected(403, "forbidden"), client.send(request(), SuccessPolicy.ANY_2XX))
    }

    @Test fun malformedUrlIsInvalidNotCrash() = runBlocking {
        val bad = request().copy(url = "not a url")
        assertEquals(SendOutcome.Invalid("request"), client.send(bad, SuccessPolicy.ANY_2XX))
    }

    @Test fun malformedHeaderIsInvalid() = runBlocking {
        val bad = request().copy(headers = listOf("X-Bad" to "caf\u00e9"))
        assertEquals(SendOutcome.Invalid("request"), client.send(bad, SuccessPolicy.ANY_2XX))
        assertEquals(0, server.requestCount)
    }

    @Test fun redirectToOtherHostIsNotFollowed() = runBlocking {
        val other = MockWebServer()
        other.start()
        try {
            other.enqueue(MockResponse.Builder().code(200).build())
            server.enqueue(MockResponse.Builder().code(307).addHeader("Location", other.url("/stolen").toString()).build())
            assertEquals(SendOutcome.Invalid("redirect"), client.send(request(), SuccessPolicy.ANY_2XX))
            assertEquals(0, other.requestCount)
        } finally {
            other.close()
        }
    }

    @Test fun movedPermanentlyIsNotAccepted() = runBlocking {
        server.enqueue(MockResponse.Builder().code(301).addHeader("Location", server.url("/next").toString()).build())
        server.enqueue(MockResponse.Builder().code(200).build())
        assertEquals(SendOutcome.Invalid("redirect"), client.send(request(), SuccessPolicy.ANY_2XX))
        assertEquals(1, server.requestCount)
    }
}
