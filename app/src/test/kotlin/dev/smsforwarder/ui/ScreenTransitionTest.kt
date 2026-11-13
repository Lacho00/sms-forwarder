package dev.smsforwarder.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import dev.smsforwarder.ui.theme.Motion
import dev.smsforwarder.ui.theme.SmsForwarderTheme
import dev.smsforwarder.ui.theme.ThemeMode
import dev.smsforwarder.ui.theme.fadeThrough
import dev.smsforwarder.ui.theme.sharedAxisX
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.GraphicsMode.Mode.NATIVE

/**
 * Drives the same AnimatedContent structure as [AppNavHost] (tabs share a key, one AppShell, inner fade-through body)
 * with plain text screens, since the real host needs every ViewModel.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(NATIVE)
class ScreenTransitionTest {
    @get:Rule val compose = createComposeRule()

    private var route by mutableStateOf(Routes.MAIN)
    private var depth = 1

    private fun go(next: String, newDepth: Int) {
        route = next
        // Mirrors AppNavHost: depthDelta is computed before the stack changes.
        pendingDelta = newDepth - depth
        depth = newDepth
    }

    private var pendingDelta = 0

    /** True when the root paints glyph-like detail inside the bounds of [text] (so the text itself shows). */
    private fun drawsAnything(text: String, bounds: androidx.compose.ui.geometry.Rect): Boolean {
        val img = compose.onRoot().captureToImage().toPixelMap()
        val colors = mutableSetOf<androidx.compose.ui.graphics.Color>()
        for (x in bounds.left.toInt() until bounds.right.toInt()) {
            for (y in bounds.top.toInt() until bounds.bottom.toInt()) colors += img[x, y]
        }
        // Antialiased glyphs add dozens of shades; a background fading in only adds one or two.
        return colors.size > 8
    }

    private fun content() {
        compose.setContent {
            SmsForwarderTheme(ThemeMode.SYSTEM) {
                val offsetPx = with(LocalDensity.current) { Motion.SHARED_AXIS_OFFSET.roundToPx() }
                AnimatedContent(
                    targetState = route,
                    modifier = Modifier.fillMaxSize(),
                    transitionSpec = {
                        if (pendingDelta == 0) fadeThrough() else sharedAxisX(pendingDelta > 0, offsetPx)
                    },
                    contentKey = { if (Routes.isTab(it)) "tabs" else it },
                    label = "screen",
                ) { r ->
                    if (Routes.isTab(r)) {
                        AppShell(current = r, title = "Bar", onTab = {}, onExport = {}, onImport = {}, onAbout = {}) { padding ->
                            AnimatedContent(targetState = r, transitionSpec = { fadeThrough() }, label = "tab") { tab ->
                                Text("body-$tab", Modifier.padding(padding))
                            }
                        }
                    } else {
                        androidx.compose.material3.Scaffold { padding -> Text("screen-$r", Modifier.padding(padding)) }
                    }
                }
            }
        }
    }

    @Test fun pushLeavesOnlyOneScreenVisibleAfterTheFadeOut() {
        content()
        compose.mainClock.autoAdvance = false
        compose.waitForIdle()
        val bounds = compose.onNodeWithText("body-main").fetchSemanticsNode().boundsInRoot
        assertTrue(drawsAnything("body-main", bounds))
        go("detail/1", 2)
        compose.mainClock.advanceTimeBy(40)
        compose.onNodeWithText("body-main").assertIsDisplayed()
        assertTrue(drawsAnything("body-main", bounds))
        // The 75 ms fade-out starts one frame after the state change and eases in, so allow it to settle by 160 ms.
        compose.mainClock.advanceTimeBy(120)
        // The outgoing screen is still composed but fully faded out: its text no longer shows.
        assertFalse(drawsAnything("body-main", bounds))
        compose.onNodeWithText("screen-detail/1").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(400)
        assertEquals(0, compose.onAllNodesWithText("body-main").fetchSemanticsNodes().size)
        compose.onNodeWithText("screen-detail/1").assertIsDisplayed()
    }

    @Test fun tabSwitchKeepsTheBarsInPlace() {
        content()
        compose.mainClock.autoAdvance = false
        compose.waitForIdle()
        val barBefore = compose.onNodeWithText("Bar").fetchSemanticsNode().id
        go(Routes.RULES, 2)
        for (step in listOf(40L, 40L, 80L, 200L)) {
            compose.mainClock.advanceTimeBy(step)
            val bar = compose.onNodeWithText("Bar")
            bar.assertIsDisplayed()
            assertEquals(barBefore, bar.fetchSemanticsNode().id)
        }
        assertTrue(compose.onAllNodesWithText("body-main").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("body-rules").assertIsDisplayed()
    }
}
