package dev.smsforwarder.net

import dev.smsforwarder.core.Clock
import dev.smsforwarder.core.DestinationDraft
import dev.smsforwarder.core.DestinationSpec
import dev.smsforwarder.core.DestinationTestResult
import dev.smsforwarder.core.HttpMethod
import dev.smsforwarder.core.SendOutcome
import dev.smsforwarder.core.SuccessPolicy
import dev.smsforwarder.core.TemplateKind
import dev.smsforwarder.core.TestVerdict
import dev.smsforwarder.core.Templates
import dev.smsforwarder.core.httpCode
import dev.smsforwarder.core.interpretDestinationTest
import dev.smsforwarder.core.testSmsVars
import dev.smsforwarder.data.DestinationSecretStore
import kotlinx.coroutines.CancellationException

/**
 * Sends one request built from a form draft, completing blank URL and secret headers from the stored secrets of
 * [existingId]. A null or blank url or header value counts as "keep stored"; with nothing stored and no url
 * nothing is sent and the result is FAILED. Does not validate (the ViewModel does) and never writes to the database or the log.
 */
class DestinationTester(
    private val client: DeliveryClient,
    private val secrets: DestinationSecretStore,
    private val testBody: String,
    private val device: String,
    private val clock: Clock,
) {
    suspend fun test(d: DestinationDraft, existingId: Long?): DestinationTestResult {
        val stored = existingId?.let { secrets.get(it) }
        val url = d.url?.takeIf { it.isNotBlank() }?.trim() ?: stored?.url.orEmpty()
        // Nothing to send to: report a failure without a request.
        if (url.isBlank()) return DestinationTestResult(null, TestVerdict.FAILED)
        val headers = d.headers.mapNotNull { h ->
            val value = h.value?.takeIf { it.isNotBlank() }
                ?: stored?.headers?.firstOrNull { it.first.equals(h.name, ignoreCase = true) }?.second
                ?: (if (h.secret) null else h.value.orEmpty())
            value?.let { h.name to it }
        }
        val standard = d.template == TemplateKind.STANDARD_JSON
        val spec = DestinationSpec(
            method = if (standard) HttpMethod.POST else d.method,
            url = url,
            headers = headers,
            body = if (standard) "{}" else d.body.ifBlank { null },
            bodyIsJson = if (standard) true else d.bodyIsJson,
        )
        val rendered = try {
            Templates.render(spec, testSmsVars(testBody, clock.now(), device))
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: IllegalStateException) {
            null
        }
        val outcome = if (rendered == null) {
            SendOutcome.Invalid("template")
        } else {
            // A 2xx proves nothing for the empty-object test; the policy only matters for the other templates.
            client.send(rendered, if (standard) SuccessPolicy.ANY_2XX else d.successPolicy)
        }
        return DestinationTestResult(outcome.httpCode, interpretDestinationTest(d.template, outcome))
    }
}
