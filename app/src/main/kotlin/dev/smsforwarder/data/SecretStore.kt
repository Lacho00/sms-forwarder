package dev.smsforwarder.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Credentials(val url: String, val token: String) {
    override fun toString(): String = "Credentials(***)"
}

interface SecretStore {
    suspend fun get(): Credentials?
    suspend fun set(c: Credentials)
}

private val Context.secretsDataStore by preferencesDataStore(name = "secrets")

/** AES-256-GCM key kept in the AndroidKeyStore; ciphertext and IV live in DataStore as base64. */
class KeystoreSecretStore(context: Context) : SecretStore {
    private val store = context.applicationContext.secretsDataStore

    override suspend fun get(): Credentials? {
        val prefs = store.data.first()
        val iv = prefs[IV_KEY]
        val blob = prefs[BLOB_KEY]
        if (iv == null || blob == null) return null
        return try {
            val key = existingKey() ?: throw java.security.KeyException("missing key")
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, Base64.decode(iv, Base64.NO_WRAP)))
            val plain = String(cipher.doFinal(Base64.decode(blob, Base64.NO_WRAP)), Charsets.UTF_8)
            val parts = plain.split(SEPARATOR, limit = 2)
            if (parts.size != 2) throw GeneralSecurityException("corrupt blob")
            Credentials(parts[0], parts[1])
        } catch (e: GeneralSecurityException) {
            // Covers AEADBadTagException, KeyPermanentlyInvalidatedException and a missing key.
            clear()
            null
        } catch (e: IllegalArgumentException) {
            clear()
            null
        }
    }

    override suspend fun set(c: Credentials) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, existingKey() ?: createKey())
        val encrypted = cipher.doFinal("${c.url}$SEPARATOR${c.token}".toByteArray(Charsets.UTF_8))
        store.edit {
            it[IV_KEY] = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
            it[BLOB_KEY] = Base64.encodeToString(encrypted, Base64.NO_WRAP)
        }
    }

    private suspend fun clear() {
        store.edit {
            it.remove(IV_KEY)
            it.remove(BLOB_KEY)
        }
    }

    private fun existingKey(): SecretKey? {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        return ks.getKey(ALIAS, null) as? SecretKey
    }

    private fun createKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "smsfwd-secrets"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val SEPARATOR = "\u0000"
        val IV_KEY = stringPreferencesKey("iv")
        val BLOB_KEY = stringPreferencesKey("blob")
    }
}

data class DestinationSecrets(val url: String, val headers: List<Pair<String, String>>) {
    override fun toString(): String = "DestinationSecrets(***)"

    companion object
}

interface DestinationSecretStore {
    suspend fun get(destinationId: Long): DestinationSecrets?
    suspend fun set(destinationId: Long, s: DestinationSecrets)
    suspend fun remove(destinationId: Long)
}

fun DestinationSecrets.Companion.encode(s: DestinationSecrets): String =
    JsonObject(
        mapOf(
            "url" to JsonPrimitive(s.url),
            "headers" to JsonArray(
                s.headers.map { (k, v) -> JsonArray(listOf(JsonPrimitive(k), JsonPrimitive(v))) },
            ),
        ),
    ).toString()

/** Throws on a malformed blob; callers treat that as corruption. */
fun DestinationSecrets.Companion.decode(text: String): DestinationSecrets {
    val obj = Json.parseToJsonElement(text).jsonObject
    val headers = obj.getValue("headers").jsonArray.map { pair ->
        val p = pair.jsonArray
        require(p.size == 2) { "bad header" }
        p[0].jsonPrimitive.content to p[1].jsonPrimitive.content
    }
    return DestinationSecrets(obj.getValue("url").jsonPrimitive.content, headers)
}

private const val BEARER_PREFIX = "Bearer "

fun Credentials.toDestinationSecrets(): DestinationSecrets =
    DestinationSecrets(url, listOf("Authorization" to "$BEARER_PREFIX$token"))

