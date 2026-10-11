package org.imunav.routing

import org.imunav.core.geo.GeoPoint
import org.imunav.core.search.AddressSearch
import org.imunav.core.search.ResultKind
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.assertEquals

class SearchCandidateTest {
    @Test
    fun nearestAddressSurvivesCandidateLimitInLargeCorpus() {
        withIndex { file ->
            DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
                connection.createStatement().use { sql ->
                    repeat(100) { index ->
                        val id = index + 1
                        val lat = if (id == 100) 50.0 else 48.0
                        sql.execute("INSERT INTO place VALUES ($id, 'Town $id', 'town', $lat, 30, 1000)")
                        sql.execute("INSERT INTO street VALUES ($id, 'Shevchenko', $id, $lat, 30)")
                        sql.execute("INSERT INTO street_fts(rowid,name) VALUES ($id, 'shevchenko')")
                    }
                    sql.execute("INSERT INTO addr VALUES (100, '22', 50, 30)")
                }
            }
            JdbcSearchDb(file).use { db ->
                val results = AddressSearch.search(db, "Shevchenko 22", GeoPoint(50.0, 30.0))
                assertEquals(ResultKind.ADDRESS, results.first().kind)
                assertEquals("Town 100", results.first().subtitle)
                assertEquals(0.0, results.first().distanceM)
            }
        }
    }

    @Test
    fun candidateOrderingWrapsLongitudeAtDateline() {
        withIndex { file ->
            DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
                connection.createStatement().use { sql ->
                    sql.execute("INSERT INTO street VALUES (1, 'Main', NULL, 0, 170)")
                    sql.execute("INSERT INTO street VALUES (2, 'Main', NULL, 0, -179.99)")
                    sql.execute("INSERT INTO street_fts(rowid,name) VALUES (1, 'main'), (2, 'main')")
                    sql.execute("INSERT INTO place VALUES (1, 'Main', 'town', 0, 170, 1000), (2, 'Main', 'town', 0, -179.99, 1000)")
                    sql.execute("INSERT INTO place_fts(rowid,name) VALUES (1, 'main'), (2, 'main')")
                }
            }
            JdbcSearchDb(file).use { db ->
                assertEquals(2L, db.streets("main*", 1, near = GeoPoint(0.0, 179.99)).single().id)
                assertEquals(2L, db.places("main*", 1, GeoPoint(0.0, 179.99)).single().id)
            }
        }
    }

    private fun withIndex(test: (File) -> Unit) {
        val file = Files.createTempFile("synthetic-search", ".db").toFile()
        try {
            DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
                connection.createStatement().use { sql ->
                    sql.execute("CREATE TABLE place(id INTEGER PRIMARY KEY, name TEXT, kind TEXT, lat REAL, lon REAL, population INTEGER)")
                    sql.execute("CREATE TABLE street(id INTEGER PRIMARY KEY, name TEXT, place_id INTEGER, lat REAL, lon REAL)")
                    sql.execute("CREATE TABLE addr(street_id INTEGER, number TEXT, lat REAL, lon REAL)")
                    sql.execute("CREATE VIRTUAL TABLE street_fts USING fts4(name)")
                    sql.execute("CREATE VIRTUAL TABLE place_fts USING fts4(name)")
                }
            }
            test(file)
        } finally {
            file.delete()
        }
    }
}
