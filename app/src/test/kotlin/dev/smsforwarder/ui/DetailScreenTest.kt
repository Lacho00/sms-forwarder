package dev.smsforwarder.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.smsforwarder.core.SmsOrigin
import dev.smsforwarder.core.SmsStatus
import dev.smsforwarder.data.SmsEntity
import dev.smsforwarder.ui.detail.DetailScreen
import dev.smsforwarder.ui.detail.DetailUiState
import dev.smsforwarder.ui.theme.SmsForwarderTheme
import dev.smsforwarder.ui.theme.ThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DetailScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun sms(status: SmsStatus) = SmsEntity(
        id = 1,
        localKey = "key",
        sender = "OPERADOR",
        senderNormalized = "OPERADOR",
        body = "OPERADOR: Se ha comprado un plan de datos de 2.5GB valido por 30 dias, " +
            "se activa con el primer uso. Se desconto 360.00. Saldo actual 140.25",
        receivedAt = 1_800_000_000_000L,
        phoneDate = null,
        subId = 1,
        origin = SmsOrigin.RECEIVER,
        status = status,
        attempts = 1,
        lastHttpCode = if (status == SmsStatus.ERROR) 401 else null,
        firstSeenAt = 1_800_000_000_000L,
    )

    @Test fun retryVisibleOnlyOnError() {
        var state by mutableStateOf(DetailUiState(sms(SmsStatus.ERROR), emptyList()))
        compose.setContent {
            SmsForwarderTheme(ThemeMode.SYSTEM) {
                DetailScreen(state = state, onRetry = {}, onBack = {})
            }
        }
        compose.onNodeWithText("Reintentar").assertExists()

        compose.runOnIdle { state = DetailUiState(sms(SmsStatus.QUEUED), emptyList()) }
        compose.onNodeWithText("Reintentar").assertDoesNotExist()

        compose.runOnIdle { state = DetailUiState(sms(SmsStatus.SENT), emptyList()) }
        compose.onNodeWithText("Reintentar").assertDoesNotExist()
    }
}
