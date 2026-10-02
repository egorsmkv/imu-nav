package org.imunav.core.record

import java.io.File

/** Creates independent recording copies so sharing never exposes or modifies the trip stored by the app. */
object TripExport {
    /**
     * Copy a finished recording on an I/O worker, preserving even a truncated gzip tail for replay salvage.
     * Each export has its own file so another share or deletion of the original cannot change an attachment.
     */
    fun snapshot(recording: File, directory: File): File {
        require(recording.isFile && recording.length() > 0) { "Trip recording is missing or empty" }
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create trip export directory" }
        val exported = File.createTempFile(recording.name.removeSuffix(".rec.gz") + "-", ".rec.gz", directory)
        var complete = false
        try {
            recording.inputStream().use { input -> exported.outputStream().use { output -> input.copyTo(output) } }
            complete = true
            return exported
        } finally {
            if (!complete) exported.delete() // Only an incomplete, never-shared copy is removed.
        }
    }
}
