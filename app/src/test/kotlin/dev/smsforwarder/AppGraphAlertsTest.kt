package dev.smsforwarder

import android.app.NotificationManager
import android.content.Context
import dev.smsforwarder.core.NotifyKind
import dev.smsforwarder.core.SmsOrigin
import dev.smsforwarder.core.SmsRecord
import dev.smsforwarder.core.SmsStatus
import dev.smsforwarder.data.Credentials
import dev.smsforwarder.data.IngestResult
import dev.smsforwarder.data.SecretStore
import dev.smsforwarder.data.SmsEntity
import dev.smsforwarder.health.HealthIssue
import dev.smsforwarder.notify.Notifier
import dev.smsforwarder.sms.SweepResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class AppGraphAlertsTest {
    private lateinit var context: Context
    private lateinit var graph: AppGraph
    private lateinit var manager: NotificationManager

    private object Configured : SecretStore {
        override suspend fun get() = Credentials("https://example.invalid/ingest", "test-token")
        override suspend fun set(c: Credentials) = Unit
    }

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        graph = (context as SmsForwarderApp).graph
        graph.destinationSecretStore = dev.smsforwarder.data.FakeDestinationSecretStore()
        runBlocking {
            graph.destinationRepository.savePrimaryCredentials(Credentials("https://example.invalid/ingest", "test-token"))
        }
        manager = context.getSystemService(NotificationManager::class.java)
    }

    @After fun tearDown() {
        graph.db.close()
    }

    private fun entity() = SmsEntity(
        id = 1,
        localKey = "key-1",
        sender = "OPERADOR",
        senderNormalized = "OPERADOR",
        body = "cuerpo de prueba",
        receivedAt = 1_800_000_000_000L,
        phoneDate = null,
        subId = null,
        origin = SmsOrigin.SWEEP,
        status = SmsStatus.QUEUED,
        firstSeenAt = 1_800_000_000_000L,
    )

    private suspend fun stuckSms(): Long {
        val record = SmsRecord("OPERADOR", "cuerpo de prueba", 1_800_000_000_000L, null, null)
        val id = (graph.smsRepository.ingest(record, SmsOrigin.RECEIVER, setOf(1L)) as IngestResult.Inserted).id
        val dao = graph.db.smsDao()
        dao.update(dao.get(id)!!.copy(firstSeenAt = System.currentTimeMillis() - 7 * 3_600_000L))
        return id
    }

    @Test fun recoveredIsSilentWhenTurnedOff() = runBlocking {
        graph.settings.setNotifyEnabled(NotifyKind.RECOVERED, false)
        graph.onRecovered(listOf(entity()))
        assertNull(shadowOf(manager).getNotification(Notifier.ID_RECOVERED))
    }

    @Test fun recoveredIsPostedWhenOn() {
        graph.onRecovered(listOf(entity()))
        assertNotNull(shadowOf(manager).getNotification(Notifier.ID_RECOVERED))
    }

    @Test fun stuckIsSilentWhenTurnedOff() = runBlocking {
        val id = stuckSms()
        graph.settings.setNotifyEnabled(NotifyKind.STUCK, false)
        graph.afterSweep(SweepResult(0, false))
        assertNull(shadowOf(manager).getNotification(Notifier.STUCK_TAG, id.toInt()))
    }

    @Test fun stuckIsPostedWhenOn() = runBlocking {
        val id = stuckSms()
        graph.afterSweep(SweepResult(0, false))
        assertNotNull(shadowOf(manager).getNotification(Notifier.STUCK_TAG, id.toInt()))
    }

    @Test fun permissionsOffSilencesPermissionIssuesButNotTokenRejected() = runBlocking {
        graph.settings.setNotifyEnabled(NotifyKind.PERMISSIONS, false)
        graph.afterSweep(SweepResult(0, false))
        // Not configured / no senders are always notified, but no permission line appears.
        val text = shadowOf(manager).getNotification(Notifier.ID_HEALTH)
            ?.extras?.getCharSequence(android.app.Notification.EXTRA_TEXT).toString()
        assertEquals(false, text.contains(context.getString(R.string.notif_issue_sms_permission)))
        assertEquals(true, text.contains(context.getString(R.string.notif_issue_no_senders)))
    }

    @Test fun permissionsOffStillNotifiesTokenRejected() = runBlocking {
        val id = stuckSms()
        val dao = graph.db.smsDao()
        dao.update(dao.get(id)!!.copy(status = SmsStatus.ERROR, lastHttpCode = 401))
        val deliveries = graph.db.deliveryDao()
        for (d in deliveries.forSms(id)) {
            deliveries.update(d.copy(status = DeliveryStatus.ERROR, lastHttpCode = 401))
        }
        graph.settings.setNotifyEnabled(NotifyKind.PERMISSIONS, false)
        graph.afterSweep(SweepResult(0, false))
        val text = shadowOf(manager).getNotification(Notifier.ID_HEALTH)
            ?.extras?.getCharSequence(android.app.Notification.EXTRA_TEXT).toString()
        assertEquals(true, text.contains(context.getString(R.string.notif_issue_token_rejected)))
        assertEquals(false, text.contains(context.getString(R.string.notif_issue_sms_permission)))
    }

    @Test fun turningPermissionsBackOnDoesNotRepeatWhatWasAlreadyNoticed() = runBlocking {
        graph.settings.setNotifyEnabled(NotifyKind.PERMISSIONS, false)
        graph.afterSweep(SweepResult(0, false))
        assertEquals(true, HealthIssue.SMS_PERMISSION.name in graph.settings.lastNotifiedIssues())
        manager.cancelAll()
        graph.settings.setNotifyEnabled(NotifyKind.PERMISSIONS, true)
        graph.afterSweep(SweepResult(0, false))
        assertNull(shadowOf(manager).getNotification(Notifier.ID_HEALTH))
    }
}
