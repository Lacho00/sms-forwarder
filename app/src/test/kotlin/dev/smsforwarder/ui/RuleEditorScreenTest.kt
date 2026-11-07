package dev.smsforwarder.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import dev.smsforwarder.R
import dev.smsforwarder.core.BodyMode
import dev.smsforwarder.core.Clock
import dev.smsforwarder.core.DestinationPresets
import dev.smsforwarder.core.RuleError
import dev.smsforwarder.core.SenderMode
import dev.smsforwarder.data.AppDatabase
import dev.smsforwarder.data.TestRepos
import dev.smsforwarder.ui.rules.NEW_RULE_DRAFT
import dev.smsforwarder.ui.rules.RuleEditorScreen
import dev.smsforwarder.ui.rules.RuleEditorState
import dev.smsforwarder.ui.rules.RuleEditorViewModel
import dev.smsforwarder.ui.theme.SmsForwarderTheme
import dev.smsforwarder.ui.theme.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class RuleEditorScreenTest {
    @get:Rule val compose = createComposeRule()

    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var db: AppDatabase
    private lateinit var repos: TestRepos

    // Cancelled before the database closes, so no collector outlives it.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
        repos = TestRepos(db, Clock { 1_800_000_000_000L })
    }

    @After fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun vm(id: Long? = null) =
        RuleEditorViewModel(id, repos.rules, repos.destinations, scope)

    private fun show(vm: RuleEditorViewModel, override: RuleEditorState? = null) {
        compose.setContent {
            SmsForwarderTheme(ThemeMode.SYSTEM) {
                val s by vm.state.collectAsState()
                RuleEditorScreen(state = override ?: s, vm = vm, onBack = {})
            }
        }
    }

    @Test fun regexErrorShown() {
        val state = RuleEditorState(
            loaded = true,
            draft = NEW_RULE_DRAFT.copy(senderMode = SenderMode.REGEX, senderPattern = "("),
            errors = setOf(RuleError.SENDER_REGEX_INVALID),
        )
        show(vm(), state)
        compose.onNodeWithText(app.getString(R.string.rule_error_regex)).assertExists()
    }

    @Test fun bodyFieldHiddenForAnyAndOffersTheThreeModes() {
        val vm = vm()
        show(vm, null)
        compose.onNodeWithText(app.getString(R.string.rule_body_help_contains)).assertDoesNotExist()
        chooseBodyMode(R.string.rule_body_any, R.string.rule_body_not_contains)
        compose.onNodeWithText(app.getString(R.string.rule_body_help_not_contains)).assertExists()
        assertEquals(BodyMode.NOT_CONTAINS, vm.state.value.draft.bodyMode)
    }

    @Test fun senderHelpFollowsTheMode() {
        show(vm())
        compose.onNodeWithText(app.getString(R.string.rule_sender_help_exact)).assertExists()
        compose.onAllNodesWithText(app.getString(R.string.rule_sender_regex))[0].performScrollTo().performClick()
        compose.onNodeWithText(app.getString(R.string.rule_sender_help_regex)).assertExists()
    }

    @Test fun pausedDestinationIsMarkedAndTickable() {
        val id = runBlocking {
            repos.destinations.create(DestinationPresets.custom("Alpha").copy(url = "https://h.example/a", body = "{}"))
        }
        runBlocking { repos.destinations.setEnabled(id, false) }
        val vm = vm()
        val end = System.currentTimeMillis() + 5_000
        while (vm.state.value.destinations.isEmpty() && System.currentTimeMillis() < end) Thread.sleep(10)
        show(vm)
        compose.waitForIdle()
        val label = "Alpha · " + app.getString(R.string.destination_paused)
        compose.onNodeWithText(label).performScrollTo().performClick()
        assertEquals(setOf(id), vm.state.value.draft.destinationIds)
    }

    @Test fun noDestinationsHintShown() {
        show(vm())
        compose.onNodeWithText(app.getString(R.string.rule_destinations_none)).assertExists()
    }

    @Test fun deleteOnlyOnExistingRules() {
        show(vm(), RuleEditorState(loaded = true, isNew = true))
        compose.onAllNodesWithText(app.getString(R.string.rule_delete)).assertCountEquals(0)
    }

    @Test fun deleteHiddenUntilLoadedAndWhenLoadFailed() {
        show(vm(), RuleEditorState(loaded = false, isNew = false))
        compose.onAllNodesWithText(app.getString(R.string.rule_delete)).assertCountEquals(0)
        compose.onAllNodesWithContentDescription(app.getString(R.string.rule_delete)).assertCountEquals(0)
    }

    @Test fun deleteHiddenWhenLoadFailed() {
        show(vm(), RuleEditorState(loaded = true, isNew = false, loadFailed = true))
        compose.onAllNodesWithContentDescription(app.getString(R.string.rule_delete)).assertCountEquals(0)
    }

    @Test fun deleteDialogShown() {
        show(vm(), RuleEditorState(loaded = true, isNew = false, deleteAsked = true))
        compose.onNodeWithText(app.getString(R.string.rule_delete_title)).assertExists()
        compose.onNodeWithContentDescription(app.getString(R.string.rule_delete)).assertExists()
    }

    /** Opens the body-mode dropdown (showing [current]) and picks [target], all in the merged tree. */
    private fun chooseBodyMode(current: Int, target: Int) {
        compose.onNode(hasClickAction() and hasText(app.getString(R.string.rule_body)) and hasText(app.getString(current)))
            .performScrollTo().performClick()
        compose.onNode(hasClickAction() and hasText(app.getString(target)) and !hasText(app.getString(R.string.rule_body)) and
                !SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .performClick()
        compose.waitForIdle()
    }

    @Test fun bodyModeDropdownChoosesEachMode() {
        val vm = vm()
        show(vm)
        var current = R.string.rule_body_any
        listOf(
            BodyMode.CONTAINS to R.string.rule_body_contains,
            BodyMode.NOT_CONTAINS to R.string.rule_body_not_contains,
            BodyMode.REGEX to R.string.rule_body_regex,
            null to R.string.rule_body_any,
        ).forEach { (mode, label) ->
            chooseBodyMode(current, label)
            assertEquals(mode, vm.state.value.draft.bodyMode)
            current = label
        }
    }

    @Test fun senderModeIsSegmented() {
        val vm = vm()
        show(vm)
        val exact = hasClickAction() and hasText(app.getString(R.string.rule_sender_exact))
        compose.onNode(exact and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).assertExists()
        val contains = hasClickAction() and hasText(app.getString(R.string.rule_sender_contains))
        compose.onNode(contains and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).performScrollTo().performClick()
        assertEquals(SenderMode.CONTAINS, vm.state.value.draft.senderMode)
    }

    @Test fun saveIsPinnedAtBottom() {
        show(vm())
        // Never scrolled to: the bar sits outside the scrolling form.
        compose.onNode(hasClickAction() and hasText(app.getString(R.string.rule_save))).assertIsDisplayed()
    }
}
