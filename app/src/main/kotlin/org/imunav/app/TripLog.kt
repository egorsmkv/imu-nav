package org.imunav.app

import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.imunav.core.util.TripFileLog
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The app's text log.
 *
 * - Every line goes to Logcat (tag `BlindDriver`) and to an in-memory [recent] list the log screen shows.
 * - During a trip it is also appended to `files/logs/trip-<time>.log` (a new file every 15 MB).
 *
 * Lines are short `key=value` messages such as `gps_state=LOST` or `turn_snap step=4 …`, which
 * makes logs easy to grep when debugging a drive.
 *
 * [write] may be called from any thread.
 */
class TripLog(context: Context, maxFileBytes: Long = 15L * 1024 * 1024) {
    /** Optional sink for an explicitly enabled developer session. */
    @Volatile var onDiagnosticLine: ((String, Long) -> Unit)? = null

    /** Optional non-blocking sink for a local profiling session. */
    @Volatile var onProfileLine: ((String, Long) -> Unit)? = null
    private val files = TripFileLog(File(context.filesDir, "logs"), maxFileBytes, { Log.w(TAG, "log_write_failed", it) })

    /** The last [MAX_RECENT] lines, for the UI. Guarded by `synchronized(tail)`. */
    private val tail = ArrayDeque<String>()

    /** Local retention and cleanup, independent of saved trip recordings and server diagnostics. */
    val management = LogManagement(context, files) { synchronized(tail) { tail.clear() } }

    /** A snapshot of the latest lines (oldest first). */
    val recent: List<String> get() = recent(MAX_RECENT)

    /** Copy only the displayed tail; routine navigation refresh does not need the full log. */
    fun recent(limit: Int): List<String> = synchronized(tail) {
        tail.takeLast(limit.coerceIn(0, MAX_RECENT))
    }

    /** Enqueue the boundary before any messages belonging to this trip. */
    fun startTrip() = files.start()

    /** Flush all messages already queued for the trip before closing its file. */
    fun endTrip() = files.end()

    /** Log one line (any thread). */
    fun write(message: String) {
        val elapsedMs = SystemClock.elapsedRealtime()
        val line = "${LocalTime.now().format(TIME)} [$elapsedMs] $message"
        Log.d(TAG, message)
        synchronized(tail) {
            tail.addLast(line)
            while (tail.size > MAX_RECENT) tail.removeFirst()
        }
        files.write(line)
        onDiagnosticLine?.invoke(line, elapsedMs)
        onProfileLine?.invoke(line, elapsedMs)
    }

    private companion object {
        const val TAG = "BlindDriver"
        const val MAX_RECENT = 200
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.US)
    }
}
