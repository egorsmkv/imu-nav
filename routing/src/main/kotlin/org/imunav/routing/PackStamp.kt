package org.imunav.routing

import java.io.File

/**
 * Remembers that a pack directory passed the full native validation, so later opens can skip
 * the scan that reads every routing record (about a minute for Ukraine on a slow phone).
 *
 * The stamp lists the metadata and routing files with their sizes and modification times, plus
 * the pack format and [VERSION]. Any change to those files, or a newer app with stricter checks
 * (bump [VERSION]), makes [matches] false and the next open validates fully again.
 * App-private storage is the trust boundary: only the app itself writes these files.
 */
object PackStamp {
    const val FILE = "validated.stamp"

    /** Bump when native validation gains checks that installed packs must pass again. */
    private const val VERSION = 2

    /** Record that [dir]'s current files were fully validated. */
    fun write(dir: File) {
        File(dir, FILE).writeText(describe(dir))
    }

    /** True when [dir] still holds exactly the files that were validated. */
    fun matches(dir: File): Boolean = File(dir, FILE).let { stamp -> stamp.isFile && stamp.length() <= MAX_BYTES && stamp.readText() == describe(dir) }

    /** Forget a validation, e.g. before the files are replaced. */
    fun clear(dir: File) {
        File(dir, FILE).delete()
    }

    private fun describe(dir: File): String = buildString {
        appendLine("version=$VERSION format=${PackInfo.FORMAT}")
        dir.listFiles { file -> file.isFile && (file.name == PackInfo.FILE || file.name.endsWith(".routing")) }
            .orEmpty()
            .sortedBy { it.name }
            .forEach { appendLine("${it.name} ${it.length()} ${it.lastModified()}") }
    }

    private const val MAX_BYTES = 4096L
}
