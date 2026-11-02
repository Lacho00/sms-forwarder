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
    private val sweeps = mutableListOf<SweepInterval>()
    private var languageCalls = 0
    private val events = mutableListOf<String>()

    @Before fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val sms = SmsRepository(db, Clock { 1_800_000_000_000L })
        val file = File(context.cacheDir, "settings-vm-${UUID.randomUUID()}.preferences_pb")
        settings = SettingsRepository(context, NoSecrets(), sms, scheduler = {}, storeFile = file)
    }

    @After fun tearDown() {
        db.close()
    }

    private fun vm() = SettingsViewModel(
        settings = settings,
        testConnection = { ConnectionTestResult.OK },
        loadSaved = { null },
        saveCredentials = {},
        scope = CoroutineScope(Dispatchers.Unconfined),
        onSweepIntervalChanged = { sweeps += it },
        onLanguageChanged = { languageCalls++ },
    )

    private fun SettingsViewModel.await(cond: (SettingsPrefs) -> Boolean): SettingsPrefs =
        runBlocking { withTimeout(5_000) { prefs.first(cond) } }

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
        vm.await { it.sweepInterval == SweepInterval.M30 }
        assertEquals(listOf(SweepInterval.M30), sweeps)
    }

    @Test fun changingLanguageSavesThenAsksForRecreate() {
        val vm = SettingsViewModel(
            settings = settings,
            testConnection = { ConnectionTestResult.OK },
            loadSaved = { null },
            saveCredentials = {},
            scope = CoroutineScope(Dispatchers.Unconfined),
            onLanguageChanged = { events += "recreate:" + runBlocking { settings.language.first() } },
        )
        vm.setLanguage(Language.ES)
        vm.await { it.language == Language.ES }
        assertEquals(listOf("recreate:" + Language.ES), events)
    }
}
