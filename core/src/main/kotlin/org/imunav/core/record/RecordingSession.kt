package org.imunav.core.record

import org.imunav.core.util.OrderedBuffer
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executor

/** Admission never waits for disk. A rejected event terminates recording rather than hiding a replay gap. */
enum class RecordingAdmission { ACCEPTED, STOPPED }

/** Persisted independently of guidance so a partial recording cannot resume silently after process death. */
enum class RecordingFailure { OVERLOAD, WRITE_FAILED }

/** Explicit overload is distinguishable from storage failure without exposing paths in UI or diagnostics. */
class RecordingOverload : IOException("Recording buffer is full")

/** A recording owns its file for its entire lifetime; the shared bounded buffer orders events and trip boundaries. */
class RecordingSession(
    private val file: File,
    worker: Executor,
    append: Boolean,
    private val onRecorded: (TripEvent) -> Unit = {},
    private val arrivalClock: (() -> Long)? = null,
    private val buffer: OrderedBuffer = newBuffer(worker),
    private val recorderFactory: (File, Boolean) -> TripRecorder = TripRecorder::open,
    private val onFailure: (Throwable) -> Unit,
) {
    private var recorder: TripRecorder? = null // Only accessed by the worker.
    private val lifecycle = buffer.reserve(2)
    private var accepting = lifecycle != null
    private var finished = false

    @Volatile var failure: RecordingFailure? = null
        private set
    val stats get() = buffer.stats

    init {
        if (lifecycle == null) {
            fail(RecordingOverload())
        } else {
            lifecycle.submit {
                runCatching {
                    if (append) TripFormat.repair(file)
                    recorder = recorderFactory(file, append)
                }.onFailure { fail(it) }
            }
        }
    }

    /** Capture arrival on the producer, before queue latency can change replay timing. */
    @Synchronized fun record(event: TripEvent): RecordingAdmission {
        if (!accepting) return RecordingAdmission.STOPPED
        val arrivalMs = arrivalClock?.invoke() ?: event.arrivalElapsedMs
        if (!buffer.offer(this, event.recordingWeight()) {
                runCatching {
                    recorder?.let {
                        it.record(event, arrivalMs)
                        onRecorded(event)
                    }
                }.onFailure { fail(it) }
            }
        ) {
            fail(RecordingOverload())
            return RecordingAdmission.STOPPED
        }
        return RecordingAdmission.ACCEPTED
    }

    /** Close after every accepted event. Repeated finish cannot close or delete another session's file. */
    @Synchronized fun finish(discard: Boolean = false) {
        if (finished) return
        finished = true
        accepting = false
        lifecycle?.submit {
            val previous = recorder
            recorder = null
            runCatching { previous?.close() }.onFailure { fail(it) }
            if (discard) file.delete()
        }
    }

    /** Overload preserves the accepted prefix; disk failures abandon pending payloads and report once. */
    @Synchronized private fun fail(cause: Throwable) {
        if (cause !is RecordingOverload) buffer.discard(this)
        if (failure != null) return
        failure = if (cause is RecordingOverload) RecordingFailure.OVERLOAD else RecordingFailure.WRITE_FAILED
        accepting = false
        finish()
        onFailure(cause)
    }

    companion object {
        const val MAX_PENDING_EVENTS = 8_192
        const val MAX_PENDING_BYTES = 16L * 1024 * 1024

        /** Share across sessions so rapid restarts cannot multiply the payload budget. */
        fun newBuffer(worker: Executor) = OrderedBuffer(worker, MAX_PENDING_EVENTS, MAX_PENDING_BYTES)

        /** Human-readable time plus uniqueness even for multiple starts in the same millisecond. */
        fun newId(wallMs: Long = System.currentTimeMillis()): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(wallMs)) + "-" + UUID.randomUUID()
    }
}

/** Conservative retained-payload accounting; route weights are cached during route construction, never encoded at admission. */
private fun TripEvent.recordingWeight(): Long = 256L + when (this) {
    is TripEvent.RouteSet -> route.recordingWeight
    is TripEvent.Imu -> 4L * ((sample.linearAcc?.size ?: 0) + (sample.gyro?.size ?: 0))
    is TripEvent.Start -> 32L * waypoints.size
    is TripEvent.Estimate -> 2L * source.length
    else -> 0L
}
