package org.imunav.app.location

import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.imunav.core.diagnostics.DiagnosticOwner
import org.imunav.core.diagnostics.DiagnosticRequestGate
import org.imunav.core.location.EncodedNavigationMirror
import org.imunav.core.location.NavigationPositionSample
import org.imunav.core.location.PositionSharingAccount
import org.imunav.core.net.Http
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Account-owned HTTPS requests; only an opaque session is retained, never a queue of positions. */
internal class LivePositionRelay(private val account: PositionSharingAccount, val owner: DiagnosticOwner, private val allowed: () -> Boolean) {
    private val gate = DiagnosticRequestGate(account::owner, allowed)
    private val client = Http.client.newBuilder().callTimeout(2, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    private val activeCall = AtomicReference<Call?>()
    private var session: String? = null
    private var sequence = 0L
    private var navigationVersion = 0
    private var routeRevision: Long? = null
    var endpoint: String? = null
        private set
    var receivers: Int = 0
        private set

    fun cancel() {
        gate.cancelAll()
        activeCall.get()?.cancel()
    }

    /** A new session waits for explicit consent; no credentials can cross an HTTP connection. */
    fun start() {
        val result = request("POST", "{}")
        val id = result.optString("session")
        if (id.length != 64 || id.any { it !in "0123456789abcdef" }) throw IOException("Invalid live session")
        session = id
        routeRevision = null
        receiver(result.optJSONObject("receiver") ?: throw IOException("Missing live receiver"))
    }

    fun publish(sample: NavigationPositionSample, nowMs: Long, mirror: EncodedNavigationMirror? = null) {
        if (!sample.fresh(nowMs)) throw IOException("Stale position")
        val startedNs = System.nanoTime()
        val body = JSONObject().put("sequence", ++sequence).put("lat", sample.point.lat).put("lon", sample.point.lon)
            .put("speed_mps", sample.speedMps).put("bearing_deg", sample.bearingDeg).put("uncertainty_m", sample.uncertaintyM)
            .put("source", sample.source.name).put("elapsed_ms", sample.elapsedMs).put("utc_ms", sample.utcMs).put("age_ms", nowMs - sample.elapsedMs)
        if (navigationVersion == 1 && mirror != null) {
            val revision = mirror.publication.routeRevision
            if (revision != null && revision != routeRevision && mirror.routeJson != null) {
                request("PUT", mirror.routeJson, suffix = "/route")
                routeRevision = revision
            }
            val ageMs = nowMs - sample.elapsedMs + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs)
            if (ageMs > NavigationPositionSample.MAX_AGE_MS) throw IOException("Stale position")
            body.put("age_ms", ageMs)
            val navigation = JSONObject().put("sample", body).put("guidance", mirror.guidanceJson?.let(::JSONObject) ?: JSONObject.NULL)
            try {
                receiver(request("PUT", navigation.toString(), suffix = "/navigation"))
            } catch (failure: RelayFailure) {
                if (failure.code == 409) routeRevision = null // Expiry can clear cached geometry before the session lease ends.
                throw failure
            }
        } else {
            receiver(request("PUT", body.toString()))
        }
    }

    /** Cleanup is attempted only under the original account; server leases also expire on lost connectivity. */
    fun stop() {
        if (session != null) runCatching { request("DELETE", null, cleanup = true) }
        session = null
    }

    private fun receiver(value: JSONObject) {
        val address = value.optString("address")
        val port = value.optInt("port", -1)
        val count = value.optInt("receivers", -1)
        if (!address.matches(Regex("[0-9.]{7,15}")) || port !in 1024..65535 || count !in 0..4) throw IOException("Invalid live receiver")
        endpoint = "$address:$port"
        receivers = count
        navigationVersion = value.optInt("navigation_version", 0)
    }

    private fun request(method: String, body: String?, cleanup: Boolean = false, suffix: String = ""): JSONObject {
        val base = owner.server.toHttpUrl()
        if (base.scheme != "https") throw IOException("Live position requires HTTPS")
        val token = account.token(owner) ?: throw RelayFailure(403)
        val builder = Request.Builder().url(base.toString().trimEnd('/') + "/v1/live/session" + suffix).header("Authorization", "Bearer $token")
        session?.let { builder.header("X-Live-Session", it) }
        val call = client.newCall(builder.method(method, body?.toRequestBody("application/json".toMediaType())).build())
        activeCall.set(call)
        return try {
            if (cleanup) {
                if (account.owner() != owner) throw IOException("Account changed")
                call.execute().use(::readResponse)
            } else {
                gate.execute(owner, call, ::readResponse)
            }
        } finally {
            activeCall.compareAndSet(call, null)
        }
    }

    /** Bounded metadata responses never expose the server body to the UI or logs. */
    private fun readResponse(response: Response): JSONObject {
        if (!response.isSuccessful) throw RelayFailure(response.code)
        val source = response.body.source()
        if (source.request(MAX_RESPONSE_BYTES + 1L)) throw IOException("Oversized live response")
        val text = source.readUtf8()
        return try {
            if (text.isBlank()) JSONObject() else JSONObject(text)
        } catch (failure: JSONException) {
            throw IOException("Invalid live response", failure)
        }
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 2_048
    }
}

/** Status-only exception avoids putting server bodies, credentials or positions into UI/log messages. */
internal class RelayFailure(val code: Int) : IOException("Live relay request failed")
