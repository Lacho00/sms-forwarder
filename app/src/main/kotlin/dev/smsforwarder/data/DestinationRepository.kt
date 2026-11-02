package dev.smsforwarder.data

import androidx.room.withTransaction
import dev.smsforwarder.core.Clock
import dev.smsforwarder.core.HttpMethod
import dev.smsforwarder.core.SuccessPolicy
import dev.smsforwarder.core.TemplateKind

/**
 * Destinations and their secrets. The "primary" is the active destination with the lowest id; the
 * Settings screen still edits it as "the connection". [primaryName] names it when it is created.
 */
class DestinationRepository(
    private val db: AppDatabase,
    private val secretStore: DestinationSecretStore,
    private val clock: Clock,
    private val primaryName: String,
) {
    private val dao = db.destinationDao()

    /** Id of the primary destination, creating the standard JSON one when there is none. */
    suspend fun ensurePrimary(): Long = db.withTransaction {
        dao.primary()?.id ?: dao.insert(
            DestinationEntity(
                name = primaryName,
                enabled = true,
                template = TemplateKind.STANDARD_JSON,
                method = HttpMethod.POST,
                bodyTemplate = STANDARD_JSON_BODY,
                bodyIsJson = true,
                publicHeaders = "[]",
                successPolicy = SuccessPolicy.ANY_2XX,
                createdAt = clock.now(),
            ),
        )
    }

    suspend fun primary(): DestinationEntity? = dao.primary()

    suspend fun primaryCredentials(): Credentials? {
        val id = dao.primary()?.id ?: return null
        return secretStore.get(id)?.toCredentialsOrNull()
    }

    suspend fun savePrimaryCredentials(c: Credentials) {
        val id = ensurePrimary()
        val fresh = c.toDestinationSecrets()
        val kept = secretStore.get(id)?.headers.orEmpty().filter { !it.first.equals("Authorization", ignoreCase = true) }
        secretStore.set(id, DestinationSecrets(fresh.url, fresh.headers + kept))
    }

    suspend fun get(id: Long): DestinationEntity? = dao.get(id)

    suspend fun secrets(id: Long): DestinationSecrets? = secretStore.get(id)
}
