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
import org.imunav.core.net.Http
import org.imunav.core.net.HttpException
import org.imunav.core.record.TripEvent
import org.imunav.core.record.TripFormat
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
    private var active: File? = null // Ingest worker only.
    private val buffer = JSONArray()
    private var bufferBytes = 0
    private var nextSequence = 0
    private var retryAtMs = 0L // Sender worker only.
    private var retryCount = 0

    init {
        sender.scheduleWithFixedDelay(::sendPendingSafely, 2, 2, TimeUnit.SECONDS)
    }

    /** Disabling drops unsent data and stops capture; received server data stays under account controls. */
    fun setEnabled(enabled: Boolean) {
        optedIn.set(enabled)
        prefs.edit { putBoolean("enabled", enabled) }
        _status.value = DiagnosticStatus(enabled, if (enabled) DiagnosticPhase.WAITING else DiagnosticPhase.IDLE)
        if (!enabled) {
            ingest.execute {
                active = null
                clearBuffer()
                root.deleteRecursively()
            }
        }
    }

    /** Begin a session using only a small allowlisted context object. */
    fun startTrip(id: String, details: JSONObject) {
        if (!optedIn.get() || !cells.diagnosticConsentGranted()) return
        val url = cells.diagnosticServerUrl()
        if (url.isBlank() || cells.diagnosticAccountEmail() == null) {
            _status.value = DiagnosticStatus(true, DiagnosticPhase.SIGN_IN)
            return
        }
        ingest.execute {
            if (!optedIn.get()) return@execute
            val dir = File(root, id)
            if (!dir.isDirectory) {
                dir.mkdirs()
                details.put("app_version", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
                File(dir, "context.json").writeText(JSONObject().put("client_id", id).put("context", details).toString())
                File(dir, "url.txt").writeText(url)
            } else {
                // A restored trip has a gap while the process was stopped; the server copy must say so.
                File(dir, "incomplete").writeText("1")
            }
            active = dir
            val nextSaved = File(dir, "next.txt").takeIf(File::isFile)?.readText()?.toIntOrNull() ?: 0
            val nextOnDisk =
                (dir.listFiles()?.mapNotNull { it.name.removeSuffix(".json").takeIf { _ -> it.name.matches(Regex("[0-9]{8}\\.json")) }?.toIntOrNull() }?.maxOrNull() ?: -1) + 1
            nextSequence = maxOf(nextSaved, nextOnDisk)
            _status.value = DiagnosticStatus(true, DiagnosticPhase.RECORDING)
        }
    }

    /** Called only after the normal replay writer has accepted the event. */
    fun onEvent(event: TripEvent) {
        if (!optedIn.get() || !cells.diagnosticConsentGranted()) return
        ingest.execute { append("event", event.elapsedMs, TripFormat.encode(event)) }
    }

    /** Capture trip log lines without collecting Android Logcat or unrelated process output. */
    fun onLog(line: String, elapsedMs: Long) {
        if (!optedIn.get() || !cells.diagnosticConsentGranted()) return
        ingest.execute { append("log", elapsedMs, redact(line)) }
    }

    /** Mark an existing trip complete after the replay recorder has written its last event. */
    fun endTrip(id: String) {
        ingest.execute {
            if (active?.name == id) {
                flush()
                File(active, "finished").writeText("1")
                active = null
            }
        }
    }

    private fun append(kind: String, elapsedMs: Long, line: String) {
        if (!optedIn.get() || active == null) return
        val entry = JSONObject().put("kind", kind).put("elapsed_ms", elapsedMs).put("line", line)
        val size = entry.toString().toByteArray().size
        if (size > MAX_ENTRY_BYTES) {
            active?.let { File(it, "incomplete").writeText("1") }
            _status.value = DiagnosticStatus(true, DiagnosticPhase.ENTRY_TOO_LARGE)
            return
        }
        if (buffer.length() >= MAX_ENTRIES || bufferBytes + size > MAX_BATCH_BYTES / 2) flush()
        buffer.put(entry)
        bufferBytes += size
    }

    private fun flush() {
        val dir = active ?: return
        if (buffer.length() == 0) return
        val payload = JSONObject().put("entries", buffer).toString()
        val pending = root.walkTopDown().filter { it.isFile && it.name.endsWith(".json") }.sumOf { it.length() }
        if (pending + payload.toByteArray().size > MAX_LOCAL_BYTES) {
            File(dir, "incomplete").writeText("1")
            _status.value = DiagnosticStatus(true, DiagnosticPhase.QUEUE_FULL, pending)
            clearBuffer()
            return
        }
        val target = File(dir, String.format(Locale.US, "%08d.json", nextSequence++))
        val temporary = File(dir, target.name + ".tmp")
        temporary.writeText(payload)
        check(temporary.renameTo(target)) { "Cannot save diagnostic batch" }
        File(dir, "next.txt").writeText(nextSequence.toString())
        clearBuffer()
        _status.value = DiagnosticStatus(true, DiagnosticPhase.QUEUED, pending + target.length())
    }

    private fun clearBuffer() {
        while (buffer.length() > 0) buffer.remove(buffer.length() - 1)
        bufferBytes = 0
    }

    private fun sendPendingSafely() {
        if (!optedIn.get() || SystemClock.elapsedRealtime() < retryAtMs) return
        try {
            ingest.submit { flush() }.get()
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
            is SignInRequired -> DiagnosticPhase.SIGN_IN
            else -> DiagnosticPhase.RETRYING
        }
        _status.value = DiagnosticStatus(true, phase, detail = if (phase == DiagnosticPhase.RETRYING) failure.message.orEmpty().take(100) else "")
    }

    private fun sendSession(dir: File) {
        if (!optedIn.get()) return
        val url = File(dir, "url.txt").takeIf(File::isFile)?.readText() ?: return
        val (base, token) = cells.diagnosticCredentials(url) ?: throw SignInRequired()
        val remoteFile = File(dir, "remote.txt")
        var remote = remoteFile.takeIf(File::isFile)?.readText()
        var next: Int
        if (remote == null) {
            val context = File(dir, "context.json").readText()
            val result = request(base, token, "/v1/debug/sessions", "POST", context)
            remote = result.getString("id")
            remoteFile.writeText(remote)
            next = result.getInt("next_seq")
        } else {
            next = try {
                request(base, token, "/v1/debug/sessions/$remote", "GET").getInt("next_seq")
            } catch (error: HttpException) {
                if (error.code != 404) throw error
                ingest.submit {
                    if (active?.name == dir.name) {
                        active = null
                        clearBuffer()
                    }
                    dir.deleteRecursively()
                }.get()
                _status.value = DiagnosticStatus(true, DiagnosticPhase.REMOVED)
                return
            }
        }
        dir.listFiles()?.filter { it.name.matches(Regex("[0-9]{8}\\.json")) }?.sortedBy { it.name }?.forEach { batch ->
            if (!optedIn.get()) return
            val sequence = batch.name.removeSuffix(".json").toInt()
            if (sequence < next) {
                batch.delete()
            } else if (sequence == next) {
                request(base, token, "/v1/debug/sessions/$remote/batches/$sequence", "PUT", batch.readText())
                batch.delete()
                next++
                _status.value = DiagnosticStatus(true, DiagnosticPhase.UPLOADING, dir.walkTopDown().filter { it.isFile && it.name.endsWith(".json") }.sumOf { it.length() })
            }
        }
        if (File(dir, "finished").isFile && dir.listFiles()?.none { it.name.matches(Regex("[0-9]{8}\\.json")) } == true) {
            request(base, token, "/v1/debug/sessions/$remote/finish", "POST", JSONObject().put("next_seq", next).put("incomplete", File(dir, "incomplete").isFile).toString())
            dir.deleteRecursively()
            _status.value = DiagnosticStatus(true, DiagnosticPhase.UPLOADED)
        }
    }

    private fun request(base: String, token: String, path: String, method: String, json: String? = null): JSONObject {
        val builder = Request.Builder().url(base + path).header("Authorization", "Bearer $token")
        val body = json?.toRequestBody("application/json".toMediaType())
        val response = Http.client.newBuilder().callTimeout(15, TimeUnit.SECONDS).build().newCall(builder.method(method, body).build()).execute()
        return response.use {
            val text = it.body.string()
            if (!it.isSuccessful) throw HttpException(it.code, text.take(120))
            JSONObject(text)
        }
    }

    private fun redact(line: String): String = DiagnosticRedaction.redact(line)

    private companion object {
        const val MAX_ENTRIES = 100
        const val MAX_ENTRY_BYTES = 7 * 1024 * 1024
        const val MAX_BATCH_BYTES = 8 * 1024 * 1024
        const val MAX_LOCAL_BYTES = 64L * 1024 * 1024
    }
}
