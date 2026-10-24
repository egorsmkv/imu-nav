package org.imunav.routing

import org.imunav.core.geo.GeoPoint
import org.imunav.core.search.AddressRow
import org.imunav.core.search.AddressSearch
import org.imunav.core.search.PlaceRow
import org.imunav.core.search.SearchDb
import org.imunav.core.search.StreetAddressRow
import org.imunav.core.search.StreetRow
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.sql.DriverManager
import kotlin.system.measureNanoTime

/** Opt-in synthetic benchmark: SEARCH_BENCHMARK=1 ./gradlew :routing:test --tests '*SearchBenchmarkTest*' --rerun-tasks. */
class SearchBenchmarkTest {
    @Test
    fun syntheticIndexes() {
        assumeTrue(System.getenv("SEARCH_BENCHMARK") == "1")
        val queries = listOf("ma", "го", "Main 22", "New Town Main 22", "Main New Town 22", "Main 99", "absent")
        for (prefix in listOf(false, true)) {
            val file = Files.createTempFile("search-benchmark", ".db").toFile()
            try {
                DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
                    connection.createStatement().use { sql ->
                        sql.executeUpdate("CREATE TABLE place(id INTEGER PRIMARY KEY, name TEXT, kind TEXT, lat REAL, lon REAL, population INTEGER)")
                        sql.executeUpdate("CREATE TABLE street(id INTEGER PRIMARY KEY, name TEXT, place_id INTEGER, lat REAL, lon REAL)")
                        sql.executeUpdate("CREATE TABLE addr(street_id INTEGER, number TEXT, lat REAL, lon REAL)")
                        sql.executeUpdate("CREATE INDEX addr_street ON addr(street_id)")
                        val options = if (prefix) ", prefix='2,3,4,6,8'" else ""
                        sql.executeUpdate("CREATE VIRTUAL TABLE street_fts USING fts4(name$options)")
                        sql.executeUpdate("CREATE VIRTUAL TABLE place_fts USING fts4(name$options)")
                        connection.autoCommit = false
                        repeat(10_000) { index ->
                            val id = index + 1
                            val lat = 40.0 + index / 1000.0
                            sql.executeUpdate("INSERT INTO place VALUES ($id, 'New Town $id', 'town', $lat, 30, 1000)")
                            sql.executeUpdate("INSERT INTO place_fts(rowid,name) VALUES ($id, 'new town $id')")
                            sql.executeUpdate("INSERT INTO street VALUES ($id, 'Main $id', $id, $lat, 30)")
                            sql.executeUpdate("INSERT INTO street_fts(rowid,name) VALUES ($id, 'main головна $id')")
                            sql.executeUpdate("INSERT INTO addr VALUES ($id, '22', $lat, 30)")
                        }
                        connection.commit()
                        connection.autoCommit = true
                        sql.executeUpdate("INSERT INTO street_fts(street_fts) VALUES ('optimize')")
                        sql.executeUpdate("INSERT INTO place_fts(place_fts) VALUES ('optimize')")
                    }
                }
                DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
                    connection.createStatement().use { it.executeUpdate("VACUUM") }
                }
                measure(file, queries, GeoPoint(45.0, 30.0), "synthetic prefix=$prefix")
            } finally {
                file.delete()
            }
        }
    }

    /** Uses a public pack copy; never rewrites an installed or source database. */
    @Test
    fun regionalPack() {
        val source = System.getenv("SEARCH_PACK_DB")?.let(::File)
        assumeTrue(source?.isFile == true)
        val queries = listOf("av", "ru", "Monaco", "Rue Bel Respiro 1", "Rue Bel Respiro 9999", "La Condamine Rue Imberty 6", "го", "absent")
        for (prefix in listOf(false, true)) {
            val file = Files.createTempFile("regional-search-benchmark", ".db").toFile()
            try {
                Files.copy(requireNotNull(source).toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                if (prefix) rebuildPrefixes(file)
                measure(file, queries, GeoPoint(43.7384, 7.4246), "Monaco prefix=$prefix")
            } finally {
                file.delete()
            }
        }
    }

    /** Rebuild only FTS in a disposable copy, preserving row IDs and all regional data. */
    private fun rebuildPrefixes(file: File) {
        DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            connection.createStatement().use { sql ->
                for ((table, column) in listOf("place_fts" to "names", "street_fts" to "name")) {
                    sql.executeUpdate("CREATE TEMP TABLE saved_fts AS SELECT rowid AS id, $column AS words FROM $table")
                    sql.executeUpdate("DROP TABLE $table")
                    sql.executeUpdate("CREATE VIRTUAL TABLE $table USING fts4($column, prefix='2,3,4,6,8')")
                    sql.executeUpdate("INSERT INTO $table(rowid,$column) SELECT id,words FROM saved_fts")
                    sql.executeUpdate("DROP TABLE saved_fts")
                    sql.executeUpdate("INSERT INTO $table($table) VALUES ('optimize')")
                }
            }
        }
        DriverManager.getConnection("jdbc:sqlite:$file").use { connection -> connection.createStatement().use { it.executeUpdate("VACUUM") } }
    }

    /** Measures only offline engine work; the first open is not an OS-cold page-cache measurement. */
    private fun measure(file: File, queries: List<String>, near: GeoPoint, label: String) {
        val cold = measureNanoTime { JdbcSearchDb(file).use { AddressSearch.search(it, queries.first(), near) } } / 1e6
        JdbcSearchDb(file).use { connection ->
            val db = CountingDb(connection)
            queries.forEach { query ->
                repeat(10) { AddressSearch.search(db, query, near) }
                db.calls = 0
                val times = List(50) { measureNanoTime { AddressSearch.search(db, query, near) } / 1e6 }.sorted()
                println("search_benchmark corpus=$label bytes=${file.length()} cold_open_ms=$cold query=$query calls=${db.calls / 50} p50_ms=${times[25]} p95_ms=${times[47]}")
            }
        }
    }

    /** Counts storage calls, excluding the warmup and index construction. */
    private class CountingDb(private val delegate: SearchDb) : SearchDb {
        var calls = 0
        override fun places(match: String, limit: Int, near: GeoPoint?): List<PlaceRow> {
            calls++
            return delegate.places(match, limit, near)
        }
        override fun streets(match: String, limit: Int, placeIds: Collection<Long>?, near: GeoPoint?): List<StreetRow> {
            calls++
            return delegate.streets(match, limit, placeIds, near)
        }
        override fun addresses(streetId: Long, number: String): List<AddressRow> {
            calls++
            return delegate.addresses(streetId, number)
        }
        override fun batchAddresses(streetIds: Collection<Long>, number: String): List<StreetAddressRow> {
            calls++
            return delegate.batchAddresses(streetIds, number)
        }
    }
}
