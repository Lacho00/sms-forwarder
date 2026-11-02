package dev.smsforwarder.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import dev.smsforwarder.R
import dev.smsforwarder.core.Clock
import dev.smsforwarder.core.ConnectionTestResult
import dev.smsforwarder.data.AppDatabase
import dev.smsforwarder.data.Credentials
import dev.smsforwarder.data.SecretStore
import dev.smsforwarder.data.SettingsRepository
import dev.smsforwarder.data.SmsRepository
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import dev.smsforwarder.core.Language
import dev.smsforwarder.core.NotifyKind
import dev.smsforwarder.core.Retention
import dev.smsforwarder.core.StuckAfter
import dev.smsforwarder.core.SweepInterval
import dev.smsforwarder.health.HealthIssue
import dev.smsforwarder.ui.settings.SettingsActions
import dev.smsforwarder.ui.settings.SettingsPrefs
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
    private val actions = SettingsActions()

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
                    prefs = SettingsPrefs(),
                    actions = actions,
                    onUrlChange = vm::setUrl,
                    onTokenChange = vm::setToken,
                    onSave = vm::save,
                    onTest = vm::test,
                    onAddSender = {},
                    onRemoveSender = {},
                    onOpenRecovery = {},
                    onOpenSystemIssue = {},
                )
            }
        }

        compose.onNodeWithTag("field_url").performTextInput("https://example.com/ingest")
        compose.onNodeWithTag("field_token").performTextInput("test-token")
        compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.settings_save_credentials)).performClick()
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
        assertEquals(R.string.settings_msg_need_both, vm.form.value.message)

        vm.setUrl("")
        vm.setToken("typed-token")
        vm.test()
        assertEquals(R.string.settings_msg_need_both, vm.form.value.message)

        assertTrue(tested.isEmpty())
    }

    @Test fun settingsTestWithEmptyFieldsUsesSavedCredentials() {
        val tested = mutableListOf<Credentials>()
        val saved = Credentials("https://saved.example.com/ingest", "saved-token")
        val vm = vmFor(tested, saved)

        vm.test()

        assertEquals(listOf(saved), tested)
    }

    private fun str(id: Int, vararg args: Any): String = RuntimeEnvironment.getApplication().getString(id, *args)

    private fun showGroups(prefs: SettingsPrefs = SettingsPrefs(), onSystem: (HealthIssue) -> Unit = {}, onRecovery: () -> Unit = {}) {
        compose.setContent {
            SmsForwarderTheme(ThemeMode.SYSTEM) {
                SettingsScreen(
                    form = dev.smsforwarder.ui.settings.SettingsForm(),
                    senders = emptyList(),
                    prefs = prefs,
                    actions = actions,
                    onUrlChange = {},
                    onTokenChange = {},
                    onSave = {},
                    onTest = {},
                    onAddSender = {},
                    onRemoveSender = {},
                    onOpenRecovery = onRecovery,
                    onOpenSystemIssue = onSystem,
                )
            }
        }
    }

    @Test fun showsSevenGroupHeadersInSpecOrder() {
        showGroups()
        val ids = listOf(
            R.string.settings_worker_connection,
            R.string.settings_senders,
            R.string.settings_group_forwarding,
            R.string.settings_group_history,
            R.string.settings_group_notifications,
            R.string.settings_group_appearance,
            R.string.settings_group_maintenance,
        )
        val ys = ids.map { compose.onNodeWithText(str(it)).fetchSemanticsNode().positionInRoot.y }
        assertEquals(ys.sorted(), ys)
        assertEquals(7, ys.toSet().size)
    }

    @Test fun maintenanceButtonsCallTheirCallbacks() {
        var recovery = 0
        val issues = mutableListOf<HealthIssue>()
        showGroups(onSystem = { issues += it }, onRecovery = { recovery++ })

        compose.onNodeWithText(str(R.string.recover_sms)).performScrollTo().performClick()
        compose.onNodeWithText(str(R.string.settings_permissions_button)).performScrollTo().performClick()

        assertEquals(1, recovery)
        assertEquals(listOf(HealthIssue.SMS_PERMISSION), issues)
    }

    @Test fun turningOffForwardingReportsFalse() {
        val seen = mutableListOf<Boolean>()
        actions.setForwarding = { seen += it }
        showGroups()
        compose.onNodeWithText(str(R.string.settings_forward_sms)).performScrollTo().performClick()
        assertEquals(listOf(false), seen)
    }

    @Test fun choosingThirtyMinutesReportsM30() {
        val seen = mutableListOf<SweepInterval>()
        actions.setSweepInterval = { seen += it }
        showGroups()
        compose.onNodeWithText(str(R.string.settings_sweep_option, 30)).performScrollTo().performClick()
        assertEquals(listOf(SweepInterval.M30), seen)
    }

    @Test fun choosingStuckRetentionAndLanguageReportTheirValues() {
        val stuck = mutableListOf<StuckAfter>()
        val retention = mutableListOf<Retention>()
        val language = mutableListOf<Language>()
        actions.setStuckAfter = { stuck += it }
        actions.setRetention = { retention += it }
        actions.setLanguage = { language += it }
        showGroups()
        compose.onNodeWithText(str(R.string.settings_stuck_option, 24)).performScrollTo().performClick()
        compose.onNodeWithText(str(R.string.settings_retention_forever)).performScrollTo().performClick()
        compose.onNodeWithText(str(R.string.language_es)).performScrollTo().performClick()
        assertEquals(listOf(StuckAfter.H24), stuck)
        assertEquals(listOf(Retention.FOREVER), retention)
        assertEquals(listOf(Language.ES), language)
    }

    @Test fun togglesReportTheirKind() {
        val notify = mutableListOf<Pair<NotifyKind, Boolean>>()
        val hide = mutableListOf<Boolean>()
        val dynamic = mutableListOf<Boolean>()
        actions.setNotify = { k, v -> notify += k to v }
        actions.setHideText = { hide += it }
        actions.setDynamicColor = { dynamic += it }
        showGroups()
        compose.onNodeWithText(str(R.string.settings_notify_stuck)).performScrollTo().performClick()
        compose.onNodeWithText(str(R.string.settings_hide_text)).performScrollTo().performClick()
        compose.onNodeWithText(str(R.string.settings_dynamic_color)).performScrollTo().performClick()
        assertEquals(listOf(NotifyKind.STUCK to false), notify)
        assertEquals(listOf(true), hide)
        assertEquals(listOf(false), dynamic)
    }

    @Test fun tokenRejectedAlertIsNotASetting() {
        showGroups()
        // Forwarding, hide text, three notification kinds and dynamic color: nothing for the rejected-token alert.
        val switches = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
        compose.onAllNodes(switches).assertCountEquals(6)
        compose.onAllNodesWithText(str(R.string.notif_token_rejected_title)).assertCountEquals(0)
    }
}
