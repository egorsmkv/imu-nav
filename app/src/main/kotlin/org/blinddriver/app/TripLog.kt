package org.blinddriver.app

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

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
class TripLog(context: Context, private val maxFileBytes: Long = 15L * 1024 * 1024) {
    private val dir = File(context.filesDir, "logs").apply { mkdirs() }

    /** The current trip's log file; null when no trip runs. `@Volatile`: read from the I/O thread. */
    @Volatile private var file: File? = null

    /** The last [MAX_RECENT] lines, for the UI. Guarded by `synchronized(tail)`. */
    private val tail = ArrayDeque<String>()

    /** File appends happen on this single background thread, so logging never blocks the UI. */
    private val io = Executors.newSingleThreadExecutor()

    /** A snapshot of the latest lines (oldest first). */
    val recent: List<String> get() = synchronized(tail) { tail.toList() }

    /** Start a new log file (called when navigation starts). */
    fun startTrip() {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        file = File(dir, "trip-$stamp.log")
    }

    /** Stop writing to a file (in-memory lines continue). */
    fun endTrip() {
        file = null
    }

    /** Log one line (any thread). */
    fun write(message: String) {
        val line = "${LocalTime.now().format(TIME)} [${SystemClock.elapsedRealtime()}] $message"
        Log.d(TAG, message)
        synchronized(tail) {
            tail.addLast(line)
            while (tail.size > MAX_RECENT) tail.removeFirst()
        }
        val target = file ?: return
        io.execute {
            if (target.length() > maxFileBytes && file === target) startTrip() // roll over to a new file
            runCatching { (file ?: target).appendText(line + "\n") }
        }
    }

    private companion object {
        const val TAG = "BlindDriver"
        const val MAX_RECENT = 200
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.US)
    }
}
