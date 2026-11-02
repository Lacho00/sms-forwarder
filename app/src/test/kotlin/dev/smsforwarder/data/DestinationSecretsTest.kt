package dev.smsforwarder.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class DestinationSecretsTest {
    @Test fun encodeDecodeRoundTrip() {
        val s = DestinationSecrets(
            "https://h/x?a=\"b\"",
            listOf("X-A" to "va\"l\u0000ue", "Authorization" to "Bearer t"),
        )
        assertEquals(s, DestinationSecrets.decode(DestinationSecrets.encode(s)))
    }

    @Test fun credentialsRoundTrip() {
        val c = Credentials("https://h", "t")
        assertEquals(c, c.toDestinationSecrets().toCredentialsOrNull())
    }

    @Test fun nonBearerHasNoCredentials() {
        assertNull(DestinationSecrets("https://h", listOf("X-A" to "b")).toCredentialsOrNull())
    }

    @Test fun toStringRedacts() {
        val text = DestinationSecrets("https://secret-host", listOf("Authorization" to "Bearer tok-1")).toString()
        assertEquals("DestinationSecrets(***)", text)
        assertFalse(text.contains("secret-host"))
        assertFalse(text.contains("tok-1"))
    }

    @Test fun fakeStoreFailOnSet() {
        val f = FakeDestinationSecretStore().apply { failOnSet = true }
        val r = runCatching { kotlinx.coroutines.runBlocking { f.set(1L, DestinationSecrets("u", emptyList())) } }
        assertEquals(true, r.isFailure)
    }
}
