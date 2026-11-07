package dev.smsforwarder.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.smsforwarder.R
import dev.smsforwarder.ui.components.EMPTY_STATE_ACTION_TAG
import dev.smsforwarder.core.BodyMode
import dev.smsforwarder.core.SenderMode
import dev.smsforwarder.core.SimMatch
import dev.smsforwarder.ui.rules.RuleRowUi
import dev.smsforwarder.ui.rules.RulesScreen
import dev.smsforwarder.ui.theme.SmsForwarderTheme
import dev.smsforwarder.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class RulesScreenTest {
    @get:Rule val compose = createComposeRule()

    private val app get() = RuntimeEnvironment.getApplication()
    private val opened = mutableListOf<Long>()
    private val toggled = mutableListOf<Pair<Long, Boolean>>()
    private var added = 0

    private fun row(
        id: Long = 1,
        name: String = "Alpha",
        senderMode: SenderMode = SenderMode.EXACT,
        bodyMode: BodyMode? = null,
        bodyPattern: String? = null,
        sim: SimMatch = SimMatch.ANY,
        destinations: List<String> = listOf("Dest"),
    ) = RuleRowUi(id, name, true, senderMode, "AAA", bodyMode, bodyPattern, sim, destinations)

    private fun show(rows: List<RuleRowUi>) {
        compose.setContent {
            SmsForwarderTheme(ThemeMode.SYSTEM) {
                RulesScreen(
                    rows = rows,
                    onOpen = { opened += it },
                    onAdd = { added++ },
                    onToggle = { id, on -> toggled += id to on },
                    padding = PaddingValues(),
                )
            }
        }
    }

    @Test fun noDestinationsIsAnErrorPill() {
        show(listOf(row(destinations = emptyList())))
        compose.onNodeWithText(app.getString(R.string.rule_no_destinations)).assertIsDisplayed()
    }

    @Test fun ruleWithDestinationsDoesNotWarn() {
        show(listOf(row()))
        compose.onNodeWithText(app.getString(R.string.rule_no_destinations)).assertDoesNotExist()
    }

    @Test fun emptyStateShown() {
        show(emptyList())
        compose.onNodeWithText(app.getString(R.string.rules_empty)).assertExists()
        compose.onNodeWithText(app.getString(R.string.rules_empty_body)).assertExists()
    }

    @Test fun emptyStateAddOpensEditor() {
        show(emptyList())
        compose.onNodeWithTag(EMPTY_STATE_ACTION_TAG).performClick()
        assertEquals(1, added)
    }

    @Test fun fabHasAccessibleNameInMergedTree() {
        show(emptyList())
        compose.onNode(hasClickAction() and hasText(app.getString(R.string.action_add)) and !hasTestTag(EMPTY_STATE_ACTION_TAG))
            .assertExists().performClick()
        assertEquals(1, added)
    }

    @Test fun longNameAndSenderStayOnScreen() {
        val name = "N".repeat(60)
        show(listOf(RuleRowUi(1, name, true, SenderMode.EXACT, "S".repeat(60), null, null, SimMatch.ANY, listOf("Dest"))))
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val sw = compose.onNode(hasContentDescription(name) and isToggleable(), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(sw.right <= root.right && sw.left >= root.left)
    }

    @Test fun summaryShowsSenderBodyFilterSimAndDestinations() {
        val sep = " · "
        show(
            listOf(
                row(1, "One", bodyMode = BodyMode.NOT_CONTAINS, bodyPattern = "login", sim = SimMatch.SIM2, destinations = listOf("D1", "D2")),
                row(2, "Two", senderMode = SenderMode.CONTAINS, bodyMode = BodyMode.CONTAINS, bodyPattern = "pay"),
                row(3, "Three", senderMode = SenderMode.REGEX, bodyMode = BodyMode.REGEX, bodyPattern = "x+"),
            ),
        )
        val one = listOf(
            app.getString(R.string.rule_row_sender_exact, "AAA"),
            app.getString(R.string.rule_row_body_not_contains, "login"),
            app.getString(R.string.rule_row_sim, 2),
            "D1, D2",
        ).joinToString(sep)
        val two = listOf(
            app.getString(R.string.rule_row_sender_contains, "AAA"),
            app.getString(R.string.rule_row_body_contains, "pay"),
            "Dest",
        ).joinToString(sep)
        val three = listOf(
            app.getString(R.string.rule_row_sender_regex, "AAA"),
            app.getString(R.string.rule_row_body_regex, "x+"),
            "Dest",
        ).joinToString(sep)
        compose.onNodeWithText(one).assertExists()
        compose.onNodeWithText(two).assertExists()
        compose.onNodeWithText(three).assertExists()
    }

    @Test fun tapAddRowAndSwitchReportTheirCallbacks() {
        show(listOf(row()))
        compose.onNode(hasClickAction() and hasText(app.getString(R.string.action_add))).performClick()
        compose.onNodeWithText("Alpha").performClick()
        assertEquals(1, added)
        assertEquals(listOf(1L), opened)
        val sw = compose.onNode(hasContentDescription("Alpha") and isToggleable(), useUnmergedTree = true)
        sw.assertExists()
        sw.performClick()
        assertEquals(listOf(1L to false), toggled)
    }
}
