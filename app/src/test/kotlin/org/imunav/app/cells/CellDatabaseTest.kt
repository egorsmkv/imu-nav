package org.imunav.app.cells

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.imunav.core.cells.CellCsv
import org.imunav.core.cells.CellKey
import org.imunav.core.cells.CellTower
import org.imunav.core.cells.Radio
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream

/** The offline tower database: lookups across sources, learning, imports, sync and export. */
@RunWith(RobolectricTestRunner::class)
class CellDatabaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: CellDatabase

    private fun lte(cid: Long, area: Int = 100, mcc: Int = 255) = CellKey(Radio.LTE, mcc, 1, area, cid)
    private fun tower(key: CellKey, lat: Double, lon: Double, samples: Int = 5) = CellTower(key, lat, lon, 800.0, samples)

    @Before
    fun open() {
        context.deleteDatabase("cells.db")
        db = CellDatabase(context)
    }

    @After
    fun close() = db.close()

    @Test
    fun lookupPrefersHigherSourcesAndLoosensTheMatchStepByStep() {
        val exact = lte(25_600)
        db.upsert(CellSource.MOZILLA, listOf(tower(exact, 50.0, 30.0)))
        db.upsert(CellSource.SHARED, listOf(tower(exact, 50.1, 30.1)))
        val (found, match) = checkNotNull(db.resolve(exact))
        assertEquals(CellDatabase.Match.EXACT, match)
        assertEquals("the sync server outranks Mozilla", 50.1, found.lat, 1e-9)

        // The operator renumbered the area code: the cell id still finds the tower.
        val (renumbered, how) = checkNotNull(db.resolve(lte(25_600, area = 999)))
        assertEquals(CellDatabase.Match.CELL_ID, how)
        assertEquals(50.1, renumbered.lat, 1e-9)

        // An unknown LTE sector of a known site (same eNB = cid / 256) borrows the site's position.
        db.upsert(CellSource.OPENCELLID, listOf(tower(lte(51_201), 49.0, 24.0), tower(lte(51_202), 49.0002, 24.0002)))
        val (site, siteMatch) = checkNotNull(db.resolve(lte(51_210, area = 7)))
        assertEquals(CellDatabase.Match.SITE, siteMatch)
        assertEquals(49.0, site.lat, 0.001)

        assertNull("other radios never borrow LTE sites", db.resolve(CellKey(Radio.GSM, 255, 1, 7, 51_210)))
        assertEquals(setOf(exact), db.lookup(listOf(exact, lte(1))).keys)
        assertEquals(2L, db.counts()[CellSource.OPENCELLID])
    }

    @Test
    fun learnedTowersAreTrackedForUpload() {
        val key = lte(77)
        db.learn(listOf(key), 50.45, 30.52, accuracyM = 10.0, nowMs = 1_000)
        db.learn(listOf(key), 50.4502, 30.5202, accuracyM = 10.0, nowMs = 2_000)
        val learned = db.learnedSince(1_500)
        assertEquals(1, learned.size)
        assertEquals(key, learned.single().key)
        assertTrue("two observations were merged", learned.single().samples >= 2)
        assertTrue(db.learnedSince(2_000).isEmpty())
    }

    @Test
    fun importKeepsOnlyChosenCountriesAndCanBeCancelled() {
        val csv = ByteArrayOutputStream().also { out ->
            CellCsv.writeGzip(listOf(tower(lte(1), 50.0, 30.0), tower(lte(2, mcc = 250), 55.0, 37.0), tower(lte(3), 48.0, 35.0)), out)
        }.toByteArray()
        fun input() = GZIPInputStream(ByteArrayInputStream(csv))
        var lastProgress = 0L to 0L
        val kept = db.importStream(CellSource.OPENCELLID, input(), mccs = setOf(255)) { read, keptRows ->
            lastProgress = read to keptRows
            true
        }
        assertEquals(2L, kept)
        assertEquals(3L to 2L, lastProgress)
        assertNull("other countries are skipped", db.resolve(lte(2, mcc = 250)))

        db.clear(CellSource.OPENCELLID)
        assertEquals(0L, db.counts()[CellSource.OPENCELLID])
        db.vacuum()
    }

    @Test
    fun sharedSyncReplacesRemovesAndAddsAtomically() {
        // Different LTE sites (cid / 256), so a removed cell cannot be found again through a sibling sector.
        val first = lte(256)
        val second = lte(512)
        db.applySharedSync(emptyList(), listOf(tower(first, 50.0, 30.0), tower(second, 50.0, 30.1)))
        db.applySharedSync(removals = listOf(first), towers = listOf(tower(lte(768), 50.0, 30.2)))
        assertNull(db.resolve(first))
        assertNotNull(db.resolve(second))
        db.applySharedSync(emptyList(), listOf(tower(lte(1024), 50.0, 30.3)), replace = true)
        assertEquals(1L, db.counts()[CellSource.SHARED])
    }

    @Test
    fun exportWritesEachCellOnceFromTheSourceLookupsWouldUse() {
        db.upsert(CellSource.SHARED, listOf(tower(lte(1), 50.0, 30.0)))
        db.upsert(CellSource.MOZILLA, listOf(tower(lte(1), 51.0, 31.0), tower(lte(2), 49.0, 24.0)))
        val out = ByteArrayOutputStream()
        val progress = ArrayList<Long>()
        assertEquals(2L, db.exportMerged(out) { progress += it })
        assertEquals(listOf(2L), progress)
        val rows = ArrayList<CellTower>()
        CellCsv.read(GZIPInputStream(ByteArrayInputStream(out.toByteArray()))) { rows += it }
        assertEquals(50.0, rows.single { it.key == lte(1) }.lat, 1e-6)
        db.upsert(CellSource.MOZILLA, emptyList())
    }

    @Test
    fun mapBoxReturnsAllTowersOrAnEvenSampleWhenThereAreTooMany() {
        val towers = (0 until 100).map { i -> tower(lte(1_000L + i), 50.0 + (i / 10) * 0.01, 30.0 + (i % 10) * 0.01) }
        db.upsert(CellSource.LEARNED, towers.take(50))
        db.upsert(CellSource.BUNDLED, towers.drop(50))

        val (all, thinnedAll) = db.towersIn(49.9, 29.9, 50.2, 30.2, limit = 500)
        assertEquals(100, all.size)
        assertFalse(thinnedAll)
        assertEquals(setOf(CellSource.LEARNED, CellSource.BUNDLED), all.map { it.second }.toSet())

        val (sample, thinned) = db.towersIn(49.9, 29.9, 50.2, 30.2, limit = 16)
        assertTrue(thinned)
        assertTrue(sample.size in 1..16)

        assertTrue(db.towersIn(49.9, 29.9, 50.2, 30.2, limit = 16, radios = emptySet()).first.isEmpty())
        assertTrue(db.towersIn(49.9, 29.9, 50.2, 30.2, limit = 16, radios = setOf(Radio.GSM)).first.isEmpty())
    }
}
