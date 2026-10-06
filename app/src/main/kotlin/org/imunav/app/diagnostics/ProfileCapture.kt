package org.imunav.app.diagnostics

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.imunav.app.BuildConfig
import org.imunav.app.nativecore.NativeProfiler
import org.imunav.core.record.TripEvent
import org.imunav.core.record.TripFormat
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A local, user-controlled capture. It never uses the diagnostics upload service. */
enum class ProfilePhase { IDLE, STARTING, RECORDING, FINISHING, READY, INTERRUPTED, ERROR }

data class ProfileStatus(val phase: ProfilePhase, val archive: File? = null, val detail: String = "")

/**
 * Captures a bounded Java method trace, process memory, selected Rust timings, and session-only
 * logs/events. Producers only offer to a bounded queue; serialization and file I/O stay off the
 * navigation and sensor threads.
 */
class ProfileCapture(private val context: Context) {
    private val root = File(context.filesDir, "profiles")
    private val shares = File(context.cacheDir, "profile-shares")
    private val control = Executors.newSingleThreadExecutor()
    private val _status = MutableStateFlow(ProfileStatus(ProfilePhase.IDLE))
    val status: StateFlow<ProfileStatus> = _status.asStateFlow()

    @Volatile private var active: CaptureSession? = null

    init {
        control.execute { recoverInterrupted() }
    }

    /** Start a fresh session; calling this again while busy has no effect. */
    fun start() {
        if (_status.value.phase !in setOf(ProfilePhase.IDLE, ProfilePhase.READY, ProfilePhase.INTERRUPTED, ProfilePhase.ERROR)) return
        _status.value = ProfileStatus(ProfilePhase.STARTING)
        control.execute {
            var started: CaptureSession? = null
            runCatching {
                check(active == null)
                check(root.isDirectory || root.mkdirs())
                val directory = File(root, "session-${System.currentTimeMillis()}")
                check(directory.mkdir())
                val session = CaptureSession(directory)
                started = session
                File(directory, "started.json").writeText(
                    JSONObject().put("start_wall_ms", System.currentTimeMillis())
                        .put("start_elapsed_ms", SystemClock.elapsedRealtime()).toString(),
                )
                session.start()
                active = session
                NativeProfiler.start()
                session.traceError = runCatching {
                    Debug.startMethodTracingSampling(File(directory, TRACE_FILE).absolutePath, TRACE_LIMIT_BYTES.toInt(), TRACE_INTERVAL_US)
                }.exceptionOrNull()?.javaClass?.simpleName
                _status.value = ProfileStatus(ProfilePhase.RECORDING)
            }.onFailure { failure ->
                active = null
                started?.close()
                started?.directory?.deleteRecursively()
                runCatching { NativeProfiler.stop() }
                _status.value = ProfileStatus(ProfilePhase.ERROR, detail = failure.javaClass.simpleName)
            }
        }
    }

    /** Stop on the control worker, then publish a complete ZIP for explicit sharing. */
    fun stop() {
        if (_status.value.phase != ProfilePhase.RECORDING) return
        _status.value = ProfileStatus(ProfilePhase.FINISHING)
        control.execute {
            val session = active ?: return@execute
            active = null
            val traceError = session.traceError
            if (traceError == null) {
                runCatching { Debug.stopMethodTracing() }.onFailure { session.traceError = it.javaClass.simpleName }
            }
            runCatching { File(session.directory, "native-timings.json").writeText(NativeProfiler.stop()) }
                .onFailure { session.nativeError = it.javaClass.simpleName }
            session.close()
            runCatching {
                val archive = packageSession(session.directory, interrupted = false, session)
                _status.value = ProfileStatus(ProfilePhase.READY, archive)
            }.onFailure { _status.value = ProfileStatus(ProfilePhase.ERROR, detail = it.javaClass.simpleName) }
        }
    }

    /** Event callback from the trip recorder; a mid-trip session deliberately excludes earlier data. */
    fun onEvent(event: TripEvent) {
        active?.offer(Entry.Event(event))
    }

    /** Log callback from TripLog; no unrelated Logcat messages are captured. */
    fun onLog(line: String, @Suppress("UNUSED_PARAMETER") elapsedMs: Long) {
        active?.offer(Entry.Log(line))
    }

