package dev.smsforwarder.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SmsIdentityTest {
    private val BODY =
        "OPERADOR: Se ha comprado un plan de datos de 2.5GB valido por 30 dias, " +
            "se activa con el primer uso. Se desconto 360.00. Saldo actual 140.25"

    @Test fun effectiveReceivedAtPrefersDateSent() =
        assertEquals(1000L, SmsIdentity.effectiveReceivedAt(1000L, 5000L))

    @Test fun effectiveReceivedAtFallsBackToDateWhenZeroOrNull() {
        assertEquals(5000L, SmsIdentity.effectiveReceivedAt(0L, 5000L))
        assertEquals(5000L, SmsIdentity.effectiveReceivedAt(null, 5000L))
    }

    @Test fun bodyHashIsFullSha256Hex() {
        val hash = SmsIdentity.bodyHash("abc")
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hash)
        assertNotEquals(hash, SmsIdentity.bodyHash("abd"))
    }

    @Test fun localKeyIsStableAcrossSenderFormatting() = assertEquals(
        SmsIdentity.localKey(SmsRecord("OPERADOR", BODY, 1_792_260_900_000, null, 1)),
        SmsIdentity.localKey(SmsRecord(" operador", BODY, 1_792_260_900_000, 1_792_260_901_234, 2)),
    )

    @Test fun sameSmsWithinTwoSeconds() = assertTrue(
        SmsIdentity.isSameSms(
            SmsRecord("OPERADOR", BODY, 1_792_260_900_000, null, null),
            SmsRecord("OPERADOR", BODY, 1_792_260_901_999, null, null),
        ),
    )

    @Test fun notSameBeyondWindowOrDifferentBody() {
        val a = SmsRecord("OPERADOR", BODY, 1_792_260_900_000, null, null)
        assertFalse(SmsIdentity.isSameSms(a, a.copy(receivedAt = a.receivedAt + 2_001)))
        assertFalse(SmsIdentity.isSameSms(a, a.copy(body = BODY + " ")))
        assertFalse(SmsIdentity.isSameSms(a, a.copy(sender = "BANCO")))
    }

    @Test fun joinPartsConcatenatesInOrderWithoutSeparator() =
        assertEquals("abcdef", joinParts(listOf("abc", "def")))
}
