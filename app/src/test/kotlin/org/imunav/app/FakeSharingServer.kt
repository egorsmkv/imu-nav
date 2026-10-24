package org.imunav.app

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.Buffer
import okio.GzipSource
import okio.buffer
import org.imunav.core.cells.CellCsv
import org.imunav.core.cells.CellKey
import org.imunav.core.cells.CellTower
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Collections

/**
 * A small in-memory stand-in for the IMU Nav sharing server (accounts, privacy consents, tower
 * sync, account sync, diagnostics and live sessions), enough for the app's clients to complete
 * their real request sequences in host tests.
 */
internal class FakeSharingServer : AutoCloseable {
    private val server = MockWebServer()
    val requests: MutableList<String> = Collections.synchronizedList(ArrayList())
    val consents: MutableSet<String> = Collections.synchronizedSet(HashSet())
    val sharedTowers = ArrayList<CellTower>()
    val removedKeys = ArrayList<CellKey>()

    @Volatile var uploadedTowers = 0

    @Volatile var syncGeneration = 1L

    @Volatile var syncEnabled = true
    private val syncEntries = JSONArray()
    val debugBatches: MutableList<String> = Collections.synchronizedList(ArrayList())

    @Volatile var airAlertsEnabled = false

    @Volatile var uploadedProfiles = 0
    val uploadedTrips: MutableList<String> = Collections.synchronizedList(ArrayList())

