package dev.smsforwarder.ui.onboarding

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.smsforwarder.R
import dev.smsforwarder.core.ConnectionTestResult
import dev.smsforwarder.health.MiuiIntents
import dev.smsforwarder.ui.recovery.RecoveryList
import dev.smsforwarder.ui.recovery.RecoveryViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Opens a system screen; vendor screens can be missing or refuse third-party apps. */
internal fun startSafely(context: Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        // Nothing to open on this phone.
    } catch (e: SecurityException) {
        // Some vendor screens refuse third-party callers.
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun isGranted(context: Context, permission: String): Boolean =
    context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

@Composable
private fun stepTitle(step: OnboardingStep): String = stringResource(
    when (step) {
        OnboardingStep.CUTOVER -> R.string.onboarding_step_cutover
        OnboardingStep.PERMISSIONS -> R.string.onboarding_step_permissions
        OnboardingStep.BATTERY -> R.string.onboarding_step_battery
        OnboardingStep.MIUI -> R.string.onboarding_step_miui
        OnboardingStep.SENDERS -> R.string.onboarding_step_senders
        OnboardingStep.CONNECTION -> R.string.onboarding_step_connection
        OnboardingStep.REMINDER -> R.string.onboarding_step_reminder
        OnboardingStep.RECOVERY -> R.string.onboarding_step_recovery
    },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    vm: OnboardingViewModel,
    recoveryVm: RecoveryViewModel,
    onFinished: () -> Unit,
) {
    val state by vm.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { vm.load() }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(
                    R.string.onboarding_progress,
                    state.step.ordinal + 1,
                    OnboardingStep.entries.size,
                    stepTitle(state.step),
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            if (state.step == OnboardingStep.RECOVERY) {
                RecoveryStep(recoveryVm) {
                    scope.launch {
                        vm.setFinishError(null)
                        try {
                            recoveryVm.sendSelected()
                            vm.finish(recoveryVm.loadedAt)
                            onFinished()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            vm.setFinishError(R.string.onboarding_finish_error)
                        }
                    }
                }
                state.finishError?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            } else {
                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when (state.step) {
                        OnboardingStep.CUTOVER -> CutoverStep(state.cutoverConfirmed, vm::confirmCutover)
                        OnboardingStep.PERMISSIONS -> PermissionsStep(state, vm)
                        OnboardingStep.BATTERY -> BatteryStep()
                        OnboardingStep.MIUI -> MiuiStep()
                        OnboardingStep.SENDERS -> SendersStep(
                            senders = state.senders,
                            onAdd = { scope.launch { vm.addSender(it) } },
                            onRemove = { scope.launch { vm.removeSender(it) } },
                        )
                        OnboardingStep.CONNECTION -> ConnectionStep(state, vm) { scope.launch { vm.testConnection() } }
                        OnboardingStep.REMINDER -> Text(stringResource(R.string.onboarding_reminder))
                        OnboardingStep.RECOVERY -> Unit
                    }
                }
                Button(
                    onClick = { scope.launch { vm.next() } },
                    enabled = state.canContinue,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.onboarding_continue)) }
            }
        }
    }
}

@Composable
private fun CutoverStep(confirmed: Boolean, onConfirm: (Boolean) -> Unit) {
    Text(stringResource(R.string.onboarding_cutover))
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onConfirm(!confirmed) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = confirmed, onCheckedChange = onConfirm)
        Text(stringResource(R.string.onboarding_cutover_done), modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun PermissionsStep(state: OnboardingUiState, vm: OnboardingViewModel) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val smsPermissions = arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)
    val toRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        smsPermissions + Manifest.permission.POST_NOTIFICATIONS
    } else {
        smsPermissions
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val granted = smsPermissions.all { result[it] == true }
        // No dialog and no grant: Android blocked the request ("restricted settings").
        val noDialog = activity?.shouldShowRequestPermissionRationale(Manifest.permission.RECEIVE_SMS) == false
        vm.onPermissionResult(smsGranted = granted, restrictedHint = noDialog)
    }
    LaunchedEffect(Unit) {
        vm.onPermissionResult(smsGranted = smsPermissions.all { isGranted(context, it) }, restrictedHint = false)
    }

    Text(stringResource(R.string.onboarding_permissions_intro))
    Text(
        stringResource(
            if (state.smsPermissionGranted) {
                R.string.onboarding_permissions_granted
            } else {
                R.string.onboarding_permissions_pending
            },
        ),
        style = MaterialTheme.typography.titleSmall,
    )
    Button(onClick = { launcher.launch(toRequest) }) { Text(stringResource(R.string.onboarding_grant_permissions)) }
    OutlinedButton(
        onClick = {
            vm.onPermissionResult(smsGranted = smsPermissions.all { isGranted(context, it) }, restrictedHint = false)
        },
    ) { Text(stringResource(R.string.onboarding_check_again)) }
    if (state.restrictedHint) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.onboarding_restricted_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.onboarding_restricted_body))
                Button(
                    onClick = {
                        startSafely(
                            context,
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                        )
                    },
                ) { Text(stringResource(R.string.onboarding_open_app_info)) }
            }
        }
    }
}

