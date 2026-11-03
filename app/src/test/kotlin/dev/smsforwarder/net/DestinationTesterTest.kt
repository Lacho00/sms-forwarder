package dev.smsforwarder.net

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.room.Room
import dev.smsforwarder.R
import dev.smsforwarder.core.Clock
import dev.smsforwarder.core.DestinationPresets
import dev.smsforwarder.core.HeaderDraft
import dev.smsforwarder.core.TestVerdict
import dev.smsforwarder.data.AppDatabase
import dev.smsforwarder.data.DestinationSecrets
import dev.smsforwarder.data.FakeDestinationSecretStore
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DestinationTesterTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var server: MockWebServer
    private lateinit var db: AppDatabase
    private val secrets = FakeDestinationSecretStore()

    private fun tester() = DestinationTester(
        DeliveryClient(defaultHttpClient()), secrets, app.getString(R.string.destination_test_body), "device-x",
        Clock { 1_800_000_000_000L },
    )

    private fun url(path: String = "/t") = server.url(path).toString()

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun tearDown() {
        server.close()
        db.close()
    }

    @Test fun standardSendsEmptyJsonAnd400IsOk() = runBlocking {
        server.enqueue(MockResponse.Builder().code(400).body("{\"error\":\"bad_request\"}").build())
        val result = tester().test(DestinationPresets.standardJson("Main", url(), "tok"), null)
        assertEquals(TestVerdict.OK, result.verdict)
        assertEquals(400, result.httpCode)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("{}", req.body!!.utf8())
        assertEquals("Bearer tok", req.headers["Authorization"])
        assertTrue(!req.body!!.utf8().contains("TEST"))
    }

    @Test fun customSendsSampleSms() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).build())
        val draft = DestinationPresets.custom("C").copy(
            url = url(), body = "{\"s\":\"{sender}\",\"b\":\"{body}\"}", bodyIsJson = true,
        )
        val result = tester().test(draft, null)
        assertEquals(TestVerdict.OK, result.verdict)
        assertEquals(200, result.httpCode)
        val body = server.takeRequest().body!!.utf8()
        assertTrue(body.contains("TEST"))
        assertTrue(body.contains(app.getString(R.string.destination_test_body)))
    }

    @Test fun blankFieldsUseStoredSecrets() = runBlocking {
        secrets.map[7L] = DestinationSecrets(url("/stored"), listOf("Authorization" to "Bearer stored"))
        server.enqueue(MockResponse.Builder().code(400).build())
        val draft = DestinationPresets.standardJson("Main", "https://h.example", "x").copy(
            url = null, headers = listOf(HeaderDraft("Authorization", null, secret = true)),
        )
        val result = tester().test(draft, 7L)
        assertEquals(TestVerdict.OK, result.verdict)
        val req = server.takeRequest()
        assertEquals("/stored", req.url.encodedPath)
        assertEquals("Bearer stored", req.headers["Authorization"])
    }

    @Test fun serverErrorIsFailedWithCode() = runBlocking {
        server.enqueue(MockResponse.Builder().code(500).build())
        val draft = DestinationPresets.custom("C").copy(url = url())
        val result = tester().test(draft, null)
        assertEquals(TestVerdict.FAILED, result.verdict)
        assertEquals(500, result.httpCode)
    }

    @Test fun unreachable() = runBlocking {
        val target = url()
        server.close()
        val result = tester().test(DestinationPresets.custom("C").copy(url = target), null)
        assertEquals(TestVerdict.UNREACHABLE, result.verdict)
        assertNull(result.httpCode)
    }

    @Test fun nothingRecorded() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).build())
        tester().test(DestinationPresets.custom("C").copy(url = url()), null)
        assertEquals(0, db.query("SELECT COUNT(*) FROM attempt", null).use { it.moveToFirst(); it.getInt(0) })
        assertEquals(0, db.query("SELECT COUNT(*) FROM delivery", null).use { it.moveToFirst(); it.getInt(0) })
        assertEquals(0, db.query("SELECT COUNT(*) FROM sms", null).use { it.moveToFirst(); it.getInt(0) })
    }
}
