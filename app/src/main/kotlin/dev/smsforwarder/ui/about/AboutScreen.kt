package dev.smsforwarder.ui.about

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.smsforwarder.R
import dev.smsforwarder.ui.components.SettingRow

const val REPO_URL = "https://github.com/Lacho00/sms-forwarder"

/** App identity, source link, license and the libraries it is built on. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    versionName: String,
    state: AboutState,
    onOpenLink: (String) -> Unit,
    onExportDiagnostic: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Adaptive mipmaps cannot go through painterResource: layer the launcher's own background and foreground.
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.testTag("about_icon").clip(CircleShape).background(colorResource(R.color.ic_launcher_background)),
            )
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.about_version, versionName), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.about_source_code), style = MaterialTheme.typography.titleSmall)
            // A link-styled text, not a TextButton: a button's inset would push it off the text column.
            Text(
                REPO_URL,
                style = MaterialTheme.typography.bodyLarge.copy(textDecoration = TextDecoration.Underline),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable(role = Role.Button) { onOpenLink(REPO_URL) }
                    .minimumInteractiveComponentSize(),
            )
            Text(stringResource(R.string.about_license), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.about_license_value), style = MaterialTheme.typography.bodyMedium)
            // Full-bleed row: the screen's 16 dp padding is cancelled so the row's own 16 dp lines up with the text above.
            SettingRow(
                title = stringResource(R.string.about_export_diagnostic),
                supporting = stringResource(R.string.about_diagnostic_hint),
                leadingIcon = painterResource(R.drawable.ic_upload),
                modifier = Modifier.layout { measurable, constraints ->
                    val bleed = 16.dp.roundToPx()
                    val placeable = measurable.measure(constraints.copy(minWidth = constraints.maxWidth + 2 * bleed, maxWidth = constraints.maxWidth + 2 * bleed))
                    layout(constraints.maxWidth, placeable.height) { placeable.place(-bleed, 0) }
                },
                enabled = !state.busy,
                onClick = onExportDiagnostic,
            )
            state.message?.let {
                Text(
                    stringResource(
                        when (it) {
                            DiagnosticMessage.SAVED -> R.string.about_diagnostic_saved
                            DiagnosticMessage.FAILED -> R.string.about_diagnostic_failed
                            DiagnosticMessage.INTERRUPTED -> R.string.about_diagnostic_interrupted
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            Text(stringResource(R.string.about_libraries_title), style = MaterialTheme.typography.titleSmall)
            stringArrayResource(R.array.about_libraries).forEach {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
