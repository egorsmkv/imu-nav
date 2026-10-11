package org.imunav.app.diagnostics

import android.content.Context
import android.os.SystemClock
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.imunav.app.cells.CellManager
import org.imunav.core.diagnostics.DiagnosticCapture
import org.imunav.core.diagnostics.DiagnosticOwner
import org.imunav.core.diagnostics.DiagnosticOwnerChanged
import org.imunav.core.diagnostics.DiagnosticRequestGate
import org.imunav.core.net.Http
import org.imunav.core.net.HttpException
import org.imunav.core.record.TripEvent
import org.imunav.core.record.TripFormat
import org.imunav.core.record.recordingWeight
import org.imunav.core.util.OrderedBuffer
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Stable states let Compose present upload progress in the selected app language. */
enum class DiagnosticPhase { IDLE, WAITING, SIGN_IN, RECORDING, QUEUED, UPLOADING, UPLOADED, QUEUE_FULL, ENTRY_TOO_LARGE, RETRYING, SERVER_DISABLED, QUOTA_FULL, REMOVED }

/** Current opt-in state and the most recent delivery outcome shown in Settings. */
data class DiagnosticStatus(val enabled: Boolean, val phase: DiagnosticPhase = DiagnosticPhase.IDLE, val pendingBytes: Long = 0, val detail: String = "")

/**
 * Spools replay events and trip logs before uploading them. Sensor callbacks only enqueue work;
 * network calls use another worker so a stalled server cannot slow navigation or recording.
 */
class DevDiagnostics(private val context: Context, private val cells: CellManager) {
    private class SignInRequired : IOException()
    private val prefs = context.getSharedPreferences("dev_diagnostics", Context.MODE_PRIVATE)
    private val root = File(context.filesDir, "dev-diagnostics")
    private val ingest = Executors.newSingleThreadExecutor()
    private val sender = Executors.newSingleThreadScheduledExecutor()
    private val optedIn = AtomicBoolean(prefs.getBoolean("enabled", false))
    private val _status = MutableStateFlow(DiagnosticStatus(optedIn.get(), if (optedIn.get()) DiagnosticPhase.WAITING else DiagnosticPhase.IDLE))
    val status: StateFlow<DiagnosticStatus> = _status.asStateFlow()
    private class Session(val dir: File, val owner: DiagnosticOwner) {
        val buffer = JSONArray() // Ingest worker only.
        var bufferBytes = 0
        var nextSequence = 0
        var ready = false
        var capture: DiagnosticCapture? = null

        @Volatile var failed = false
    }
    private val admission = OrderedBuffer(ingest, MAX_PENDING_ITEMS, MAX_PENDING_BYTES)
    private val sessions = LinkedHashMap<String, Session>() // Protected by sessions lock.
    private var active: Session? = null // Protected by sessions lock.
    private val requests = DiagnosticRequestGate(cells::diagnosticOwner) { optedIn.get() && cells.diagnosticConsentGranted() }
    private var retryAtMs = 0L // Sender worker only.
    private var retryCount = 0

    init {
        cells.onDiagnosticIdentityChanged(requests::cancelAll)
        sender.scheduleWithFixedDelay(::sendPendingSafely, 2, 2, TimeUnit.SECONDS)
    }

    /** Disabling drops unsent data and stops capture; received server data stays under account controls. */
    fun setEnabled(enabled: Boolean) {
        optedIn.set(enabled)
        prefs.edit { putBoolean("enabled", enabled) }
        _status.value = DiagnosticStatus(enabled, if (enabled) DiagnosticPhase.WAITING else DiagnosticPhase.IDLE)
        if (!enabled) {
            requests.cancelAll()
            synchronized(sessions) {
                sessions.values.forEach { it.capture?.finish { } }
                sessions.clear()
                active = null
            }
            admission.reserve(1)?.submit { root.deleteRecursively() }
        }
    }

