package org.imunav.app.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.imunav.core.net.Http
import org.imunav.core.net.HttpException
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Uses the shared HTTP stack and cancels in-flight requests when the account changes. */
internal class SyncApi(private val url: String, private val token: String) {
    suspend fun request(path: String = "/v1/account-sync", method: String = "GET", body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            val request = Request.Builder().url(url + path).header("Authorization", "Bearer $token")
                .method(method, body?.toString()?.toRequestBody("application/json".toMediaType())).build()
            val call = Http.client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            val bytes = it.body.source().readByteArray(MAX_RESPONSE_BYTES + 1L)
                            if (bytes.size > MAX_RESPONSE_BYTES) throw IOException("Account response too large")
                            if (!it.isSuccessful) throw HttpException(it.code, "Account synchronization failed")
                            if (bytes.isEmpty()) JSONObject() else JSONObject(bytes.toString(Charsets.UTF_8))
                        }
                    }
                    if (continuation.isActive) result.fold(continuation::resume, continuation::resumeWithException)
                }
            })
        }
    }
    private companion object {
        const val MAX_RESPONSE_BYTES = 32 * 1024 * 1024
    }
}
