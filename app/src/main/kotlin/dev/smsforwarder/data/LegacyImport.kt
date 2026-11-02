package dev.smsforwarder.data

import androidx.room.withTransaction
import dev.smsforwarder.core.Clock
import dev.smsforwarder.core.SenderFilter
import dev.smsforwarder.core.SenderMode
import dev.smsforwarder.core.SimMatch
import dev.smsforwarder.core.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One-time import of the 1.1.x settings: the single URL and token become the secrets of the
 * primary destination, and each sender becomes an exact-sender rule that points at it.
 *
 * Secrets go first, because Room can roll back and the Keystore cannot; every step is safe to
 * repeat, so a failure leaves nothing half done and the next [run] picks up from the start.
 */
class LegacyImport(
    private val db: AppDatabase,
    private val legacySecrets: SecretStore,
    private val secrets: DestinationSecretStore,
    private val settings: SettingsRepository,
    private val clock: Clock = SystemClock,
) {
    private val lock = Mutex()

    /** True when the import is done or was never needed; false means "safe mode": do not send. */
    suspend fun run(): Boolean = lock.withLock {
        if (settings.isImportDone()) return true
        try {
            // A fresh install has no 1.1 data and no primary destination.
            if (db.destinationDao().get(PRIMARY_DESTINATION_ID) == null) {
                settings.markImportDone()
                return true
            }
            val credentials = legacySecrets.get()
            if (credentials != null && secrets.get(PRIMARY_DESTINATION_ID) == null) {
                secrets.set(PRIMARY_DESTINATION_ID, credentials.toDestinationSecrets())
            }
            val senders = settings.legacySenders()
            db.withTransaction {
                val known = db.ruleDao().exactSenderOnlyPatterns().map { SenderFilter.normalize(it) }.toMutableSet()
                for (sender in senders) {
                    if (!known.add(SenderFilter.normalize(sender))) continue
                    val id = db.ruleDao().insert(
                        RuleEntity(
                            name = sender,
                            enabled = true,
                            senderMode = SenderMode.EXACT,
                            senderPattern = sender,
                            bodyMode = null,
                            bodyPattern = null,
                            sim = SimMatch.ANY,
                            createdAt = clock.now(),
                        ),
                    )
                    db.ruleDao().insertLinks(listOf(RuleDestinationEntity(id, PRIMARY_DESTINATION_ID)))
                }
            }
            settings.markImportDone()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            try {
                settings.markImportFailed()
            } catch (inner: CancellationException) {
                throw inner
            } catch (inner: Exception) {
                // Nothing more to do: the next run tries again anyway.
            }
            false
        }
    }
}
