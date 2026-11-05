package dev.smsforwarder.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import dev.smsforwarder.core.StatusFilter
import dev.smsforwarder.health.HealthIssue
import dev.smsforwarder.ui.about.AboutScreen
import dev.smsforwarder.ui.about.AboutViewModel
import dev.smsforwarder.ui.detail.DetailScreen
import dev.smsforwarder.ui.detail.DetailViewModel
import dev.smsforwarder.ui.destinations.DestinationEditorScreen
import dev.smsforwarder.ui.destinations.DestinationEditorViewModel
import dev.smsforwarder.ui.destinations.DestinationsScreen
import dev.smsforwarder.ui.destinations.DestinationsViewModel
import dev.smsforwarder.ui.main.MainScreen
import dev.smsforwarder.ui.main.MainSearchField
import dev.smsforwarder.ui.main.MainViewModel
import dev.smsforwarder.ui.onboarding.OnboardingScreen
import dev.smsforwarder.ui.onboarding.OnboardingViewModel
import dev.smsforwarder.ui.recovery.RecoveryScreen
import dev.smsforwarder.ui.recovery.RecoveryViewModel
import dev.smsforwarder.ui.rules.RuleEditorScreen
import dev.smsforwarder.ui.rules.RuleEditorViewModel
import dev.smsforwarder.ui.rules.RulesScreen
import dev.smsforwarder.ui.rules.RulesViewModel
import dev.smsforwarder.ui.transfer.TransferDialogs
import dev.smsforwarder.ui.transfer.TransferUi
import dev.smsforwarder.ui.transfer.TransferViewModel
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

    /** `rule/new` to create, `rule/<id>` to edit. */
    fun rule(id: Long?): String = if (id == null) "rule/new" else "rule/$id"

    /** The editor target of a `rule/...` route, or null if [route] is another route. */
    fun ruleTarget(route: String): RuleTarget? {
        if (!route.startsWith("rule/")) return null
        val rest = route.removePrefix("rule/")
        if (rest == "new") return RuleTarget.New
        return rest.toLongOrNull()?.let { RuleTarget.Edit(it) }
    }

    /** Returns the id of a `detail/{id}` route, or null if [route] is another route. */
    fun detailId(route: String): Long? =
        if (route.startsWith("detail/")) route.removePrefix("detail/").toLongOrNull() else null
}

sealed interface DestinationTarget {
    data object New : DestinationTarget
    data class Edit(val id: Long) : DestinationTarget
}

sealed interface RuleTarget {
    data object New : RuleTarget
    data class Edit(val id: Long) : RuleTarget
}

