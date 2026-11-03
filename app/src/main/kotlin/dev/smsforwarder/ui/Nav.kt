package dev.smsforwarder.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.smsforwarder.R
import dev.smsforwarder.health.HealthIssue
import dev.smsforwarder.ui.about.AboutScreen
import dev.smsforwarder.ui.detail.DetailScreen
import dev.smsforwarder.ui.detail.DetailViewModel
import dev.smsforwarder.ui.destinations.DestinationEditorScreen
import dev.smsforwarder.ui.destinations.DestinationEditorViewModel
import dev.smsforwarder.ui.destinations.DestinationsScreen
import dev.smsforwarder.ui.destinations.DestinationsViewModel
import dev.smsforwarder.ui.main.MainScreen
import dev.smsforwarder.ui.main.MainViewModel
import dev.smsforwarder.ui.onboarding.OnboardingScreen
import dev.smsforwarder.ui.onboarding.OnboardingViewModel
import dev.smsforwarder.ui.recovery.RecoveryScreen
import dev.smsforwarder.ui.recovery.RecoveryViewModel
import dev.smsforwarder.ui.settings.SettingsActions
import dev.smsforwarder.ui.settings.SettingsScreen
import dev.smsforwarder.ui.settings.SettingsViewModel
import kotlinx.coroutines.launch

object Routes {
    const val ONBOARDING = "onboarding"
    const val MAIN = "main"
    const val DETAIL = "detail/{id}"
    const val SETTINGS = "settings"
    const val RECOVERY = "recovery"
    const val ABOUT = "about"
    const val DESTINATIONS = "destinations"
    const val RULES = "rules"

    /** Top-level screens: the only ones wrapped by [AppShell]. */
    fun isTab(route: String): Boolean = route == MAIN || route == SETTINGS || route == DESTINATIONS || route == RULES

    fun detail(id: Long): String = "detail/$id"

    /** `destination/new` to create, `destination/<id>` to edit. */
    fun destination(id: Long?): String = if (id == null) "destination/new" else "destination/$id"

    /** The editor target of a `destination/...` route, or null if [route] is another route. */
    fun destinationTarget(route: String): DestinationTarget? {
        if (!route.startsWith("destination/")) return null
        val rest = route.removePrefix("destination/")
        if (rest == "new") return DestinationTarget.New
        return rest.toLongOrNull()?.let { DestinationTarget.Edit(it) }
    }

    /** Returns the id of a `detail/{id}` route, or null if [route] is another route. */
    fun detailId(route: String): Long? =
        if (route.startsWith("detail/")) route.removePrefix("detail/").toLongOrNull() else null
}

sealed interface DestinationTarget {
    data object New : DestinationTarget
    data class Edit(val id: Long) : DestinationTarget
}

/**
 * Where a warning is fixed: an in-app tab, or null for a system screen. Until the Rules tab exists (task 8),
 * NO_RULES still points to Settings.
 */
internal fun issueTarget(issue: HealthIssue): String? = when (issue) {
    HealthIssue.TOKEN_REJECTED, HealthIssue.NOT_CONFIGURED -> Routes.DESTINATIONS
    HealthIssue.NO_RULES -> Routes.SETTINGS
    else -> null
}

private const val STACK_SEPARATOR = "|"

/** Selecting a tab pins the stack to `main` or `main|<tab>`; it never pushes on top of what is there. */
internal fun stackForTab(tab: String): String =
    if (tab == Routes.MAIN) Routes.MAIN else Routes.MAIN + STACK_SEPARATOR + tab

/**
 * Minimal navigation: the back stack is a list of route strings kept in a single saveable String.
 * Hand-rolled on purpose so the project needs no extra navigation dependency.
 */
