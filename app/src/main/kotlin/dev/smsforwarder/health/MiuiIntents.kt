package dev.smsforwarder.health

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import dev.smsforwarder.ui.MainActivity

/**
 * Shortcuts to the system screens the user has to touch. MIUI screens are not public API, so every
 * intent falls back to the app's own settings page when the MIUI activity cannot be resolved.
 */
object MiuiIntents {
    private const val SECURITY_CENTER = "com.miui.securitycenter"
    private const val POWER_KEEPER = "com.miui.powerkeeper"
    private const val OP_AUTO_START = 10008

    private fun isMiui(): Boolean =
        Build.MANUFACTURER.lowercase() in setOf("xiaomi", "redmi", "poco")

    /** true = allowed, false = blocked, null = not MIUI or it could not be determined. */
    fun autostartState(context: Context): Boolean? {
        if (!isMiui()) return null
        return try {
            val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val method = AppOpsManager::class.java.getMethod(
                "checkOpNoThrow",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java,
            )
            val mode = method.invoke(ops, OP_AUTO_START, Process.myUid(), context.packageName) as Int
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Throwable) {
            null
        }
    }

    fun autostart(context: Context): Intent = withFallback(
        context,
        Intent().setComponent(
            ComponentName(SECURITY_CENTER, "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        ),
    )

    fun permissions(context: Context): Intent = withFallback(
        context,
        Intent()
            .setComponent(
                ComponentName(SECURITY_CENTER, "com.miui.permcenter.permissions.PermissionsEditorActivity"),
            )
            .putExtra("extra_pkgname", context.packageName),
    )

    fun battery(context: Context): Intent = withFallback(
        context,
        Intent()
            .setComponent(ComponentName(POWER_KEEPER, "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"))
            .putExtra("package_name", context.packageName),
    )

    fun forIssue(context: Context, issue: HealthIssue): Intent = when (issue) {
        HealthIssue.SMS_PERMISSION, HealthIssue.MIUI_SMS_BLOCKED -> permissions(context)
        HealthIssue.NOTIFICATION_PERMISSION ->
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        HealthIssue.BATTERY_OPTIMIZED, HealthIssue.BACKGROUND_RESTRICTED -> battery(context)
        HealthIssue.AUTOSTART_OFF, HealthIssue.AUTOSTART_UNKNOWN -> autostart(context)
        HealthIssue.TOKEN_REJECTED, HealthIssue.NOT_CONFIGURED, HealthIssue.NO_SENDERS ->
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun withFallback(context: Context, intent: Intent): Intent {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) != null) return intent
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
