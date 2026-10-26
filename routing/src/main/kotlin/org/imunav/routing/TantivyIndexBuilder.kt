package org.imunav.routing

import org.imunav.core.search.PlaceRow
import org.imunav.core.search.SearchIndexCodec
import org.imunav.core.search.SearchIndexRow
import org.imunav.core.search.StreetAddressRow
import org.imunav.core.search.StreetRow
import org.imunav.core.search.TantivyNative
import org.imunav.core.search.TantivySearch
import java.io.File
import java.io.Writer
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/** Converts the desktop builder's relational staging data into the immutable index shipped in a pack. */
object TantivyIndexBuilder {
    /** Read-only export preserves aliases and SQLite row IDs; no installed database is modified. */
    fun build(database: File, directory: File) {
        require(!directory.exists()) { "Search output already exists" }
        val input = File.createTempFile("search-export-", ".jsonl", directory.parentFile)
        val staging = Files.createTempDirectory(directory.parentFile.toPath(), ".tantivy-build-").toFile()
        val index = File(staging, "index")
        try {
            DriverManager.getConnection("jdbc:sqlite:file:${database.toURI().rawPath}?mode=ro").use { connection ->
                input.bufferedWriter().use { writer -> export(connection, writer) }
            }
            TantivyNative.build(input.absolutePath, index.absolutePath)
            TantivySearch(index).close()
            Files.move(index.toPath(), directory.toPath())
        } finally {
            staging.deleteRecursively()
            input.delete()
        }
    }

    /** Stream typed rows instead of retaining a country-sized JSON document in memory. */
    private fun export(connection: Connection, writer: Writer) {
        writeRows(connection, writer, "SELECT p.id,p.name,p.kind AS place_kind,p.lat,p.lon,p.population,f.names AS text FROM place p JOIN place_fts f ON f.rowid=p.id") {
            val population = getLong("population")
            require(population in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
            SearchIndexRow.Place(PlaceRow(getLong("id"), getString("name"), getString("place_kind"), getDouble("lat"), getDouble("lon"), population.toInt()), getString("text"))
        }
        writeRows(
            connection,
            writer,
            "SELECT s.id,s.name,s.place_id,p.name AS place_name,s.lat,s.lon,f.name AS text FROM street s JOIN street_fts f ON f.rowid=s.id LEFT JOIN place p ON p.id=s.place_id",
        ) {
            val placeId = getLong("place_id").let { if (wasNull()) null else it }
            SearchIndexRow.Street(StreetRow(getLong("id"), getString("name"), placeId, getString("place_name"), getDouble("lat"), getDouble("lon")), getString("text"))
        }
        writeRows(connection, writer, "SELECT rowid AS id,street_id AS place_id,number,lat,lon FROM addr") {
            SearchIndexRow.Address(getLong("id"), StreetAddressRow(getLong("place_id"), getString("number"), getDouble("lat"), getDouble("lon")))
        }
    }

    private fun writeRows(connection: Connection, writer: Writer, query: String, read: ResultSet.() -> SearchIndexRow) {
        connection.createStatement().use { statement ->
            statement.executeQuery(query).use { rows ->
                while (rows.next()) writer.appendLine(SearchIndexCodec.row(rows.read()))
            }
        }
    }
}
