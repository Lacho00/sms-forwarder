package dev.smsforwarder.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import dev.smsforwarder.R

/**
 * Frame for the top-level screens: top bar with an overflow menu, bottom bar with Home, Rules, Destinations and Settings.
 * [actions] are placed before the overflow button.
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
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                actions = {
                    actions()
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.menu_more_options))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_export)) },
                            onClick = {
                                menuOpen = false
                                onExport()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_import)) },
                            onClick = {
                                menuOpen = false
                                onImport()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.about_title)) },
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
                NavigationBarItem(
                    selected = current == Routes.MAIN,
                    onClick = { onTab(Routes.MAIN) },
                    icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_home)) },
                )
                NavigationBarItem(
                    selected = current == Routes.RULES,
                    onClick = { onTab(Routes.RULES) },
                    icon = { Icon(painterResource(R.drawable.ic_tab_rules), contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_rules)) },
                )
                NavigationBarItem(
                    selected = current == Routes.DESTINATIONS,
                    onClick = { onTab(Routes.DESTINATIONS) },
                    icon = { Icon(painterResource(R.drawable.ic_tab_destinations), contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_destinations)) },
                )
                NavigationBarItem(
                    selected = current == Routes.SETTINGS,
                    onClick = { onTab(Routes.SETTINGS) },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_settings)) },
                )
            }
        },
        content = content,
    )
}
