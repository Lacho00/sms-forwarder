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
import dev.smsforwarder.ui.about.AboutViewModel
import dev.smsforwarder.ui.destinations.DestinationEditorViewModel
import dev.smsforwarder.ui.destinations.DestinationsViewModel
import dev.smsforwarder.ui.destinations.templateLabelRes
import dev.smsforwarder.ui.detail.DetailViewModel
import dev.smsforwarder.sms.ContentInboxReader
import dev.smsforwarder.ui.main.MainViewModel
import dev.smsforwarder.ui.onboarding.OnboardingViewModel
import dev.smsforwarder.ui.recovery.RecoveryViewModel
import dev.smsforwarder.ui.rules.RuleEditorViewModel
import dev.smsforwarder.ui.rules.RulesViewModel
import dev.smsforwarder.ui.settings.SettingsViewModel
import dev.smsforwarder.ui.theme.SmsForwarderTheme
import dev.smsforwarder.ui.theme.ThemeMode
import dev.smsforwarder.ui.transfer.TransferViewModel
import dev.smsforwarder.work.WorkScheduler

class MainActivity : ComponentActivity() {
    private lateinit var graph: AppGraph
    private lateinit var mainViewModel: MainViewModel
    private lateinit var onboardingViewModel: OnboardingViewModel
    private lateinit var recoveryViewModel: RecoveryViewModel
    private lateinit var settingsViewModel: SettingsViewModel
    private lateinit var destinationsViewModel: DestinationsViewModel
    private lateinit var rulesViewModel: RulesViewModel
    private lateinit var transferViewModel: TransferViewModel
    private lateinit var aboutViewModel: AboutViewModel

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
            MainViewModel.Factory(repository, settings, g.healthChecker, g.destinationRepository),
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
                g.engineLog,
            ) { id -> WorkScheduler.kickSend(app, id) },
        )[RecoveryViewModel::class.java]
        settingsViewModel = ViewModelProvider(
            this,
            SettingsViewModel.Factory(
                settings,
                onSweepIntervalChanged = { interval ->
                    WorkScheduler.schedulePeriodicSweep(app, interval, update = true)
                },
            ),
        )[SettingsViewModel::class.java]
        destinationsViewModel = ViewModelProvider(
            this,
            DestinationsViewModel.Factory(g.destinationRepository) { ids -> WorkScheduler.kickSends(app, ids) },
        )[DestinationsViewModel::class.java]
        rulesViewModel = ViewModelProvider(
            this,
            RulesViewModel.Factory(g.ruleRepository),
        )[RulesViewModel::class.java]
        val versionName = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull().orEmpty()
        transferViewModel = ViewModelProvider(
            this,
            TransferViewModel.Factory(g.configTransfer, ContentResolverDocumentIo(app.contentResolver), versionName, g.settings.importFailed),
        )[TransferViewModel::class.java]
        aboutViewModel = ViewModelProvider(
            this,
            AboutViewModel.Factory(g.diagnostics, ContentResolverDocumentIo(app.contentResolver)),
        )[AboutViewModel::class.java]
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
                        destinationsViewModel = destinationsViewModel,
                        destinationEditorViewModel = this::destinationEditorViewModel,
                        rulesViewModel = rulesViewModel,
                        ruleEditorViewModel = this::ruleEditorViewModel,
                        onOpenSystemIssue = this::openSystemIssue,
                        onSweepNow = { WorkScheduler.sweepNow(applicationContext) },
                        transferViewModel = transferViewModel,
                        aboutViewModel = aboutViewModel,
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
                destinations = g.destinationRepository.observeLive(),
            ),
        ).get("detail-$id", DetailViewModel::class.java)
    }

    private fun destinationEditorViewModel(target: DestinationTarget): DestinationEditorViewModel {
        // Locals only: the factory lambdas must not capture this activity. Default names are read when needed,
        // in the language saved at that moment.
        val app = applicationContext
        val g = graph
        val id = (target as? DestinationTarget.Edit)?.id
        return ViewModelProvider(
            this,
            DestinationEditorViewModel.Factory(
                id,
                g.destinationRepository,
                g.destinationTester,
                enqueue = { ids -> WorkScheduler.kickSends(app, ids) },
                defaultName = { kind -> Localized.wrap(app, Localized.savedTag(app)).getString(templateLabelRes(kind)) },
            ),
        ).get("destination-${id ?: "new"}", DestinationEditorViewModel::class.java)
    }

    private fun ruleEditorViewModel(target: RuleTarget): RuleEditorViewModel {
        // Locals only: the factory lambdas must not capture this activity.
        val g = graph
        val id = (target as? RuleTarget.Edit)?.id
        return ViewModelProvider(
            this,
            RuleEditorViewModel.Factory(id, g.ruleRepository, g.destinationRepository),
        ).get("rule-${id ?: "new"}", RuleEditorViewModel::class.java)
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
