package dev.smsforwarder.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
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

    private fun show(current: String = Routes.MAIN) {
        compose.setContent {
            SmsForwarderTheme(ThemeMode.SYSTEM) {
                AppShell(
                    current = current,
                    title = "Title",
                    onTab = { tabs += it },
                    onAbout = { aboutCount++ },
                ) { Text("body") }
            }
        }
    }

    @Test fun showsBothTabs() {
        show()
        compose.onNodeWithText(str(R.string.tab_home)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.tab_settings)).assertIsDisplayed()
    }

    @Test fun tappingSettingsTabReportsSettingsRoute() {
        show()
        compose.onNodeWithText(str(R.string.tab_settings)).performClick()
        assertEquals(listOf(Routes.SETTINGS), tabs)
    }

    @Test fun overflowMenuOnlyHasAbout() {
        show()
        compose.onNodeWithContentDescription(str(R.string.menu_more_options)).performClick()
        compose.onNodeWithText(str(R.string.about_title)).assertIsDisplayed().performClick()
        assertEquals(1, aboutCount)
    }

    @Test fun onlyMainAndSettingsAreTabs() {
        assertTrue(Routes.isTab(Routes.MAIN))
        assertTrue(Routes.isTab(Routes.SETTINGS))
        listOf(Routes.ONBOARDING, Routes.RECOVERY, Routes.ABOUT, Routes.detail(3)).forEach {
            assertFalse(it, Routes.isTab(it))
        }
    }

    @Test fun selectingATabPinsTheStack() {
        assertEquals("main", stackForTab(Routes.MAIN))
        assertEquals("main|settings", stackForTab(Routes.SETTINGS))
    }
}
