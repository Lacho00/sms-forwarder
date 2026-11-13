package dev.smsforwarder.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically

/** Shared motion specs: short, eased, no bounce, and nothing that runs while the UI is idle. */
object Motion {
    const val SHORT = 150
    const val MEDIUM = 250

    /** Element entering the screen: decelerates into place. */
    fun <T> enter(duration: Int = MEDIUM) = tween<T>(duration, easing = LinearOutSlowInEasing)

    /** Element leaving the screen: accelerates away. */
    fun <T> exit(duration: Int = SHORT) = tween<T>(duration, easing = FastOutLinearInEasing)

    /** Element changing in place (colour, size, progress). */
    fun <T> standard(duration: Int = MEDIUM) = tween<T>(duration, easing = FastOutSlowInEasing)

    val fadeInShort: EnterTransition = fadeIn(enter(SHORT))
    val fadeOutShort: ExitTransition = fadeOut(exit(SHORT))

    /** Banner or hint block appearing in a column. */
    val expandIn: EnterTransition =
        expandVertically(standard<androidx.compose.ui.unit.IntSize>()) + fadeIn(enter(MEDIUM))
    val shrinkOut: ExitTransition =
        shrinkVertically(standard<androidx.compose.ui.unit.IntSize>()) + fadeOut(exit(SHORT))
}
