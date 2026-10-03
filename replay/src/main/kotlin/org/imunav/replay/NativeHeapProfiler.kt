package org.imunav.replay

import java.io.File

/** Captures sampled Rust heap allocations on the replay thread, using simulated-time intervals. */
internal class NativeHeapProfiler(private val directory: File) {
    private var lastSnapshotMs: Long? = null
    private var sequence = 0

    init {
        require(directory.mkdirs() || directory.isDirectory) { "cannot create heap profile directory: $directory" }
        System.loadLibrary("imu_nav_jni")
        // Fail before processing recordings if the library lacks profiling support.
        writeSnapshot("baseline")
    }

    /** Snapshot after input delivery so live route geometry and bounded replay history are visible. */
    fun sample(elapsedMs: Long) {
        val previous = lastSnapshotMs
        if (previous == null || elapsedMs - previous >= SNAPSHOT_INTERVAL_MS) {
            writeSnapshot("$elapsedMs")
            lastSnapshotMs = elapsedMs
        }
    }

    /** Also captures retained allocations after each comparison session has released its handles. */
    fun finish() {
        writeSnapshot("after-close")
        lastSnapshotMs = null
    }

    private fun writeSnapshot(label: String) {
        File(directory, "heap-${sequence++}-$label.pb.gz").writeBytes(snapshot())
    }

    private external fun snapshot(): ByteArray

    private companion object {
        const val SNAPSHOT_INTERVAL_MS = 60_000L
    }
}
