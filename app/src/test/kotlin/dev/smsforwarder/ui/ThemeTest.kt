package dev.smsforwarder.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import dev.smsforwarder.ui.theme.SmsForwarderTheme
import dev.smsforwarder.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ThemeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun withoutDynamicColorTheStaticMaterialSchemeIsUsed() {
        var primary = Color.Unspecified
        compose.setContent {
            SmsForwarderTheme(ThemeMode.LIGHT, dynamicColor = false) { primary = MaterialTheme.colorScheme.primary }
        }
        compose.waitForIdle()
        assertEquals(lightColorScheme().primary, primary)
    }
}
