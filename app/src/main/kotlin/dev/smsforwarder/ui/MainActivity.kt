package dev.smsforwarder.ui

import android.content.ActivityNotFoundException
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.ViewModelProvider
import dev.smsforwarder.AppGraph
import dev.smsforwarder.SmsForwarderApp
import dev.smsforwarder.health.HealthIssue
import dev.smsforwarder.health.MiuiIntents
import dev.smsforwarder.ui.detail.DetailViewModel
import dev.smsforwarder.sms.ContentInboxReader
import dev.smsforwarder.ui.main.MainViewModel
import dev.smsforwarder.ui.onboarding.OnboardingViewModel
import dev.smsforwarder.ui.recovery.RecoveryViewModel
import dev.smsforwarder.ui.settings.SettingsViewModel
import dev.smsforwarder.ui.theme.SmsForwarderTheme
import dev.smsforwarder.ui.theme.ThemeMode
import dev.smsforwarder.work.WorkScheduler

class MainActivity : ComponentActivity() {
    private lateinit var graph: AppGraph
    private lateinit var mainViewModel: MainViewModel
    private lateinit var onboardingViewModel: OnboardingViewModel
    private lateinit var recoveryViewModel: RecoveryViewModel
    private lateinit var settingsViewModel: SettingsViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        graph = (application as SmsForwarderApp).graph
        WorkScheduler.schedulePeriodicSweep(applicationContext)
        mainViewModel = ViewModelProvider(
            this,
            MainViewModel.Factory(graph.smsRepository, graph.settings, graph.healthChecker),
        )[MainViewModel::class.java]
        onboardingViewModel = ViewModelProvider(
            this,
            OnboardingViewModel.Factory(graph.settings, graph.clock) { graph.ingestClient.testConnection(it) },
        )[OnboardingViewModel::class.java]
        recoveryViewModel = ViewModelProvider(
            this,
            RecoveryViewModel.Factory(
                ContentInboxReader(applicationContext),
                graph.smsRepository,
                graph.settings,
                graph.clock,
            ) { id -> WorkScheduler.enqueueSend(applicationContext, id, replace = true) },
        )[RecoveryViewModel::class.java]
        settingsViewModel = ViewModelProvider(
            this,
            SettingsViewModel.Factory(
                graph.settings,
                testConnection = { graph.ingestClient.testConnection(it) },
                loadSaved = { graph.secretStore.get() },
            ),
        )[SettingsViewModel::class.java]

        setContent {
            val themeMode by graph.settings.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
            // null while the flag is being read from DataStore.
            val onboardingDone by graph.settings.onboardingDone.collectAsState(initial = null)
            SmsForwarderTheme(themeMode) {
                val done = onboardingDone
                if (done == null) {
                    LoadingScreen()
                } else {
                    AppNavHost(
                        startRoute = if (done) Routes.MAIN else Routes.ONBOARDING,
                        mainViewModel = mainViewModel,
                        detailViewModel = this::detailViewModel,
                        onboardingViewModel = onboardingViewModel,
                        recoveryViewModel = recoveryViewModel,
                        settingsViewModel = settingsViewModel,
                        onOpenSystemIssue = this::openSystemIssue,
                        onSweepNow = { WorkScheduler.sweepNow(applicationContext) },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        WorkScheduler.sweepNow(applicationContext)
        // The user may have just changed a permission in a system screen.
        mainViewModel.refreshIssues()
    }

    private fun detailViewModel(id: Long): DetailViewModel =
        ViewModelProvider(
            this,
            DetailViewModel.Factory(id, graph.smsRepository) { smsId ->
                WorkScheduler.enqueueSend(applicationContext, smsId, replace = true)
            },
        ).get("detail-$id", DetailViewModel::class.java)

    private fun openSystemIssue(issue: HealthIssue) {
        try {
            startActivity(MiuiIntents.forIssue(this, issue))
        } catch (e: ActivityNotFoundException) {
            // MiuiIntents already falls back to the app settings; nothing else to open.
        } catch (e: SecurityException) {
            // Some vendor screens refuse third-party callers.
        }
    }
}
