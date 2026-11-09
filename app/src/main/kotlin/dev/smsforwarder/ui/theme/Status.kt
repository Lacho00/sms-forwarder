package dev.smsforwarder.ui.theme

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.smsforwarder.R
import dev.smsforwarder.core.DeliveryStatus
import dev.smsforwarder.core.StatusKind
import kotlin.math.max
import kotlin.math.min

enum class StatusTone { OK, PENDING, ERROR, NEUTRAL }

data class ToneColors(val container: Color, val onContainer: Color)

/** OK (green) and PENDING (amber) are fixed hues, independent of the dynamic scheme. ERROR and NEUTRAL come from the colour scheme. */
@Immutable
class StatusColors(val ok: ToneColors, val pending: ToneColors)

val LightStatusColors = StatusColors(
    ok = ToneColors(Color(0xFFD3F1D8), Color(0xFF0B4F1C)),
    pending = ToneColors(Color(0xFFFFE6AE), Color(0xFF5A3B00)),
)

val DarkStatusColors = StatusColors(
    ok = ToneColors(Color(0xFF1F4D2A), Color(0xFFB9F2C3)),
    pending = ToneColors(Color(0xFF5B4300), Color(0xFFFFDE9E)),
)

val LocalStatusColors = staticCompositionLocalOf { LightStatusColors }

@Composable
fun toneColors(tone: StatusTone): ToneColors = when (tone) {
    StatusTone.OK -> LocalStatusColors.current.ok
    StatusTone.PENDING -> LocalStatusColors.current.pending
    StatusTone.ERROR -> MaterialTheme.colorScheme.let { ToneColors(it.errorContainer, it.onErrorContainer) }
    StatusTone.NEUTRAL -> MaterialTheme.colorScheme.let { ToneColors(it.surfaceVariant, it.onSurfaceVariant) }
}

fun toneOf(kind: StatusKind): StatusTone = when (kind) {
    StatusKind.SENT, StatusKind.RECOVERED, StatusKind.MANUAL -> StatusTone.OK
    StatusKind.QUEUED -> StatusTone.PENDING
    StatusKind.ERROR -> StatusTone.ERROR
}

fun toneOf(status: DeliveryStatus): StatusTone = when (status) {
    DeliveryStatus.SENT -> StatusTone.OK
    DeliveryStatus.QUEUED -> StatusTone.PENDING
    DeliveryStatus.ERROR -> StatusTone.ERROR
    DeliveryStatus.CANCELED -> StatusTone.NEUTRAL
}

@Composable
fun toneIcon(tone: StatusTone): Painter = when (tone) {
    StatusTone.OK -> rememberVectorPainter(Icons.Filled.Check)
    StatusTone.PENDING -> painterResource(R.drawable.ic_status_schedule)
    StatusTone.ERROR -> painterResource(R.drawable.ic_status_error)
    StatusTone.NEUTRAL -> rememberVectorPainter(Icons.Filled.Close)
}

/** The icon is decorative: the text carries the meaning, so state is never colour alone. */
@Composable
fun StatusPill(tone: StatusTone, text: String, modifier: Modifier = Modifier, maxLines: Int = 1) {
    val colors = toneColors(tone)
    Surface(modifier = modifier, shape = RoundedCornerShape(50), color = colors.container, contentColor = colors.onContainer) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(toneIcon(tone), contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(text, style = MaterialTheme.typography.labelMedium, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** WCAG 2.x contrast ratio between two colours. */
internal fun contrastRatio(a: Color, b: Color): Double {
    val la = a.luminance().toDouble()
    val lb = b.luminance().toDouble()
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
}
