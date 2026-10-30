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
import androidx.compose.ui.unit.dp
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

    val detailId = Routes.detailId(current)
    when {
        current == Routes.MAIN -> {
            val state by mainViewModel.state.collectAsState()
            MainScreen(
                state = state,
                onOpenIssue = { issue ->
                    if (issue in IN_APP_ISSUES) navigate(Routes.SETTINGS) else onOpenSystemIssue(issue)
                },
                onSweepNow = onSweepNow,
                onOpen = { id -> navigate(Routes.detail(id)) },
                onSettings = { navigate(Routes.SETTINGS) },
            )
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
                onBack = back,
            )
        }
        current == Routes.RECOVERY -> {
            val items by recoveryViewModel.candidates.collectAsState()
            val unreadable by recoveryViewModel.unreadable.collectAsState()
            var result by remember { mutableStateOf<String?>(null) }
            val scope = rememberCoroutineScope()
            LaunchedEffect(Unit) { recoveryViewModel.load() }
            RecoveryScreen(
                items = items,
                unreadable = unreadable,
                resultMessage = result,
                onToggle = recoveryViewModel::toggle,
                onSend = {
                    scope.launch {
                        val sent = recoveryViewModel.sendSelected()
                        result = "$sent SMS puestos en cola para enviar"
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
    Text("SMS Forwarder", modifier = Modifier.padding(16.dp))
}
