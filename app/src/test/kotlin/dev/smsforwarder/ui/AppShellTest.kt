package dev.smsforwarder.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.smsforwarder.R
import dev.smsforwarder.ui.theme.SmsForwarderTheme
import dev.smsforwarder.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AppShellTest {
    @get:Rule val compose = createComposeRule()

    private fun str(id: Int): String = RuntimeEnvironment.getApplication().getString(id)

    private val tabs = mutableListOf<String>()
    private var aboutCount = 0
    private var exportCount = 0
    private var importCount = 0

    private fun show(current: String = Routes.MAIN, transferEnabled: Boolean = true) {
        compose.setContent {
            SmsForwarderTheme(ThemeMode.SYSTEM) {
                AppShell(
                    current = current,
                    title = "Title",
                    onTab = { tabs += it },
                    onExport = { exportCount++ },
                    onImport = { importCount++ },
                    onAbout = { aboutCount++ },
                    transferEnabled = transferEnabled,
                ) { Text("body") }
            }
        }
    }

    @Test fun showsBothTabs() {
        show()
        compose.onNodeWithText(str(R.string.tab_home)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.tab_settings)).assertIsDisplayed()
    }

    @Test fun fourTabsInSpecOrder() {
        show()
        val ids = listOf(R.string.tab_home, R.string.tab_rules, R.string.tab_destinations, R.string.tab_settings)
        val xs = ids.map { compose.onNodeWithText(str(it)).fetchSemanticsNode().positionInRoot.x }
        assertEquals(xs.sorted(), xs)
        assertEquals(4, xs.toSet().size)
    }

    @Test fun rulesTabReportsRulesRoute() {
        show()
        compose.onNodeWithText(str(R.string.tab_rules)).assertExists().performClick()
        assertEquals(listOf(Routes.RULES), tabs)
    }

    @Test fun destinationsTabShown() {
        show()
        compose.onNodeWithText(str(R.string.tab_destinations)).assertIsDisplayed().performClick()
        assertEquals(listOf(Routes.DESTINATIONS), tabs)
    }

    @Test fun tappingSettingsTabReportsSettingsRoute() {
        show()
        compose.onNodeWithText(str(R.string.tab_settings)).performClick()
        assertEquals(listOf(Routes.SETTINGS), tabs)
    }

    @Test fun menuHasExportImportAbout() {
        show()
        compose.onNodeWithContentDescription(str(R.string.menu_more_options)).performClick()
        val labels = listOf(R.string.menu_export, R.string.menu_import, R.string.about_title)
        val ys = labels.map { compose.onNodeWithText(str(it)).assertExists().fetchSemanticsNode().positionInRoot.y }
        assertEquals(ys.sorted(), ys)
        assertEquals(3, ys.toSet().size)
        compose.onNodeWithText(str(R.string.menu_export)).performClick()
        assertEquals(1, exportCount)
        compose.onNodeWithContentDescription(str(R.string.menu_more_options)).performClick()
        compose.onNodeWithText(str(R.string.menu_import)).performClick()
        assertEquals(1, importCount)
        compose.onNodeWithContentDescription(str(R.string.menu_more_options)).performClick()
        compose.onNodeWithText(str(R.string.about_title)).assertIsDisplayed().performClick()
        assertEquals(1, aboutCount)
        assertEquals(1, exportCount)
    }

    @Test fun onlyTopLevelScreensAreTabs() {
        listOf(Routes.MAIN, Routes.SETTINGS, Routes.DESTINATIONS, Routes.RULES).forEach { assertTrue(it, Routes.isTab(it)) }
        listOf(Routes.ONBOARDING, Routes.RECOVERY, Routes.ABOUT, Routes.detail(3), Routes.destination(null), Routes.destination(7), Routes.rule(null), Routes.rule(7)).forEach {
            assertFalse(it, Routes.isTab(it))
        }
    }

    @Test fun selectingATabPinsTheStack() {
        assertEquals("main", stackForTab(Routes.MAIN))
        assertEquals("main|settings", stackForTab(Routes.SETTINGS))
        assertEquals("main|destinations", stackForTab(Routes.DESTINATIONS))
        assertEquals("main|rules", stackForTab(Routes.RULES))
    }

    @Test fun exportAndImportAreDisabledInSafeMode() {
        show(transferEnabled = false)
        compose.onNodeWithContentDescription(str(R.string.menu_more_options)).performClick()
        compose.onNodeWithText(str(R.string.menu_export)).assertIsNotEnabled()
        compose.onNodeWithText(str(R.string.menu_import)).assertIsNotEnabled()
        compose.onNodeWithText(str(R.string.about_title)).assertIsEnabled()
    }
}
