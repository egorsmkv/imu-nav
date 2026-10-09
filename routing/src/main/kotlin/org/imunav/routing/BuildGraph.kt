package org.imunav.routing

import com.graphhopper.GraphHopper
import com.graphhopper.reader.dem.ElevationProvider
import java.io.File
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Metadata written next to the graph as `pack.json`, shown in the app. */
data class PackInfo(
    val name: String,
    val builtAt: String,
    val source: String,
    val bounds: DoubleArray,
    val sizeBytes: Long,
    /** Routing profiles in the pack; packs from before walking support have only "car". */
    val profiles: List<String> = listOf(GraphSpec.CAR),
    /** The graph stores a height for every road point (needed for barometric terrain matching). */
    val elevation: Boolean = false,
) {
    /** Written as `pack.json` next to the graph; the app reads it to show and compare packs. */
    fun toJson(): String = "{\"name\":\"$name\",\"builtAt\":\"$builtAt\",\"source\":\"$source\",\"graphhopper\":\"11.0\"," +
        "\"bounds\":[${bounds.joinToString(",")}],\"sizeBytes\":$sizeBytes," +
        "\"profiles\":[${profiles.joinToString(",") { "\"$it\"" }}],\"elevation\":$elevation}"

    companion object {
        const val FILE = "pack.json"

        /** Read `pack.json`; null if it is missing fields. */
        fun parse(json: String): PackInfo? = runCatching {
            // A tiny hand-written reader: pack.json is small and flat, so no JSON library is needed.
            fun str(key: String) = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(json)?.groupValues?.get(1).orEmpty()
            val boundsText = Regex("\"bounds\"\\s*:\\s*\\[([^]]*)]").find(json)?.groupValues?.get(1) ?: error("pack.json has no bounds")
            val b = boundsText.split(',').map { it.trim().toDouble() }.toDoubleArray()
            val size = Regex("\"sizeBytes\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toLong() ?: 0
            val profiles = Regex("\"profiles\"\\s*:\\s*\\[([^]]*)]").find(json)?.groupValues?.get(1)
                ?.split(',')?.map { it.trim().trim('"') }?.filter { it.isNotEmpty() }
                ?: listOf(GraphSpec.CAR)
            val elevation = Regex("\"elevation\"\\s*:\\s*true").containsMatchIn(json)
            PackInfo(str("name"), str("builtAt"), str("source"), b, size, profiles, elevation)
        }.getOrNull()
    }
}

/**
 * Where a pack's heights come from.
 * @param provider GraphHopper elevation source: `skadi` (default, AWS terrain tiles, ~30–90 m),
 *   `srtm`, `srtmgl1`, `cgiar`, `gmted` or `multi`. Tiles are downloaded once into [cacheDir].
 * @param custom a ready-made provider instead (tests, or DEM files you prepared yourself)
 */
class PackElevation(val provider: String = "skadi", val cacheDir: File = File("elevation-cache"), val custom: ElevationProvider? = null)

/**
 * Import an OpenStreetMap extract into a GraphHopper graph with contraction hierarchies for the
 * car and foot profiles, write `pack.json` and return its metadata. Needs a desktop JVM (uses Janino).
 */
