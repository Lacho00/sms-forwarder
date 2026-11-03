package dev.smsforwarder.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.smsforwarder.core.StatusFilter
import org.junit.Assert.assertEquals
import dev.smsforwarder.R
import dev.smsforwarder.core.StatusKind
import dev.smsforwarder.core.StatusLabel
import dev.smsforwarder.health.HealthIssue
import dev.smsforwarder.core.DeliveryStatus
import dev.smsforwarder.ui.main.DeliveryChipUi
import dev.smsforwarder.ui.main.MainScreen
import dev.smsforwarder.ui.main.MainUiState
import dev.smsforwarder.ui.main.RowUi
import dev.smsforwarder.ui.theme.SmsForwarderTheme
import dev.smsforwarder.ui.theme.ThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class MainScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun str(id: Int, vararg args: Any): String =
        RuntimeEnvironment.getApplication().getString(id, *args)

    private fun show(state: MainUiState) {
        compose.setContent {
            SmsForwarderTheme(ThemeMode.SYSTEM) {
                MainScreen(
                    state = state,
                    onOpenIssue = {},
                    onSweepNow = {},
                    onOpen = {},
                )
            }
        }
    }

    private fun state(
        issues: Set<HealthIssue> = emptySet(),
        rows: List<RowUi> = emptyList(),
    ) = MainUiState(issues = issues, lastSweepAt = null, queued = 2, errors = 1, rows = rows)

    @Test fun showsWarningCardWithButtonForAutostartOff() {
        show(state(issues = setOf(HealthIssue.AUTOSTART_OFF)))
        compose.onNodeWithText(str(R.string.issue_autostart_off)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.main_open_settings)).assertIsDisplayed()
    }

    @Test fun showsPausedLineOnlyWhenPaused() {
        show(state().copy(paused = true))
        compose.onNodeWithText(str(R.string.forwarding_paused)).assertIsDisplayed()
    }

    @Test fun showsStatusLineAndSweepButton() {
        show(state())
        compose.onNodeWithText(str(R.string.main_status_line, str(R.string.ago_never), 2, 1)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.main_sweep_now)).assertIsDisplayed()
    }

    @Test fun rowShowsLabel() {
        val row = RowUi(
            id = 1,
            sender = "OPERADOR",
            subId = 1,
            receivedAt = 1_800_000_000_000L,
            preview = "Se ha comprado un plan de datos",
            label = StatusLabel(StatusKind.RECOVERED, 1, 200),
        )
        show(state(rows = listOf(row)))
        compose.onNodeWithText(str(R.string.status_recovered_code, 200)).assertIsDisplayed()
    }

    private val previewRow = RowUi(
        id = 1,
        sender = "SENDER",
        subId = null,
        receivedAt = 1_800_000_000_000L,
        preview = "Code 1234",
        label = StatusLabel(StatusKind.SENT, 1, 200),
    )

    @Test fun rowShowsPreviewWhenTextIsVisible() {
        show(state(rows = listOf(previewRow)))
        compose.onNodeWithText("Code 1234").assertIsDisplayed()
    }

    @Test fun hideTextKeepsSenderAndHidesBody() {
        show(state(rows = listOf(previewRow)).copy(hideText = true))
        compose.onNodeWithText("SENDER").assertIsDisplayed()
        compose.onNodeWithText("Code 1234").assertDoesNotExist()
    }

    @Test fun filterChipsAreShownAndReportTheChoice() {
        var picked: StatusFilter? = null
        compose.setContent {
            SmsForwarderTheme(ThemeMode.SYSTEM) {
                MainScreen(
                    state = state(),
                    onOpenIssue = {},
                    onSweepNow = {},
                    onOpen = {},
                    onFilterChange = { picked = it },
                )
            }
        }
        compose.onNodeWithText(str(R.string.main_filter_all)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.main_filter_queued)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.main_filter_error)).performClick()
        compose.runOnIdle { assertEquals(StatusFilter.ERROR, picked) }
    }

    @Test fun emptyResultOfASearchSaysNoMatch() {
        show(state().copy(query = "zzz"))
        compose.onNodeWithText(str(R.string.main_no_results)).assertIsDisplayed()
    }

    private fun chipRow(vararg chips: DeliveryChipUi) = RowUi(
        id = 1, sender = "AAA", subId = null, receivedAt = 1_800_000_000_000L, preview = "TEST",
        label = StatusLabel(StatusKind.QUEUED, 1, null), deliveries = chips.toList(),
    )

    @Test fun rowRendersDeliveryLine() {
        val row = chipRow(
            DeliveryChipUi("Main", DeliveryStatus.SENT, 204, false),
            DeliveryChipUi("T2", DeliveryStatus.QUEUED, null, false),
        )
        show(state(rows = listOf(row)))
        compose.onNodeWithText(str(R.string.delivery_sent, "Main", 204) + " · " + str(R.string.delivery_queued, "T2"))
            .assertExists()
    }

    @Test fun deliveryLineCoversErrorConfigAndCanceled() {
        val row = chipRow(
            DeliveryChipUi("A", DeliveryStatus.ERROR, 403, false),
            DeliveryChipUi("B", DeliveryStatus.ERROR, null, true),
            DeliveryChipUi("C", DeliveryStatus.CANCELED, null, false),
        )
        show(state(rows = listOf(row)))
        compose.onNodeWithText(
            listOf(str(R.string.delivery_error, "A", 403), str(R.string.delivery_config, "B"), str(R.string.delivery_canceled, "C"))
                .joinToString(" · "),
        ).assertExists()
    }

    @Test fun destinationChipsFilter() {
        var picked: Long? = -1L
        compose.setContent {
            SmsForwarderTheme(ThemeMode.SYSTEM) {
                MainScreen(
                    state = state().copy(destinations = listOf(1L to "Main", 2L to "T2")),
                    onOpenIssue = {}, onSweepNow = {}, onOpen = {},
                    onDestinationFilterChange = { picked = it },
                )
            }
        }
        compose.onNodeWithText("T2").performClick()
        compose.runOnIdle { assertEquals(2L, picked) }
    }
}
