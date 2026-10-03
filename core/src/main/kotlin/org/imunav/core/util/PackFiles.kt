package org.imunav.core.util

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.zip.ZipInputStream
import kotlin.concurrent.read
import kotlin.concurrent.write

/** Readers retain ownership until their operation returns; replacement and close require exclusive ownership. */
class ResourceAccess {
    private val lock = ReentrantReadWriteLock(true)
    fun <T> read(block: () -> T): T = lock.read(block)
    fun <T> write(block: () -> T): T = lock.write(block)
}

/** Cancellation and the irreversible part of installation have one ordering point. */
class PackOperation {
    private var cancelled = false
    private var committing = false

    @Volatile var result: String? = null

    /** Cancellation after commit begins cannot misreport a successfully installed pack as cancelled. */
    @Synchronized
    fun cancel() {
        if (!committing) cancelled = true
    }

    @Synchronized
    fun checkCancelled() {
        if (cancelled) throw InterruptedException("pack operation cancelled")
    }

    /** Call after acquiring exclusive resource ownership, immediately before changing installed files. */
    @Synchronized
    fun beginCommit() {
        checkCancelled()
        committing = true
    }
}

/** Shared archive handling and rollback for routing and visual map packs. All methods run on workers. */
object PackFiles {
    /** Check cancellation even inside a single large final ZIP entry. */
    fun extract(input: InputStream, staging: File, flat: Boolean, check: () -> Unit, progress: (Long) -> Unit) {
        val stagingPath = staging.canonicalPath + File.separator
        var bytes = 0L
        ZipInputStream(input.buffered(BUFFER_BYTES)).use { zip ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                check()
                val entry = zip.nextEntry ?: break
                val name = if (flat) File(entry.name).name else entry.name
                if (name.isBlank() || (flat && entry.isDirectory)) continue
                val target = File(staging, name)
                if (!target.canonicalPath.startsWith(stagingPath)) throw IOException("bad path in pack: ${entry.name}")
                if (entry.isDirectory) {
                    target.mkdirs()
                    continue
                }
                target.parentFile?.mkdirs()
                target.outputStream().use { output ->
                    while (true) {
                        check()
                        val count = zip.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        bytes += count
                        progress(bytes)
                    }
                }
            }
        }
        check()
    }

    /** Preserve the previous directory until activation succeeds, restoring it if any commit step fails. */
    fun replace(staging: File, current: File, close: () -> Unit, activate: () -> Unit) {
        val backup = File(current.parentFile, "previous-${UUID.randomUUID()}")
        close()
        val hadPrevious = current.exists()
        if (hadPrevious && !current.renameTo(backup)) {
            activate()
            throw IOException("cannot back up installed pack")
        }
        try {
            if (!staging.renameTo(current)) throw IOException("cannot install pack")
            activate()
        } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
            // Library linkage errors must also restore the working pack before propagating.
            runCatching {
                close()
                restoreDirectory(current, backup, hadPrevious)
                activate()
            }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
        backup.deleteRecursively()
    }

    private fun restoreDirectory(current: File, backup: File, hadPrevious: Boolean) {
        if (current.exists() && !current.deleteRecursively()) throw IOException("cannot remove failed pack")
        if (hadPrevious && !backup.renameTo(current)) throw IOException("cannot restore previous pack: $backup")
    }

    private const val BUFFER_BYTES = 1 shl 16
}
