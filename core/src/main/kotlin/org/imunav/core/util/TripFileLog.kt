package org.imunav.core.util

import java.io.BufferedWriter
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Orders trip boundaries and buffered writes on one worker, including messages queued before stop. */
class TripFileLog(
    private val directory: File,
    private val maxBytes: Long,
    private val onFailure: (Exception) -> Unit,
    private val worker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(),
) : AutoCloseable {
    private var accepting = false
    private var closed = false
    private var writer: BufferedWriter? = null
    private var bytes = 0L
    private var flush: ScheduledFuture<*>? = null

    @Synchronized fun start() {
        check(!closed)
        accepting = true
        submit {
            endFile()
            openFile()
        }
    }

    @Synchronized fun end() {
        if (closed) return
        accepting = false
        submit { endFile() }
    }

    /** Submission order, rather than a mutable current-file reference, determines each line's trip. */
    @Synchronized fun write(line: String) {
        if (!accepting) return
        submit {
            if (writer != null) {
                val encodedBytes = (line + "\n").toByteArray(Charsets.UTF_8).size
                if (bytes > 0 && bytes + encodedBytes > maxBytes) {
                    endFile()
                    openFile()
                }
                writer?.apply {
                    write(line)
                    newLine()
                }
                bytes += encodedBytes
                if (flush == null) {
                    flush = worker.schedule({
                        safely { writer?.flush() }
                        flush = null
                    }, FLUSH_DELAY_SECONDS, TimeUnit.SECONDS)
                }
            }
        }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        accepting = false
        worker.execute {
            try {
                safely { endFile() }
            } finally {
                worker.shutdown()
            }
        }
    }

    private fun submit(action: () -> Unit) = worker.execute { safely(action) }

    // A background logging failure must be reported without terminating subsequent queued commands.
    @Suppress("TooGenericExceptionCaught")
    private fun safely(action: () -> Unit) {
        try {
            action()
        } catch (failure: Exception) {
            onFailure(failure)
        }
    }

    private fun openFile() {
        Files.createDirectories(directory.toPath())
        val stamp = LocalDateTime.now().format(STAMP)
        val path = Files.createTempFile(directory.toPath(), "trip-$stamp-", ".log")
        writer = Files.newBufferedWriter(path, Charsets.UTF_8)
        bytes = 0L
    }

    private fun endFile() {
        flush?.cancel(false)
        flush = null
        val previous = writer
        writer = null
        previous?.close()
    }

    private companion object {
        const val FLUSH_DELAY_SECONDS = 5L
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}
