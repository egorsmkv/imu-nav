package org.imunav.app

import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Debug and benchmark builds only: finds UI freezes caused by heavy work on the main thread.
 *
 * Android's main thread runs one message (task) at a time. [Looper.setMessageLogging] tells us
 * when each message starts and ends; a background thread checks every [SAMPLE_MS] whether the
 * current message has been running longer than [SLOW_MS] and, if so, logs what the main thread is
 * doing (its stack, app frames first) under the `MainThreadWatchdog` Logcat tag.
 *
 * StrictMode catches disk/network access; this catches slow computation (e.g. encoding a long
 * route) that StrictMode cannot see.
 */
object MainThreadWatchdog {
    private const val TAG = "MainThreadWatchdog"
    private const val SLOW_MS = 200L
    private const val SAMPLE_MS = 50L
    private const val MAX_FRAMES = 25

    /** Start time of the message the main thread is running now (0 = idle). Written by the main thread. */
    @Volatile private var messageStartMs = 0L

    /** Last message we already reported, so one long task is logged once, not every sample. */
    private var reportedStartMs = 0L

    fun start() {
        val main = Looper.getMainLooper()
        // The printer is called with ">>>>> Dispatching …" before and "<<<<< Finished …" after each message.
        main.setMessageLogging { line -> messageStartMs = if (line.startsWith(">")) SystemClock.uptimeMillis() else 0L }
        Thread({ watch(main.thread) }, TAG).apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    private fun watch(mainThread: Thread) {
        while (true) {
            Thread.sleep(SAMPLE_MS)
            val start = messageStartMs
            if (start == 0L || start == reportedStartMs) continue
            val runningMs = SystemClock.uptimeMillis() - start
            if (runningMs < SLOW_MS) continue
            reportedStartMs = start
            // Top of the stack, unfiltered: in R8 builds app classes are renamed (use R8 retrace with
            // the mapping file to read them), so filtering by package would drop the useful frames.
            val frames = mainThread.stackTrace.take(MAX_FRAMES)
            Log.w(TAG, "main thread busy ${runningMs}ms in:\n" + frames.joinToString("\n") { "    at $it" })
        }
    }
}
