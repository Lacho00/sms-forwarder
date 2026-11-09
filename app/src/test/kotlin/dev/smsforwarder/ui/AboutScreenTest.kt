package dev.smsforwarder.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import dev.smsforwarder.R
import dev.smsforwarder.ui.about.AboutScreen
import dev.smsforwarder.ui.about.AboutState
import dev.smsforwarder.ui.about.DiagnosticMessage
import dev.smsforwarder.ui.about.REPO_URL
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
class AboutScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context = RuntimeEnvironment.getApplication()

    private fun show(
        onOpenLink: (String) -> Unit = {},
        onBack: () -> Unit = {},
        state: AboutState = AboutState(),
        onExport: () -> Unit = {},
    ) {
        compose.setContent {
            SmsForwarderTheme(ThemeMode.SYSTEM, dynamicColor = false) {
                AboutScreen(
                    versionName = "9.8.7", state = state, onOpenLink = onOpenLink,
                    onExportDiagnostic = onExport, onBack = onBack,
                )
            }
        }
    }

    @Test fun showsVersionAndLink() {
        show()
        compose.onNodeWithText(context.getString(R.string.about_version, "9.8.7")).assertExists()
        compose.onNodeWithText(REPO_URL).assertExists()
    }

    @Test fun tappingLinkOpensRepo() {
        val opened = mutableListOf<String>()
        show(onOpenLink = { opened += it })
        compose.onNodeWithText(REPO_URL).performClick()
        assertEquals(listOf("https://github.com/Lacho00/sms-forwarder"), opened)
    }

    @Test fun showsLicenseAndLibraries() {
        show()
        compose.onNodeWithText(context.getString(R.string.about_license_value)).assertExists()
        context.resources.getStringArray(R.array.about_libraries).forEach {
            compose.onNodeWithText(it).assertExists()
        }
    }

    @Test fun backCallsOnBack() {
        var backs = 0
        show(onBack = { backs++ })
        compose.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
        assertEquals(1, backs)
    }

    @Test fun backIsArrow() {
        show()
        compose.onNodeWithText(context.getString(R.string.back)).assertDoesNotExist()
        val raw = compose.onNodeWithContentDescription(context.getString(R.string.back)).fetchSemanticsNode().boundsInRoot
        val bounds = Pair(raw.width / compose.density.density, raw.height / compose.density.density)
        assertEquals(40f, bounds.first, 0.5f)
        assertEquals(40f, bounds.second, 0.5f)
        val touch = compose.onNodeWithContentDescription(context.getString(R.string.back)).fetchSemanticsNode().touchBoundsInRoot
        assertTrue(touch.width / compose.density.density >= 48f)
        assertTrue(touch.height / compose.density.density >= 48f)
    }

    @Test fun iconIsShownAt108dp() {
        show()
        val raw = compose.onNodeWithTag("about_icon").fetchSemanticsNode().boundsInRoot
        val bounds = Pair(raw.width / compose.density.density, raw.height / compose.density.density)
        assertEquals(108f, bounds.first, 0.5f)
        assertEquals(108f, bounds.second, 0.5f)
    }

    @Test fun exportDiagnosticButton() {
        var clicks = 0
        show(onExport = { clicks++ })
        compose.onNodeWithText(context.getString(R.string.about_diagnostic_hint)).assertExists()
        compose.onNodeWithText(context.getString(R.string.about_export_diagnostic)).performScrollTo().performClick()
        assertEquals(1, clicks)
        compose.onNodeWithText(context.getString(R.string.about_diagnostic_saved)).assertDoesNotExist()
    }

    @Test fun savedMessageShown() {
        show(state = AboutState(message = DiagnosticMessage.SAVED))
        compose.onNodeWithText(context.getString(R.string.about_diagnostic_saved)).performScrollTo()
            .assert(androidx.compose.ui.test.SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.LiveRegion,
                androidx.compose.ui.semantics.LiveRegionMode.Polite,
            ))
    }

    @Test fun aboutLinkOpensCode() {
        val opened = mutableListOf<String>()
        show(onOpenLink = { opened += it })
        val link = compose.onNode(hasClickAction() and hasText(REPO_URL))
        link.assertExists()
        assertEquals(Role.Button, link.fetchSemanticsNode().config[SemanticsProperties.Role])
        link.performClick()
        assertEquals(listOf(REPO_URL), opened)
    }

    @Test fun aboutLinkAlignsWithTheText() {
        show()
        val title = compose.onNodeWithText(context.getString(R.string.about_source_code), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left
        val link = compose.onNodeWithText(REPO_URL, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left
        assertEquals(title, link, 0.5f)
    }

    @Test fun aboutDiagnosticsIsARow() {
        var clicks = 0
        show(onExport = { clicks++ })
        val row = compose.onNode(
            hasClickAction() and hasText(context.getString(R.string.about_export_diagnostic)) and
                hasText(context.getString(R.string.about_diagnostic_hint)),
        )
        row.performScrollTo().performClick()
        assertEquals(1, clicks)
    }

    @Test fun aboutDiagnosticsRowIsDisabledWhileBusy() {
        show(state = AboutState(busy = true))
        compose.onNode(hasClickAction() and hasText(context.getString(R.string.about_export_diagnostic)))
            .performScrollTo().assertIsNotEnabled()
    }
}
