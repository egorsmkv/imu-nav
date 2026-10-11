package org.imunav.core.util

import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Orders trip boundaries and buffered writes on one worker, including messages queued before stop. */
class TripFileLog(
    private val directory: File,
    private val maxBytes: Long,
    private val onFailure: (Exception) -> Unit,
    private val worker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(),
    private val buffer: OrderedBuffer = OrderedBuffer(worker, MAX_PENDING_LINES, MAX_PENDING_BYTES),
) : AutoCloseable {
    private class TripBoundary(val lifecycle: OrderedBuffer.Reservation) {
        val omitted = AtomicLong()
    }

    private val closing = checkNotNull(buffer.reserve(1))
    private var boundary: TripBoundary? = null
    val stats get() = buffer.stats
    private var accepting = false
    private var closed = false
    private var writer: BufferedWriter? = null
    private var activeFile: File? = null
    private var retention = LogRetention()
    private var bytes = 0L
    private var flush: ScheduledFuture<*>? = null

    @Synchronized fun start() {
        check(!closed)
        end()
        val lifecycle = buffer.reserve(2)
        if (lifecycle == null) {
            onFailure(IOException("Log lifecycle buffer is full"))
            return
        }
        boundary = TripBoundary(lifecycle)
        accepting = true
        lifecycle.submit {
            safely {
                endFile()
                openFile()
                prune()
            }
        }
    }

    @Synchronized fun end() {
        if (closed) return
        accepting = false
        val previous = boundary ?: return
        boundary = null
        previous.lifecycle.submit {
            safely {
                writeOmissions(previous)
                endFile()
                prune()
            }
        }
    }

    /** Incoming lines are omitted on overload; counters belong to their original trip, even after restart. */
    @Synchronized fun write(line: String) {
        if (!accepting) return
        val trip = boundary ?: return
        val weight = 64L + 2L * line.length
        if (!buffer.offer(trip, weight) {
                safely {
                    writeOmissions(trip)
                    writeLine(line)
                }
            }
        ) {
            trip.omitted.incrementAndGet()
        }
    }

    private fun writeOmissions(trip: TripBoundary) {
        val count = trip.omitted.getAndSet(0)
        if (count > 0) writeLine("log_omitted count=$count")
    }

    /** Worker-only UTF-8 rotation and delayed flush, shared by normal lines and overload summaries. */
    private fun writeLine(line: String) {
        if (writer == null) return
        val encodedBytes = (line + "\n").toByteArray(Charsets.UTF_8).size
        if (bytes > 0 && bytes + encodedBytes > maxBytes) {
            endFile()
            openFile()
            prune()
        }
        writer?.apply {
            write(line)
            write("\n")
        }
        bytes += encodedBytes
        if (flush == null) {
            flush = worker.schedule({
                safely {
                    writer?.flush()
                    prune()
                }
                flush = null
            }, FLUSH_DELAY_SECONDS, TimeUnit.SECONDS)
        }
    }

    /** Applies the user-selected limits on the same worker as writes and trip boundaries. */
    @Synchronized fun configure(value: LogRetention): CompletableFuture<LogStorage> = manage {
        retention = value
        prune()
    }

    /** Flush before measuring so the usage shown in Settings includes buffered messages. */
    @Synchronized fun storage(): CompletableFuture<LogStorage> = manage { prune() }

    /** Confirmed deletion is ordered with writes; an active trip continues in a fresh file. */
    @Synchronized fun clear(): CompletableFuture<LogStorage> = manage {
        val resume = writer != null
        endFile()
        try {
            logFiles().forEach { Files.delete(it.toPath()) }
        } finally {
            if (resume) openFile()
        }
    }

    /** Complete failures explicitly so Settings can report a failed cleanup rather than claiming success. */
    private fun manage(action: () -> Unit): CompletableFuture<LogStorage> {
        check(!closed)
        val result = CompletableFuture<LogStorage>()
        val reservation = buffer.reserve(1)
        if (reservation == null) {
            result.completeExceptionally(IOException("Log management buffer is full"))
            return result
        }
        reservation.submit {
            runCatching {
                writer?.flush()
                action()
                val files = logFiles()
                LogStorage(files.size, files.sumOf { it.length() })
            }.fold(result::complete, result::completeExceptionally)
        }
        return result
    }

    private fun logFiles(): List<File> = directory.listFiles()?.filter { it.isFile && it.name.startsWith("trip-") && it.name.endsWith(".log") }
        ?: if (directory.exists()) error("Cannot list diagnostic logs") else emptyList()

    /** Oldest completed files go first; the current file stays open until rotation or trip end. */
    private fun prune() {
        if (retention == LogRetention()) return
        val files = logFiles().sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
        var total = files.sumOf { it.length() }
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(retention.days.toLong())
        for (file in files) {
            if (file == activeFile) continue
            val expired = retention.days > 0 && file.lastModified() < cutoff
            val overLimit = retention.maxTotalBytes > 0 && total > retention.maxTotalBytes
            if (expired || overLimit) {
                val size = file.length()
                Files.delete(file.toPath())
                total -= size
            }
        }
    }

    @Synchronized override fun close() {
        if (closed) return
        end()
        closed = true
        accepting = false
        closing.submit {
            try {
                safely {
                    endFile()
                    prune()
                }
            } finally {
                worker.shutdown()
            }
        }
    }

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
        activeFile = path.toFile()
        bytes = 0L
    }

    private fun endFile() {
        flush?.cancel(false)
        flush = null
        val previous = writer
        writer = null
        activeFile = null
        previous?.close()
    }

    private companion object {
        const val MAX_PENDING_LINES = 2_048
        const val MAX_PENDING_BYTES = 1024L * 1024
        const val FLUSH_DELAY_SECONDS = 5L
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}
