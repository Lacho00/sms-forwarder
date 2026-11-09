package dev.smsforwarder.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import org.robolectric.RuntimeEnvironment
import dev.smsforwarder.ui.components.EmptyState
import dev.smsforwarder.ui.components.SectionTitle
import dev.smsforwarder.ui.components.SettingRow
import dev.smsforwarder.ui.components.SingleChoiceDialog
import dev.smsforwarder.ui.theme.SmsForwarderTheme
import dev.smsforwarder.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ComponentsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun emptyStateActionFires() {
        var fired = 0
        compose.setContent {
            SmsForwarderTheme(ThemeMode.LIGHT, dynamicColor = false) {
                EmptyState(rememberVectorPainter(Icons.Filled.Info), "T", "B", actionLabel = "Go", onAction = { fired++ })
            }
        }
        compose.onNodeWithText("Go").performClick()
        assertEquals(1, fired)
    }

    @Test fun emptyStateWithoutActionShowsNoButton() {
        compose.setContent {
            SmsForwarderTheme(ThemeMode.LIGHT, dynamicColor = false) {
                EmptyState(rememberVectorPainter(Icons.Filled.Info), "T", "B")
            }
        }
        compose.onNodeWithText("T").assertIsDisplayed()
        compose.onAllNodes(androidx.compose.ui.test.hasClickAction()).assertCountEquals(0)
    }

    @Test fun sectionTitleIsHeading() {
        compose.setContent { SmsForwarderTheme(ThemeMode.LIGHT, dynamicColor = false) { SectionTitle("Sec") } }
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertIsDisplayed()
    }

    @Test fun singleChoiceDialogSelectsAndCloses() {
        var picked: String? = null
        compose.setContent {
            SmsForwarderTheme(ThemeMode.LIGHT, dynamicColor = false) {
                SingleChoiceDialog("Pick", listOf("a", "b"), "a", { it.uppercase() }, { picked = it }, {})
            }
        }
        compose.onNodeWithText("B").performClick()
        assertEquals("b", picked)
    }

    @Test fun singleChoiceDialogIgnoresTheSelectedOption() {
        var picked: String? = null
        var dismissed = 0
        compose.setContent {
            SmsForwarderTheme(ThemeMode.LIGHT, dynamicColor = false) {
                SingleChoiceDialog("Pick", listOf("a", "b"), "a", { it.uppercase() }, { picked = it }, { dismissed++ })
            }
        }
        compose.onNodeWithText("A").performClick()
        assertEquals(null, picked)
        assertEquals(1, dismissed)
    }

    @Test fun singleChoiceDialogCancelIsGeneric() {
        compose.setContent {
            SmsForwarderTheme(ThemeMode.LIGHT, dynamicColor = false) {
                SingleChoiceDialog("Pick", listOf("a", "b"), "a", { it.uppercase() }, {}, {})
            }
        }
        compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(dev.smsforwarder.R.string.action_cancel)).assertExists()
    }

    @Test fun segmentedChoiceExposesItsGroupLabel() {
        compose.setContent {
            SmsForwarderTheme(ThemeMode.LIGHT, dynamicColor = false) {
                dev.smsforwarder.ui.components.SegmentedChoice(listOf("a", "b"), "a", { it.uppercase() }, {}, groupLabel = "Grp")
            }
        }
        compose.onNodeWithContentDescription("Grp").assertExists()
    }

    @Test fun settingRowShowsSupportingText() {
        compose.setContent {
            SmsForwarderTheme(ThemeMode.LIGHT, dynamicColor = false) { SettingRow("Ti", supporting = "Sup", onClick = {}) }
        }
        compose.onNodeWithText("Ti").assertIsDisplayed()
        compose.onNodeWithText("Sup").assertIsDisplayed()
    }
}
