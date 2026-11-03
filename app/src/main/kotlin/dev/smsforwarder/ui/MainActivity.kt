package dev.smsforwarder.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.lifecycleScope
import dev.smsforwarder.core.SweepInterval
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
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

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Localized.wrap(newBase, Localized.savedTag(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        graph = (application as SmsForwarderApp).graph
        // The ViewModels outlive this activity, so their factory lambdas capture only these locals
        // and never `this` (applicationContext and graph would both be read through it).
        val app = applicationContext
        val g = graph
        val settings = g.settings
        val repository = g.smsRepository
        val clock = g.clock
        val ingestClient = g.ingestClient
        val destinations = g.destinationRepository
        // The saved period is read off the main thread; if it cannot be read, keep the default so the job survives.
        lifecycleScope.launch {
            val interval = try {
                settings.sweepIntervalNow()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SweepInterval.M15
            }
            WorkScheduler.schedulePeriodicSweep(app, interval)
        }
        mainViewModel = ViewModelProvider(
            this,
            MainViewModel.Factory(repository, settings, g.healthChecker),
        )[MainViewModel::class.java]
        onboardingViewModel = ViewModelProvider(
            this,
            OnboardingViewModel.Factory(settings, clock) { ingestClient.testConnection(it) },
        )[OnboardingViewModel::class.java]
        recoveryViewModel = ViewModelProvider(
            this,
            RecoveryViewModel.Factory(
                ContentInboxReader(app),
                repository,
                g.ruleRepository,
                app,
                clock,
                { g.legacyImport.run() },
            ) { id -> WorkScheduler.kickSend(app, id) },
        )[RecoveryViewModel::class.java]
        settingsViewModel = ViewModelProvider(
            this,
            SettingsViewModel.Factory(
                settings,
                testConnection = { ingestClient.testConnection(it) },
                loadSaved = { destinations.primaryCredentials() },
                onSweepIntervalChanged = { interval ->
                    WorkScheduler.schedulePeriodicSweep(app, interval, update = true)
                },
            ),
        )[SettingsViewModel::class.java]
        // Collected here, not in the ViewModel: after a recreation this is the activity that is alive.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                settingsViewModel.languageChanged.collect { recreate() }
            }
        }

        setContent {
            val themeMode by graph.settings.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
            // null while the flag is being read from DataStore.
            val onboardingDone by graph.settings.onboardingDone.collectAsState(initial = null)
            val dynamicColor by graph.settings.dynamicColor.collectAsState(initial = true)
            SmsForwarderTheme(themeMode, dynamicColor) {
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

    private fun detailViewModel(id: Long): DetailViewModel {
        // Locals only: the factory lambdas must not capture this activity.
        val app = applicationContext
        val g = graph
        return ViewModelProvider(
            this,
            DetailViewModel.Factory(
                id,
                g.smsRepository,
                enqueueSend = { smsId -> WorkScheduler.kickSend(app, smsId) },
                hideText = g.settings.hideText,
                cancelSend = { smsId -> WorkScheduler.cancelSend(app, smsId) },
            ),
        ).get("detail-$id", DetailViewModel::class.java)
    }

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
