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
import dev.smsforwarder.core.TemplateKind
import dev.smsforwarder.ui.destinations.DestinationRowUi
import dev.smsforwarder.ui.destinations.DestinationsScreen
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
class DestinationsScreenTest {
    @get:Rule val compose = createComposeRule()

    private val app get() = RuntimeEnvironment.getApplication()
    private val opened = mutableListOf<Long>()
    private val toggled = mutableListOf<Pair<Long, Boolean>>()
    private var added = 0

    private fun show(rows: List<DestinationRowUi>) {
        compose.setContent {
            SmsForwarderTheme(ThemeMode.SYSTEM) {
                DestinationsScreen(
                    rows = rows,
                    onOpen = { opened += it },
                    onAdd = { added++ },
                    onToggle = { id, on -> toggled += id to on },
                    padding = PaddingValues(),
                )
            }
        }
    }

    @Test fun showsRowsAndPausedState() {
        show(
            listOf(
                DestinationRowUi(1, "Alpha", TemplateKind.STANDARD_JSON, enabled = true, queued = 2, errors = 1),
                DestinationRowUi(2, "Beta", TemplateKind.TELEGRAM, enabled = false, queued = 3, errors = 0),
                DestinationRowUi(3, "Gamma", TemplateKind.CUSTOM, enabled = true, queued = 0, errors = 0),
            ),
        )
        val sep = " · "
        val json = app.getString(R.string.template_standard_json)
        val queued = app.resources.getQuantityString(R.plurals.destination_queued_count, 2, 2)
        val errors = app.resources.getQuantityString(R.plurals.destination_error_count, 1, 1)
        compose.onNodeWithText("Alpha").assertExists()
        compose.onNodeWithText(json + sep + queued).assertExists()
        compose.onNodeWithText(errors).assertExists()
        compose.onNodeWithText(app.getString(R.string.template_telegram) + sep + app.getString(R.string.destination_paused)).assertExists()
        // Zero counters are omitted.
        compose.onNodeWithText(app.getString(R.string.template_custom)).assertExists()
    }

    @Test fun emptyStateShown() {
        show(emptyList())
        compose.onNodeWithText(app.getString(R.string.destinations_empty)).assertExists()
        compose.onNodeWithText(app.getString(R.string.destinations_empty_body)).assertExists()
    }

    @Test fun errorCountIsAnErrorPill() {
        show(listOf(DestinationRowUi(1, "Alpha", TemplateKind.CUSTOM, enabled = true, queued = 0, errors = 3)))
        compose.onNodeWithText(app.resources.getQuantityString(R.plurals.destination_error_count, 3, 3)).assertIsDisplayed()
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

    @Test fun fabIsExtendedWithAddText() {
        show(listOf(DestinationRowUi(1, "Alpha", TemplateKind.CUSTOM, enabled = true, queued = 0, errors = 0)))
        compose.onNode(hasClickAction() and hasText(app.getString(R.string.action_add))).performClick()
        assertEquals(1, added)
    }

    @Test fun longNameKeepsSwitchAndPillOnScreen() {
        val name = "N".repeat(60)
        show(listOf(DestinationRowUi(1, name, TemplateKind.CUSTOM, enabled = true, queued = 0, errors = 3)))
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val pill = compose.onNodeWithText(app.resources.getQuantityString(R.plurals.destination_error_count, 3, 3), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val sw = compose.onNode(hasContentDescription(name) and isToggleable(), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(pill.right <= root.right && pill.left >= root.left)
        assertTrue(sw.right <= root.right && sw.left >= pill.right)
    }

    @Test fun tapAddAndRowAndSwitchReportTheirCallbacks() {
        show(listOf(DestinationRowUi(1, "Alpha", TemplateKind.CUSTOM, enabled = true, queued = 0, errors = 0)))
        compose.onNode(hasClickAction() and hasText(app.getString(R.string.action_add))).performClick()
        compose.onNodeWithText("Alpha").performClick()
        assertEquals(1, added)
        assertEquals(listOf(1L), opened)
    }

    @Test fun rowSwitchIsLabelledWithTheName() {
        show(listOf(DestinationRowUi(1, "Alpha", TemplateKind.CUSTOM, enabled = true, queued = 0, errors = 0)))
        val sw = compose.onNode(
            hasContentDescription("Alpha") and isToggleable(),
            useUnmergedTree = true,
        )
        sw.assertExists()
        sw.performClick()
        assertEquals(listOf(1L to false), toggled)
    }

    @Test fun withoutCredentialsLabel() {
        show(
            listOf(
                DestinationRowUi(1, "Alpha", TemplateKind.CUSTOM, enabled = true, queued = 0, errors = 0, configured = false),
                DestinationRowUi(2, "Beta", TemplateKind.CUSTOM, enabled = true, queued = 0, errors = 0),
            ),
        )
        val sep = " · "
        val custom = app.getString(R.string.template_custom)
        compose.onNodeWithText(custom + sep + app.getString(R.string.destination_without_credentials)).assertExists()
        compose.onNodeWithText(custom).assertExists()
    }
}
