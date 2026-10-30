package dev.smsforwarder.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import dev.smsforwarder.core.Clock
import dev.smsforwarder.core.ConnectionTestResult
import dev.smsforwarder.data.AppDatabase
import dev.smsforwarder.data.Credentials
import dev.smsforwarder.data.SecretStore
import dev.smsforwarder.data.SettingsRepository
import dev.smsforwarder.data.SmsRepository
import dev.smsforwarder.ui.settings.SettingsScreen
import dev.smsforwarder.ui.settings.SettingsViewModel
import dev.smsforwarder.ui.theme.SmsForwarderTheme
import dev.smsforwarder.ui.theme.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class SettingsScreenTest {
    @get:Rule val compose = createComposeRule()

    private class NoSecrets : SecretStore {
        override suspend fun get(): Credentials? = null
        override suspend fun set(c: Credentials) = Unit
    }

    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsRepository
    private val saved = mutableListOf<Credentials>()

    @Before fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val sms = SmsRepository(db, Clock { 1_800_000_000_000L })
        val file = File(context.cacheDir, "settings-ui-${UUID.randomUUID()}.preferences_pb")
        settings = SettingsRepository(context, NoSecrets(), sms, scheduler = {}, storeFile = file)
    }

    @After fun tearDown() {
        db.close()
    }

    @Test fun settingsNeverShowsTokenValue() {
        // Unconfined scope + lambdas without real I/O: save() finishes before the next assertion.
        val vm = SettingsViewModel(
            settings = settings,
            testConnection = { ConnectionTestResult.OK },
            loadSaved = { null },
            saveCredentials = { saved += it },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        compose.setContent {
            SmsForwarderTheme(ThemeMode.SYSTEM) {
                val form by vm.form.collectAsState()
                SettingsScreen(
                    form = form,
                    senders = emptyList(),
                    themeMode = ThemeMode.SYSTEM,
                    onUrlChange = vm::setUrl,
                    onTokenChange = vm::setToken,
                    onSave = vm::save,
                    onTest = vm::test,
                    onAddSender = {},
                    onRemoveSender = {},
                    onThemeChange = {},
                    onOpenRecovery = {},
                    onBack = {},
                )
            }
        }

        compose.onNodeWithTag("field_url").performTextInput("https://example.com/ingest")
        compose.onNodeWithTag("field_token").performTextInput("test-token")
        compose.onNodeWithText("Guardar credenciales").performClick()
        compose.waitForIdle()

        assertEquals("test-token", saved.single().token)
        compose.onAllNodes(hasText("test-token", substring = true), useUnmergedTree = true)
            .assertCountEquals(0)
    }

    private fun vmFor(tested: MutableList<Credentials>, savedCreds: Credentials?) = SettingsViewModel(
        settings = settings,
        testConnection = { tested += it; ConnectionTestResult.OK },
        loadSaved = { savedCreds },
        saveCredentials = {},
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

    @Test fun settingsTestWithOnlyOneFieldTypedDoesNotUseSavedCredentials() {
        val tested = mutableListOf<Credentials>()
        val vm = vmFor(tested, Credentials("https://saved.example.com/ingest", "saved-token"))

        vm.setUrl("https://example.com/ingest")
        vm.test()
        assertEquals("Escribe la URL y el token para probarlos", vm.form.value.message)

        vm.setUrl("")
        vm.setToken("typed-token")
        vm.test()
        assertEquals("Escribe la URL y el token para probarlos", vm.form.value.message)

        assertTrue(tested.isEmpty())
    }

    @Test fun settingsTestWithEmptyFieldsUsesSavedCredentials() {
        val tested = mutableListOf<Credentials>()
        val saved = Credentials("https://saved.example.com/ingest", "saved-token")
        val vm = vmFor(tested, saved)

        vm.test()

        assertEquals(listOf(saved), tested)
    }
}
