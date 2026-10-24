package org.imunav.app.search

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal
import android.os.OperationCanceledException
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Cancellation must reach blocking work before an empty offline result can start fallback. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PlaceSearchCancellationTest {
    @Test
    fun cancelledOfflineRequestSignalsWorkerAndNeverChecksOnlinePolicy() = runBlocking {
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val onlineChecks = AtomicInteger()
        val search = PlaceSearch(
            ApplicationProvider.getApplicationContext<Context>(),
            { _, _, signal ->
                signal.setOnCancelListener { cancelled.countDown() }
                entered.countDown()
                check(cancelled.await(5, TimeUnit.SECONDS))
                signal.throwIfCanceled()
                emptyList()
            },
            { true },
            {
                onlineChecks.incrementAndGet()
                true
            },
        )
        val request = async(Dispatchers.Default) { search.search("synthetic", null) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        request.cancelAndJoin()
        assertEquals(0L, cancelled.count)
        assertEquals(0, onlineChecks.get())
    }

    @Test
    fun cancellationClosesDelayedPhotonConnection() = runBlocking {
        ServerSocket(0).use { server ->
            val entered = CountDownLatch(1)
            val worker = Executors.newSingleThreadExecutor()
            try {
                val closed = worker.submit<Int> {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val reader = socket.getInputStream().bufferedReader()
                        while (!reader.readLine().isNullOrEmpty()) { /* Consume the synthetic request headers. */ }
                        entered.countDown()
                        reader.read()
                    }
                }
                val search = PlaceSearch(ApplicationProvider.getApplicationContext<Context>(), { _, _, _ -> emptyList() }, { false }, { true })
                search.setPhotonUrl("http://127.0.0.1:${server.localPort}/api")
                val request = async(Dispatchers.Default) { search.search("synthetic", null) }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                request.cancelAndJoin()
                assertEquals(-1, closed.get(5, TimeUnit.SECONDS))
            } finally {
                worker.shutdownNow()
            }
        }
    }

    @Test
    fun cancellationInterruptsActiveSqliteStatement() {
        val file = Files.createTempFile("synthetic-cancel", ".db").toFile()
        val worker = Executors.newSingleThreadExecutor()
        try {
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                db.execSQL("CREATE TABLE place(id INTEGER PRIMARY KEY, name TEXT, kind TEXT, lat REAL, lon REAL, population INTEGER)")
                db.execSQL("CREATE VIRTUAL TABLE place_fts USING fts4(name)")
                db.execSQL("CREATE VIRTUAL TABLE street_fts USING fts4(name)")
                db.execSQL("INSERT INTO street_fts(rowid,name) VALUES (1, 'synthetic')")
                // A recursive expression keeps the read active long enough to cancel without creating a huge fixture.
                db.execSQL(
                    "CREATE VIEW street AS SELECT 1 AS id, 'synthetic' AS name, NULL AS place_id, 30 AS lon, " +
                        "(WITH RECURSIVE n(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM n WHERE x<100000000) SELECT sum(x) FROM n) AS lat",
                )
            }
            AndroidSearchDb(file).use { db ->
                val signal = CancellationSignal()
                val started = CountDownLatch(1)
                val request = worker.submit<Boolean> {
                    started.countDown()
                    try {
                        db.search("synthetic", null, signal)
                        false
                    } catch (_: OperationCanceledException) {
                        true
                    }
                }
                assertTrue(started.await(5, TimeUnit.SECONDS))
                Thread.sleep(100)
                assertTrue(!request.isDone)
                signal.cancel()
                assertTrue(request.get(5, TimeUnit.SECONDS))
            }
        } finally {
            worker.shutdownNow()
            file.delete()
        }
    }
}