@Composable
private fun BatteryStep() {
    val context = LocalContext.current
    Text(stringResource(R.string.onboarding_battery_body))
    Button(
        onClick = {
            startSafely(
                context,
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:${context.packageName}")),
            )
        },
    ) { Text(stringResource(R.string.onboarding_battery_button)) }
}

@Composable
private fun MiuiStep() {
    val context = LocalContext.current
    Text(stringResource(R.string.onboarding_miui_body))
    Button(onClick = { startSafely(context, MiuiIntents.autostart(context)) }) { Text(stringResource(R.string.onboarding_miui_autostart)) }
    OutlinedButton(onClick = { startSafely(context, MiuiIntents.permissions(context)) }) {
        Text(stringResource(R.string.onboarding_miui_permissions))
    }
    OutlinedButton(onClick = { startSafely(context, MiuiIntents.battery(context)) }) {
        Text(stringResource(R.string.onboarding_miui_battery))
    }
}

@Composable
private fun SendersStep(senders: List<String>, onAdd: (String) -> Unit, onRemove: (String) -> Unit) {
    var draft by rememberSaveable { mutableStateOf("") }
    Text(stringResource(R.string.onboarding_senders_body))
    senders.forEach { sender ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(sender)
            TextButton(onClick = { onRemove(sender) }) { Text(stringResource(R.string.action_remove)) }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text(stringResource(R.string.field_sender)) },
            singleLine = true,
            modifier = Modifier.weight(1f).testTag("field_sender"),
        )
        Button(
            onClick = {
                onAdd(draft)
                draft = ""
            },
            enabled = draft.isNotBlank(),
        ) { Text(stringResource(R.string.action_add)) }
    }
}

@Composable
private fun ConnectionStep(state: OnboardingUiState, vm: OnboardingViewModel, onTest: () -> Unit) {
    Text(stringResource(R.string.onboarding_connection_body))
    OutlinedTextField(
        value = state.url,
        onValueChange = vm::setUrl,
        label = { Text(stringResource(R.string.field_worker_url)) },
        singleLine = true,
        isError = state.urlError != null,
        supportingText = state.urlError?.let { error -> { Text(stringResource(error)) } },
        modifier = Modifier.fillMaxWidth().testTag("field_url"),
    )
    OutlinedTextField(
        value = state.token,
        onValueChange = vm::setToken,
        label = { Text(stringResource(R.string.field_token)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        isError = state.tokenError != null,
        supportingText = state.tokenError?.let { error -> { Text(stringResource(error)) } },
        modifier = Modifier.fillMaxWidth().testTag("field_token"),
    )
    Button(onClick = onTest, enabled = !state.testing) { Text(stringResource(R.string.action_test_connection)) }
    when (state.testResult) {
        ConnectionTestResult.OK -> Text(stringResource(R.string.onboarding_conn_ok))
        ConnectionTestResult.BAD_TOKEN -> Text(stringResource(R.string.onboarding_conn_bad_token))
        ConnectionTestResult.UNREACHABLE -> Text(stringResource(R.string.onboarding_conn_unreachable))
        null -> Unit
    }
}

@Composable
private fun ColumnScope.RecoveryStep(
    recoveryVm: RecoveryViewModel,
    onDone: () -> Unit,
) {
    val items by recoveryVm.candidates.collectAsState()
    val unreadable by recoveryVm.unreadable.collectAsState()
    LaunchedEffect(Unit) { recoveryVm.load() }
    RecoveryList(
        items = items,
        unreadable = unreadable,
        onToggle = recoveryVm::toggle,
        modifier = Modifier.weight(1f),
    )
    val count = items.count { it.selected }
    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
        Text(
            if (count > 0) {
                stringResource(R.string.onboarding_send_and_finish, count)
            } else {
                stringResource(R.string.onboarding_finish_without)
            },
        )
    }
}
