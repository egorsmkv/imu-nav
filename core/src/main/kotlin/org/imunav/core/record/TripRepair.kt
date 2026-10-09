package org.imunav.core.record

import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Fail closed when replacement is unavailable: never delete the only original recording. */
internal object TripRepair {
    /** Injectable file operations allow deterministic disk-write and rename failures in tests. */
    fun repair(
        file: File,
        openOutput: (File) -> OutputStream = { it.outputStream() },
        replace: (File, File) -> Unit = { temporary, original ->
            Files.move(temporary.toPath(), original.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        },
    ) {
        if (!file.exists()) return
        val events = TripFormat.read(file)
        // A unique sibling stays on the same filesystem and cannot overwrite an earlier repair file.
        val temporary = File.createTempFile("${file.name}.", ".repair", file.absoluteFile.parentFile)
        // Cleanup also covers serialization failures; the original failure is always rethrown.
        try {
            // Own the stream outside the recorder too, so a failing gzip constructor still closes it.
            openOutput(temporary).use { output ->
                TripRecorder(output).use { recorder -> events.forEach { recorder.record(it) } }
            }
            replace(temporary, file)
        } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
            runCatching { Files.deleteIfExists(temporary.toPath()) }.onFailure { failure.addSuppressed(it) }
            throw failure
        }
    }
}
