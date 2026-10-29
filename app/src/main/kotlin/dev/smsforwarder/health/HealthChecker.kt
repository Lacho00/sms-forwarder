package dev.smsforwarder.health

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import dev.smsforwarder.data.SecretStore
import dev.smsforwarder.data.SettingsRepository
import dev.smsforwarder.data.SmsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

enum class HealthIssue {
    SMS_PERMISSION,
    NOTIFICATION_PERMISSION,
    BATTERY_OPTIMIZED,
    BACKGROUND_RESTRICTED,
    AUTOSTART_OFF,
    AUTOSTART_UNKNOWN,
    MIUI_SMS_BLOCKED,
    TOKEN_REJECTED,
    NOT_CONFIGURED,
    NO_SENDERS,
}

/** Looks at everything that can silently stop the app from forwarding and lists what is wrong. */
class HealthChecker(
    context: Context,
    private val smsRepository: SmsRepository,
    private val secretStore: SecretStore,
    private val settings: SettingsRepository,
) {
    private val context = context.applicationContext

    suspend fun check(inboxUnreadable: Boolean = false): Set<HealthIssue> {
        val issues = mutableSetOf<HealthIssue>()

        val smsGranted = granted(Manifest.permission.RECEIVE_SMS) && granted(Manifest.permission.READ_SMS)
        if (!smsGranted) issues += HealthIssue.SMS_PERMISSION
        if (inboxUnreadable && smsGranted) issues += HealthIssue.MIUI_SMS_BLOCKED

        val notifications = context.getSystemService(NotificationManager::class.java)
        // POST_NOTIFICATIONS is a runtime permission only from Android 13; before that it reads as denied.
        val postGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            granted(Manifest.permission.POST_NOTIFICATIONS)
        if (!postGranted || !notifications.areNotificationsEnabled()) {
            issues += HealthIssue.NOTIFICATION_PERMISSION
        }

        val power = context.getSystemService(PowerManager::class.java)
        if (!power.isIgnoringBatteryOptimizations(context.packageName)) issues += HealthIssue.BATTERY_OPTIMIZED

        val activityManager = context.getSystemService(ActivityManager::class.java)
        if (activityManager.isBackgroundRestricted) issues += HealthIssue.BACKGROUND_RESTRICTED

        when (MiuiIntents.autostartState(context)) {
            false -> issues += HealthIssue.AUTOSTART_OFF
            null -> issues += HealthIssue.AUTOSTART_UNKNOWN
            true -> Unit
        }

        if (smsRepository.idsWithError401().isNotEmpty()) issues += HealthIssue.TOKEN_REJECTED
        if (settings.senders.first().isEmpty()) issues += HealthIssue.NO_SENDERS

        // A Keystore hiccup is not "not configured": skip the check instead of raising a false alarm.
        try {
            if (secretStore.get() == null) issues += HealthIssue.NOT_CONFIGURED
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // ignored on purpose
        }
        return issues
    }

    private fun granted(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