    /** Begin a session only with a persistent owner; legacy ownerless sessions remain quarantined. */
    fun startTrip(id: String, details: JSONObject) {
        if (!optedIn.get() || !cells.diagnosticConsentGranted()) return
        val owner = cells.diagnosticOwner() ?: run {
            _status.value = DiagnosticStatus(true, DiagnosticPhase.SIGN_IN)
            return
        }
        synchronized(sessions) {
            if (sessions.containsKey(id)) return
            val session = Session(File(root, id), owner)
            val capture = DiagnosticCapture.start(admission, onOverload = {
                work(session) {
                    if (readOwner(session.dir) == owner) {
                        File(session.dir, "incomplete").writeText("overload")
                        flush(session)
                    }
                }
            }) {
                work(session) {
                    val dir = session.dir
                    if (!optedIn.get() || cells.diagnosticOwner() != owner) return@work
                    if (!dir.isDirectory) {
                        check(dir.mkdirs()) { "Cannot create diagnostic session" }
                        // Persist ownership before context: interrupted creation is never uploadable as another account.
                        File(dir, "owner.json").writeText(ownerJson(owner).toString())
                        details.put("app_version", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
                        File(dir, "context.json").writeText(JSONObject().put("client_id", id).put("context", details).toString())
                        File(dir, "url.txt").writeText(owner.server)
                    } else {
                        if (readOwner(dir) != owner) {
                            session.failed = true
                            return@work
                        }
                        File(dir, "incomplete").writeText("1")
                    }
                    val nextSaved = File(dir, "next.txt").takeIf(File::isFile)?.readText()?.toIntOrNull() ?: 0
                    val nextOnDisk = (dir.listFiles()?.mapNotNull { batchSequence(it) }?.maxOrNull() ?: -1) + 1
                    session.nextSequence = maxOf(nextSaved, nextOnDisk)
                    session.ready = true
                    _status.value = DiagnosticStatus(true, DiagnosticPhase.RECORDING)
                }
            } ?: run {
                _status.value = DiagnosticStatus(true, DiagnosticPhase.QUEUE_FULL)
                return
            }
            session.capture = capture
            sessions[id] = session
            active = session
        }
    }

    /** Session IDs keep delayed replay callbacks from crossing rapid trip restarts. */
    fun onEvent(id: String, event: TripEvent) {
        val session = synchronized(sessions) { sessions[id] } ?: return
        enqueue(session, event.recordingWeight()) { append(session, "event", event.elapsedMs, TripFormat.encode(event)) }
    }

    /** Retain only bounded log payloads; encoding and redaction stay off sensor and UI threads. */
    fun onLog(line: String, elapsedMs: Long) {
        val session = synchronized(sessions) { active } ?: return
        enqueue(session, 256L + 2L * line.length) { append(session, "log", elapsedMs, redact(line)) }
    }

    private fun enqueue(session: Session, weight: Long, action: () -> Unit) {
        if (!optedIn.get() || session.failed || !cells.diagnosticConsentGranted() || cells.diagnosticOwner() != session.owner) return
        if (session.capture?.offer(weight) { work(session, action) } == false) {
            _status.value = DiagnosticStatus(true, DiagnosticPhase.QUEUE_FULL)
        }
    }

    /** Reserved close survives overload and marks omitted events without stopping navigation. */
    fun endTrip(id: String) {
        synchronized(sessions) {
            val session = sessions.remove(id) ?: return
            if (active === session) active = null
            session.capture?.finish { omitted ->
                work(session) {
                    if (readOwner(session.dir) != session.owner) return@work
                    if (omitted > 0 || session.failed) File(session.dir, "incomplete").writeText("omitted=$omitted")
                    flush(session)
                    File(session.dir, "finished").writeText("1")
                }
            }
        }
    }

    /** Disk failures release queued payloads and remain local to optional diagnostics. */
    private fun work(session: Session, action: () -> Unit) {
        runCatching(action).onFailure {
            session.failed = true
            session.capture?.fail()
            clearBuffer(session)
            runCatching { if (session.dir.isDirectory) File(session.dir, "incomplete").writeText("write_failed") }
            _status.value = DiagnosticStatus(optedIn.get(), DiagnosticPhase.QUEUE_FULL)
        }
    }

    private fun append(session: Session, kind: String, elapsedMs: Long, line: String) {
        if (!optedIn.get() || session.failed || !session.ready) return
        val entry = JSONObject().put("kind", kind).put("elapsed_ms", elapsedMs).put("line", line)
        val size = entry.toString().toByteArray().size
        if (size > MAX_ENTRY_BYTES) {
            File(session.dir, "incomplete").writeText("1")
            _status.value = DiagnosticStatus(true, DiagnosticPhase.ENTRY_TOO_LARGE)
            return
        }
        if (session.buffer.length() >= MAX_ENTRIES || session.bufferBytes + size > MAX_BATCH_BYTES / 2) flush(session)
        session.buffer.put(entry)
        session.bufferBytes += size
    }

    private fun flush(session: Session) {
        val dir = session.dir
        if (session.buffer.length() == 0) return
        val payload = JSONObject().put("entries", session.buffer).toString()
        val pending = root.walkTopDown().filter { it.isFile && it.name.endsWith(".json") }.sumOf { it.length() }
        if (pending + payload.toByteArray().size > MAX_LOCAL_BYTES) {
            File(dir, "incomplete").writeText("1")
            _status.value = DiagnosticStatus(true, DiagnosticPhase.QUEUE_FULL, pending)
            clearBuffer(session)
            return
        }
        val target = File(dir, String.format(Locale.US, "%08d.json", session.nextSequence++))
        val temporary = File(dir, target.name + ".tmp")
        temporary.writeText(payload)
        check(temporary.renameTo(target)) { "Cannot save diagnostic batch" }
        File(dir, "next.txt").writeText(session.nextSequence.toString())
        clearBuffer(session)
        _status.value = DiagnosticStatus(true, DiagnosticPhase.QUEUED, pending + target.length())
    }

    private fun clearBuffer(session: Session) {
        while (session.buffer.length() > 0) session.buffer.remove(session.buffer.length() - 1)
        session.bufferBytes = 0
    }

    private fun ownerJson(owner: DiagnosticOwner): JSONObject = JSONObject().put("server", owner.server).put("account_id", owner.accountId).put("sync_identity", owner.syncIdentity)

    private fun readOwner(dir: File): DiagnosticOwner? = runCatching {
        val file = File(dir, "owner.json")
        if (!file.isFile || file.length() > MAX_OWNER_BYTES) return null
        val json = JSONObject(file.readText())
        DiagnosticOwner(json.getString("server"), json.getLong("account_id"), json.getString("sync_identity"))
    }.getOrNull()

    private fun batchSequence(file: File): Int? = file.name.takeIf { BATCH_NAME.matches(it) }?.removeSuffix(".json")?.toIntOrNull()

    private fun sendPendingSafely() {
        if (!optedIn.get() || SystemClock.elapsedRealtime() < retryAtMs) return
        try {
            ingest.submit { synchronized(sessions) { sessions.values.toList() }.forEach { work(it) { flush(it) } } }.get()
            root.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach(::sendSession)
            retryCount = 0
        } catch (failure: IOException) {
            recordFailure(failure)
        } catch (failure: JSONException) {
            recordFailure(failure)
        } catch (failure: IllegalArgumentException) {
            recordFailure(failure)
        } catch (failure: ExecutionException) {
            recordFailure(failure.cause ?: failure)
        }
    }

    /** Show a useful error while backing off enough to avoid hammering an unavailable server. */
    private fun recordFailure(failure: Throwable) {
        retryCount = (retryCount + 1).coerceAtMost(6)
        retryAtMs = SystemClock.elapsedRealtime() + (1L shl retryCount) * 1000L
        val phase = when (failure) {
            is HttpException if failure.code == 403 && failure.bodyStart.contains("DEBUG_DISABLED") -> DiagnosticPhase.SERVER_DISABLED
            is HttpException if failure.code == 413 -> DiagnosticPhase.QUOTA_FULL
            is SignInRequired, is DiagnosticOwnerChanged -> DiagnosticPhase.SIGN_IN
            else -> DiagnosticPhase.RETRYING
        }
        _status.value = DiagnosticStatus(true, phase, detail = if (phase == DiagnosticPhase.RETRYING) failure.message.orEmpty().take(100) else "")
    }

    private fun sendSession(dir: File) {
        if (!optedIn.get()) return
        val url = File(dir, "url.txt").takeIf(File::isFile)?.readText() ?: return
        val owner = readOwner(dir) ?: return
        if (owner.server != url || cells.diagnosticOwner() != owner) return
        val (base, token) = cells.diagnosticCredentials(owner) ?: throw SignInRequired()
        val remoteFile = File(dir, "remote.txt")
        var remote = remoteFile.takeIf(File::isFile)?.readText()
        var next: Int
        if (remote == null) {
            val context = File(dir, "context.json").readText()
            val result = request(owner, base, token, "/v1/debug/sessions", "POST", context)
            remote = result.getString("id")
            remoteFile.writeText(remote)
            next = result.getInt("next_seq")
        } else {
            next = try {
                request(owner, base, token, "/v1/debug/sessions/$remote", "GET").getInt("next_seq")
            } catch (error: HttpException) {
                if (error.code != 404) throw error
                ingest.submit {
                    if (cells.diagnosticOwner() == owner) {
                        synchronized(sessions) {
                            sessions.remove(dir.name)?.let { session ->
                                session.failed = true
                                session.capture?.fail()
                                session.capture?.finish { }
                                clearBuffer(session)
                                if (active === session) active = null
                            }
                        }
                        dir.deleteRecursively()
                    }
                }.get()
                _status.value = DiagnosticStatus(true, DiagnosticPhase.REMOVED)
                return
            }
        }
        dir.listFiles()?.filter { it.name.matches(Regex("[0-9]{8}\\.json")) }?.sortedBy { it.name }?.forEach { batch ->
            if (!optedIn.get() || cells.diagnosticOwner() != owner) return
            val sequence = batch.name.removeSuffix(".json").toInt()
            if (sequence < next) {
                batch.delete()
            } else if (sequence == next) {
                request(owner, base, token, "/v1/debug/sessions/$remote/batches/$sequence", "PUT", batch.readText())
                batch.delete()
                next++
                _status.value = DiagnosticStatus(true, DiagnosticPhase.UPLOADING, dir.walkTopDown().filter { it.isFile && it.name.endsWith(".json") }.sumOf { it.length() })
            }
        }
        if (File(dir, "finished").isFile && dir.listFiles()?.none { it.name.matches(Regex("[0-9]{8}\\.json")) } == true) {
            request(
                owner,
                base,
                token,
                "/v1/debug/sessions/$remote/finish",
                "POST",
                JSONObject().put("next_seq", next).put("incomplete", File(dir, "incomplete").isFile).toString(),
            )
            dir.deleteRecursively()
            _status.value = DiagnosticStatus(true, DiagnosticPhase.UPLOADED)
        }
    }

    /** Recheck the persistent owner at each request and before acknowledging/deleting local data. */
    private fun request(owner: DiagnosticOwner, base: String, token: String, path: String, method: String, json: String? = null): JSONObject {
        val builder = Request.Builder().url(base + path).header("Authorization", "Bearer $token")
        val body = json?.toRequestBody("application/json".toMediaType())
        val call = Http.client.newBuilder().callTimeout(15, TimeUnit.SECONDS).build().newCall(builder.method(method, body).build())
        return requests.execute(owner, call) {
            val source = it.body.source()
            if (source.request(MAX_RESPONSE_BYTES + 1L)) throw IOException("Diagnostic response too large")
            val text = source.readUtf8()
            if (!it.isSuccessful) throw HttpException(it.code, text.take(120))
            JSONObject(text)
        }
    }

    private fun redact(line: String): String = DiagnosticRedaction.redact(line)

    private companion object {
        const val MAX_PENDING_ITEMS = 2_048
        const val MAX_PENDING_BYTES = 8L * 1024 * 1024
        const val MAX_OWNER_BYTES = 4 * 1024
        const val MAX_RESPONSE_BYTES = 64 * 1024
        val BATCH_NAME = Regex("[0-9]{8}\\.json")
        const val MAX_ENTRIES = 100
        const val MAX_ENTRY_BYTES = 7 * 1024 * 1024
        const val MAX_BATCH_BYTES = 8 * 1024 * 1024
        const val MAX_LOCAL_BYTES = 64L * 1024 * 1024
    }
}
