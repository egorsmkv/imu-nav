package org.imunav.routing

import org.imunav.core.search.SearchIndexJson
import org.imunav.core.search.TantivyNative
import org.imunav.core.search.TantivySearch
import java.io.File
import java.io.Writer
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager

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

    /** Stream rows instead of retaining a country-sized JSON document in memory. */
    private fun export(connection: Connection, writer: Writer) {
        val queries = listOf(
            "place" to "SELECT p.id,p.name,p.kind AS place_kind,p.lat,p.lon,p.population,f.names AS text FROM place p JOIN place_fts f ON f.rowid=p.id",
            "street" to
                "SELECT s.id,s.name,s.place_id,p.name AS place_name,s.lat,s.lon,f.name AS text FROM street s JOIN street_fts f ON f.rowid=s.id LEFT JOIN place p ON p.id=s.place_id",
            "address" to "SELECT rowid AS id,street_id AS place_id,number,lat,lon FROM addr",
        )
        for ((kind, query) in queries) writeRows(connection, writer, kind, query)
    }

    private fun writeRows(connection: Connection, writer: Writer, kind: String, query: String) {
        connection.createStatement().use { statement ->
            statement.executeQuery(query).use { rows ->
                val metadata = rows.metaData
                while (rows.next()) {
                    val row = mutableMapOf<String, Any?>("kind" to kind)
                    for (column in 1..metadata.columnCount) row[metadata.getColumnLabel(column)] = rows.getObject(column)
                    writer.appendLine(SearchIndexJson.encode(row))
                }
            }
        }
    }
}
