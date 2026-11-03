package dev.smsforwarder.net

import dev.smsforwarder.core.Clock
import dev.smsforwarder.core.DestinationDraft
import dev.smsforwarder.core.DestinationSpec
import dev.smsforwarder.core.DestinationTestResult
import dev.smsforwarder.core.HttpMethod
import dev.smsforwarder.core.SendOutcome
import dev.smsforwarder.core.SuccessPolicy
import dev.smsforwarder.core.TemplateKind
import dev.smsforwarder.core.Templates
import dev.smsforwarder.core.httpCode
import dev.smsforwarder.core.interpretDestinationTest
import dev.smsforwarder.core.testSmsVars
import dev.smsforwarder.data.DestinationSecretStore
import kotlinx.coroutines.CancellationException

/**
 * Sends one request built from a form draft, completing blank URL and secret headers from the stored secrets of
 * [existingId]. Does not validate (the ViewModel does) and never writes to the database or the log.
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
        val url = d.url?.trim() ?: stored?.url.orEmpty()
        val headers = d.headers.mapNotNull { h ->
            val value = h.value
                ?: stored?.headers?.firstOrNull { it.first.equals(h.name, ignoreCase = true) }?.second
                ?: (if (h.secret) null else "")
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
        val outcome = try {
            val rendered = Templates.render(spec, testSmsVars(testBody, clock.now(), device))
            // A 2xx proves nothing for the empty-object test; the policy only matters for the other templates.
            client.send(rendered, if (standard) SuccessPolicy.ANY_2XX else d.successPolicy)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SendOutcome.Invalid("template")
        }
        return DestinationTestResult(outcome.httpCode, interpretDestinationTest(d.template, outcome))
    }
}
