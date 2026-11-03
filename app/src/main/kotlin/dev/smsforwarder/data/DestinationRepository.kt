package dev.smsforwarder.data

import androidx.room.withTransaction
import dev.smsforwarder.core.Clock
import dev.smsforwarder.core.DestinationDraft
import dev.smsforwarder.core.HeaderDraft
import dev.smsforwarder.core.HttpMethod
import dev.smsforwarder.core.SuccessPolicy
import dev.smsforwarder.core.TemplateKind
import dev.smsforwarder.core.validateDestination
import dev.smsforwarder.notify.Notifier
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/** A form's view of a stored destination: URL and secret header values never leave the store. */
data class EditableDestination(val draft: DestinationDraft, val urlStored: Boolean)

/**
 * Destinations and their secrets. The "primary" is the live standard-JSON destination with the lowest id; the
 * Settings screen still edits it as "the connection". [primaryName] names it when it is created.
 */
class DestinationRepository(
    private val db: AppDatabase,
    private val secretStore: DestinationSecretStore,
    private val clock: Clock,
    private val primaryName: String,
    private val sms: SmsRepository,
    private val notifier: Notifier,
) {
    private val dao = db.destinationDao()
    private val rulesDao = db.ruleDao()
    private val deliveries = db.deliveryDao()

    fun observeLive(): Flow<List<DestinationEntity>> = dao.observeLive()

    /** QUEUED and ERROR delivery counts per destination (visible SMS only); destinations with none are absent. */
    fun observeCounts(): Flow<List<DestinationCounts>> = deliveries.observeCounts()

    /** Stores a new destination; the row is removed again if its secrets cannot be saved. */
    suspend fun create(d: DestinationDraft): Long {
        require(validateDestination(d, urlStored = false).isEmpty()) { "invalid destination" }
        val id = dao.insert(
            DestinationEntity(
                name = d.name.trim(),
                enabled = d.enabled,
                template = d.template,
                method = d.method,
                bodyTemplate = d.body.ifBlank { null },
                bodyIsJson = d.bodyIsJson,
                publicHeaders = publicHeadersJson(d.headers),
                successPolicy = d.successPolicy,
                createdAt = clock.now(),
            ),
        )
        try {
            secretStore.set(id, DestinationSecrets(d.url.orEmpty().trim(), secretHeaders(d.headers, emptyList())))
        } catch (e: Throwable) {
            // Also when the scope is cancelled: a live row without secrets could become the primary.
            withContext(NonCancellable) { dao.hardDelete(id) }
            throw e
        }
        return id
    }

    suspend fun editable(id: Long): EditableDestination? {
        val e = dao.get(id)?.takeIf { it.deletedAt == null } ?: return null
        val stored = secretStore.get(id)
        val headers = e.publicHeaderList().map { (n, v) -> HeaderDraft(n, v, secret = false) } +
            stored?.headers.orEmpty().map { (n, _) -> HeaderDraft(n, null, secret = true) }
        return EditableDestination(
            DestinationDraft(
                name = e.name, enabled = e.enabled, template = e.template, method = e.method, url = null,
                headers = headers, body = e.bodyTemplate.orEmpty(), bodyIsJson = e.bodyIsJson,
                successPolicy = e.successPolicy,
            ),
            urlStored = stored != null,
        )
    }

    /**
     * Saves the edit; a null URL or secret header value keeps what is stored. Auth and configuration errors of this
     * destination go back to QUEUED unless the saved destination is paused; returns those SMS ids for the caller to schedule.
     */
    suspend fun update(id: Long, d: DestinationDraft): List<Long> {
        val entity = dao.get(id)?.takeIf { it.deletedAt == null } ?: throw IllegalArgumentException("unknown destination")
        // A transient Keystore failure throws here, before anything changes.
        val stored = secretStore.get(id)
        require(validateDestination(d, urlStored = stored != null).isEmpty()) { "invalid destination" }
        return db.withTransaction {
            dao.update(
                entity.copy(
                    name = d.name.trim(),
                    enabled = d.enabled,
                    template = d.template,
                    method = d.method,
                    bodyTemplate = d.body.ifBlank { null },
                    bodyIsJson = d.bodyIsJson,
                    publicHeaders = publicHeadersJson(d.headers),
                    successPolicy = d.successPolicy,
                ),
            )
            val url = d.url?.trim() ?: stored?.url ?: throw IllegalArgumentException("invalid destination")
            secretStore.set(id, DestinationSecrets(url, secretHeaders(d.headers, stored?.headers.orEmpty())))
            val resumed = if (!entity.enabled && d.enabled) deliveries.smsIdsQueuedFor(id) else emptyList()
            // Saving a paused destination (or pausing it) leaves its errors alone: nothing would send them and
            // they would read as "queued". They go back to the queue when it is active again.
            val fixed = if (d.enabled) sms.requeueAuthErrors(id) + sms.requeueConfigErrors(id) else emptyList()
            (fixed + resumed).distinct()
        }
    }

    /** Pauses or resumes; resuming returns the SMS with a QUEUED delivery here for the caller to schedule. */
    suspend fun setEnabled(id: Long, enabled: Boolean): List<Long> = db.withTransaction {
        val entity = dao.get(id)?.takeIf { it.deletedAt == null } ?: return@withTransaction emptyList()
        if (entity.enabled != enabled) dao.update(entity.copy(enabled = enabled))
        if (enabled) deliveries.smsIdsQueuedFor(id) else emptyList()
    }

    /** QUEUED plus ERROR deliveries here, for the delete confirmation. */
    suspend fun pendingCount(id: Long): Int = deliveries.pendingCountFor(id)

    /**
     * Soft-deletes: pending deliveries become CANCELED (finished ones keep the destination's name), rules lose the
     * link, then the secrets and the token alert go.
     */
    suspend fun delete(id: Long) {
        db.withTransaction {
            val entity = dao.get(id) ?: return@withTransaction
            if (entity.deletedAt == null) dao.update(entity.copy(deletedAt = clock.now()))
            sms.cancelDeliveriesTo(id)
            rulesDao.deleteLinksTo(id)
        }
        withContext(NonCancellable) {
            secretStore.remove(id)
            notifier.cancelTokenRejected(id)
        }
    }

    /** Live destinations that an enabled rule points at and that have no stored secrets. */
    suspend fun unconfiguredInUse(): List<Long> {
        val used = rulesDao.activeWithDestinations()
            .flatMap { it.destinations }.filter { it.deletedAt == null }.map { it.id }.distinct().sorted()
        return used.filter { secretStore.get(it) == null }
    }

    private fun publicHeadersJson(headers: List<HeaderDraft>): String =
        JsonArray(
            headers.filter { !it.secret }.map { JsonArray(listOf(JsonPrimitive(it.name), JsonPrimitive(it.value.orEmpty()))) },
        ).toString()

    /** Secret headers of the draft; a null value takes the stored one of the same name, or drops the header. */
    private fun secretHeaders(headers: List<HeaderDraft>, stored: List<Pair<String, String>>): List<Pair<String, String>> =
        headers.filter { it.secret }.mapNotNull { h ->
            val value = h.value ?: stored.firstOrNull { it.first.equals(h.name, ignoreCase = true) }?.second
            value?.let { h.name to it }
        }

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
