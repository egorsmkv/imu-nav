package org.blinddriver.app

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Append-only text log per trip (files/logs/trip-*.log) plus an in-memory tail for the UI. */
class TripLog(context: Context, private val maxFileBytes: Long = 15L * 1024 * 1024) {
    private val dir = File(context.filesDir, "logs").apply { mkdirs() }
    private var file: File? = null
    private val tail = ArrayDeque<String>()

    val recent: List<String> get() = synchronized(tail) { tail.toList() }

    fun startTrip() {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        file = File(dir, "trip-$stamp.log")
    }

    fun endTrip() {
        file = null
    }

    fun write(message: String) {
        val line = "${SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())} [${SystemClock.elapsedRealtime()}] $message"
        Log.d("BlindDriver", message)
        synchronized(tail) {
            tail.addLast(line)
            while (tail.size > 200) tail.removeFirst()
        }
        val f = file ?: return
        if (f.length() > maxFileBytes) startTrip()
        runCatching { file?.appendText(line + "\n") }
    }
}
