package dev.smsforwarder.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import dev.smsforwarder.R
import dev.smsforwarder.core.SmsOrigin
import dev.smsforwarder.core.SmsStatus
import dev.smsforwarder.core.DeliveryStatus
import dev.smsforwarder.core.HttpMethod
import dev.smsforwarder.core.SuccessPolicy
import dev.smsforwarder.core.TemplateKind
import dev.smsforwarder.data.AttemptEntity
import dev.smsforwarder.data.DeliveryEntity
import dev.smsforwarder.data.DestinationEntity
import dev.smsforwarder.data.SmsEntity
import dev.smsforwarder.ui.detail.DetailScreen
import dev.smsforwarder.ui.detail.DetailUiState
import dev.smsforwarder.ui.theme.SmsForwarderTheme
import dev.smsforwarder.ui.theme.ThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

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
        val retry = RuntimeEnvironment.getApplication().getString(R.string.detail_retry)
        var state by mutableStateOf(DetailUiState(sms(SmsStatus.ERROR), emptyList()))
        compose.setContent {
            SmsForwarderTheme(ThemeMode.SYSTEM) {
                DetailScreen(state = state, onRetry = {}, onDelete = {}, onBack = {})
            }
        }
        compose.onNodeWithText(retry).assertExists()

        compose.runOnIdle { state = DetailUiState(sms(SmsStatus.QUEUED), emptyList()) }
        compose.onNodeWithText(retry).assertDoesNotExist()

        compose.runOnIdle { state = DetailUiState(sms(SmsStatus.SENT), emptyList()) }
        compose.onNodeWithText(retry).assertDoesNotExist()
    }

    private fun str(id: Int, vararg args: Any): String = RuntimeEnvironment.getApplication().getString(id, *args)

    @Test fun deleteAsksForConfirmationAndOnlyConfirmCallsOnDelete() {
        var deleted = 0
        compose.setContent {
            SmsForwarderTheme(ThemeMode.SYSTEM) {
                DetailScreen(
                    state = DetailUiState(sms(SmsStatus.QUEUED), emptyList()),
                    onRetry = {},
                    onDelete = { deleted++ },
                    onBack = {},
                )
            }
        }
        compose.onNodeWithText(str(R.string.detail_delete)).performScrollTo().performClick()
        compose.onNodeWithText(str(R.string.detail_delete_title)).assertExists()
        compose.runOnIdle { assertEquals(0, deleted) }

        compose.onNodeWithText(str(R.string.detail_delete_cancel)).performClick()
        compose.onNodeWithText(str(R.string.detail_delete_title)).assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, deleted) }

        compose.onNodeWithText(str(R.string.detail_delete)).performScrollTo().performClick()
        compose.onNode(hasText(str(R.string.detail_delete_confirm)) and hasAnyAncestor(isDialog())).performClick()
        compose.runOnIdle { assertEquals(1, deleted) }
    }

    @Test fun hiddenTextIsRevealedByShow() {
        compose.setContent {
            SmsForwarderTheme(ThemeMode.SYSTEM) {
                DetailScreen(
                    state = DetailUiState(sms(SmsStatus.SENT), emptyList(), hideText = true),
                    onRetry = {},
                    onDelete = {},
                    onBack = {},
                )
            }
        }
        compose.onNodeWithText("OPERADOR: Se ha comprado", substring = true).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.detail_show_text)).performScrollTo().performClick()
        compose.onNodeWithText("OPERADOR: Se ha comprado", substring = true).assertExists()
    }

    private fun destination(id: Long, name: String) = DestinationEntity(
        id = id, name = name, enabled = true, template = TemplateKind.STANDARD_JSON, method = HttpMethod.POST,
        bodyTemplate = null, bodyIsJson = true, publicHeaders = "[]",
        successPolicy = SuccessPolicy.ANY_2XX, createdAt = 1L,
    )

    @Test fun showsDeliveriesAndResend() {
        var resent: Long? = null
        val deliveries = listOf(
            DeliveryEntity(id = 10, smsId = 1, destinationId = 1, destinationName = "Main", status = DeliveryStatus.SENT, lastHttpCode = 204),
            DeliveryEntity(id = 11, smsId = 1, destinationId = 2, destinationName = "T2", status = DeliveryStatus.QUEUED),
        )
        val attempts = listOf(AttemptEntity(id = 1, smsId = 1, at = 1_800_000_000_000L, httpCode = 204, error = null, deliveryId = 10))
        compose.setContent {
            SmsForwarderTheme(ThemeMode.SYSTEM) {
                DetailScreen(
                    state = DetailUiState(
                        sms(SmsStatus.QUEUED), attempts, deliveries = deliveries,
                        destinations = listOf(destination(1, "Main"), destination(2, "T2")),
                    ),
                    onRetry = {}, onDelete = {}, onBack = {},
                    onResendTo = { resent = it },
                )
            }
        }
        compose.onNodeWithText(str(R.string.delivery_sent, "Main", 204)).performScrollTo().assertExists()
        compose.onNodeWithText(str(R.string.delivery_queued, "T2")).performScrollTo().assertExists()
        compose.onNodeWithText("Main · ", substring = true).performScrollTo().assertExists()

        compose.onNodeWithText(str(R.string.detail_resend_to)).performScrollTo().performClick()
        compose.onNode(hasText("T2") and hasAnyAncestor(isDialog())).performClick()
        compose.runOnIdle { assertEquals(2L, resent) }
    }

    @Test fun resendDialogLabelsPausedDestinations() {
        compose.setContent {
            SmsForwarderTheme(ThemeMode.SYSTEM) {
                DetailScreen(
                    state = DetailUiState(
                        sms(SmsStatus.SENT), emptyList(),
                        destinations = listOf(destination(1, "Main"), destination(2, "T2").copy(enabled = false)),
                    ),
                    onRetry = {}, onDelete = {}, onBack = {},
                )
            }
        }
        compose.onNodeWithText(str(R.string.detail_resend_to)).performScrollTo().performClick()
        compose.onNode(hasText("T2 · " + str(R.string.destination_paused)) and hasAnyAncestor(isDialog())).assertExists()
    }

    @Test fun resendHiddenWithoutDestinations() {
        compose.setContent {
            SmsForwarderTheme(ThemeMode.SYSTEM) {
                DetailScreen(state = DetailUiState(sms(SmsStatus.SENT), emptyList()), onRetry = {}, onDelete = {}, onBack = {})
            }
        }
        compose.onNodeWithText(str(R.string.detail_resend_to)).assertDoesNotExist()
    }

    @Test fun backIsArrowWithContentDescription() {
        var backs = 0
        compose.setContent {
            SmsForwarderTheme(ThemeMode.SYSTEM) {
                DetailScreen(state = DetailUiState(sms(SmsStatus.QUEUED), emptyList()), onRetry = {}, onDelete = {}, onBack = { backs++ })
            }
        }
        val back = str(R.string.back)
        compose.onNodeWithText(back).assertDoesNotExist()
        val raw = compose.onNodeWithContentDescription(back).fetchSemanticsNode().boundsInRoot
        val bounds = Pair(raw.width / compose.density.density, raw.height / compose.density.density)
        println("MEASURE detail back after w=${bounds.first}dp h=${bounds.second}dp")
        compose.onNodeWithContentDescription(back).performClick()
        assertEquals(1, backs)
    }
}
