package dev.smsforwarder.data

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.security.KeyStoreException
import java.security.ProviderException
import javax.crypto.AEADBadTagException

@RunWith(RobolectricTestRunner::class)
class KeystoreSecretStoreTest {
    private val context = RuntimeEnvironment.getApplication()
    private val b64 = "AAAA"

    private suspend fun seedLegacy() = context.secretsDataStore.edit {
        it[stringPreferencesKey("iv")] = b64
        it[stringPreferencesKey("blob")] = b64
    }

    private suspend fun seedDestination(id: Long) = context.secretsDataStore.edit {
        it[stringPreferencesKey("dest_${id}_iv")] = b64
        it[stringPreferencesKey("dest_${id}_blob")] = b64
    }

    private suspend fun legacyBlob() = context.secretsDataStore.data.first()[stringPreferencesKey("blob")]
    private suspend fun destBlob(id: Long) = context.secretsDataStore.data.first()[stringPreferencesKey("dest_${id}_blob")]

    private fun failWith(e: Exception): (ByteArray, ByteArray) -> ByteArray = { _, _ -> throw e }

    @Test fun legacyTransientKeepsBlobAndThrows() = runBlocking {
        seedLegacy()
        for (e in listOf<Exception>(KeyStoreException("not ready"), ProviderException("busy"))) {
            val store = KeystoreSecretStore(context, failWith(e))
            try {
                store.get()
                error("expected TransientSecretException")
            } catch (t: TransientSecretException) {
                assertNotNull(legacyBlob())
            }
        }
    }

    @Test fun legacyCorruptionThrowsUnrecoverableButKeepsBlob() = runBlocking {
        seedLegacy()
        for (d in listOf<(ByteArray, ByteArray) -> ByteArray>(
            failWith(AEADBadTagException("bad")),
            { _, _ -> "no separator".toByteArray() },
        )) {
            try {
                KeystoreSecretStore(context, d).get()
                error("expected UnrecoverableSecretException")
            } catch (e: UnrecoverableSecretException) {
                assertNotNull(legacyBlob())
            }
        }
    }

    @Test fun legacyDiscardDeletesBlob() = runBlocking {
        seedLegacy()
        KeystoreSecretStore(context, failWith(AEADBadTagException("bad"))).discard()
        assertNull(legacyBlob())
    }

    @Test fun legacyHealthyRoundTrip() = runBlocking {
        seedLegacy()
        val c = KeystoreSecretStore(context, { _, _ -> "u\u0000t".toByteArray() }).get()
        assertEquals(Credentials("u", "t"), c)
        assertNotNull(legacyBlob())
    }

    @Test fun destinationTransientKeepsEntriesAndThrows() = runBlocking {
        seedDestination(7)
        try {
            KeystoreDestinationSecretStore(context, failWith(KeyStoreException("not ready"))).get(7)
            error("expected TransientSecretException")
        } catch (t: TransientSecretException) {
            assertNotNull(destBlob(7))
        }
    }

    @Test fun destinationCorruptionDeletesEntries() = runBlocking {
        seedDestination(7)
        assertNull(KeystoreDestinationSecretStore(context, failWith(AEADBadTagException("bad"))).get(7))
        assertNull(destBlob(7))
        seedDestination(8)
        assertNull(KeystoreDestinationSecretStore(context, { _, _ -> "not json".toByteArray() }).get(8))
        assertNull(destBlob(8))
    }

    /** Stand-in for android.security.KeyStoreException, which Robolectric cannot construct. */
    private class FakeKsFault : Exception("ks")

    private fun classify(e: Exception): Class<*> = try {
        decodeSecret({ _, _ -> throw e }, "AAAA", "AAAA", { it is FakeKsFault }, { it })
        Nothing::class.java
    } catch (t: Exception) {
        t.javaClass
    }

    @Test fun wrappedKeystoreFaultsAreTransient() {
        val transient = TransientSecretException::class.java
        assertEquals(transient, classify(java.security.UnrecoverableKeyException("x").initCause(FakeKsFault()) as Exception))
        assertEquals(transient, classify(javax.crypto.IllegalBlockSizeException("x").initCause(FakeKsFault()) as Exception))
    }

    @Test fun wrappedPermanentInvalidationIsPermanent() {
        val kpie = android.security.keystore.KeyPermanentlyInvalidatedException("x")
        val wrapped = java.security.UnrecoverableKeyException("x").also { it.initCause(kpie) }
        assertEquals(UnrecoverableSecretException::class.java, classify(wrapped))
        assertEquals(UnrecoverableSecretException::class.java, classify(java.security.UnrecoverableKeyException("plain")))
        assertEquals(UnrecoverableSecretException::class.java, classify(AEADBadTagException("bad")))
    }

    @Test fun realKeystoreFaultClassIsMatchedByName() {
        assertEquals(false, isAndroidKeystoreFault(RuntimeException()))
    }
}
