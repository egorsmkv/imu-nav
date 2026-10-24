package org.imunav.routing

import org.imunav.core.geo.GeoPoint
import org.imunav.core.search.AddressSearch
import org.imunav.core.search.ResultKind
import org.imunav.core.search.TantivyRequest
import org.imunav.core.search.TantivySearch
import org.imunav.search.CountingSearchDb
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.measureNanoTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Complete JNI searches, including Kotlin planning/ranking and serialization, compared with SQLite. */
class TantivyPrototypeTest {
    @Test
    fun parityAndTimings() {
        val directory = System.getenv("TANTIVY_DATA_DIR")?.let(::File)
        assumeTrue(directory?.isDirectory == true)
        val datasets = requireNotNull(directory).listFiles().orEmpty().filter { it.extension == "db" }.sortedBy { it.name }
        assertTrue(datasets.isNotEmpty())
        for (database in datasets) compare(database)
    }

    @Test
    fun closeWaitsForReaderAndCancellationIsIsolated() {
        val directory = System.getenv("TANTIVY_DATA_DIR")?.let(::File)
        assumeTrue(directory?.isDirectory == true)
        val index = File(requireNotNull(directory), "synthetic-10000.tantivy")
        assumeTrue(index.isDirectory)
        val executor = Executors.newFixedThreadPool(2)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            TantivySearch(index).use { native ->
                TantivyRequest().use { request ->
                    val search = executor.submit {
                        native.search("Main", null, request) {
                            entered.countDown()
                            check(release.await(10, TimeUnit.SECONDS))
                        }
                    }
                    assertTrue(entered.await(10, TimeUnit.SECONDS))
                    val close = executor.submit { native.close() }
                    request.cancel()
                    release.countDown()
                    val failure = assertFailsWith<ExecutionException> { search.get(10, TimeUnit.SECONDS) }
                    assertTrue(failure.cause is CancellationException)
                    close.get(10, TimeUnit.SECONDS)
                    assertFailsWith<IllegalStateException> { native.search("Main", null, request) }
                }
            }
            TantivySearch(index).use { reopened ->
                TantivyRequest().use { assertTrue(reopened.search("Main", null, it).isNotEmpty()) }
            }
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    private fun compare(database: File) {
        val index = File(database.parentFile, "${database.nameWithoutExtension}.tantivy")
        val regional = database.name.startsWith("regional")
        val queries = if (regional) {
            listOf("av", "ru", "Monaco", "Rue Bel Respiro 1", "Rue Bel Respiro 9999", "La Condamine Rue Imberty 6", "го", "absent")
        } else {
            listOf("ma", "го", "Main 22", "Main 22 к1", "Main 220", "New Town Main 22", "Main New Town 22", "Main 99", "Far Away Town Main 22", "D'Artagnan", "absent")
        }
        val near = if (regional) GeoPoint(43.7384, 7.4246) else GeoPoint(45.0, 30.0)
        val sqliteOpen = measureNanoTime { JdbcSearchDb(database).use { AddressSearch.search(it, queries.first(), near) } } / 1e6
        val nativeOpen = measureNanoTime { TantivySearch(index).use { search -> TantivyRequest().use { search.search(queries.first(), near, it) } } } / 1e6
        println(
            "tantivy_open dataset=${database.name} sqlite_ms=$sqliteOpen native_ms=$nativeOpen sqlite_bytes=${database.length()} native_bytes=${index.walkTopDown().filter {
                it.isFile
            }.sumOf { it.length() }}",
        )
        JdbcSearchDb(database).use { sqlite ->
            TantivySearch(index).use { native ->
                for (reference in listOf(near, null, GeoPoint(0.0, 179.999))) {
                    for (query in queries) {
                        val expected = AddressSearch.search(sqlite, query, reference)
                        val actual = TantivyRequest().use { native.search(query, reference, it) }
                        assertEquals(expected, actual, "${database.name}: $query reference=$reference")
                        if (!regional && query == "Main 22") {
                            assertTrue(actual.count { it.kind == ResultKind.ADDRESS && it.title.endsWith(", 22") } > 5)
                        }
                        if (!regional && query == "Far Away Town Main 22") {
                            assertTrue(actual.any { it.kind == ResultKind.ADDRESS && it.subtitle == "Far Away Town" })
                        }
                    }
                }
                TantivyRequest().use { request ->
                    request.cancel()
                    assertFailsWith<CancellationException> { native.search(queries.first(), near, request) }
                }
                for ((queryIndex, query) in queries.withIndex()) {
                    repeat(30) {
                        AddressSearch.search(sqlite, query, near)
                        TantivyRequest().use { native.search(query, near, it) }
                    }
                    val sqlTimes = mutableListOf<Double>()
                    val nativeTimes = mutableListOf<Double>()
                    repeat(100) { iteration ->
                        val sqlRun = { sqlTimes.add(measureNanoTime { AddressSearch.search(sqlite, query, near) } / 1e6) }
                        val nativeRun = { nativeTimes.add(measureNanoTime { TantivyRequest().use { native.search(query, near, it) } } / 1e6) }
                        if (iteration % 2 == 0) {
                            sqlRun()
                            nativeRun()
                        } else {
                            nativeRun()
                            sqlRun()
                        }
                    }
                    val counted = CountingSearchDb(sqlite)
                    AddressSearch.search(counted, query, near)
                    sqlTimes.sort()
                    nativeTimes.sort()
                    println(
                        "tantivy_warm dataset=${database.name} case=$queryIndex sqlite_calls=${counted.calls} house_calls=${counted.houseCalls} retrieval_jni_calls=1 sqlite_p50=${sqlTimes[49]} sqlite_p95=${sqlTimes[94]} native_p50=${nativeTimes[49]} native_p95=${nativeTimes[94]}",
                    )
                }
            }
        }
    }
}
