package dev.smsforwarder.ui.about

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.smsforwarder.R

const val REPO_URL = "https://github.com/Lacho00/sms-forwarder"
const val LICENSE_URL = "$REPO_URL/blob/main/LICENSE"

/** The repository link as shown on screen: no scheme, so it fits on one line. */
val REPO_LABEL = REPO_URL.removePrefix("https://")

/** Splits a library entry such as "Room (Apache-2.0)" into its name and license; entries without a license keep it null. */
internal fun splitLibrary(entry: String): Pair<String, String?> {
    val m = Regex("""^(.*\S)\s*\(([^()]+)\)$""").find(entry) ?: return entry to null
    return m.groupValues[1] to m.groupValues[2]
}

/** App identity up top, then grouped cards: the project (source, license), support (diagnostics) and the libraries. */
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
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Header(versionName)

            GroupTitle(stringResource(R.string.about_section_project))
            Group {
                val opens = stringResource(R.string.about_opens_in_browser)
                AboutRow(
                    title = stringResource(R.string.about_source_code),
                    supporting = REPO_LABEL,
                    icon = painterResource(R.drawable.ic_code),
                    trailing = { ExternalMark() },
                    onClickLabel = opens,
                    onClick = { onOpenLink(REPO_URL) },
                )
                GroupDivider()
                AboutRow(
                    title = stringResource(R.string.about_license) + " · " + stringResource(R.string.about_license_value),
                    supporting = stringResource(R.string.about_license_hint),
                    icon = painterResource(R.drawable.ic_license),
                    trailing = { ExternalMark() },
                    onClickLabel = opens,
                    onClick = { onOpenLink(LICENSE_URL) },
                )
            }

            GroupTitle(stringResource(R.string.about_section_support))
            Group {
                AboutRow(
                    title = stringResource(R.string.about_export_diagnostic),
                    supporting = stringResource(R.string.about_diagnostic_hint),
                    icon = painterResource(R.drawable.ic_upload),
                    trailing = if (state.busy) {
                        { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
                    } else null,
                    enabled = !state.busy,
                    onClick = onExportDiagnostic,
                )
            }
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
                    color = if (it == DiagnosticMessage.SAVED) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .padding(start = 16.dp, top = 8.dp, end = 16.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }

            GroupTitle(stringResource(R.string.about_libraries_title))
            Group {
                val libraries = stringArrayResource(R.array.about_libraries)
                libraries.forEachIndexed { i, entry ->
                    val (name, license) = splitLibrary(entry)
                    ListItem(
                        headlineContent = { Text(name) },
                        trailingContent = license?.let {
                            { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                    if (i < libraries.lastIndex) GroupDivider()
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Header(versionName: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Adaptive mipmaps cannot go through painterResource: layer the launcher's own background and foreground.
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.testTag("about_icon").clip(CircleShape).background(colorResource(R.color.ic_launcher_background)),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.about_tagline),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
            Text(
                stringResource(R.string.about_version, versionName),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun GroupTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp).semantics { heading() },
    )
}

/** A rounded tonal card that holds a few rows. */
@Composable
private fun Group(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(content = content)
    }
}

@Composable
private fun GroupDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun ExternalMark() {
    Icon(
        painterResource(R.drawable.ic_open_external),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(20.dp),
    )
}

@Composable
private fun AboutRow(
    title: String,
    supporting: String,
    icon: Painter,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
    onClickLabel: String? = null,
    enabled: Boolean = true,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(supporting) },
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.38f)
            .clickable(enabled = enabled, onClickLabel = onClickLabel, role = Role.Button, onClick = onClick),
    )
}
