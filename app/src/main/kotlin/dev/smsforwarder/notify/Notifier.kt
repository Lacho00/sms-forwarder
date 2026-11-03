package dev.smsforwarder.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.smsforwarder.R
import dev.smsforwarder.core.SmsStatus
import dev.smsforwarder.data.SmsEntity
import dev.smsforwarder.health.HealthIssue
import dev.smsforwarder.ui.Localized
import dev.smsforwarder.ui.MainActivity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Posts the few alerts the app ever shows. Never puts SMS bodies, URLs or tokens in a notification. */
class Notifier(context: Context, private val zone: ZoneId = ZoneId.systemDefault()) {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(NotificationManager::class.java)
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

    init {
        ensureChannel(this.context)
    }

    fun recovered(items: List<SmsEntity>) {
        if (items.isEmpty()) return
        val res = localized()
        val latest = items.maxBy { it.receivedAt }
        val text = if (items.size == 1) {
            res.getString(R.string.notif_recovered_one, latest.sender, time(latest.receivedAt))
        } else {
            res.getString(R.string.notif_recovered_many, items.size, latest.sender, time(latest.receivedAt))
        }
        post(ID_RECOVERED, res.getString(R.string.notif_recovered_title), text)
    }

    /** A 400 answer: shown at once, per SMS, and not something the user can turn off. */
    fun rejected(item: SmsEntity) {
        val res = localized()
        post(
            item.id.toInt(),
            res.getString(R.string.notif_stuck_title),
            res.getString(R.string.notif_stuck_rejected, item.sender, time(item.receivedAt), item.lastHttpCode ?: 0),
            STUCK_TAG,
        )
    }

    fun stuck(item: SmsEntity) {
        val res = localized()
        val text = if (item.status == SmsStatus.ERROR) {
            res.getString(
                R.string.notif_stuck_rejected,
                item.sender,
                time(item.receivedAt),
                item.lastHttpCode ?: 0,
            )
        } else {
            res.resources.getQuantityString(
                R.plurals.notif_stuck_unsent,
                item.attempts,
                item.sender,
                time(item.receivedAt),
                item.attempts,
            )
        }
        post(
            item.id.toInt(),
            res.getString(R.string.notif_stuck_title),
            text,
            STUCK_TAG,
        )
    }

    /** AUTOSTART_UNKNOWN is shown in the app only, so it never raises a notification. */
    fun health(issues: Set<HealthIssue>) {
        val notifiable = issues.filter { it != HealthIssue.AUTOSTART_UNKNOWN }.sorted()
        if (notifiable.isEmpty()) {
            manager.cancel(ID_HEALTH)
            return
        }
        val res = localized()
        post(
            ID_HEALTH,
            res.getString(R.string.notif_health_title),
            notifiable.joinToString(" ") { res.getString(describe(it)) },
        )
    }

    /** One notification per destination: a new count replaces the previous one for the same destination. */
    fun tokenRejected(destinationId: Long, waiting: Int) {
        val res = localized()
        post(
            destinationId.toInt(),
            res.getString(R.string.notif_token_rejected_title),
            res.getString(R.string.notif_token_rejected_text, waiting),
            TOKEN_TAG,
        )
    }

    /** 1.1 posted the token alert as id 3002 with no tag; 1.2 tags it per destination, so drop the old one. */
    fun cancelLegacyTokenRejected() = manager.cancel(LEGACY_ID_TOKEN_REJECTED)

    private fun describe(issue: HealthIssue): Int = when (issue) {
        HealthIssue.SMS_PERMISSION -> R.string.notif_issue_sms_permission
        HealthIssue.NOTIFICATION_PERMISSION -> R.string.notif_issue_notification_permission
        HealthIssue.BATTERY_OPTIMIZED -> R.string.notif_issue_battery_optimized
        HealthIssue.BACKGROUND_RESTRICTED -> R.string.notif_issue_background_restricted
        HealthIssue.AUTOSTART_OFF -> R.string.notif_issue_autostart_off
        HealthIssue.AUTOSTART_UNKNOWN -> R.string.notif_issue_autostart_unknown
        HealthIssue.MIUI_SMS_BLOCKED -> R.string.notif_issue_miui_sms_blocked
        HealthIssue.TOKEN_REJECTED -> R.string.notif_issue_token_rejected
        HealthIssue.NOT_CONFIGURED -> R.string.notif_issue_not_configured
        HealthIssue.NO_RULES -> R.string.notif_issue_no_senders
        HealthIssue.MIGRATION_FAILED -> R.string.notif_issue_migration_failed
    }

    /** Re-creates the channel so its name follows the language chosen in settings. */
    fun refreshChannel() = ensureChannel(context)

    private fun localized(): Context = Localized.wrap(context, Localized.savedTag(context))

    private fun time(epochMs: Long): String =
        timeFormat.format(Instant.ofEpochMilli(epochMs).atZone(zone))

    private fun post(id: Int, title: String, text: String, tag: String? = null) {
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(tag, id, notification)
    }

    companion object {
        const val CHANNEL_ID = "alerts"
        const val ID_RECOVERED = 1001
        /** Stuck notifications use this tag with the SMS id, so they never clash with the fixed ids. */
        const val STUCK_TAG = "stuck"
        const val ID_HEALTH = 3001
        /** Untagged id the 1.1 token-rejected alert used. */
        const val LEGACY_ID_TOKEN_REJECTED = 3002
        /** Token-rejected notifications use this tag with the destination id. */
        const val TOKEN_TAG = "token"

        /** Creating an existing channel is a no-op, so this is safe to call from several places. */
        fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    Localized.wrap(context, Localized.savedTag(context)).getString(R.string.notif_channel_alerts),
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
        }
    }
}
