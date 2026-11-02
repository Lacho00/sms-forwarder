package dev.smsforwarder.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.smsforwarder.R
import dev.smsforwarder.ui.about.AboutScreen
import dev.smsforwarder.ui.about.REPO_URL
import dev.smsforwarder.ui.theme.SmsForwarderTheme
import dev.smsforwarder.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AboutScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context = RuntimeEnvironment.getApplication()

    private fun show(onOpenLink: (String) -> Unit = {}, onBack: () -> Unit = {}) {
        compose.setContent {
            SmsForwarderTheme(ThemeMode.SYSTEM, dynamicColor = false) {
                AboutScreen(versionName = "9.8.7", onOpenLink = onOpenLink, onBack = onBack)
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
        compose.onNodeWithText(context.getString(R.string.back)).performClick()
        assertEquals(1, backs)
    }
}