    /** The caller opens Android's chooser; this class never picks or contacts a destination. */
    fun shareIntent(): Intent {
        val archive = status.value.archive?.takeIf(File::isFile) ?: error("No profile archive is ready")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", archive)
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, archive.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** On process restart, package flushed files but omit an unclosed method trace. */
    private fun recoverInterrupted() {
        val directory = root.listFiles()?.filter(File::isDirectory)?.maxByOrNull { it.lastModified() }
        if (directory == null) {
            val latest = shares.listFiles()?.filter { it.isFile && it.extension == "zip" }?.maxByOrNull { it.lastModified() } ?: return
            _status.value = ProfileStatus(if (latest.name.contains("partial")) ProfilePhase.INTERRUPTED else ProfilePhase.READY, latest)
            return
        }
        if (File(directory, "finished.json").isFile) {
            directory.deleteRecursively()
            val latest = shares.listFiles()?.filter { it.isFile && it.extension == "zip" }?.maxByOrNull { it.lastModified() } ?: return
            _status.value = ProfileStatus(if (latest.name.contains("partial")) ProfilePhase.INTERRUPTED else ProfilePhase.READY, latest)
            return
        }
        if (!File(directory, "started.json").isFile) return
        runCatching {
            val archive = packageSession(directory, interrupted = true, session = null)
            _status.value = ProfileStatus(ProfilePhase.INTERRUPTED, archive)
        }.onFailure { _status.value = ProfileStatus(ProfilePhase.ERROR, detail = it.javaClass.simpleName) }
    }

    private fun packageSession(directory: File, interrupted: Boolean, session: CaptureSession?): File {
        check(shares.isDirectory || shares.mkdirs())
        val draft = File.createTempFile(if (interrupted) "imu-nav-profile-partial-" else "imu-nav-profile-", ".part", shares)
        val archive = File(shares, draft.name.removeSuffix(".part") + ".zip")
        var complete = false
        try {
            val manifest = manifest(directory, interrupted, session)
            ZipOutputStream(draft.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(manifest.toString(2).toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
                listOf("memory.csv", "logs.txt", "trip-events.rec", "native-timings.json").forEach { addFile(zip, File(directory, it)) }
                if (!interrupted && session?.traceError == null) addFile(zip, File(directory, TRACE_FILE))
            }
            check(draft.renameTo(archive)) { "Could not finish profile ZIP" }
            File(directory, "finished.json").writeText(manifest.toString())
            complete = true
            directory.deleteRecursively()
            pruneArchives(archive)
            return archive
        } finally {
            if (!complete) {
                draft.delete()
                archive.delete()
            }
        }
    }

    private fun manifest(directory: File, interrupted: Boolean, session: CaptureSession?): JSONObject = JSONObject()
        .put("schema", 1)
        .put("app_version", BuildConfig.VERSION_NAME)
        .put("app_version_code", BuildConfig.VERSION_CODE)
        .put("android_api", Build.VERSION.SDK_INT)
        .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
        .put("start", JSONObject(File(directory, "started.json").readText()))
        .put("end_wall_ms", System.currentTimeMillis())
        .put("interrupted", interrupted)
        .put("trace_status", if (interrupted) "unclosed" else session?.traceError ?: if (File(directory, TRACE_FILE).isFile) "complete" else "missing")
        .put("native_timing_status", session?.nativeError ?: if (interrupted) "unavailable" else "complete")
        .put("stream_status", session?.streamError ?: if (interrupted) "possibly_incomplete" else "complete")
        .put("dropped_queue_entries", session?.dropped?.get() ?: -1)
        .put("dropped_log_entries", session?.droppedLogs ?: -1)
        .put("dropped_event_entries", session?.droppedEvents ?: -1)
        .put("dropped_memory_samples", session?.droppedMemory ?: -1)
        .put("trip_events_replayable", false)

    private fun addFile(zip: ZipOutputStream, file: File) {
        if (!file.isFile) return
        zip.putNextEntry(ZipEntry(file.name))
        file.inputStream().buffered().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    private fun pruneArchives(current: File) {
        shares.listFiles()?.filter { it != current && it.isFile && it.extension == "zip" }?.sortedByDescending { it.lastModified() }?.forEachIndexed { index, file ->
            if (index >= MAX_ARCHIVES - 1 || file.lastModified() < System.currentTimeMillis() - SHARE_RETENTION_MS) file.delete()
        }
    }

    private sealed interface Entry {
        data class Event(val value: TripEvent) : Entry
        data class Log(val value: String) : Entry
    }

    private class CaptureSession(val directory: File) {
        private val queue = ArrayBlockingQueue<Entry>(QUEUE_SIZE)
        private val running = AtomicBoolean(true)
        val dropped = AtomicLong()

        @Volatile var traceError: String? = null

        @Volatile var nativeError: String? = null

        @Volatile var streamError: String? = null

        @Volatile var droppedLogs = 0L

        @Volatile var droppedEvents = 0L

        @Volatile var droppedMemory = 0L
        private val worker = Thread({ runCatching { writeLoop() }.onFailure { streamError = it.javaClass.simpleName } }, "ProfileCapture")

        fun start() = worker.start()

        fun offer(entry: Entry) {
            if (running.get() && !queue.offer(entry)) dropped.incrementAndGet()
        }

        fun close() {
            running.set(false)
            worker.join()
        }

        private var logBytes = 0L
        private var eventBytes = 0L
        private var memoryBytes = 0L

        private fun writeLoop() {
            File(directory, "logs.txt").bufferedWriter().use { logs ->
                File(directory, "trip-events.rec").bufferedWriter().use { events ->
                    File(directory, "memory.csv").bufferedWriter().use { memory ->
                        capture(logs, events, memory)
                    }
                }
            }
        }

        private fun capture(logs: BufferedWriter, events: BufferedWriter, memory: BufferedWriter) {
            var lastSample = 0L
            val header = "elapsed_ms,java_heap_used_bytes,native_heap_bytes,total_pss_kb,process_cpu_ms\n"
            memory.write(header)
            memoryBytes = header.toByteArray(StandardCharsets.UTF_8).size.toLong()
            while (running.get() || queue.isNotEmpty()) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastSample >= SAMPLE_INTERVAL_MS) {
                    sampleMemory(now, memory)
                    lastSample = now
                }
                when (val entry = queue.poll(500, TimeUnit.MILLISECONDS)) {
                    is Entry.Log -> writeLog(entry.value, logs)

                    is Entry.Event -> writeEvent(entry.value, events)

                    null -> {
                        logs.flush()
                        events.flush()
                    }
                }
            }
        }

        private fun sampleMemory(now: Long, memory: BufferedWriter) {
            if (memoryBytes >= MEMORY_LIMIT_BYTES) {
                droppedMemory++
                return
            }
            val info = Debug.MemoryInfo()
            Debug.getMemoryInfo(info)
            val runtime = Runtime.getRuntime()
            val line =
                "$now,${runtime.totalMemory() - runtime.freeMemory()},${Debug.getNativeHeapAllocatedSize()},${info.totalPss},${Process.getElapsedCpuTime()}\n"
            val bytes = line.toByteArray(StandardCharsets.UTF_8).size
            if (memoryBytes + bytes <= MEMORY_LIMIT_BYTES) {
                memory.write(line)
                memory.flush()
                memoryBytes += bytes
            } else {
                droppedMemory++
                memoryBytes = MEMORY_LIMIT_BYTES
            }
        }

        private fun writeLog(value: String, logs: BufferedWriter) {
            val line = DiagnosticRedaction.redact(value) + "\n"
            val bytes = line.toByteArray(StandardCharsets.UTF_8).size
            if (logBytes + bytes <= LOG_LIMIT_BYTES) {
                logs.write(line)
                logBytes += bytes
            } else {
                droppedLogs++
            }
        }

        private fun writeEvent(value: TripEvent, events: BufferedWriter) {
            val line = TripFormat.encode(value) + "\n"
            val bytes = line.toByteArray(StandardCharsets.UTF_8).size
            if (eventBytes + bytes <= EVENT_LIMIT_BYTES) {
                events.write(line)
                eventBytes += bytes
            } else {
                droppedEvents++
            }
        }
    }

    private companion object {
        const val TRACE_FILE = "methods.trace"
        const val TRACE_INTERVAL_US = 10_000
        const val TRACE_LIMIT_BYTES = 16L * 1024 * 1024
        const val LOG_LIMIT_BYTES = 8L * 1024 * 1024
        const val EVENT_LIMIT_BYTES = 16L * 1024 * 1024
        const val MEMORY_LIMIT_BYTES = 1L * 1024 * 1024
        const val QUEUE_SIZE = 4_096
        const val MAX_ARCHIVES = 3
        const val SAMPLE_INTERVAL_MS = 5_000L
        const val SHARE_RETENTION_MS = 7L * 24 * 60 * 60 * 1000
    }
}
