package dev.smsforwarder.net

import dev.smsforwarder.core.HttpMethod
import dev.smsforwarder.core.RenderedRequest
import dev.smsforwarder.core.SendOutcome
import dev.smsforwarder.core.SuccessPolicy
import dev.smsforwarder.core.classifyDelivery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** Sends one rendered request. Never logs the URL, headers or body. */
class DeliveryClient(private val http: OkHttpClient) {

    suspend fun send(req: RenderedRequest, policy: SuccessPolicy): SendOutcome {
        return try {
            val builder = Request.Builder().url(req.url)
            req.headers.forEach { (k, v) -> builder.header(k, v) }
            val payload = req.body?.toRequestBody(req.contentType?.toMediaTypeOrNull())
            when (req.method) {
                HttpMethod.GET -> builder.get()
                HttpMethod.POST -> builder.post(payload ?: EMPTY)
                HttpMethod.PUT -> builder.put(payload ?: EMPTY)
            }
            http.newCall(builder.build()).await().use { response ->
                val code = response.code
                // 2xx is accepted without reading the body; only 4xx bodies carry an error code.
                val text = if (code in 400..499) {
                    withContext(Dispatchers.IO) { response.peekBody(MAX_ERROR_BODY).string() }
                } else {
                    null
                }
                classifyDelivery(code, text, policy)
            }
        } catch (e: IOException) {
            classifyDelivery(null, null, policy)
        } catch (e: IllegalArgumentException) {
            // A malformed URL or header can never succeed until the destination is edited.
            SendOutcome.Invalid("request")
        }
    }

    private companion object {
        const val MAX_ERROR_BODY = 4096L
        val EMPTY = ByteArray(0).toRequestBody(null)
    }
}
