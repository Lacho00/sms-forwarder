package dev.smsforwarder.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.smsforwarder.R
import dev.smsforwarder.ui.recovery.RecoveryScreen
import dev.smsforwarder.ui.theme.SmsForwarderTheme
import dev.smsforwarder.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class RecoveryScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun backIsArrowWithContentDescription() {
        var backs = 0
        compose.setContent {
            SmsForwarderTheme(ThemeMode.SYSTEM) {
                RecoveryScreen(emptyList(), false, null, {}, {}, { backs++ })
            }
        }
        val back = RuntimeEnvironment.getApplication().getString(R.string.back)
        compose.onNodeWithText(back).assertDoesNotExist()
        val raw = compose.onNodeWithContentDescription(back).fetchSemanticsNode().boundsInRoot
        val bounds = Pair(raw.width / compose.density.density, raw.height / compose.density.density)
        println("MEASURE recovery back after w=${bounds.first}dp h=${bounds.second}dp")
        compose.onNodeWithContentDescription(back).performClick()
        assertEquals(1, backs)
    }
}