fun DestinationSecrets.toCredentialsOrNull(): Credentials? {
    val auth = headers.firstOrNull { it.first.equals("Authorization", ignoreCase = true) }?.second ?: return null
    if (!auth.startsWith(BEARER_PREFIX)) return null
    return Credentials(url, auth.removePrefix(BEARER_PREFIX))
}

/** Same Keystore key as [KeystoreSecretStore]; one IV and blob entry pair per destination in the `secrets` DataStore. */
class KeystoreDestinationSecretStore(context: Context) : DestinationSecretStore {
    private val store = context.applicationContext.secretsDataStore

    override suspend fun get(destinationId: Long): DestinationSecrets? {
        val prefs = store.data.first()
        val iv = prefs[ivKey(destinationId)]
        val blob = prefs[blobKey(destinationId)]
        if (iv == null || blob == null) return null
        return try {
            val key = existingSecretKey() ?: throw java.security.KeyException("missing key")
            val cipher = Cipher.getInstance(CIPHER_MODE)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(CIPHER_TAG_BITS, Base64.decode(iv, Base64.NO_WRAP)))
            val plain = String(cipher.doFinal(Base64.decode(blob, Base64.NO_WRAP)), Charsets.UTF_8)
            DestinationSecrets.decode(plain)
        } catch (e: GeneralSecurityException) {
            clearIfUnchanged(destinationId, iv, blob)
            null
        } catch (e: IllegalArgumentException) {
            // Also covers SerializationException.
            clearIfUnchanged(destinationId, iv, blob)
            null
        } catch (e: NoSuchElementException) {
            clearIfUnchanged(destinationId, iv, blob)
            null
        } catch (e: IllegalStateException) {
            clearIfUnchanged(destinationId, iv, blob)
            null
        }
    }

    /** Deletes the entries only if a concurrent set() has not replaced them since they were read. */
    private suspend fun clearIfUnchanged(id: Long, iv: String, blob: String) {
        store.edit {
            if (it[ivKey(id)] == iv && it[blobKey(id)] == blob) {
                it.remove(ivKey(id))
                it.remove(blobKey(id))
            }
        }
    }

    override suspend fun set(destinationId: Long, s: DestinationSecrets) {
        keyMutex.withLock {
            val cipher = Cipher.getInstance(CIPHER_MODE)
            cipher.init(Cipher.ENCRYPT_MODE, existingSecretKey() ?: createSecretKey())
            val encrypted = cipher.doFinal(DestinationSecrets.encode(s).toByteArray(Charsets.UTF_8))
            store.edit {
                it[ivKey(destinationId)] = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
                it[blobKey(destinationId)] = Base64.encodeToString(encrypted, Base64.NO_WRAP)
            }
        }
    }

    override suspend fun remove(destinationId: Long) {
        store.edit {
            it.remove(ivKey(destinationId))
            it.remove(blobKey(destinationId))
        }
    }

    private fun ivKey(id: Long) = stringPreferencesKey("dest_${id}_iv")
    private fun blobKey(id: Long) = stringPreferencesKey("dest_${id}_blob")
}

/** Process-wide: serialises key get-or-create and the encrypt+write of set(). */
private val keyMutex = Mutex()

private const val KS_PROVIDER = "AndroidKeyStore"
private const val KS_ALIAS = "smsfwd-secrets"
private const val CIPHER_MODE = "AES/GCM/NoPadding"
private const val CIPHER_TAG_BITS = 128

private fun existingSecretKey(): SecretKey? {
    val ks = KeyStore.getInstance(KS_PROVIDER).apply { load(null) }
    return ks.getKey(KS_ALIAS, null) as? SecretKey
}

private fun createSecretKey(): SecretKey {
    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KS_PROVIDER)
    generator.init(
        KeyGenParameterSpec.Builder(KS_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build(),
    )
    return generator.generateKey()
}
