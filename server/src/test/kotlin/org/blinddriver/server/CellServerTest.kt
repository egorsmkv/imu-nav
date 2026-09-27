package org.blinddriver.server

import org.blinddriver.core.cells.CellKey
import org.blinddriver.core.cells.CellSyncClient
import org.blinddriver.core.cells.CellTower
import org.blinddriver.core.cells.Radio
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** End-to-end: the real sync client talking to the real server over HTTP. */
class CellServerTest {
    private fun tower(cid: Long, lat: Double, samples: Int = 4, mcc: Int = 255) =
        CellTower(CellKey(Radio.LTE, mcc, 1, 1864, cid), lat, 30.5, 500.0, samples)

    @Test
    fun uploadMergeDownloadAndPersist() {
        val file = File.createTempFile("cells", ".csv.gz").apply { delete() }
        val server = CellServer(CellStore(file), apiKey = "secret", port = 0).also { it.start() }
        try {
            val url = "http://127.0.0.1:${server.port}"
            val phoneA = CellSyncClient(url, "secret")
            val phoneB = CellSyncClient(url, "secret")
            assertEquals(2, phoneA.upload(listOf(tower(1, 50.40), tower(2, 50.41, mcc = 434))))
            assertEquals(1, phoneB.upload(listOf(tower(1, 50.42))))

            val got = ArrayList<CellTower>()
            CellSyncClient(url).download(listOf(255), 0) { got += it }
            assertEquals(1, got.size, "filtered to MCC 255")
            assertEquals(50.41, got[0].lat, 1e-6, "equal-weight merge of two phones")
            assertEquals(8, got[0].samples)

            assertFailsWith<IOException> { CellSyncClient(url, "wrong").upload(listOf(tower(3, 50.0))) }

            val none = ArrayList<CellTower>()
            CellSyncClient(url).download(listOf(255), System.currentTimeMillis() / 1000 + 60) { none += it }
            assertTrue(none.isEmpty(), "incremental download returns only newer rows")
        } finally {
            server.stop()
        }
        // Restart from the persisted file.
        val reloaded = CellStore(file)
        assertEquals(2, reloaded.size)
        assertTrue(reloaded.query(setOf(255), 1).isNotEmpty(), "timestamps survive a restart")
        file.delete()
    }
}