/** Where a warning is fixed: an in-app tab, or null for a system screen. */
internal fun issueTarget(issue: HealthIssue): String? = when (issue) {
    HealthIssue.TOKEN_REJECTED, HealthIssue.NOT_CONFIGURED -> Routes.DESTINATIONS
    HealthIssue.NO_RULES -> Routes.RULES
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
    rulesViewModel: RulesViewModel,
    ruleEditorViewModel: (RuleTarget) -> RuleEditorViewModel,
    onOpenSystemIssue: (HealthIssue) -> Unit,
    onSweepNow: () -> Unit,
    transferViewModel: TransferViewModel,
    aboutViewModel: AboutViewModel,
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

    // The picker is launched only from the Pick* states; pickerLaunched() moves on so a recomposition cannot repeat it.
    val transferState by transferViewModel.state.collectAsState()
    val transferBlocked by transferViewModel.blocked.collectAsState()
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {
        transferViewModel.onExportFile(it)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        transferViewModel.onImportFile(it)
    }
    LaunchedEffect(transferState) {
        when (val s = transferState) {
            is TransferUi.PickExportFile -> {
                try {
                    exportLauncher.launch(s.fileName)
                    transferViewModel.pickerLaunched()
                } catch (_: ActivityNotFoundException) {
                    transferViewModel.pickerUnavailable()
                }
            }
            TransferUi.PickImportFile -> {
                try {
                    importLauncher.launch(arrayOf("application/json", "text/*", "application/octet-stream"))
                    transferViewModel.pickerLaunched()
                } catch (_: ActivityNotFoundException) {
                    transferViewModel.pickerUnavailable()
                }
            }
            else -> Unit
        }
    }

    val aboutState by aboutViewModel.state.collectAsState()
    val diagnosticLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) {
        aboutViewModel.onFile(it)
    }
    LaunchedEffect(aboutState.pickFile) {
        aboutState.pickFile?.let {
            try {
                diagnosticLauncher.launch(it)
                aboutViewModel.pickerLaunched()
            } catch (_: ActivityNotFoundException) {
                aboutViewModel.pickerUnavailable()
            }
        }
    }

    val detailId = Routes.detailId(current)
    val destinationTarget = Routes.destinationTarget(current)
    val ruleTarget = Routes.ruleTarget(current)
    when {
        current == Routes.MAIN -> {
            val state by mainViewModel.state.collectAsState()
            AppShell(
                current = current,
                title = stringResource(R.string.app_name),
                onTab = onTab,
                onExport = transferViewModel::startExport,
                onImport = transferViewModel::startImport,
                onAbout = { navigate(Routes.ABOUT) },
                transferEnabled = !transferBlocked,
                titleContent = if (searchOpen) {
                    { MainSearchField(state.query, mainViewModel::setQuery) }
                } else {
                    null
                },
                navigationIcon = {
                    if (searchOpen) {
                        IconButton(onClick = {
                            // Closing the field also clears the query, so a hidden search never filters the list.
                            mainViewModel.setQuery("")
                            searchOpen = false
                        }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.main_search_close),
                            )
                        }
                    }
                },
                actions = {
                    if (!searchOpen) {
                        IconButton(onClick = { searchOpen = true }) {
                            Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.main_search_open))
                        }
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
                    onFilterChange = mainViewModel::setFilter,
                    onDestinationFilterChange = mainViewModel::setDestinationFilter,
                    onClearFilters = {
                        mainViewModel.setQuery("")
                        mainViewModel.setFilter(StatusFilter.ALL)
                        mainViewModel.setDestinationFilter(null)
                    },
                )
            }
        }
        detailId != null -> {
            val vm = remember(detailId) { detailViewModel(detailId) }
            val state by vm.state.collectAsState()
            DetailScreen(
                state = state,
                onRetry = { vm.retry() },
                onResendTo = { vm.resendTo(it) },
                onDelete = {
                    vm.delete()
                    back()
                },
                onBack = back,
            )
        }
        current == Routes.SETTINGS -> {
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
                onExport = transferViewModel::startExport,
                onImport = transferViewModel::startImport,
                onAbout = { navigate(Routes.ABOUT) },
                transferEnabled = !transferBlocked,
            ) { padding ->
                SettingsScreen(
                    prefs = prefs,
                    actions = actions,
                    onOpenRecovery = { navigate(Routes.RECOVERY) },
                    onOpenSystemIssue = onOpenSystemIssue,
                    padding = padding,
                )
            }
        }
        current == Routes.RULES -> {
            val rows by rulesViewModel.rows.collectAsState()
            AppShell(
                current = current,
                title = stringResource(R.string.tab_rules),
                onTab = onTab,
                onExport = transferViewModel::startExport,
                onImport = transferViewModel::startImport,
                onAbout = { navigate(Routes.ABOUT) },
                transferEnabled = !transferBlocked,
            ) { padding ->
                RulesScreen(
                    rows = rows,
                    onOpen = { id -> navigate(Routes.rule(id)) },
                    onAdd = { navigate(Routes.rule(null)) },
                    onToggle = rulesViewModel::setEnabled,
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
                onExport = transferViewModel::startExport,
                onImport = transferViewModel::startImport,
                onAbout = { navigate(Routes.ABOUT) },
                transferEnabled = !transferBlocked,
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
        ruleTarget != null -> {
            val vm = remember(ruleTarget) { ruleEditorViewModel(ruleTarget) }
            val state by vm.state.collectAsState()
            // Same as the destination editor: fresh on every open, nothing kept once left.
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
            RuleEditorScreen(state = state, vm = vm, onBack = leave)
        }
        current.startsWith("rule/") -> {
            // A route that names no rule: go back instead of falling through to onboarding.
            LaunchedEffect(current) { if (routes.size > 1) back() else stack = Routes.MAIN }
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
                state = aboutState,
                onExportDiagnostic = aboutViewModel::startExport,
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
    TransferDialogs(
        state = transferState,
        onIncludeSecrets = transferViewModel::setIncludeSecrets,
        onConfirmExport = transferViewModel::confirmExport,
        onConfirmImport = transferViewModel::confirmImport,
        onDismiss = transferViewModel::dismiss,
    )
}

/** Shown while the stored "onboarding done" flag is still being read. */
@Composable
fun LoadingScreen() {
    Text(stringResource(R.string.app_name), modifier = Modifier.padding(16.dp))
}
