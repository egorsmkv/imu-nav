package org.imunav.core.record

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executor

/** A recording owns its file for its entire lifetime, with all I/O ordered on the supplied serial worker. */
class RecordingSession(
    private val file: File,
    private val worker: Executor,
    append: Boolean,
    private val onRecorded: (TripEvent) -> Unit = {},
    private val onFailure: (Throwable) -> Unit,
) {
    private var recorder: TripRecorder? = null // Only accessed by the worker.

    init {
        worker.execute {
            runCatching {
                if (append) TripFormat.repair(file)
                recorder = TripRecorder.open(file, append)
            }.onFailure(onFailure)
        }
    }

    /** Queue against this session, even when the main thread has already started another trip. */
    fun record(event: TripEvent) = worker.execute {
        runCatching {
            recorder?.let {
                it.record(event)
                onRecorded(event)
            }
        }.onFailure(onFailure)
    }

    /** Close after every queued event, then optionally discard this session's file. */
    fun finish(discard: Boolean = false) = worker.execute {
        try {
            runCatching { recorder?.close() }.onFailure(onFailure)
        } finally {
            recorder = null
            if (discard) file.delete()
        }
    }

    companion object {
        /** Human-readable time plus uniqueness even for multiple starts in the same millisecond. */
        fun newId(wallMs: Long = System.currentTimeMillis()): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(wallMs)) + "-" + UUID.randomUUID()
    }
}
