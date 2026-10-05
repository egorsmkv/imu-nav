package org.imunav.core.record

import java.io.EOFException
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Creates a standard ZIP attachment without changing the gzip recording stored by the app. */
object TripExport {
    /**
     * Put the decompressed recording in a ZIP so common archive apps can open it directly.
     * A missing gzip footer is expected after an interrupted trip; keep the lines decoded before it.
     * Each export has its own file so deleting the original cannot change an attachment.
     */
    fun snapshot(recording: File, directory: File): File {
        require(recording.isFile && recording.length() > 0 && recording.name.endsWith(".rec.gz")) { "Trip recording is missing or invalid" }
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create trip export directory" }
        val exported = File.createTempFile(recording.name.removeSuffix(".rec.gz") + "-", ".zip", directory)
        var complete = false
        try {
            ZipOutputStream(exported.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(recording.name.removeSuffix(".gz")))
                recording.inputStream().buffered().use { source ->
                    GZIPInputStream(source).use { gzip ->
                        try {
                            gzip.copyTo(zip)
                        } catch (_: EOFException) {
                            // The flushed recording is still useful when the final gzip footer was lost.
                        }
                    }
                }
                zip.closeEntry()
            }
            complete = true
            return exported
        } finally {
            if (!complete) exported.delete() // Only an incomplete, never-shared archive is removed.
        }
    }
}
