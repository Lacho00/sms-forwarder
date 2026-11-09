package dev.smsforwarder.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import dev.smsforwarder.R

/**
 * Frame for the top-level screens: top bar with an overflow menu, bottom bar with Home, Rules, Destinations and Settings.
 * [actions] are placed before the overflow button. [titleContent], when set, replaces the title text (Home search field).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShell(
    current: String,
    title: String,
    onTab: (String) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onAbout: () -> Unit,
    transferEnabled: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    titleContent: (@Composable () -> Unit)? = null,
    navigationIcon: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = titleContent ?: { Text(title) },
                navigationIcon = navigationIcon,
                actions = {
                    actions()
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.menu_more_options))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_export)) },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_upload), contentDescription = null) },
                            enabled = transferEnabled,
                            onClick = {
                                menuOpen = false
                                onExport()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_import)) },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_download), contentDescription = null) },
                            enabled = transferEnabled,
                            onClick = {
                                menuOpen = false
                                onImport()
                            },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.about_title)) },
                            leadingIcon = { Icon(Icons.Outlined.Info, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onAbout()
                            },
                        )
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                TabItem(current == Routes.MAIN, { onTab(Routes.MAIN) }, R.string.tab_home) { sel ->
                    painterResource(if (sel) R.drawable.ic_tab_home_filled else R.drawable.ic_tab_home)
                }
                TabItem(current == Routes.RULES, { onTab(Routes.RULES) }, R.string.tab_rules) { sel ->
                    painterResource(if (sel) R.drawable.ic_tab_rules_filled else R.drawable.ic_tab_rules)
                }
                TabItem(current == Routes.DESTINATIONS, { onTab(Routes.DESTINATIONS) }, R.string.tab_destinations) { sel ->
                    painterResource(if (sel) R.drawable.ic_tab_destinations_filled else R.drawable.ic_tab_destinations)
                }
                TabItem(current == Routes.SETTINGS, { onTab(Routes.SETTINGS) }, R.string.tab_settings) { sel ->
                    rememberVectorPainter(if (sel) Icons.Filled.Settings else Icons.Outlined.Settings)
                }
            }
        },
        content = content,
    )
}

/** A bottom-bar item whose icon is outlined when idle and filled when [selected]. */
@Composable
private fun RowScope.TabItem(selected: Boolean, onClick: () -> Unit, label: Int, icon: @Composable (Boolean) -> Painter) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = { Icon(icon(selected), contentDescription = null) },
        label = { Text(stringResource(label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
    )
}
