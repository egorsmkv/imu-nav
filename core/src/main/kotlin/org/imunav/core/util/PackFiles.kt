package org.imunav.core.util

import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.zip.ZipEntry
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

    /** Cancellation after commit begins cannot misreport a successfully installed pack as canceled. */
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
    /** Limits apply to extracted data, since a small ZIP can expand far beyond its download size. */
    data class Limits(val maxBytes: Long, val maxEntries: Int) {
        init {
            require(maxBytes > 0 && maxEntries > 0)
        }
    }

    /** Leave room for the rest of the phone while limiting even highly compressed archives. */
    fun limitsFor(staging: File, maxBytes: Long, maxEntries: Int): Limits {
        val available = staging.usableSpace - FREE_SPACE_RESERVE_BYTES
        if (available <= 0) throw IOException("not enough free space to install pack")
        return Limits(minOf(maxBytes, available), maxEntries)
    }

    /**
     * How far an extraction has got. A streamed ZIP announces its entry count only in the central
     * directory at the very end, so the share of the archive already read is the only honest
     * percentage; it also weights a 1 GB routing file more than a 1 KB index file.
     */
    data class Progress(val extractedBytes: Long, val archiveBytes: Long) {
        /** 0–100 when the archive size is known (a picked file, an HTTP download), otherwise null. */
        fun percent(archiveSize: Long): Int? = if (archiveSize > 0) (archiveBytes * 100 / archiveSize).toInt().coerceIn(0, 100) else null
    }

    /**
     * Check cancellation even inside a single large final ZIP entry. With [expandNested], a `.zip`
     * entry is unpacked in place: a GitHub Actions artifact is a ZIP wrapping the pack's own ZIP.
     */
    fun extract(
        input: InputStream,
        staging: File,
        flat: Boolean,
        limits: Limits,
        check: () -> Unit,
        preserveDirectories: Set<String> = emptySet(),
        expandNested: Boolean = false,
        progress: (Progress) -> Unit,
    ) {
        val counted = CountingInputStream(input)
        val extraction = Extraction(staging, flat, limits, check, preserveDirectories, progress, counted)
        ZipInputStream(counted.buffered(BUFFER_BYTES)).use { zip -> extraction.entries(zip, nested = expandNested) }
        check()
    }

    /** Shared counters for an archive and any archive nested in it. */
    private class Extraction(
        private val staging: File,
        private val flat: Boolean,
        private val limits: Limits,
        private val check: () -> Unit,
        private val preserveDirectories: Set<String>,
        private val progress: (Progress) -> Unit,
        private val archive: CountingInputStream,
    ) {
        private val stagingPath = staging.canonicalPath + File.separator
        private val buffer = ByteArray(BUFFER_BYTES)
        private var bytes = 0L
        private var entries = 0

        fun entries(zip: ZipInputStream, nested: Boolean) {
            while (true) {
                check()
                val entry = zip.nextEntry ?: break
                entries++
                if (entries > limits.maxEntries) throw IOException("pack has too many entries")
                // Legacy routing ZIPs may have a wrapper directory; preserve only named index subtrees.
                val parts = entry.name.split('/')
                if (parts.any { it == ".." || '\\' in it }) throw IOException("bad path in pack")
                if (nested && !entry.isDirectory && entry.name.endsWith(".zip", ignoreCase = true)) {
                    // One level only; closing the inner reader frees its inflater but keeps the outer archive open.
                    ZipInputStream(KeepOpenInputStream(zip)).use { inner -> entries(inner, nested = false) }
                } else {
                    write(zip, entry, parts)
                }
            }
        }

        private fun write(zip: ZipInputStream, entry: ZipEntry, parts: List<String>) {
            val preserved = parts.indexOfFirst { it in preserveDirectories }
            val name = when {
                !flat -> entry.name
                preserved >= 0 -> parts.drop(preserved).joinToString("/")
                else -> File(entry.name).name
            }
            if (name.isBlank() || (flat && entry.isDirectory)) return
            val target = File(staging, name)
            if (!target.canonicalPath.startsWith(stagingPath)) throw IOException("bad path in pack: ${entry.name}")
            if (entry.isDirectory) {
                target.mkdirs()
                return
            }
            target.parentFile?.mkdirs()
            target.outputStream().use { output -> copy(zip, output) }
        }

        private fun copy(zip: ZipInputStream, output: OutputStream) {
            while (true) {
                check()
                val count = zip.read(buffer)
                if (count < 0) break
                if (count > limits.maxBytes - bytes) throw IOException("pack exceeds extracted size limit")
                output.write(buffer, 0, count)
                bytes += count
                progress(Progress(bytes, archive.count))
            }
        }
    }

    /** Counts compressed bytes consumed, which (unlike extracted bytes) can be compared with the file size. */
    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        var count = 0L
            private set

        override fun read(): Int = super.read().also { if (it >= 0) count++ }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = super.read(buffer, offset, length).also { if (it > 0) count += it }

        override fun skip(n: Long): Long = super.skip(n).also { count += it }
    }

    /** Lets a nested archive reader be closed without closing the entry it reads from. */
    private class KeepOpenInputStream(input: InputStream) : FilterInputStream(input) {
        override fun close() = Unit
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
    private const val FREE_SPACE_RESERVE_BYTES = 256L * 1024 * 1024
}
