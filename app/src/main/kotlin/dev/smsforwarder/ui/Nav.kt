package dev.smsforwarder.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.smsforwarder.R
import dev.smsforwarder.health.HealthIssue
import dev.smsforwarder.ui.detail.DetailScreen
import dev.smsforwarder.ui.detail.DetailViewModel
import dev.smsforwarder.ui.main.MainScreen
import dev.smsforwarder.ui.main.MainViewModel
import dev.smsforwarder.ui.onboarding.OnboardingScreen
import dev.smsforwarder.ui.onboarding.OnboardingViewModel
import dev.smsforwarder.ui.recovery.RecoveryScreen
import dev.smsforwarder.ui.recovery.RecoveryViewModel
import dev.smsforwarder.ui.settings.SettingsScreen
import dev.smsforwarder.ui.settings.SettingsViewModel
import dev.smsforwarder.ui.theme.ThemeMode
import kotlinx.coroutines.launch

object Routes {
    const val ONBOARDING = "onboarding"
    const val MAIN = "main"
    const val DETAIL = "detail/{id}"
    const val SETTINGS = "settings"
    const val RECOVERY = "recovery"
    const val ABOUT = "about"

    /** Top-level screens: the only ones wrapped by [AppShell]. */
    fun isTab(route: String): Boolean = route == MAIN || route == SETTINGS

    fun detail(id: Long): String = "detail/$id"

    /** Returns the id of a `detail/{id}` route, or null if [route] is another route. */
    fun detailId(route: String): Long? =
        if (route.startsWith("detail/")) route.removePrefix("detail/").toLongOrNull() else null
}

// Warnings that are fixed inside the app instead of in a system screen.
private val IN_APP_ISSUES = setOf(
    HealthIssue.TOKEN_REJECTED,
    HealthIssue.NOT_CONFIGURED,
    HealthIssue.NO_SENDERS,
)

private const val STACK_SEPARATOR = "|"

/** Selecting a tab pins the stack to `main` or `main|settings`; it never pushes on top of what is there. */
internal fun stackForTab(tab: String): String =
    if (tab == Routes.SETTINGS) Routes.MAIN + STACK_SEPARATOR + Routes.SETTINGS else Routes.MAIN

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
    onOpenSystemIssue: (HealthIssue) -> Unit,
    onSweepNow: () -> Unit,
) {
    var stack by rememberSaveable { mutableStateOf(startRoute) }
    val routes = stack.split(STACK_SEPARATOR)
    val current = routes.last()

    val navigate: (String) -> Unit = { route -> stack = stack + STACK_SEPARATOR + route }
    val back: () -> Unit = {
        if (routes.size > 1) stack = routes.dropLast(1).joinToString(STACK_SEPARATOR)
    }
    BackHandler(enabled = routes.size > 1) { back() }

    val onTab: (String) -> Unit = { tab -> stack = stackForTab(tab) }

    val detailId = Routes.detailId(current)
    when {
        current == Routes.MAIN -> {
            val state by mainViewModel.state.collectAsState()
            AppShell(
                current = current,
                title = stringResource(R.string.app_name),
                onTab = onTab,
                onAbout = { navigate(Routes.ABOUT) },
            ) { padding ->
                MainScreen(
                    state = state,
                    onOpenIssue = { issue ->
                        if (issue in IN_APP_ISSUES) onTab(Routes.SETTINGS) else onOpenSystemIssue(issue)
                    },
                    onSweepNow = onSweepNow,
                    onOpen = { id -> navigate(Routes.detail(id)) },
                    padding = padding,
                )
            }
        }
        detailId != null -> {
            val vm = remember(detailId) { detailViewModel(detailId) }
            val state by vm.state.collectAsState()
            DetailScreen(state = state, onRetry = { vm.retry() }, onBack = back)
        }
        current == Routes.SETTINGS -> {
            val form by settingsViewModel.form.collectAsState()
            val senders by settingsViewModel.senders.collectAsState(initial = emptyList())
            val themeMode by settingsViewModel.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
            AppShell(
                current = current,
                title = stringResource(R.string.settings_title),
                onTab = onTab,
                onAbout = { navigate(Routes.ABOUT) },
            ) { padding ->
            SettingsScreen(
                form = form,
                senders = senders,
                themeMode = themeMode,
                onUrlChange = settingsViewModel::setUrl,
                onTokenChange = settingsViewModel::setToken,
                onSave = settingsViewModel::save,
                onTest = settingsViewModel::test,
                onAddSender = settingsViewModel::addSender,
                onRemoveSender = settingsViewModel::removeSender,
                onThemeChange = settingsViewModel::setTheme,
                onOpenRecovery = { navigate(Routes.RECOVERY) },
                padding = padding,
            )
            }
        }
        // Placeholder until the real About screen arrives.
        current == Routes.ABOUT -> Text(stringResource(R.string.about_title), modifier = Modifier.padding(16.dp))
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
