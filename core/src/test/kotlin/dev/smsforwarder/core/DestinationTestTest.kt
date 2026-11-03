package dev.smsforwarder.core

import org.junit.Assert.assertEquals
import org.junit.Test

class DestinationTestTest {
    private fun rejected(code: Int) = SendOutcome.Rejected(code, null)
    private fun retry(code: Int?) = SendOutcome.Retry(code, "x")

    @Test fun standardJsonTable() {
        val t = TemplateKind.STANDARD_JSON
        assertEquals(TestVerdict.OK, interpretDestinationTest(t, rejected(400)))
        assertEquals(TestVerdict.BAD_TOKEN, interpretDestinationTest(t, rejected(401)))
        assertEquals(TestVerdict.BAD_TOKEN, interpretDestinationTest(t, rejected(403)))
        assertEquals(TestVerdict.UNREACHABLE, interpretDestinationTest(t, retry(null)))
        assertEquals(TestVerdict.FAILED, interpretDestinationTest(t, retry(500)))
        assertEquals(TestVerdict.FAILED, interpretDestinationTest(t, retry(404)))
        assertEquals(TestVerdict.FAILED, interpretDestinationTest(t, SendOutcome.Accepted(200)))
        assertEquals(TestVerdict.FAILED, interpretDestinationTest(t, SendOutcome.Invalid("template")))
    }

    @Test fun otherTemplatesTable() {
        for (t in listOf(TemplateKind.TELEGRAM, TemplateKind.NTFY, TemplateKind.CUSTOM)) {
            assertEquals(TestVerdict.OK, interpretDestinationTest(t, SendOutcome.Accepted(200)))
            assertEquals(TestVerdict.OK, interpretDestinationTest(t, SendOutcome.Accepted(204)))
            assertEquals(TestVerdict.BAD_TOKEN, interpretDestinationTest(t, rejected(401)))
            assertEquals(TestVerdict.BAD_TOKEN, interpretDestinationTest(t, rejected(403)))
            assertEquals(TestVerdict.UNREACHABLE, interpretDestinationTest(t, retry(null)))
            assertEquals(TestVerdict.FAILED, interpretDestinationTest(t, rejected(400)))
            assertEquals(TestVerdict.FAILED, interpretDestinationTest(t, retry(500)))
            assertEquals(TestVerdict.FAILED, interpretDestinationTest(t, SendOutcome.Invalid("template")))
        }
    }
}
