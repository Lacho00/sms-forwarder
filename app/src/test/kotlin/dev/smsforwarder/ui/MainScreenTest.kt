package dev.smsforwarder.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.smsforwarder.health.HealthIssue
import dev.smsforwarder.ui.main.MainScreen
import dev.smsforwarder.ui.main.MainUiState
import dev.smsforwarder.ui.main.RowUi
import dev.smsforwarder.ui.theme.SmsForwarderTheme
import dev.smsforwarder.ui.theme.ThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MainScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun show(state: MainUiState) {
        compose.setContent {
            SmsForwarderTheme(ThemeMode.SYSTEM) {
                MainScreen(
                    state = state,
                    onOpenIssue = {},
                    onSweepNow = {},
                    onOpen = {},
                    onSettings = {},
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
        compose.onNodeWithText("Inicio automático desactivado").assertIsDisplayed()
        compose.onNodeWithText("Abrir ajustes").assertIsDisplayed()
    }

    @Test fun showsStatusLineAndSweepButton() {
        show(state())
        compose.onNodeWithText("Cola: 2", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Barrer bandeja ahora").assertIsDisplayed()
    }

    @Test fun rowShowsLabel() {
        val row = RowUi(
            id = 1,
            sender = "OPERADOR",
            subId = 1,
            receivedAt = 1_800_000_000_000L,
            preview = "Se ha comprado un plan de datos",
            label = "Recuperado · 200",
        )
        show(state(rows = listOf(row)))
        compose.onNodeWithText("Recuperado · 200").assertIsDisplayed()
    }
}
