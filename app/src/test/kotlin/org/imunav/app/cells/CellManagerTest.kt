package org.imunav.app.cells

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.telephony.CellInfo
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.imunav.core.cells.CellCsv
import org.imunav.core.cells.CellKey
import org.imunav.core.cells.CellTower
import org.imunav.core.cells.Radio
import org.imunav.core.gnss.FixSource
import org.imunav.core.gnss.PositioningHub
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.CellIdentityLteBuilder
import org.robolectric.shadows.CellInfoLteBuilder
import org.robolectric.shadows.CellSignalStrengthLteBuilder
import org.robolectric.shadows.ShadowLooper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** Cell positioning end to end: modem cells → offline database → a CELL fix, plus tower data management. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CellManagerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val hub = PositioningHub()
    private val log = ArrayList<String>()
    private val serving = CellKey(Radio.LTE, 255, 1, 100, 25_600)
    private lateinit var cells: CellManager

    private fun lteCell(key: CellKey, registered: Boolean): CellInfo = CellInfoLteBuilder.newBuilder()
        .setRegistered(registered)
        .setTimeStampNanos(SystemClock.elapsedRealtimeNanos())
        .setCellIdentity(
            CellIdentityLteBuilder.newBuilder().setMcc(key.mcc.toString()).setMnc("%02d".format(key.mnc)).setTac(key.area).setCi(key.cid.toInt()).setPci(1).setEarfcn(1300).build(),
        )
        .setCellSignalStrength(CellSignalStrengthLteBuilder.newBuilder().setRsrp(-85).setTimingAdvance(3).build())
        .build()

    private fun waitFor(what: String, done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 30_000
        while (!done()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what: ${cells.status.value} ${log.takeLast(5)}" }
            ShadowLooper.idleMainLooper(SCAN_STEP_MS, TimeUnit.MILLISECONDS)
            Thread.sleep(10)
        }
    }

    @Before
    fun setUp() {
        context.deleteDatabase("cells.db")
        context.getSharedPreferences("cells", Context.MODE_PRIVATE).edit().clear().commit()
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_PHONE_STATE)
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_TELEPHONY, true)
        val telephony = shadowOf(context.getSystemService(TelephonyManager::class.java))
        telephony.setSimState(TelephonyManager.SIM_STATE_READY)
        val modem = listOf(lteCell(serving, registered = true), lteCell(CellKey(Radio.LTE, 255, 1, 100, 25_601), registered = false))
        telephony.setAllCellInfo(modem)
        telephony.setCallbackCellInfos(modem)
        cells = CellManager(context, scope, hub) { log += it }
    }

    @After
    fun tearDown() {
        cells.scanner.stop()
        scope.cancel()
    }

    @Test
    fun knownServingCellGivesAPositionFixAndItsCountry() {
        cells.db.upsert(CellSource.OPENCELLID, listOf(CellTower(serving, 50.45, 30.52, 1200.0, 20)))
        cells.scanner.start()
        waitFor("cell fix") { hub.lastCell != null }
        val fix = checkNotNull(hub.lastCell)
        assertEquals(FixSource.CELL, fix.source)
        assertEquals(50.45, fix.lat, 0.01)
        assertEquals(CellAvailability.AVAILABLE, cells.scanner.availability)
        assertNotNull(cells.scanner.lastFix)
        // The serving cell's country becomes the default tower region.
        waitFor("region from the modem") { context.getSharedPreferences("cells", Context.MODE_PRIVATE).getString("mccs", null) == "255" }
        cells.refresh()
        waitFor("status") { cells.status.value.seen > 0 }
        assertTrue(cells.status.value.located > 0)
    }

    @Test
    fun missingSimIsReportedInsteadOfAStaleFix() {
        shadowOf(context.getSystemService(TelephonyManager::class.java)).setSimState(TelephonyManager.SIM_STATE_ABSENT)
        cells.scanner.start()
        waitFor("no SIM") { cells.scanner.availability == CellAvailability.NO_SIM }
        assertTrue(cells.scanner.lastUsable.isEmpty())
    }

    @Test
    fun towerDataCanBeImportedShownExportedAndReset() {
        cells.saveSettings("", autoSync = false, mccs = "255")
        val csv = ByteArrayOutputStream().also { out ->
            CellCsv.writeGzip(listOf(CellTower(serving, 50.45, 30.52, 1200.0, 20), CellTower(CellKey(Radio.GSM, 250, 1, 1, 1), 55.7, 37.6, 2000.0, 3)), out)
        }.toByteArray()
        cells.importFile { ByteArrayInputStream(csv) }
        waitFor("import") { cells.status.value.busy == null && cells.db.counts()[CellSource.OPENCELLID] == 1L }

        cells.setRadioEnabled(Radio.GSM, true)
        assertTrue(Radio.GSM in cells.enabledRadios())
        cells.setRadioEnabled(Radio.GSM, false)
        cells.setShowTowers(true)
        cells.onViewport(50.4, 30.4, 50.5, 30.6, 14.0)
        waitFor("tower layer") { cells.towerLayer.value.towers.isNotEmpty() }
        cells.onViewport(50.4, 30.4, 50.5, 30.6, 5.0)
        waitFor("zoomed out") { cells.towerLayer.value.zoomTooLow }
        cells.setShowTowers(false)
        cells.setLearning(false)

        cells.exportDatabase()
        waitFor("export") { (cells.status.value.busy == null && log.any { it.startsWith("cells_export") }) || cells.status.value.message != null }
        cells.resetDatabase(deleteLearned = true)
        waitFor("reset") { cells.status.value.busy == null && cells.db.counts().values.all { it == 0L } }
        cells.sync()
        waitFor("sync without server") { cells.status.value.message != null }
        assertFalse(cells.status.value.message.isNullOrBlank())
        cells.cancelTask()
    }
}

private const val SCAN_STEP_MS = 50L
