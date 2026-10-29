package dev.smsforwarder.net

import dev.smsforwarder.core.ConnectionTestResult
import dev.smsforwarder.core.IngestPayload
import dev.smsforwarder.core.SendOutcome
import dev.smsforwarder.core.classify
import dev.smsforwarder.core.interpretConnectionTest
import dev.smsforwarder.data.Credentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

fun defaultHttpClient(): OkHttpClient =
    OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

/** Talks to the Worker. Never logs the URL, the token or the SMS body. */
class IngestClient(private val http: OkHttpClient) {

    suspend fun send(c: Credentials, sender: String, body: String, receivedAt: Long): SendOutcome {
        val result = post(c, IngestPayload.encode(sender, body, receivedAt))
        return classify(result?.code, result?.body)
    }

    suspend fun testConnection(c: Credentials): ConnectionTestResult =
        interpretConnectionTest(post(c, "{}")?.code)

    private class Reply(val code: Int, val body: String?)

    /** Returns null on any network-level failure (the caller maps it to a retry). */
    private suspend fun post(c: Credentials, json: String): Reply? {
        return try {
            val request = Request.Builder()
                .url(c.url)
                .header("Authorization", "Bearer ${c.token}")
                .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            http.newCall(request).await().use { response ->
                val code = response.code
                // 2xx is accepted without reading the body; only 4xx bodies carry an error code.
                val text = if (code in 400..499) {
                    withContext(Dispatchers.IO) { response.peekBody(MAX_ERROR_BODY).string() }
                } else {
                    null
                }
                Reply(code, text)
            }
        } catch (e: IOException) {
            null
        } catch (e: IllegalArgumentException) {
            // A malformed URL can never succeed, but it must not crash the worker.
            null
        }
    }

    private companion object {
        const val MAX_ERROR_BODY = 4096L
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                cont.resume(response) { response.close() }
            }
        },
    )
}
