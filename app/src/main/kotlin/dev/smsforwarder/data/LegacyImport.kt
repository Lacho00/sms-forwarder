package dev.smsforwarder.data

import dev.smsforwarder.core.EngineEvent
import dev.smsforwarder.core.EngineEventType
import dev.smsforwarder.diag.EngineLog
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
    /** Runs once after a real 1.1 import completes (e.g. to drop alerts 1.1 left behind). */
    private val onImported: () -> Unit = {},
    private val engineLog: EngineLog? = null,
) {
    private val lock = Mutex()

    private suspend fun readLegacy(): Credentials? {
        try {
            return legacySecrets.get()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val (count, firstAt) = settings.recordLegacyReadFailure(clock.now())
            // Runs pile up within seconds after boot: wait for both enough failures and a day.
            if (count < MAX_READ_FAILURES || clock.now() - firstAt < GIVE_UP_AFTER_MS) throw e
            try {
                legacySecrets.discard()
            } catch (inner: CancellationException) {
                throw inner
            } catch (inner: Exception) {
                // The blob stays; the import still completes without it.
            }
            return null
        }
    }

    /** True when the import is done or was never needed; false means "safe mode": do not send. */
    suspend fun run(): Boolean = lock.withLock {
        try {
            if (settings.isImportDone()) return true
            // A fresh install has no 1.1 data and no primary destination.
            if (db.destinationDao().get(PRIMARY_DESTINATION_ID) == null) {
                settings.markImportDone()
                return true
            }
            // The 1.1 blob is never deleted on the first failure: Keystore errors are often wrapped and
            // look permanent. A failed read lands in safe mode and retries; after MAX_READ_FAILURES
            // failed runs spanning a day it is given up on (import completes without credentials).
            val credentials = readLegacy()
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
            try {
                onImported()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Cosmetic cleanup; never undo a finished import.
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The same broken DataStore may fail again; the next run tries anyway.
            try {
                settings.markImportFailed()
            } catch (inner: CancellationException) {
                throw inner
            } catch (inner: Exception) {
                // ignored on purpose
            }
            engineLog?.add(EngineEvent(clock.now(), EngineEventType.SAFE_MODE, code = "legacy_import"))
            false
        }
    }

    private companion object {
        const val MAX_READ_FAILURES = 5
        const val GIVE_UP_AFTER_MS = 24L * 60 * 60 * 1000
    }
}