    /** Sends a snapshot with one active region, then a change clearing it and starting another. */
    private val airAlertSocket = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val kyiv = JSONObject().put("region_id", "kyiv").put("name_uk", "Київ").put("name_en", "Kyiv")
            val lviv = JSONObject().put("region_id", "lviv").put("name_uk", "Львів").put("name_en", "Lviv")
            webSocket.send(JSONObject().put("type", "snapshot").put("sequence", 1).put("stale", false).put("active", JSONArray().put(kyiv)).toString())
            webSocket.send(JSONObject().put("type", "changes").put("sequence", 2).put("started", JSONArray().put(lviv)).put("cleared", JSONArray().put(kyiv)).toString())
        }
    }

    val url: String get() = server.url("/").toString().trimEnd('/')

    private val notice = JSONObject()
        .put("version", "2026-10").put("controller", "Test operator").put("contact", "ops@example.com")
        .put("rights_contact", "rights@example.com").put("region", "EU").put("recipients", "none").put("transfers", "none")
        .put("account_basis", "contract").put("security_basis", "legitimate interest")
        .put("notice_en", "Notice").put("notice_uk", "Повідомлення").put("notice_ru", "Уведомление")

    private fun json(value: Any, code: Int = 200) = MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(value.toString()).build()

    private fun session(token: String) = JSONObject()
        .put("access_token", token).put("refresh_token", "refresh-$token").put("expires_in", 3600).put("sync_identity", "identity-1")
        .put("account", JSONObject().put("id", ACCOUNT_ID).put("email", EMAIL).put("email_verified", true).put("sharing_enabled", true))

    private fun syncState() = JSONObject().put("version", 1).put("account_id", ACCOUNT_ID).put("notice_version", "2026-10")
        .put("enabled", syncEnabled).put("generation", syncGeneration).put("entries", syncEntries).put("conflicts", JSONArray())

    private fun body(request: RecordedRequest): String = request.body?.let { bytes ->
        val buffer = Buffer().write(bytes)
        if (request.headers["Content-Encoding"] == "gzip") GzipSource(buffer).buffer().readUtf8() else buffer.readUtf8()
    }.orEmpty()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.target.substringBefore('?')
                val method = request.method
                requests += "$method $path"
                return when {
                    path == "/v1/auth/login" || path == "/v1/auth/register" -> json(session("token-1"))

                    path == "/v1/auth/refresh" -> json(session("token-2"))

                    path == "/v1/auth/logout" || path == "/v1/auth/password-reset/request" -> json(JSONObject())

                    path == "/v1/privacy" -> json(notice)

                    path == "/v1/privacy/me" -> json(
                        JSONObject().put("notice", notice).apply { listOf("tower_upload", "diagnostics", "live_position").forEach { put(it, it in consents) } },
                    )

                    path.startsWith("/v1/privacy/consents/") -> {
                        val purpose = path.substringAfterLast('/')
                        if (method == "DELETE") consents -= purpose else consents += purpose
                        json(JSONObject())
                    }

                    path == "/v1/cells" -> {
                        val text = body(request)
                        val count = text.lines().count { line -> line.isNotBlank() && !line.startsWith("radio") }
                        uploadedTowers += count
                        json(JSONObject().put("accepted", count))
                    }

                    path == "/v1/cells.csv.gz" -> {
                        val out = ByteArrayOutputStream()
                        CellCsv.writeGzip(sharedTowers, out)
                        MockResponse.Builder().addHeader("Content-Type", "application/gzip").body(Buffer().write(out.toByteArray())).build()
                    }

                    path == "/v1/cells/removals.csv" -> MockResponse.Builder().addHeader("X-Cell-Sync-Time", "1792800000")
                        .body("radio,mcc,mnc,area,cid\n" + removedKeys.joinToString("") { "${it.radio.name},${it.mcc},${it.mnc},${it.area},${it.cid}\n" }).build()

                    path == "/v1/account-sync" && method == "GET" -> json(syncState())

                    path == "/v1/account-sync" && method == "PUT" -> {
                        val changes = JSONObject(body(request)).getJSONArray("changes")
                        synchronized(syncEntries) {
                            for (i in 0 until changes.length()) {
                                val change = changes.getJSONObject(i)
                                (0 until syncEntries.length()).firstOrNull { syncEntries.getJSONObject(it).getString("key") == change.getString("key") }
                                    ?.let { syncEntries.remove(it) }
                                syncEntries.put(change.put("revision", change.getLong("revision") + 1))
                            }
                        }
                        json(syncState())
                    }

                    path == "/v1/debug/sessions" -> json(JSONObject().put("id", "remote-1"))

                    path.startsWith("/v1/debug/sessions/") -> {
                        debugBatches += "$method $path"
                        json(JSONObject().put("next_seq", debugBatches.count { it.contains("/batches/") }))
                    }

                    path == "/v1/debug/profiles" && method == "GET" ->
                        json(JSONObject().put("notice_version", "2026-10").put("enabled", true).put("limits", JSONObject().put("upload_bytes", MAX_UPLOAD)))

                    path == "/v1/debug/profiles" -> {
                        uploadedProfiles++
                        json(JSONObject().put("id", "profile-1"))
                    }

                    path == "/v1/trips" ->
                        json(JSONObject().put("notice_version", "2026-10").put("enabled", true).put("limits", JSONObject().put("upload_bytes", MAX_UPLOAD)))

                    path.startsWith("/v1/trips/") -> {
                        uploadedTrips += path.substringAfterLast('/')
                        json(JSONObject())
                    }

                    path == "/v1/live/session" -> json(JSONObject().put("token", "live-1").put("expires_in", 600))

                    path == "/v1/air-alerts/preferences" -> {
                        if (method == "PUT") airAlertsEnabled = JSONObject(body(request)).getBoolean("enabled")
                        json(JSONObject().put("enabled", airAlertsEnabled).put("available", true))
                    }

                    path == "/v1/air-alerts/stream" -> MockResponse.Builder().webSocketUpgrade(airAlertSocket).build()

                    else -> json(JSONObject().put("error", "unknown $path"), 404)
                }
            }
        }
        server.start()
    }

    override fun close() = server.close()

    companion object {
        const val ACCOUNT_ID = 7L
        const val EMAIL = "driver@example.com"
        const val MAX_UPLOAD = 10_000_000
    }
}
