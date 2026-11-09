package dev.smsforwarder.ui.main

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.smsforwarder.R
import dev.smsforwarder.core.DeliveryStatus
import dev.smsforwarder.core.StatusKind
import dev.smsforwarder.core.StatusLabel

/** Text of a status badge, taken from resources so it follows the app language. */
@Composable
fun statusText(label: StatusLabel): String {
    val code = label.httpCode
    return when (label.kind) {
        StatusKind.QUEUED -> pluralStringResource(R.plurals.status_queued, label.attempts, label.attempts)
        StatusKind.ERROR ->
            if (code == null) stringResource(R.string.status_error) else stringResource(R.string.status_error_code, code)
        StatusKind.SENT ->
            if (code == null) stringResource(R.string.status_sent) else stringResource(R.string.status_sent_code, code)
        StatusKind.RECOVERED ->
            if (code == null) {
                stringResource(R.string.status_recovered)
            } else {
                stringResource(R.string.status_recovered_code, code)
            }
        StatusKind.MANUAL ->
            if (code == null) stringResource(R.string.status_manual) else stringResource(R.string.status_manual_code, code)
    }
}

/**
 * The status word a screen reader needs when the pill text alone does not say it ("Main" is the same text for a sent
 * and a failed delivery); null where the text already does (queued, canceled).
 */
@Composable
fun deliveryStateWord(status: DeliveryStatus): String? = when (status) {
    DeliveryStatus.SENT -> stringResource(R.string.delivery_state_sent)
    DeliveryStatus.ERROR -> stringResource(R.string.delivery_state_error)
    DeliveryStatus.QUEUED, DeliveryStatus.CANCELED -> null
}

/** One destination's state, e.g. `Main 200` or `T2 queued`. */
@Composable
fun deliveryChipText(chip: DeliveryChipUi): String {
    val name = chip.destinationName
    val code = chip.httpCode
    return when (chip.status) {
        DeliveryStatus.QUEUED -> stringResource(R.string.delivery_queued, name)
        DeliveryStatus.SENT ->
            if (code == null) stringResource(R.string.delivery_sent_nocode, name) else stringResource(R.string.delivery_sent, name, code)
        DeliveryStatus.ERROR -> when {
            chip.configError -> stringResource(R.string.delivery_config, name)
            code == null -> stringResource(R.string.delivery_error_nocode, name)
            else -> stringResource(R.string.delivery_error, name, code)
        }
        DeliveryStatus.CANCELED -> stringResource(R.string.delivery_canceled, name)
    }
}
