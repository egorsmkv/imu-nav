package org.imunav.routing

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Immutable metadata for the versioned Rust routing and Tantivy search pack. */
data class PackInfo(
    val name: String,
    val builtAt: String,
    val source: String,
    val bounds: DoubleArray,
    val sizeBytes: Long,
    val profiles: List<String> = listOf("car"),
    val elevation: Boolean = false,
    val turnCosts: Boolean = false,
    val searchFormat: String? = null,
    val routingFormat: String? = null,
) {
    fun toJson(): String = PackInfoJson.encode(this)

    companion object {
        const val FILE = "pack.json"
        const val FORMAT = "rust-ch-2"

        /** Parse metadata before native I/O; malformed bounds and unsupported profiles are rejected. */
        fun parse(json: String): PackInfo? = runCatching {
            require(json.length <= 65_536)
            PackInfoJson.decode(json)
        }.getOrNull()
    }
}

/** Host-only convenience entry point, using the exact Rust importer invoked by CI. */
fun buildGraph(osmFile: File, outDir: File, name: String, minNetworkSize: Int = 200, withSearch: Boolean = true, withAddresses: Boolean = true): PackInfo {
    RoutingNative.build(osmFile.absolutePath, outDir.absolutePath, name, minNetworkSize, withSearch, withAddresses)
    return requireNotNull(PackInfo.parse(File(outDir, PackInfo.FILE).readText()))
}

/** Preserve native routing files and the Tantivy subtree at the archive root. */
fun zipPack(dir: File, zip: File) {
    ZipOutputStream(zip.outputStream().buffered()).use { out ->
        dir.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(dir).invariantSeparatorsPath }.forEach { file ->
            out.putNextEntry(ZipEntry(file.relativeTo(dir).invariantSeparatorsPath))
            file.inputStream().use { it.copyTo(out) }
            out.closeEntry()
        }
    }
}

/** Compatibility launcher for developers; production CI calls the Rust binary directly. */
fun main(args: Array<String>) {
    val flags = args.filter { it == "--no-search" || it == "--no-addresses" }.toSet()
    val options = (args.toList() - flags).chunked(2).associate {
        require(it.size == 2)
        it[0] to it[1]
    }
    require(options.keys.all { it in setOf("--osm", "--out", "--name") }) { "Use build-routing for advanced Rust builder options" }
    val input = File(requireNotNull(options["--osm"]))
    val output = File(requireNotNull(options["--out"]))
    buildGraph(input, output, options["--name"] ?: "Region", withSearch = "--no-search" !in flags, withAddresses = "--no-addresses" !in flags)
    zipPack(output, File(output.absolutePath + ".zip"))
}
