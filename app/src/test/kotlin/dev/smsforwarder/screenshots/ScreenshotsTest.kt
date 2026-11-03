package dev.smsforwarder.screenshots

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import dev.smsforwarder.core.BodyMode
import dev.smsforwarder.core.DeliveryStatus
import dev.smsforwarder.core.SenderMode
import dev.smsforwarder.core.SimMatch
import dev.smsforwarder.core.SmsOrigin
import dev.smsforwarder.core.SmsStatus
import dev.smsforwarder.core.StatusKind
import dev.smsforwarder.core.StatusLabel
import dev.smsforwarder.core.TemplateKind
import dev.smsforwarder.data.DeliveryEntity
import dev.smsforwarder.data.SmsEntity
import dev.smsforwarder.ui.destinations.DestinationRowUi
import dev.smsforwarder.ui.destinations.DestinationsScreen
import dev.smsforwarder.ui.detail.DetailScreen
import dev.smsforwarder.ui.detail.DetailUiState
import dev.smsforwarder.ui.main.DeliveryChipUi
import dev.smsforwarder.ui.main.MainScreen
import dev.smsforwarder.ui.main.MainUiState
import dev.smsforwarder.ui.main.RowUi
import dev.smsforwarder.ui.rules.RuleRowUi
import dev.smsforwarder.ui.rules.RulesScreen
import dev.smsforwarder.ui.theme.SmsForwarderTheme
import dev.smsforwarder.ui.theme.ThemeMode
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.GraphicsMode.Mode.NATIVE

/**
 * Renders the main screens in light and dark with fake data and writes PNGs to `app/build/screenshots/`.
 * Not part of the normal suite: `app/build.gradle.kts` skips it unless `-Pscreenshots` is given. See
 * docs/screenshots.md.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(NATIVE)
class ScreenshotsTest {
    @get:Rule val compose = createComposeRule()

    private val at = 1_800_000_000_000L

    private fun shot(name: String, content: @Composable () -> Unit) {
        var dark by mutableStateOf(false)
        compose.setContent {
            SmsForwarderTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false) {
                Surface { content() }
            }
        }
        for (mode in listOf(false, true)) {
            compose.runOnIdle { dark = mode }
            compose.waitForIdle()
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            val dir = File("build/screenshots").apply { mkdirs() }
            File(dir, "$name-${if (mode) "dark" else "light"}.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    private val rows = listOf(
        RowUi(
            1, "AAA", 1, at, "TEST message one", StatusLabel(StatusKind.SENT, 1, 200),
            listOf(DeliveryChipUi("Main", DeliveryStatus.SENT, 200, false), DeliveryChipUi("Backup", DeliveryStatus.SENT, 204, false)),
        ),
        RowUi(
            2, "BANK", 2, at - 60_000, "TEST message two", StatusLabel(StatusKind.ERROR, 2, 403),
            listOf(DeliveryChipUi("Main", DeliveryStatus.SENT, 200, false), DeliveryChipUi("Backup", DeliveryStatus.ERROR, 403, false)),
        ),
        RowUi(
            3, "AAA", null, at - 120_000, "TEST message three", StatusLabel(StatusKind.QUEUED, 1, null),
            listOf(DeliveryChipUi("Main", DeliveryStatus.QUEUED, null, false)),
        ),
    )

    @Test fun main() = shot("main") {
        MainScreen(
            state = MainUiState(
                issues = emptySet(), lastSweepAt = at, queued = 1, errors = 1, rows = rows,
                destinations = listOf(1L to "Main", 2L to "Backup"),
            ),
            onOpenIssue = {}, onSweepNow = {}, onOpen = {},
        )
    }

    @Test fun rules() = shot("rules") {
        RulesScreen(
            rows = listOf(
                RuleRowUi(1, "Rule one", true, SenderMode.EXACT, "AAA", null, null, SimMatch.ANY, listOf("Main")),
                RuleRowUi(2, "Rule two", true, SenderMode.EXACT, "BANK", BodyMode.CONTAINS, "TEST", SimMatch.SIM1, listOf("Main", "Backup")),
                RuleRowUi(3, "Rule three", false, SenderMode.REGEX, "^A+$", null, null, SimMatch.ANY, emptyList()),
            ),
            onOpen = {}, onAdd = {}, onToggle = { _, _ -> }, padding = PaddingValues(),
        )
    }

    @Test fun destinations() = shot("destinations") {
        DestinationsScreen(
            rows = listOf(
                DestinationRowUi(1, "Main", TemplateKind.STANDARD_JSON, enabled = true, queued = 1, errors = 0),
                DestinationRowUi(2, "Backup", TemplateKind.TELEGRAM, enabled = true, queued = 0, errors = 1),
                DestinationRowUi(3, "Paused", TemplateKind.CUSTOM, enabled = false, queued = 2, errors = 0),
            ),
            onOpen = {}, onAdd = {}, onToggle = { _, _ -> }, padding = PaddingValues(),
        )
    }

    @Test fun detail() = shot("detail") {
        val sms = SmsEntity(
            id = 1, localKey = "key", sender = "BANK", senderNormalized = "BANK", body = "TEST message two",
            receivedAt = at, phoneDate = null, subId = 1, origin = SmsOrigin.RECEIVER, status = SmsStatus.ERROR,
            attempts = 2, lastHttpCode = 403, firstSeenAt = at,
        )
        val deliveries = listOf(
            DeliveryEntity(1, 1, 1L, "Main", DeliveryStatus.SENT, 1, 200, null, at),
            DeliveryEntity(2, 1, 2L, "Backup", DeliveryStatus.ERROR, 1, 403, "forbidden"),
        )
        DetailScreen(
            state = DetailUiState(sms, emptyList(), deliveries = deliveries),
            onRetry = {}, onDelete = {}, onBack = {},
        )
    }
}
