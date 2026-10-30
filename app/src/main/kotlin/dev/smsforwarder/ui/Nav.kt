package dev.smsforwarder.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.smsforwarder.health.HealthIssue
import dev.smsforwarder.ui.detail.DetailScreen
import dev.smsforwarder.ui.detail.DetailViewModel
import dev.smsforwarder.ui.main.MainScreen
import dev.smsforwarder.ui.main.MainViewModel

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
        current == Routes.SETTINGS -> PlaceholderScreen("Ajustes", onBack = back)
        current == Routes.RECOVERY -> PlaceholderScreen("Recuperación manual", onBack = back)
        else -> OnboardingPlaceholder(onContinue = { stack = Routes.MAIN })
    }
}

/** Shown while the stored "onboarding done" flag is still being read. */
@Composable
fun LoadingScreen() {
    Text("SMS Forwarder", modifier = Modifier.padding(16.dp))
}

// Task 14 replaces the three placeholders below with the real screens.
@Composable
private fun OnboardingPlaceholder(onContinue: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("SMS Forwarder")
        Text("Configuración inicial (pendiente)")
        Button(onClick = onContinue) { Text("Ir a la pantalla principal") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaceholderScreen(title: String, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { TextButton(onClick = onBack) { Text("Atrás") } },
            )
        },
    ) { padding ->
        Text("Pendiente", modifier = Modifier.padding(padding).padding(16.dp))
    }
}
