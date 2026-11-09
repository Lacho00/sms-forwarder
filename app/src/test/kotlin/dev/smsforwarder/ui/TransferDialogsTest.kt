package dev.smsforwarder.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.smsforwarder.R
import dev.smsforwarder.core.ConfigDestination
import dev.smsforwarder.core.ConfigError
import dev.smsforwarder.core.ConfigFile
import dev.smsforwarder.core.DestinationPresets
import dev.smsforwarder.core.planImport
import dev.smsforwarder.ui.theme.SmsForwarderTheme
import dev.smsforwarder.ui.theme.ThemeMode
import dev.smsforwarder.ui.transfer.TransferDialogs
import dev.smsforwarder.ui.transfer.TransferUi
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class TransferDialogsTest {
    @get:Rule val compose = createComposeRule()

    private val app get() = RuntimeEnvironment.getApplication()
    private var confirmedExport = 0
    private var confirmedImport = 0
    private var dismissed = 0

    private fun show(initial: TransferUi) {
        compose.setContent {
            var state by remember { mutableStateOf(initial) }
            // Light theme on purpose: the expected tone below is the light PENDING one.
            SmsForwarderTheme(ThemeMode.LIGHT) {
                TransferDialogs(
                    state = state,
                    onIncludeSecrets = { state = TransferUi.AskExport(it) },
                    onConfirmExport = { confirmedExport++ },
                    onConfirmImport = { confirmedImport++ },
                    onDismiss = { dismissed++ },
                )
            }
        }
    }

    @Test fun exportCheckboxStartsOff() {
        show(TransferUi.AskExport(false))
        compose.onNode(isToggleable()).assertExists().assertIsOff()
        compose.onNodeWithText(app.getString(R.string.export_secrets_label)).assertExists()
        compose.onNodeWithText(app.getString(R.string.export_secrets_warning)).assertDoesNotExist()
        compose.onNode(isToggleable()).performClick()
        compose.onNode(isToggleable()).assertIsOn()
        compose.onNodeWithText(app.getString(R.string.export_secrets_warning)).assertExists()
        compose.onNodeWithText(app.getString(R.string.export_confirm)).performClick()
        assertEquals(1, confirmedExport)
        compose.onNodeWithText(app.getString(R.string.transfer_cancel)).performClick()
        assertEquals(1, dismissed)
    }

    @Test fun summaryShowsCountsRenamesAndMissingCredentials() {
        val file = ConfigFile(
            listOf(
                ConfigDestination("d1", DestinationPresets.custom("Main").copy(url = null)),
                ConfigDestination("d2", DestinationPresets.custom("Other").copy(url = null)),
            ),
            emptyList(),
            false,
        )
        show(TransferUi.ImportSummary(planImport(file, listOf("Main"), emptyList())))
        compose.onNodeWithText(app.resources.getQuantityString(R.plurals.import_summary_destinations, 2, 2)).assertExists()
        compose.onNodeWithText(app.getString(R.string.import_renamed, "Main", "Main (2)")).assertExists()
        compose.onNodeWithText(app.getString(R.string.import_without_credentials, "Main (2), Other")).assertExists()
        // The names never stand alone without their label.
        compose.onNodeWithText("Main (2), Other").assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.import_confirm)).performClick()
        assertEquals(1, confirmedImport)
    }

    @Test fun invalidShowsReason() {
        show(TransferUi.ImportInvalid(ConfigError.NEWER_VERSION, null))
        compose.onNodeWithText(app.getString(R.string.import_error_newer_version)).assertExists()
    }

    @Test fun doneShowsMessage() {
        show(TransferUi.Done(dev.smsforwarder.ui.transfer.TransferMessage.Exported))
        compose.onNodeWithText(app.getString(R.string.transfer_exported)).assertExists()
    }

    @Test fun idleShowsNothing() {
        show(TransferUi.Idle)
        compose.onNodeWithText(app.getString(R.string.export_confirm)).assertDoesNotExist()
    }

    @Test fun waitingShowsProgress() {
        show(TransferUi.Waiting)
        compose.onNode(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo))
            .assertExists()
    }

    @Test fun waitingSpinnerHasAnAccessibleLabel() {
        show(TransferUi.Waiting)
        compose.onNode(androidx.compose.ui.test.hasContentDescription(app.getString(R.string.transfer_working))).assertExists()
    }

    @Test fun importedResultShowsPluralsAndMissingCredentials() {
        show(
            TransferUi.Done(
                dev.smsforwarder.ui.transfer.TransferMessage.Imported(dev.smsforwarder.data.ImportResult(1, 2, 1)),
            ),
        )
        compose.onNodeWithText(app.resources.getQuantityString(R.plurals.transfer_imported_destinations, 1, 1)).assertExists()
        compose.onNodeWithText(app.resources.getQuantityString(R.plurals.transfer_imported_rules, 2, 2)).assertExists()
        compose.onNodeWithText(app.resources.getQuantityString(R.plurals.transfer_imported_without_credentials, 1, 1)).assertExists()
    }

    @Test fun importedResultWithoutMissingCredentialsHidesThatLine() {
        show(
            TransferUi.Done(
                dev.smsforwarder.ui.transfer.TransferMessage.Imported(dev.smsforwarder.data.ImportResult(2, 0, 0)),
            ),
        )
        compose.onNodeWithText(app.resources.getQuantityString(R.plurals.transfer_imported_without_credentials, 1, 1)).assertDoesNotExist()
    }

    @Test fun secretsNotSavedHasItsOwnMessage() {
        show(TransferUi.Done(dev.smsforwarder.ui.transfer.TransferMessage.SecretsNotSaved))
        compose.onNodeWithText(app.getString(R.string.transfer_secrets_not_saved)).assertExists()
    }

    @Test fun exportWarningAppearsOnlyWhenChecked() {
        val warning = app.getString(R.string.export_secrets_warning)
        show(TransferUi.AskExport(false))
        compose.onNodeWithText(warning).assertDoesNotExist()
        compose.onNode(isToggleable()).performClick()
        compose.onNodeWithText(warning).assertExists()
    }

    @Test fun exportWarningUsesThePendingTone() {
        show(TransferUi.AskExport(true))
        val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText(app.getString(R.string.export_secrets_warning)).fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        val color = results.single().layoutInput.style.color
        assertEquals(dev.smsforwarder.ui.theme.LightStatusColors.pending.onContainer, color)
    }
}
