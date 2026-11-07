package dev.smsforwarder.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
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
import dev.smsforwarder.data.TestRepos
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
import dev.smsforwarder.ui.settings.TOKEN_NOTE_ICON_TAG
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
        settings = TestRepos(db).let { SettingsRepository(context, it.destinations, it.rules, sms, scheduler = {}, storeFile = file) }
    }

    @After fun tearDown() {
        db.close()
    }

    @Test fun noSendersGroup() {
        showGroups()
        compose.onAllNodesWithText("Senders").assertCountEquals(0)
        compose.onAllNodesWithText("Remitentes").assertCountEquals(0)
    }

    @Test fun noConnectionGroup() {
        showGroups()
        compose.onAllNodesWithText(str(R.string.field_worker_url)).assertCountEquals(0)
        compose.onAllNodesWithText(str(R.string.field_token)).assertCountEquals(0)
    }

    private fun str(id: Int, vararg args: Any): String = RuntimeEnvironment.getApplication().getString(id, *args)

    private fun showGroups(prefs: SettingsPrefs = SettingsPrefs(), onSystem: (HealthIssue) -> Unit = {}, onRecovery: () -> Unit = {}) {
        compose.setContent {
            SmsForwarderTheme(ThemeMode.SYSTEM) {
                SettingsScreen(
                    prefs = prefs,
                    actions = actions,
                    onOpenRecovery = onRecovery,
                    onOpenSystemIssue = onSystem,
                )
            }
        }
    }

    @Test fun showsFiveGroupHeadersInSpecOrder() {
        showGroups()
        val ids = listOf(
            R.string.settings_group_forwarding,
            R.string.settings_group_history,
            R.string.settings_group_notifications,
            R.string.settings_group_appearance,
            R.string.settings_group_maintenance,
        )
        val ys = ids.map { compose.onNodeWithText(str(it)).fetchSemanticsNode().positionInRoot.y }
        assertEquals(ys.sorted(), ys)
        assertEquals(5, ys.toSet().size)
    }

    private fun row(title: String) = hasClickAction() and hasText(title)

    @Test fun maintenanceRowsOpenTheirScreens() {
        var recovery = 0
        val issues = mutableListOf<HealthIssue>()
        showGroups(onSystem = { issues += it }, onRecovery = { recovery++ })

        compose.onNode(row(str(R.string.recover_sms))).performScrollTo().performClick()
        compose.onNode(row(str(R.string.settings_permissions_button))).performScrollTo().performClick()

        assertEquals(1, recovery)
        assertEquals(listOf(HealthIssue.SMS_PERMISSION), issues)
    }

    @Test fun clickableRowsAreButtons() {
        showGroups()
        val button = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
        compose.onNode(row(str(R.string.recover_sms)) and button).assertExists()
        compose.onNode(row(str(R.string.settings_sweep_interval)) and button).assertExists()
    }

    @Test fun sectionTitlesAreHeadings() {
        showGroups()
        listOf(
            R.string.settings_group_forwarding,
            R.string.settings_group_history,
            R.string.settings_group_notifications,
            R.string.settings_group_appearance,
            R.string.settings_group_maintenance,
        ).forEach {
            compose.onNode(hasText(str(it)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertExists()
        }
    }

    /** The row shows [current] as its value; tapping it opens a dialog with every option; picking [pick] closes it. */
    private fun chooseFromRow(title: String, current: String, options: List<String>, pick: String) {
        compose.onNode(row(title) and hasText(current)).performScrollTo().assertExists()
        compose.onNode(row(title)).performClick()
        val inDialog = hasAnyAncestor(isDialog())
        options.forEach { compose.onNode(hasText(it) and inDialog).assertExists() }
        compose.onNode(hasText(pick) and inDialog).performClick()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
    }

    @Test fun sweepIntervalRowShowsCurrentValueAndOpensDialog() {
        val seen = mutableListOf<SweepInterval>()
        actions.setSweepInterval = { seen += it }
        showGroups(SettingsPrefs(sweepInterval = SweepInterval.M15))
        chooseFromRow(
            str(R.string.settings_sweep_interval),
            str(R.string.settings_sweep_option, 15),
            listOf(15, 30, 60).map { str(R.string.settings_sweep_option, it) },
            str(R.string.settings_sweep_option, 30),
        )
        assertEquals(listOf(SweepInterval.M30), seen)
    }

    @Test fun stuckAfterRowShowsCurrentValueAndOpensDialog() {
        val seen = mutableListOf<StuckAfter>()
        actions.setStuckAfter = { seen += it }
        showGroups(SettingsPrefs(stuckAfter = StuckAfter.H1))
        chooseFromRow(
            str(R.string.settings_stuck_after),
            str(R.string.settings_stuck_option, 1),
            listOf(1, 6, 24).map { str(R.string.settings_stuck_option, it) },
            str(R.string.settings_stuck_option, 24),
        )
        assertEquals(listOf(StuckAfter.H24), seen)
    }

    @Test fun retentionRowShowsCurrentValueAndOpensDialog() {
        val seen = mutableListOf<Retention>()
        actions.setRetention = { seen += it }
        showGroups(SettingsPrefs(retention = Retention.D30))
        chooseFromRow(
            str(R.string.settings_retention),
            str(R.string.settings_retention_days, 30),
            listOf(str(R.string.settings_retention_days, 30), str(R.string.settings_retention_days, 90), str(R.string.settings_retention_days, 365), str(R.string.settings_retention_forever)),
            str(R.string.settings_retention_forever),
        )
        assertEquals(listOf(Retention.FOREVER), seen)
    }

    @Test fun languageRowShowsCurrentValueAndOpensDialog() {
        val seen = mutableListOf<Language>()
        actions.setLanguage = { seen += it }
        showGroups(SettingsPrefs(language = Language.SYSTEM))
        chooseFromRow(
            str(R.string.settings_language),
            str(R.string.language_system),
            listOf(R.string.language_system, R.string.language_es, R.string.language_en).map { str(it) },
            str(R.string.language_es),
        )
        assertEquals(listOf(Language.ES), seen)
    }

    @Test fun dismissingAChoiceDialogChangesNothing() {
        val seen = mutableListOf<SweepInterval>()
        actions.setSweepInterval = { seen += it }
        showGroups()
        compose.onNode(row(str(R.string.settings_sweep_interval))).performScrollTo().performClick()
        compose.onNode(hasText(str(R.string.detail_delete_cancel)) and hasAnyAncestor(isDialog())).performClick()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        assertTrue(seen.isEmpty())
    }

    @Test fun themeIsSegmented() {
        val seen = mutableListOf<ThemeMode>()
        actions.setTheme = { seen += it }
        showGroups()
        val radio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
        listOf(R.string.theme_system, R.string.theme_light, R.string.theme_dark).forEach {
            compose.onNode(hasClickAction() and hasText(str(it)) and radio).performScrollTo().assertExists()
        }
        compose.onNode(hasClickAction() and hasText(str(R.string.theme_dark))).performClick()
        assertEquals(listOf(ThemeMode.DARK), seen)
    }

    @Test fun tokenAlertWarningHasIcon() {
        showGroups()
        compose.onNodeWithText(str(R.string.settings_notify_token_note)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(TOKEN_NOTE_ICON_TAG, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun turningOffForwardingReportsFalse() {
        val seen = mutableListOf<Boolean>()
        actions.setForwarding = { seen += it }
        showGroups()
        compose.onNodeWithText(str(R.string.settings_forward_sms)).performScrollTo().performClick()
        assertEquals(listOf(false), seen)
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
