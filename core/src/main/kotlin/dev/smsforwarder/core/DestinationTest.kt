package dev.smsforwarder.core

enum class TestVerdict { OK, BAD_TOKEN, FAILED, UNREACHABLE }

/** `httpCode` is null for a network error or a configuration error. */
data class DestinationTestResult(val httpCode: Int?, val verdict: TestVerdict)

/**
 * STANDARD_JSON is tested with an empty object: a 400 proves url and token are good. Any other template is tested
 * with a sample SMS and must be accepted. A configuration error (no request could be built) is a failure, not a
 * network problem.
 */
fun interpretDestinationTest(template: TemplateKind, outcome: SendOutcome): TestVerdict {
    if (outcome is SendOutcome.Invalid) return TestVerdict.FAILED
    val code = outcome.httpCode
    return when {
        code == null -> TestVerdict.UNREACHABLE
        isAuthRejection(code) -> TestVerdict.BAD_TOKEN
        template == TemplateKind.STANDARD_JSON -> if (code == 400) TestVerdict.OK else TestVerdict.FAILED
        outcome is SendOutcome.Accepted -> TestVerdict.OK
        else -> TestVerdict.FAILED
    }
}
