package dev.smsforwarder.sms

import android.content.Context
import android.content.Intent
import android.provider.Telephony
import dev.smsforwarder.AppGraph
import dev.smsforwarder.SmsForwarderApp
import dev.smsforwarder.core.SmsOrigin
import dev.smsforwarder.data.Credentials
import dev.smsforwarder.data.SecretStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.ByteArrayOutputStream
import java.io.Serializable

private class NoSecrets : SecretStore {
    override suspend fun get(): Credentials? = null
    override suspend fun set(c: Credentials) = Unit
}

@RunWith(RobolectricTestRunner::class)
class SmsReceiverTest {
    private lateinit var context: Context
    private lateinit var graph: AppGraph
    private val enqueued = mutableListOf<Long>()
    private val receiver = SmsReceiver().apply { enqueue = { _, id -> enqueued += id } }

    private val part1 = "OPERADOR: Se ha comprado un plan de datos de 2.5GB valido por 30 dias, "
    private val part2 = "se activa con el primer uso. Se desconto 360.00. Saldo actual 140.25"

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        graph = (context as SmsForwarderApp).graph
        graph.secretStore = NoSecrets()
    }

    @After fun tearDown() {
        graph.db.close()
    }

    @Test fun multipartFromFilteredSenderIsStoredOnceAndEnqueued() = runBlocking {
        graph.settings.setOnboardingDone()
        graph.settings.addSender("OPERADOR")

        receiver.process(context, smsIntent("OPERADOR", part1, part2))

        val all = graph.smsRepository.observeAll().first()
        assertEquals(1, all.size)
        assertEquals(part1 + part2, all.single().body)
        assertEquals("OPERADOR", all.single().sender)
        assertEquals(SmsOrigin.RECEIVER, all.single().origin)
        assertEquals(listOf(all.single().id), enqueued)
    }

    @Test fun otherSenderIgnored() = runBlocking {
        graph.settings.setOnboardingDone()
        graph.settings.addSender("OPERADOR")

        receiver.process(context, smsIntent("OTRO", part1, part2))

        assertTrue(graph.smsRepository.observeAll().first().isEmpty())
        assertTrue(enqueued.isEmpty())
    }

    @Test fun ignoredBeforeOnboarding() = runBlocking {
        graph.settings.addSender("OPERADOR")

        receiver.process(context, smsIntent("OPERADOR", part1, part2))

        assertTrue(graph.smsRepository.observeAll().first().isEmpty())
        assertTrue(enqueued.isEmpty())
    }

    // --- Test PDU helpers: GSM 7-bit SMS-DELIVER with an alphanumeric sender, parts joined by UDH. ---

    private fun smsIntent(sender: String, vararg parts: String): Intent {
        val pdus: Any = parts.mapIndexed { i, text -> pdu(sender, text, parts.size, i + 1) }.toTypedArray()
        return Intent(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)
            .putExtra("pdus", pdus as Serializable)
            .putExtra("format", "3gpp")
    }

    private fun pdu(sender: String, text: String, total: Int, seq: Int): ByteArray {
        val multipart = total > 1
        val udh = if (multipart) byteArrayOf(0x05, 0x00, 0x03, 0x2A, total.toByte(), seq.toByte()) else ByteArray(0)
        val headerSeptets = (udh.size * 8 + 6) / 7
        val septets = text.map { it.code }
        val userData = packSeptets(septets, headerSeptets * 7)
        udh.copyInto(userData)

        val out = ByteArrayOutputStream()
        out.write(0x00) // no SMSC
        out.write(if (multipart) 0x40 else 0x00) // SMS-DELIVER, UDH present for parts
        out.write((sender.length * 7 + 3) / 4) // sender length in semi-octets
        out.write(0xD0) // alphanumeric sender
        out.write(packSeptets(sender.map { it.code }, 0))
        out.write(0x00) // PID
        out.write(0x00) // DCS: GSM 7-bit
        out.write(byteArrayOf(0x62, 0x01, 0x71, 0x21, 0x00, 0x00, 0x00)) // timestamp
        out.write(headerSeptets + septets.size) // UDL in septets
        out.write(userData)
        return out.toByteArray()
    }

    // Only characters that are identical in ASCII and the GSM default alphabet are used.
    private fun packSeptets(septets: List<Int>, startBit: Int): ByteArray {
        val out = ByteArray((startBit + septets.size * 7 + 7) / 8)
        septets.forEachIndexed { i, s ->
            val bit = startBit + i * 7
            val idx = bit / 8
            val shift = bit % 8
            out[idx] = (out[idx].toInt() or ((s shl shift) and 0xFF)).toByte()
            if (shift > 1) out[idx + 1] = (out[idx + 1].toInt() or (s shr (8 - shift))).toByte()
        }
        return out
    }
}
