package org.imunav.routing

import org.imunav.core.geo.GeoPoint
import org.imunav.core.route.TravelMode
import java.io.File
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Keep the developer CLI usable even though production CI invokes the Rust builder directly. */
class BuildGraphCliTest {
    @Test
    fun launcherBuildsAnImportableArchiveWithoutSearch() {
        val temporary = createTempDirectory().toFile()
        try {
            val input = File(temporary, "grid.osm").also { it.writeText(grid()) }
            val output = File(temporary, "pack")
            main(arrayOf("--osm", input.path, "--out", output.path, "--no-search", "--no-addresses"))
            val metadata = assertNotNull(PackInfo.parse(File(output, PackInfo.FILE).readText()))
            assertEquals("Region", metadata.name)
            assertEquals(PackInfo.FORMAT, metadata.routingFormat)
            assertEquals(listOf("car", "car_balanced", "foot"), metadata.profiles)
            assertFalse(File(output, "search.tantivy").exists())
            val extracted = File(temporary, "extracted")
            ZipFile(File(output.path + ".zip")).use { archive ->
                assertEquals(setOf("pack.json", "shared.routing", "car.routing", "car_balanced.routing", "foot.routing"), archive.entries().asSequence().map { it.name }.toSet())
                archive.entries().asSequence().forEach { entry ->
                    val destination = File(extracted, entry.name)
                    destination.parentFile.mkdirs()
                    archive.getInputStream(entry).use { source -> destination.outputStream().use { source.copyTo(it) } }
                }
            }
            OfflineGraph.load(extracted).use { graph ->
                val points = listOf(GeoPoint(50.002, 30.002), GeoPoint(50.012, 30.012))
                for (mode in TravelMode.entries) assertTrue(graph.route(points, mode).length > 1000)
            }
        } finally {
            temporary.deleteRecursively()
        }
    }

    @Test
    fun launcherRejectsIncompleteAndUnsupportedOptionsBeforeImport() {
        for (arguments in listOf(emptyArray(), arrayOf("--osm"), arrayOf("--out", "unused"), arrayOf("--threads", "2"))) {
            assertFailsWith<IllegalArgumentException> { main(arguments) }
        }
    }

    /** More than 200 connected junctions survive the launcher's production pruning default. */
    private fun grid(): String = buildString {
        appendLine("<osm version=\"0.6\">")
        for (row in 0 until 16) {
            for (column in 0 until 16) {
                appendLine("<node id=\"${row * 16 + column + 1}\" lat=\"${50.0 + row * 0.001}\" lon=\"${30.0 + column * 0.001}\"/>")
            }
        }
        for (axis in 0..1) {
            for (line in 0 until 16) {
                appendLine("<way id=\"${axis * 16 + line + 1}\">")
                for (point in 0 until 16) {
                    val id = if (axis == 0) line * 16 + point + 1 else point * 16 + line + 1
                    appendLine("<nd ref=\"$id\"/>")
                }
                appendLine("<tag k=\"highway\" v=\"residential\"/></way>")
            }
        }
        appendLine("</osm>")
    }
}
