package dev.smsforwarder.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
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
