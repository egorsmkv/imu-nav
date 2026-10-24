package org.imunav.routing

import org.imunav.core.geo.GeoPoint
import org.imunav.core.search.AddressSearch
import org.imunav.core.search.PlaceRow
import org.imunav.core.search.ResultKind
import org.imunav.core.search.SearchDb
import org.imunav.core.search.StreetAddressRow
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import java.util.concurrent.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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

    @Test
    fun batchFindsDistantMultiwordTownAndRanksExactBeforeParts() {
        for (prefix in listOf(false, true)) {
            withIndex(prefix) { file ->
                DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
                    connection.createStatement().use { sql ->
                        repeat(12) { index ->
                            val id = index + 1
                            val town = if (id == 12) "New Town" else "Old City $id"
                            sql.execute("INSERT INTO place VALUES ($id, '$town', 'town', $id, 30, 1000)")
                            sql.execute("INSERT INTO place_fts(rowid,name) VALUES ($id, '${town.lowercase()}')")
                            sql.execute("INSERT INTO street VALUES ($id, 'Main', $id, $id, 30)")
                            sql.execute("INSERT INTO street_fts(rowid,name) VALUES ($id, 'main головна')")
                        }
                        sql.execute("INSERT INTO addr VALUES (1, '22 к1', 1, 30), (12, '22', 12, 30), (2, '220', 2, 30)")
                    }
                }
                JdbcSearchDb(file).use { actual ->
                    var calls = 0
                    val db = object : SearchDb by actual {
                        override fun batchAddresses(streetIds: Collection<Long>, number: String): List<StreetAddressRow> {
                            calls++
                            return actual.batchAddresses(streetIds, number)
                        }
                    }
                    for (query in listOf("Main 22", "New Town Main 22", "Main New Town 22")) {
                        val hits = AddressSearch.search(db, query, GeoPoint(1.0, 30.0))
                        assertEquals("Main, 22", hits.first().title)
                        if (query == "Main 22") assertEquals("Main, 22 к1", hits[1].title)
                        assertTrue(hits.none { it.title.endsWith(", 220") })
                    }
                    assertEquals(3, calls)
                    assertEquals("Main, 22 к1", AddressSearch.search(db, "Main 22 к1", null).first().title)
                    assertEquals(ResultKind.STREET, AddressSearch.search(db, "Main 99", null).first().kind)
                    assertEquals(AddressSearch.search(db, "Main 22", null), AddressSearch.search(db, "Main 22", null))
                    assertTrue(AddressSearch.search(db, "го", null).isNotEmpty())
                    assertTrue(AddressSearch.search(db, "ma", null).isNotEmpty())
                    assertTrue(AddressSearch.search(db, "absent", null).isEmpty())
                    var stages = 0
                    assertFailsWith<CancellationException> {
                        AddressSearch.search(db, "Main 22", null, checkCancelled = { if (++stages == 3) throw CancellationException() })
                    }
                }
            }
        }
    }

    @Test
    fun narrowingCachedPlacesStillQueriesWhenOnlyAnAliasMatches() {
        withIndex { file ->
            DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
                connection.createStatement().use { sql ->
                    sql.execute("INSERT INTO place VALUES (1, 'New Town', 'town', 1, 30, 1000), (2, 'Alias', 'town', 2, 30, 1000)")
                    sql.execute("INSERT INTO place_fts(rowid,name) VALUES (1, 'new town'), (2, 'new town alias')")
                    sql.execute("INSERT INTO street VALUES (2, 'Main', 2, 2, 30)")
                    sql.execute("INSERT INTO street_fts(rowid,name) VALUES (2, 'main')")
                    sql.execute("INSERT INTO addr VALUES (2, '22', 2, 30)")
                }
            }
            JdbcSearchDb(file).use { actual ->
                val lookups = mutableListOf<String>()
                val db = object : SearchDb by actual {
                    override fun places(match: String, limit: Int, near: GeoPoint?): List<PlaceRow> {
                        lookups += match
                        return actual.places(match, limit, near)
                    }
                }
                assertEquals("Alias", AddressSearch.search(db, "New Town Main 22", null).first().subtitle)
                assertTrue("new* town*" in lookups)
            }
        }
    }

    private fun withIndex(prefix: Boolean = false, test: (File) -> Unit) {
        val file = Files.createTempFile("synthetic-search", ".db").toFile()
        try {
            DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
                connection.createStatement().use { sql ->
                    sql.execute("CREATE TABLE place(id INTEGER PRIMARY KEY, name TEXT, kind TEXT, lat REAL, lon REAL, population INTEGER)")
                    sql.execute("CREATE TABLE street(id INTEGER PRIMARY KEY, name TEXT, place_id INTEGER, lat REAL, lon REAL)")
                    sql.execute("CREATE TABLE addr(street_id INTEGER, number TEXT, lat REAL, lon REAL)")
                    val options = if (prefix) ", prefix='2,3,4,6,8'" else ""
                    sql.execute("CREATE VIRTUAL TABLE street_fts USING fts4(name$options)")
                    sql.execute("CREATE VIRTUAL TABLE place_fts USING fts4(name$options)")
                }
            }
            test(file)
        } finally {
            file.delete()
        }
    }
}