fun buildGraph(
    osmFile: File,
    outDir: File,
    name: String,
    minNetworkSize: Int = 200,
    withSearch: Boolean = true,
    withAddresses: Boolean = true,
    profiles: List<String> = GraphSpec.ALL_PROFILES,
    /** Store road heights from a digital elevation model (null = flat 2-D pack, like older packs). */
    elevation: PackElevation? = null,
): PackInfo {
    require(osmFile.exists()) { "OSM file not found: $osmFile" }
    outDir.deleteRecursively()
    outDir.mkdirs()
    val hopper = GraphHopper()
    val config = GraphSpec.config(outDir.absolutePath, profiles)
        .putObject("datareader.file", osmFile.absolutePath)
        // Drop tiny disconnected road islands (parking lots, private yards) that routes cannot use.
        .putObject("prepare.min_network_size", minNetworkSize)
    if (elevation != null) {
        // Bridges and tunnels get heights interpolated between their ends (not the valley below).
        config.putObject("graph.elevation.interpolate", "bilinear")
            // Long straight edges get extra points so hills along them are not lost.
            .putObject("graph.elevation.long_edge_sampling_distance", GraphSpec.ELEVATION_SAMPLING_M)
        if (elevation.custom == null) {
            elevation.cacheDir.mkdirs()
            config.putObject("graph.elevation.provider", elevation.provider)
                .putObject("graph.elevation.cache_dir", elevation.cacheDir.absolutePath)
        }
    }
    hopper.init(config)
    elevation?.custom?.let { hopper.setElevationProvider(it) }
    hopper.importOrLoad()
    val b = hopper.baseGraph.bounds
    if (withSearch) SearchIndexBuilder.build(osmFile, hopper, File(outDir, SearchIndexBuilder.FILE), withAddresses)
    hopper.close()
    val size = outDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    val info = PackInfo(name, Instant.now().toString(), osmFile.name, doubleArrayOf(b.minLat, b.maxLat, b.minLon, b.maxLon), size, profiles, elevation != null)
    File(outDir, PackInfo.FILE).writeText(info.toJson())
    return info
}

/** Zip a graph folder (flat: its files at the archive root) for distribution. */
fun zipPack(dir: File, zip: File) {
    ZipOutputStream(zip.outputStream().buffered()).use { out ->
        dir.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }.forEach { f ->
            out.putNextEntry(ZipEntry(f.name))
            f.inputStream().use { it.copyTo(out) }
            out.closeEntry()
        }
    }
}

/**
 * Build an offline routing pack:
 *   ./gradlew :routing:run --args="--osm ukraine-latest.osm.pbf --out build/graph-ukraine --name Ukraine"
 * Produces the graph folder (with the address search index `search.db`) and `<out>.zip` to import
 * in the app (Settings → Offline routing). `--no-addresses` skips house numbers, `--no-search` the index.
 * `--elevation skadi` (or srtm, cgiar, gmted…) stores road heights for barometric terrain matching;
 * `--elevation-cache <dir>` keeps the downloaded elevation tiles for the next build.
 */
fun main(args: Array<String>) {
    val flags = args.filter { it == "--no-search" || it == "--no-addresses" }.map { it.removePrefix("--") }.toSet()
    val opts = (args.toList() - flags.map { "--$it" }.toSet()).windowed(2, 2).associate { (k, v) -> k.removePrefix("--") to v }
    val osm = File(opts["osm"] ?: error("--osm <file.osm.pbf> is required"))
    val out = File(opts["out"] ?: "graph-${osm.nameWithoutExtension.substringBefore('.')}")
    val name = opts["name"] ?: osm.nameWithoutExtension.substringBefore('.')
    val t0 = System.currentTimeMillis()
    println("[graph] importing ${osm.name} (${osm.length() / 1_048_576} MB) → $out")
    val elevation = opts["elevation"]?.let { PackElevation(it, File(opts["elevation-cache"] ?: "elevation-cache")) }
    if (elevation != null) println("[graph] elevation from ${elevation.provider}, tiles cached in ${elevation.cacheDir}")
    val info = buildGraph(osm, out, name, withSearch = "no-search" !in flags, withAddresses = "no-addresses" !in flags, elevation = elevation)
    val zip = File(out.absolutePath + ".zip")
    zipPack(out, zip)
    println("[graph] done in ${(System.currentTimeMillis() - t0) / 1000}s: graph ${info.sizeBytes / 1_048_576} MB, pack ${zip.length() / 1_048_576} MB → $zip")
    println("[graph] bounds lat ${info.bounds[0]}..${info.bounds[1]}, lon ${info.bounds[2]}..${info.bounds[3]}")
}