@Composable
fun AppNavHost(
    startRoute: String,
    mainViewModel: MainViewModel,
    detailViewModel: (Long) -> DetailViewModel,
    onboardingViewModel: OnboardingViewModel,
    recoveryViewModel: RecoveryViewModel,
    settingsViewModel: SettingsViewModel,
    destinationsViewModel: DestinationsViewModel,
    destinationEditorViewModel: (DestinationTarget) -> DestinationEditorViewModel,
    onOpenSystemIssue: (HealthIssue) -> Unit,
    onSweepNow: () -> Unit,
) {
    var stack by rememberSaveable { mutableStateOf(startRoute) }
    // Kept here, not in the Home branch, so opening an SMS and coming back keeps the search field next to its query.
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    // The route whose editor was reset on open; cleared when the editor is left.
    var editorReady by rememberSaveable { mutableStateOf<String?>(null) }
    val routes = stack.split(STACK_SEPARATOR)
    val current = routes.last()

    val navigate: (String) -> Unit = { route -> stack = stack + STACK_SEPARATOR + route }
    val back: () -> Unit = {
        if (routes.size > 1) stack = routes.dropLast(1).joinToString(STACK_SEPARATOR)
    }
    BackHandler(enabled = routes.size > 1) { back() }

    val onTab: (String) -> Unit = { tab -> stack = stackForTab(tab) }

    val detailId = Routes.detailId(current)
    val destinationTarget = Routes.destinationTarget(current)
    when {
        current == Routes.MAIN -> {
            val state by mainViewModel.state.collectAsState()
            AppShell(
                current = current,
                title = stringResource(R.string.app_name),
                onTab = onTab,
                onAbout = { navigate(Routes.ABOUT) },
                actions = {
                    IconButton(onClick = {
                        // Closing the field also clears the query, so a hidden search never filters the list.
                        if (searchOpen) mainViewModel.setQuery("")
                        searchOpen = !searchOpen
                    }) {
                        Icon(
                            if (searchOpen) Icons.Filled.Close else Icons.Filled.Search,
                            contentDescription = stringResource(
                                if (searchOpen) R.string.main_search_close else R.string.main_search_open,
                            ),
                        )
                    }
                },
            ) { padding ->
                MainScreen(
                    state = state,
                    onOpenIssue = { issue ->
                        val target = issueTarget(issue)
                        if (target != null) onTab(target) else onOpenSystemIssue(issue)
                    },
                    onSweepNow = onSweepNow,
                    onOpen = { id -> navigate(Routes.detail(id)) },
                    padding = padding,
                    searchOpen = searchOpen,
                    onQueryChange = mainViewModel::setQuery,
                    onFilterChange = mainViewModel::setFilter,
                )
            }
        }
        detailId != null -> {
            val vm = remember(detailId) { detailViewModel(detailId) }
            val state by vm.state.collectAsState()
            DetailScreen(
                state = state,
                onRetry = { vm.retry() },
                onDelete = {
                    vm.delete()
                    back()
                },
                onBack = back,
            )
        }
        current == Routes.SETTINGS -> {
            val senders by settingsViewModel.senders.collectAsState(initial = emptyList())
            val prefs by settingsViewModel.prefs.collectAsState()
            val actions = remember(settingsViewModel) {
                SettingsActions(
                    setForwarding = settingsViewModel::setForwarding,
                    setSweepInterval = settingsViewModel::setSweepInterval,
                    setStuckAfter = settingsViewModel::setStuckAfter,
                    setRetention = settingsViewModel::setRetention,
                    setHideText = settingsViewModel::setHideText,
                    setNotify = settingsViewModel::setNotify,
                    setTheme = settingsViewModel::setTheme,
                    setDynamicColor = settingsViewModel::setDynamicColor,
                    setLanguage = settingsViewModel::setLanguage,
                )
            }
            AppShell(
                current = current,
                title = stringResource(R.string.settings_title),
                onTab = onTab,
                onAbout = { navigate(Routes.ABOUT) },
            ) { padding ->
                SettingsScreen(
                    senders = senders,
                    prefs = prefs,
                    actions = actions,
                    onAddSender = settingsViewModel::addSender,
                    onRemoveSender = settingsViewModel::removeSender,
                    onOpenRecovery = { navigate(Routes.RECOVERY) },
                    onOpenSystemIssue = onOpenSystemIssue,
                    padding = padding,
                )
            }
        }
        current == Routes.DESTINATIONS -> {
            val rows by destinationsViewModel.rows.collectAsState()
            AppShell(
                current = current,
                title = stringResource(R.string.tab_destinations),
                onTab = onTab,
                onAbout = { navigate(Routes.ABOUT) },
            ) { padding ->
                DestinationsScreen(
                    rows = rows,
                    onOpen = { id -> navigate(Routes.destination(id)) },
                    onAdd = { navigate(Routes.destination(null)) },
                    onToggle = destinationsViewModel::setEnabled,
                    padding = padding,
                )
            }
        }
        destinationTarget != null -> {
            val vm = remember(destinationTarget) { destinationEditorViewModel(destinationTarget) }
            val state by vm.state.collectAsState()
            // A fresh editor on every open (a rotation keeps the marker, so the form survives it), and nothing
            // typed stays in the ViewModel once the editor is left.
            LaunchedEffect(current) {
                if (editorReady != current) {
                    vm.reset()
                    editorReady = current
                }
            }
            val leave: () -> Unit = {
                vm.reset()
                editorReady = null
                back()
            }
            BackHandler { leave() }
            DestinationEditorScreen(state = state, vm = vm, onBack = leave)
        }
        current.startsWith("destination/") -> {
            // A route that names no destination: go back instead of falling through to onboarding.
            LaunchedEffect(current) { if (routes.size > 1) back() else stack = Routes.MAIN }
        }
        current == Routes.ABOUT -> {
            val context = LocalContext.current
            val versionName = remember {
                runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
            }
            AboutScreen(
                versionName = versionName,
                onOpenLink = { url ->
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: ActivityNotFoundException) {
                    }
                },
                onBack = back,
            )
        }
        current == Routes.RECOVERY -> {
            val items by recoveryViewModel.candidates.collectAsState()
            val unreadable by recoveryViewModel.unreadable.collectAsState()
            var sentCount by remember { mutableStateOf<Int?>(null) }
            val scope = rememberCoroutineScope()
            LaunchedEffect(Unit) { recoveryViewModel.load() }
            RecoveryScreen(
                items = items,
                unreadable = unreadable,
                resultMessage = sentCount?.let { pluralStringResource(R.plurals.recovery_queued_result, it, it) },
                onToggle = recoveryViewModel::toggle,
                onSend = {
                    scope.launch {
                        sentCount = recoveryViewModel.sendSelected()
                    }
                },
                onBack = back,
            )
        }
        else -> OnboardingScreen(
            vm = onboardingViewModel,
            recoveryVm = recoveryViewModel,
            onFinished = { stack = Routes.MAIN },
        )
    }
}

/** Shown while the stored "onboarding done" flag is still being read. */
@Composable
fun LoadingScreen() {
    Text(stringResource(R.string.app_name), modifier = Modifier.padding(16.dp))
}
