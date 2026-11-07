package dev.smsforwarder.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.smsforwarder.R
import dev.smsforwarder.ui.recovery.RecoveryItem
import dev.smsforwarder.ui.recovery.RecoveryScreen
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

    private fun item(i: Int, known: Boolean = false) =
        RecoveryItem("k$i", "AAA$i", 1_000L * i, "TEST $i", alreadyKnown = known, selected = false)

    @Test fun alreadySavedRowShowsNeutralPill() {
        var toggles = 0
        compose.setContent {
            SmsForwarderTheme(ThemeMode.SYSTEM) {
                RecoveryScreen(listOf(item(1, known = true)), false, null, { toggles++ }, {}, {})
            }
        }
        val app = RuntimeEnvironment.getApplication()
        compose.onNodeWithText(app.getString(R.string.recovery_already_saved)).assertIsDisplayed()
        compose.onNodeWithText("AAA1").performClick()
        assertEquals(0, toggles)
        compose.onNode(isToggleable()).assertIsNotEnabled()
    }

    @Test fun sendSelectedIsPinnedAtBottom() {
        val items = (1..40).map { item(it) }
        compose.setContent {
            SmsForwarderTheme(ThemeMode.SYSTEM) {
                RecoveryScreen(items, false, null, {}, {}, {})
            }
        }
        val app = RuntimeEnvironment.getApplication()
        val label = app.getString(R.string.recovery_send_selected, 0)
        compose.onNodeWithText(label).assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToIndex(items.size)
        compose.onNodeWithText(label).assertIsDisplayed()
        val list = compose.onNode(hasScrollAction()).fetchSemanticsNode().boundsInRoot
        val button = compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
        assertTrue(button.top >= list.bottom)
    }

    @Test fun longSenderStaysOnScreen() {
        val long = RecoveryItem("k", "S".repeat(60), 1_000L, "TEST", alreadyKnown = true, selected = false)
        compose.setContent {
            SmsForwarderTheme(ThemeMode.SYSTEM) {
                RecoveryScreen(listOf(long), false, null, {}, {}, {})
            }
        }
        val app = RuntimeEnvironment.getApplication()
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val pill = compose.onNodeWithText(app.getString(R.string.recovery_already_saved)).fetchSemanticsNode().boundsInRoot
        assertTrue(pill.right <= root.right)
    }
}
