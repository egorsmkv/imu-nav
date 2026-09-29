package org.imunav.server

import org.imunav.core.cells.CellKey
import org.imunav.core.cells.CellSyncClient
import org.imunav.core.cells.CellTower
import org.imunav.core.cells.Radio
import org.imunav.core.geo.Geo
import org.imunav.core.geo.ServiceArea
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CellServerTest {
    private fun key(cid: Long, mcc: Int = 255) = CellKey(Radio.LTE, mcc, 1, 1864, cid)
    private fun tower(cid: Long, lat: Double, lon: Double = 30.5, samples: Int = 4, mcc: Int = 255) = CellTower(key(cid, mcc), lat, lon, 500.0, samples)

    private fun download(url: String, since: Long = 0): List<CellTower> = ArrayList<CellTower>().also { out -> CellSyncClient(url).download(listOf(255), since) { out += it } }

    @Test
    fun confirmedByTwoDevicesMergedAndPersisted() {
        val file = File.createTempFile("cells", ".csv.gz").apply { delete() }
        val server = CellServer(CellStore(file), apiKey = "secret", port = 0).also { it.start() }
        try {
            val url = "http://127.0.0.1:${server.port}"
            assertEquals(1, CellSyncClient(url, "secret", "device-aaaa-1111").upload(listOf(tower(1, 50.400))))
            assertTrue(download(url).isEmpty(), "one device is not enough to publish")
            CellSyncClient(url, "secret", "device-bbbb-2222").upload(listOf(tower(1, 50.402)))
            val got = download(url)
            assertEquals(1, got.size)
            assertEquals(50.401, got[0].lat, 1e-6)
            assertFailsWith<IOException> { CellSyncClient(url, "wrong", "device-cccc-3333").upload(listOf(tower(3, 50.0))) }
            assertTrue(download(url, System.currentTimeMillis() / 1000 + 60).isEmpty(), "incremental download")
        } finally {
            server.stop()
        }
        val reloaded = CellStore(file)
        assertEquals(1, reloaded.size)
        assertEquals(2, reloaded.contributionCount)
        file.delete()
    }

    @Test
    fun poisoningAttemptsDoNotMoveConsensus() {
        val store = CellStore(null, Policy(area = ServiceArea.UKRAINE_COARSE))
        // Three honest phones agree on the tower within ~100 m.
        store.contribute("honest-aaaa-01", listOf(tower(7, 50.4500, 30.5200, samples = 30)))
        store.contribute("honest-bbbb-02", listOf(tower(7, 50.4508, 30.5210, samples = 12)))
        store.contribute("honest-cccc-03", listOf(tower(7, 50.4495, 30.5195, samples = 25)))
        val before = store.consensusOf(key(7))!!.tower

        // Attacker claims the same cell is 20 km away with a million samples.
        store.contribute("attacker-dddd-04", listOf(tower(7, 50.62, 30.70, samples = 1_000_000)))
        val after = store.consensusOf(key(7))!!
        assertTrue(Geo.distance(before.lat, before.lon, after.tower.lat, after.tower.lon) < 50, "consensus did not move")
        assertEquals(3, after.devices, "attacker dropped as outlier")

        // Two attacker identities cannot outvote three honest devices either (sample cap + median).
        store.contribute("attacker-eeee-05", listOf(tower(7, 50.62, 30.70, samples = 1_000_000)))
        assertTrue(Geo.distance(before.lat, before.lon, store.consensusOf(key(7))!!.tower.lat, store.consensusOf(key(7))!!.tower.lon) < 50)

        // A lone invented tower is never published; out-of-area claims are rejected outright.
        store.contribute("attacker-dddd-04", listOf(tower(99, 50.1, 30.1)))
        assertTrue(store.query(setOf(255), 0).none { it.tower.key == key(99) })
        val r = store.contribute("attacker-dddd-04", listOf(tower(100, 55.75, 37.62)))
        assertEquals(1, r.rejected)
        assertNull(store.consensusOf(key(100)))

        // A device cannot drag its own claim across the map.
        val jump = store.contribute("honest-aaaa-01", listOf(tower(7, 50.60, 30.52)))
        assertEquals(1, jump.rejected)
    }

    @Test
    fun seededDataPublishedAndRateLimited() {
        val store = CellStore(null, Policy(maxUploadsPerHourPerDevice = 2))
        store.contribute(CellStore.SEED, listOf(tower(5, 50.3, 30.4, samples = 100)))
        val server = CellServer(store, apiKey = null, port = 0).also { it.start() }
        try {
            val url = "http://127.0.0.1:${server.port}"
            assertEquals(1, download(url).size, "seeded cells are published without a second device")
            val phone = CellSyncClient(url, deviceId = "phone-ffff-0006")
            phone.upload(listOf(tower(6, 50.31, 30.41)))
            phone.upload(listOf(tower(6, 50.31, 30.41)))
            assertFailsWith<IOException> { phone.upload(listOf(tower(6, 50.31, 30.41))) }
        } finally {
            server.stop()
        }
    }
}
