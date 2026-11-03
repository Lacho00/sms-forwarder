package dev.smsforwarder.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.smsforwarder.R
import dev.smsforwarder.core.TemplateKind
import dev.smsforwarder.ui.destinations.DestinationRowUi
import dev.smsforwarder.ui.destinations.DestinationsScreen
import dev.smsforwarder.ui.theme.SmsForwarderTheme
import dev.smsforwarder.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
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
        compose.onNodeWithText(json + sep + queued + sep + errors).assertExists()
        compose.onNodeWithText(app.getString(R.string.template_telegram) + sep + app.getString(R.string.destination_paused)).assertExists()
        // Zero counters are omitted.
        compose.onNodeWithText(app.getString(R.string.template_custom)).assertExists()
    }

    @Test fun emptyStateShown() {
        show(emptyList())
        compose.onNodeWithText(app.getString(R.string.destinations_empty)).assertExists()
    }

    @Test fun tapAddAndRowAndSwitchReportTheirCallbacks() {
        show(listOf(DestinationRowUi(1, "Alpha", TemplateKind.CUSTOM, enabled = true, queued = 0, errors = 0)))
        compose.onNodeWithContentDescription(app.getString(R.string.destination_add)).performClick()
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
}
