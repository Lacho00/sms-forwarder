package dev.smsforwarder.notify

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import dev.smsforwarder.R
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

    @Test fun tokenRejectedIsOneNotificationPerDestination() {
        val notifier = Notifier(context, utc)
        notifier.tokenRejected(1L, 1)
        notifier.tokenRejected(1L, 3)
        notifier.tokenRejected(2L, 5)
        val all = shadowOf(manager).allNotifications
        assertEquals(2, all.size)
        val first = shadowOf(manager).getNotification(Notifier.TOKEN_TAG, 1)
        assertNotNull(first)
        assertNotNull(shadowOf(manager).getNotification(Notifier.TOKEN_TAG, 2))
        assertTrue(text(first).contains(context.getString(R.string.notif_token_rejected_text, 3)))
    }

    @Test fun legacyUntaggedTokenAlertIsCancelled() {
        val notifier = Notifier(context, utc)
        manager.notify(
            Notifier.LEGACY_ID_TOKEN_REJECTED,
            Notification.Builder(context, Notifier.CHANNEL_ID).setSmallIcon(android.R.drawable.stat_notify_error).build(),
        )
        notifier.tokenRejected(1L, 1)
        notifier.cancelLegacyTokenRejected()
        assertEquals(null, shadowOf(manager).getNotification(null, Notifier.LEGACY_ID_TOKEN_REJECTED))
        assertNotNull(shadowOf(manager).getNotification(Notifier.TOKEN_TAG, 1))
    }

    @Test fun recoveredTextNamesSenderAndTime() {
        val at = LocalDateTime.of(2026, 10, 29, 11, 42).toInstant(ZoneOffset.UTC).toEpochMilli()
        Notifier(context, utc).recovered(listOf(entity(1, at)))
        val shown = text(shadowOf(manager).getNotification(Notifier.ID_RECOVERED))
        assertTrue(shown, shown.contains(context.getString(R.string.notif_recovered_one, "OPERADOR", "11:42")))
    }

    @Test fun rejectedKeepsTheSameTextAsTheOldRejectedNotice() {
        val item = entity(5, 1_800_000_000_000L).copy(status = SmsStatus.ERROR, lastHttpCode = 400)
        Notifier(context, utc).rejected(item)
        val n = shadowOf(manager).getNotification(Notifier.STUCK_TAG, 5)
        assertNotNull(n)
        val shown = text(n)
        assertTrue(shown, shown.contains(context.getString(R.string.notif_stuck_title)))
        assertTrue(shown, shown.contains(context.getString(R.string.notif_stuck_rejected, "OPERADOR", "08:00", 400)))
    }
}
