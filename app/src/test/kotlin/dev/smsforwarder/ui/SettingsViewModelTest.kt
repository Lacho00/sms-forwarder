package dev.smsforwarder.ui

import androidx.room.Room
import dev.smsforwarder.core.Clock
import dev.smsforwarder.core.ConnectionTestResult
import dev.smsforwarder.core.Language
import dev.smsforwarder.core.NotifyKind
import dev.smsforwarder.core.Retention
import dev.smsforwarder.core.StuckAfter
import dev.smsforwarder.core.SweepInterval
import dev.smsforwarder.data.AppDatabase
import dev.smsforwarder.data.Credentials
import dev.smsforwarder.data.SecretStore
import dev.smsforwarder.data.TestRepos
import dev.smsforwarder.data.SettingsRepository
import dev.smsforwarder.data.SmsRepository
import dev.smsforwarder.ui.settings.SettingsPrefs
import dev.smsforwarder.ui.settings.SettingsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class SettingsViewModelTest {
    private class NoSecrets : SecretStore {
        override suspend fun get(): Credentials? = null
        override suspend fun set(c: Credentials) = Unit
    }

    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsRepository
    private val sweeps = java.util.concurrent.CopyOnWriteArrayList<SweepInterval>()

    @Before fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val sms = SmsRepository(db, Clock { 1_800_000_000_000L })
        val file = File(context.cacheDir, "settings-vm-${UUID.randomUUID()}.preferences_pb")
        settings = TestRepos(db).let { SettingsRepository(context, it.destinations, it.rules, sms, scheduler = {}, storeFile = file) }
    }

    @After fun tearDown() {
        db.close()
    }

    private fun vm() = SettingsViewModel(
        settings = settings,
        scope = CoroutineScope(Dispatchers.Unconfined),
        onSweepIntervalChanged = { sweeps += it },
    )

    // 10 s, like the language tests: on a cold CI runner the first DataStore write can take
    // more than 5 s, and a shorter timeout made these tests fail at random.
    private fun SettingsViewModel.await(cond: (SettingsPrefs) -> Boolean): SettingsPrefs =
        runBlocking { withTimeout(10_000) { prefs.first(cond) } }

    // The callback runs right after the save, on another thread, so the flow can update first.
    private fun waitUntil(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!cond() && System.currentTimeMillis() < end) Thread.sleep(10)
    }

    @Test fun prefsStartWithDefaults() {
        val p = vm().await { true }
        assertEquals(true, p.forwarding)
        assertEquals(SweepInterval.M15, p.sweepInterval)
        assertEquals(StuckAfter.H6, p.stuckAfter)
        assertEquals(Retention.D90, p.retention)
        assertEquals(NotifyKind.entries.toSet(), p.notify.keys)
        assertEquals(true, p.dynamicColor)
        assertEquals(Language.SYSTEM, p.language)
    }

    @Test fun settersPersistAndShowInPrefs() {
        val vm = vm()
        vm.setForwarding(false)
        vm.setStuckAfter(StuckAfter.H24)
        vm.setRetention(Retention.FOREVER)
        vm.setHideText(true)
        vm.setDynamicColor(false)
        vm.setNotify(NotifyKind.STUCK, false)
        val p = vm.await { !it.forwarding && it.stuckAfter == StuckAfter.H24 && it.retention == Retention.FOREVER && it.hideText && !it.dynamicColor && it.notify[NotifyKind.STUCK] == false }
        assertEquals(true, p.notify[NotifyKind.RECOVERED])
    }

    @Test fun changingSweepIntervalSavesThenNotifiesTheScheduler() {
        val vm = vm()
        vm.setSweepInterval(SweepInterval.M30)
        // The callback only runs after the save, so once it has fired the stored value must already be M30.
        // Reading the repository, not vm.prefs, keeps the test off the eagerly shared StateFlow, whose
        // collector runs on Dispatchers.Unconfined and sometimes missed the update under Robolectric.
        waitUntil { sweeps.isNotEmpty() }
        assertEquals(listOf(SweepInterval.M30), sweeps)
        assertEquals(SweepInterval.M30, runBlocking { withTimeout(5_000) { settings.sweepInterval.first() } })
    }

    // The event is buffered (conflated channel), so it is not lost if it is emitted before the
    // collector starts; the saved value is read afterwards, on the test thread.
    @Test fun changingLanguageSavesThenEmitsLanguageChanged() {
        val vm = vm()
        vm.setLanguage(Language.ES)
        runBlocking { withTimeout(10_000) { vm.languageChanged.first() } }
        assertEquals(Language.ES, runBlocking { withTimeout(5_000) { settings.language.first() } })
    }

    @Test fun everyLanguageChangeEmitsAnEvent() {
        val vm = vm()
        vm.setLanguage(Language.ES)
        runBlocking { withTimeout(10_000) { vm.languageChanged.first() } }
        vm.setLanguage(Language.EN)
        runBlocking { withTimeout(10_000) { vm.languageChanged.first() } }
        assertEquals(Language.EN, runBlocking { withTimeout(5_000) { settings.language.first() } })
    }
}
