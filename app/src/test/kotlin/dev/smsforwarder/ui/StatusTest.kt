package dev.smsforwarder.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import dev.smsforwarder.core.DeliveryStatus
import dev.smsforwarder.core.StatusKind
import dev.smsforwarder.ui.theme.DarkStatusColors
import dev.smsforwarder.ui.theme.LightStatusColors
import dev.smsforwarder.ui.theme.SmsForwarderTheme
import dev.smsforwarder.ui.theme.StatusPill
import dev.smsforwarder.ui.theme.StatusTone
import dev.smsforwarder.ui.theme.ThemeMode
import dev.smsforwarder.ui.theme.contrastRatio
import dev.smsforwarder.ui.theme.toneOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StatusTest {
    @get:Rule val compose = createComposeRule()

    @Test fun toneMapsEveryStatusKind() {
        assertEquals(StatusTone.OK, toneOf(StatusKind.SENT))
        assertEquals(StatusTone.OK, toneOf(StatusKind.RECOVERED))
        assertEquals(StatusTone.OK, toneOf(StatusKind.MANUAL))
        assertEquals(StatusTone.PENDING, toneOf(StatusKind.QUEUED))
        assertEquals(StatusTone.ERROR, toneOf(StatusKind.ERROR))
    }

    @Test fun toneMapsEveryDeliveryStatus() {
        assertEquals(StatusTone.OK, toneOf(DeliveryStatus.SENT))
        assertEquals(StatusTone.PENDING, toneOf(DeliveryStatus.QUEUED))
        assertEquals(StatusTone.ERROR, toneOf(DeliveryStatus.ERROR))
        assertEquals(StatusTone.NEUTRAL, toneOf(DeliveryStatus.CANCELED))
    }

    @Test fun fixedTonesPassAaInBothModes() {
        for (set in listOf(LightStatusColors, DarkStatusColors)) {
            for (t in listOf(set.ok, set.pending)) {
                assertTrue(contrastRatio(t.container, t.onContainer) >= 4.5)
            }
        }
    }

    @Test fun pillShowsItsText() {
        compose.setContent {
            SmsForwarderTheme(ThemeMode.LIGHT, dynamicColor = false) { StatusPill(StatusTone.OK, "x") }
        }
        compose.onNodeWithText("x").assertIsDisplayed()
    }
}
