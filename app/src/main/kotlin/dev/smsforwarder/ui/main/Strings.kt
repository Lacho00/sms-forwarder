package dev.smsforwarder.ui.main

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.smsforwarder.R
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
