package dev.smsforwarder.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.smsforwarder.core.SmsStatus
import dev.smsforwarder.data.SmsEntity
import dev.smsforwarder.health.HealthIssue
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
        val latest = items.maxBy { it.receivedAt }
        val text = if (items.size == 1) {
            "Recuperado 1 SMS de ${latest.sender} de las ${time(latest.receivedAt)}. " +
                "El receptor no se ejecutó: revisa Inicio automático."
        } else {
            "Recuperados ${items.size} SMS (el último de ${latest.sender} de las ${time(latest.receivedAt)}). " +
                "El receptor no se ejecutó: revisa Inicio automático."
        }
        post(ID_RECOVERED, "SMS recuperados", text)
    }

    fun stuck(item: SmsEntity) {
        val state = if (item.status == SmsStatus.ERROR) {
            "rechazado (Error ${item.lastHttpCode})"
        } else {
            "sin enviar (${item.attempts} intentos)"
        }
        post(
            ID_STUCK_BASE + item.id.toInt(),
            "SMS atascado",
            "El SMS de ${item.sender} de las ${time(item.receivedAt)} está $state.",
        )
    }

    /** AUTOSTART_UNKNOWN is shown in the app only, so it never raises a notification. */
    fun health(issues: Set<HealthIssue>) {
        val notifiable = issues.filter { it != HealthIssue.AUTOSTART_UNKNOWN }.sorted()
        if (notifiable.isEmpty()) {
            manager.cancel(ID_HEALTH)
            return
        }
        post(ID_HEALTH, "Revisa la configuración", notifiable.joinToString(" ") { describe(it) })
    }

    fun tokenRejected(count: Int) {
        post(ID_TOKEN_REJECTED, "Token rechazado", "Token rechazado: $count SMS esperando")
    }

    private fun describe(issue: HealthIssue): String = when (issue) {
        HealthIssue.SMS_PERMISSION -> "Falta el permiso de SMS."
        HealthIssue.NOTIFICATION_PERMISSION -> "Las notificaciones están desactivadas."
        HealthIssue.BATTERY_OPTIMIZED -> "La batería está optimizada para esta app."
        HealthIssue.BACKGROUND_RESTRICTED -> "El segundo plano está restringido."
        HealthIssue.AUTOSTART_OFF -> "Inicio automático desactivado."
        HealthIssue.AUTOSTART_UNKNOWN -> "Inicio automático no comprobable."
        HealthIssue.MIUI_SMS_BLOCKED -> "MIUI bloquea la lectura de SMS."
        HealthIssue.TOKEN_REJECTED -> "El token fue rechazado."
        HealthIssue.NOT_CONFIGURED -> "Falta configurar URL y token."
        HealthIssue.NO_SENDERS -> "No hay remitentes configurados."
    }

    private fun time(epochMs: Long): String =
        timeFormat.format(Instant.ofEpochMilli(epochMs).atZone(zone))

    private fun post(id: Int, title: String, text: String) {
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
        manager.notify(id, notification)
    }

    companion object {
        const val CHANNEL_ID = "alerts"
        const val ID_RECOVERED = 1001
        const val ID_STUCK_BASE = 2000
        const val ID_HEALTH = 3001
        const val ID_TOKEN_REJECTED = 3002

        /** Creating an existing channel is a no-op, so this is safe to call from several places. */
        fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Avisos", NotificationManager.IMPORTANCE_HIGH),
            )
        }
    }
}
