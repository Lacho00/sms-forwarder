package dev.smsforwarder.ui.theme

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.unit.dp

/** Shared motion specs: short, eased, no bounce, and nothing that runs while the UI is idle. */
object Motion {
    const val SHORT = 150
    const val MEDIUM = 250

    // Material 3 shared-axis and fade-through motion. The outgoing screen is gone after OUT ms and the incoming one
    // starts fading in then, so the two are never both visibly opaque.
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    const val TOTAL = 250
    const val OUT = 75
    const val IN = 175
    val SHARED_AXIS_OFFSET = 30.dp

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

/** Material 3 shared axis on X: both screens move [offsetPx] in the travel direction while one fades out, then the other in. */
fun sharedAxisX(forward: Boolean, offsetPx: Int): ContentTransform =
    ContentTransform(
        targetContentEnter = slideInHorizontally(tween(Motion.TOTAL, easing = Motion.EmphasizedDecelerate)) { if (forward) offsetPx else -offsetPx } +
            fadeIn(tween(Motion.IN, delayMillis = Motion.OUT, easing = LinearOutSlowInEasing)),
        initialContentExit = slideOutHorizontally(tween(Motion.TOTAL, easing = Motion.EmphasizedDecelerate)) { if (forward) -offsetPx else offsetPx } +
            fadeOut(tween(Motion.OUT, easing = FastOutLinearInEasing)),
        sizeTransform = SizeTransform(clip = false),
    )

/** Material 3 fade through: the outgoing content fades out, then the incoming one fades in while scaling up from 92%. */
fun fadeThrough(): ContentTransform =
    ContentTransform(
        targetContentEnter = fadeIn(tween(Motion.IN, delayMillis = Motion.OUT, easing = LinearOutSlowInEasing)) +
            scaleIn(tween(Motion.IN, delayMillis = Motion.OUT, easing = Motion.EmphasizedDecelerate), initialScale = 0.92f),
        initialContentExit = fadeOut(tween(Motion.OUT, easing = FastOutLinearInEasing)),
        sizeTransform = SizeTransform(clip = false),
    )
