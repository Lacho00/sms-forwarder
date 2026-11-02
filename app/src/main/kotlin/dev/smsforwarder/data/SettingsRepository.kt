package dev.smsforwarder.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dev.smsforwarder.core.Language
import dev.smsforwarder.core.NotifyKind
import dev.smsforwarder.core.Retention
import dev.smsforwarder.core.SenderFilter
import dev.smsforwarder.core.StuckAfter
import dev.smsforwarder.core.SweepInterval
import dev.smsforwarder.notify.Notifier
import dev.smsforwarder.ui.Localized
import dev.smsforwarder.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File

/**
 * App settings in DataStore. [storeFile] only exists so tests can use a separate file each.
 * [scheduler] kicks a send for one SMS id; it is a lambda so tests can record the ids.
 */
class SettingsRepository(
    context: Context,
    private val secretStore: SecretStore,
    private val smsRepository: SmsRepository,
    private val scheduler: (Long) -> Unit,
    storeFile: File = File(context.applicationContext.filesDir, "datastore/settings.preferences_pb"),
) {
    private val appContext = context.applicationContext
    private val store: DataStore<Preferences> = PreferenceDataStoreFactory.create { storeFile }

    // Stored as the user typed them, one per line; matching always goes through normalize.
    val senders: Flow<List<String>> = store.data.map { decodeSenders(it[SENDERS_KEY]) }

    suspend fun addSender(s: String) {
        val clean = s.trim()
        val normalized = SenderFilter.normalize(clean)
        if (normalized.isEmpty()) return
        store.edit { prefs ->
            val current = decodeSenders(prefs[SENDERS_KEY])
            if (current.none { SenderFilter.normalize(it) == normalized }) {
                prefs[SENDERS_KEY] = (current + clean).joinToString(SEPARATOR)
            }
        }
    }

    suspend fun removeSender(s: String) {
        val normalized = SenderFilter.normalize(s)
        store.edit { prefs ->
            val kept = decodeSenders(prefs[SENDERS_KEY]).filter { SenderFilter.normalize(it) != normalized }
            prefs[SENDERS_KEY] = kept.joinToString(SEPARATOR)
        }
    }

    val themeMode: Flow<ThemeMode> = store.data.map { prefs ->
        prefs[THEME_KEY]?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } } ?: ThemeMode.SYSTEM
    }

    suspend fun setThemeMode(m: ThemeMode) {
        store.edit { it[THEME_KEY] = m.name }
    }

    suspend fun sweepCursor(): Long? = store.data.first()[SWEEP_CURSOR_KEY]

    suspend fun setSweepCursor(t: Long) {
        store.edit { it[SWEEP_CURSOR_KEY] = t }
    }

    suspend fun sweepFloor(): Long? = store.data.first()[SWEEP_FLOOR_KEY]

    suspend fun setSweepFloor(t: Long) {
        store.edit { it[SWEEP_FLOOR_KEY] = t }
    }

    val onboardingDone: Flow<Boolean> = store.data.map { it[ONBOARDING_KEY] ?: false }

    suspend fun setOnboardingDone() {
        store.edit { it[ONBOARDING_KEY] = true }
    }

    val lastSweepAtFlow: Flow<Long?> = store.data.map { it[LAST_SWEEP_AT_KEY] }

    suspend fun lastSweepAt(): Long? = store.data.first()[LAST_SWEEP_AT_KEY]

    suspend fun setLastSweepAt(t: Long) {
        store.edit { it[LAST_SWEEP_AT_KEY] = t }
    }

    val inboxUnreadableFlow: Flow<Boolean> = store.data.map { it[INBOX_UNREADABLE_KEY] ?: false }

    suspend fun inboxUnreadable(): Boolean = store.data.first()[INBOX_UNREADABLE_KEY] ?: false

    suspend fun setInboxUnreadable(v: Boolean) {
        store.edit { it[INBOX_UNREADABLE_KEY] = v }
    }

    /**
     * New credentials usually mean the old token failed (401) or none existed yet, so every
     * 401 row goes back to QUEUED and every queued SMS gets a fresh send, skipping its backoff.
     */
    suspend fun saveCredentials(c: Credentials) {
        secretStore.set(c)
        val unauthorized = smsRepository.idsWithError401()
        unauthorized.forEach { smsRepository.requeue(it) }
        (unauthorized + smsRepository.queuedIds()).distinct().forEach(scheduler)
    }

    val forwardingEnabled: Flow<Boolean> = store.data.map { it[FORWARDING_KEY] ?: true }

    suspend fun isForwardingEnabled(): Boolean = store.data.first()[FORWARDING_KEY] ?: true

    /** Resuming sends every queued SMS again right away; pausing only stops the sending. */
    suspend fun setForwardingEnabled(v: Boolean) {
        store.edit { it[FORWARDING_KEY] = v }
        if (v) smsRepository.queuedIds().forEach(scheduler)
    }

    val sweepInterval: Flow<SweepInterval> = store.data.map { readEnum(it[SWEEP_INTERVAL_KEY], SweepInterval.M15) }

    suspend fun sweepIntervalNow(): SweepInterval = sweepInterval.first()

    suspend fun setSweepInterval(v: SweepInterval) {
        store.edit { it[SWEEP_INTERVAL_KEY] = v.name }
    }

    val stuckAfter: Flow<StuckAfter> = store.data.map { readEnum(it[STUCK_AFTER_KEY], StuckAfter.H6) }

    suspend fun stuckAfterNow(): StuckAfter = stuckAfter.first()

    suspend fun setStuckAfter(v: StuckAfter) {
        store.edit { it[STUCK_AFTER_KEY] = v.name }
    }

    val retention: Flow<Retention> = store.data.map { readEnum(it[RETENTION_KEY], Retention.D90) }

    suspend fun retentionNow(): Retention = retention.first()

    suspend fun setRetention(v: Retention) {
        store.edit { it[RETENTION_KEY] = v.name }
    }

    val hideText: Flow<Boolean> = store.data.map { it[HIDE_TEXT_KEY] ?: false }

    suspend fun setHideText(v: Boolean) {
        store.edit { it[HIDE_TEXT_KEY] = v }
    }

    val dynamicColor: Flow<Boolean> = store.data.map { it[DYNAMIC_COLOR_KEY] ?: true }

    suspend fun setDynamicColor(v: Boolean) {
        store.edit { it[DYNAMIC_COLOR_KEY] = v }
    }

    val language: Flow<Language> = store.data.map { readEnum(it[LANGUAGE_KEY], Language.SYSTEM) }

    suspend fun setLanguage(v: Language) {
        store.edit { it[LANGUAGE_KEY] = v.name }
        // Synchronous mirror for attachBaseContext, then rename the channel in the new language.
        Localized.saveTag(appContext, v.tag)
        Notifier.ensureChannel(appContext)
    }

    fun notifyEnabled(kind: NotifyKind): Flow<Boolean> = store.data.map { it[notifyKey(kind)] ?: true }

    suspend fun isNotifyEnabled(kind: NotifyKind): Boolean = notifyEnabled(kind).first()

    suspend fun setNotifyEnabled(kind: NotifyKind, v: Boolean) {
        store.edit { it[notifyKey(kind)] = v }
    }

    suspend fun lastNotifiedIssues(): Set<String> = store.data.first()[NOTIFIED_ISSUES_KEY] ?: emptySet()

    suspend fun setLastNotifiedIssues(s: Set<String>) {
        store.edit { it[NOTIFIED_ISSUES_KEY] = s }
    }

    private fun decodeSenders(raw: String?): List<String> =
        raw?.split(SEPARATOR)?.filter { it.isNotBlank() } ?: emptyList()

    private inline fun <reified E : Enum<E>> readEnum(raw: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == raw } ?: default

    private fun notifyKey(kind: NotifyKind) = booleanPreferencesKey("notify_${kind.name.lowercase()}")

    private companion object {
        const val SEPARATOR = "\n"
        val SENDERS_KEY = stringPreferencesKey("senders")
        val THEME_KEY = stringPreferencesKey("theme_mode")
        val SWEEP_CURSOR_KEY = longPreferencesKey("sweep_cursor")
        val SWEEP_FLOOR_KEY = longPreferencesKey("sweep_floor")
        val LAST_SWEEP_AT_KEY = longPreferencesKey("last_sweep_at")
        val ONBOARDING_KEY = booleanPreferencesKey("onboarding_done")
        val INBOX_UNREADABLE_KEY = booleanPreferencesKey("inbox_unreadable")
        val FORWARDING_KEY = booleanPreferencesKey("forwarding_enabled")
        val SWEEP_INTERVAL_KEY = stringPreferencesKey("sweep_interval")
        val STUCK_AFTER_KEY = stringPreferencesKey("stuck_after")
        val RETENTION_KEY = stringPreferencesKey("retention")
        val HIDE_TEXT_KEY = booleanPreferencesKey("hide_text")
        val DYNAMIC_COLOR_KEY = booleanPreferencesKey("dynamic_color")
        val LANGUAGE_KEY = stringPreferencesKey("language")
        val NOTIFIED_ISSUES_KEY = stringSetPreferencesKey("last_notified_issues")
    }
}
