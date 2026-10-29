package dev.smsforwarder.notify

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import dev.smsforwarder.core.SmsOrigin
import dev.smsforwarder.core.SmsStatus
import dev.smsforwarder.data.SmsEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
class NotifierTest {
    private lateinit var context: Context
    private lateinit var manager: NotificationManager
    private val utc: ZoneId = ZoneOffset.UTC

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        manager = context.getSystemService(NotificationManager::class.java)
    }

    private fun entity(id: Long, receivedAt: Long) = SmsEntity(
        id = id,
        localKey = "key-$id",
        sender = "OPERADOR",
        senderNormalized = "OPERADOR",
        body = "cuerpo de prueba",
        receivedAt = receivedAt,
        phoneDate = null,
        subId = null,
        origin = SmsOrigin.SWEEP,
        status = SmsStatus.QUEUED,
        firstSeenAt = receivedAt,
    )

    private fun text(n: Notification): String =
        listOfNotNull(
            n.extras.getCharSequence(Notification.EXTRA_TITLE),
            n.extras.getCharSequence(Notification.EXTRA_TEXT),
            n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
        ).joinToString(" | ")

    @Test fun tokenRejectedIsSingleNotification() {
        val notifier = Notifier(context, utc)
        notifier.tokenRejected(1)
        notifier.tokenRejected(3)
        val all = shadowOf(manager).allNotifications
        assertEquals(1, all.size)
        assertNotNull(shadowOf(manager).getNotification(Notifier.ID_TOKEN_REJECTED))
        assertTrue(text(all.single()).contains("Token rechazado: 3 SMS esperando"))
    }

    @Test fun recoveredTextNamesSenderAndTime() {
        val at = LocalDateTime.of(2026, 10, 29, 11, 42).toInstant(ZoneOffset.UTC).toEpochMilli()
        Notifier(context, utc).recovered(listOf(entity(1, at)))
        val shown = text(shadowOf(manager).getNotification(Notifier.ID_RECOVERED))
        assertTrue(shown, shown.contains("Recuperado 1 SMS de OPERADOR de las 11:42"))
        assertTrue(shown, shown.contains("revisa Inicio automático"))
    }
}
