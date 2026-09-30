package org.imunav.core

import org.imunav.core.cells.CellContribution
import org.imunav.core.cells.CellKey
import org.imunav.core.cells.CellObservation
import org.imunav.core.cells.CellTower
import org.imunav.core.cells.CellUsageCsv
import org.imunav.core.cells.CellUsageRecord
import org.imunav.core.cells.Radio
import java.io.StringWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Export must retain complete cell identities and distinguish absent modem fields from zero. */
class CellUsageTest {
    private fun record(dbm: Int? = null, timingAdvance: Int? = null): CellUsageRecord {
        val key = CellKey(Radio.NR, 255, 1, 1234, 68_719_476_735)
        val contribution = CellContribution(CellObservation(key, dbm, true, timingAdvance), CellTower(key, 50.45, 30.52, 800.0, 10))
        return CellUsageRecord("session", 1_700_000_000_000, 5000, contribution, 50.451, 30.521, 500.0)
    }

    @Test
    fun exportsCompleteIdentityTimestampsAndGeometry() {
        val writer = StringWriter()
        CellUsageCsv.writeRecord(writer, record(-95, 0))
        assertEquals(
            "\"session\",1700000000000,5000,NR,255,1,1234,68719476735,-95,1,0,50.45,30.52,800.0,50.451,30.521,500.0\n",
            writer.toString(),
        )
        assertEquals(CellUsageCsv.HEADER.split(',').size, writer.toString().trim().split(',').size)
    }

    @Test
    fun missingModemValuesAreEmptyNotZero() {
        val writer = StringWriter()
        CellUsageCsv.writeRecord(writer, record())
        assertTrue(writer.toString().contains(",68719476735,,1,,50.45,"))
    }

    @Test
    fun sessionIdQuotesAreEscaped() {
        val writer = StringWriter()
        CellUsageCsv.writeRecord(writer, record().copy(sessionId = "a,\"b"))
        assertTrue(writer.toString().startsWith("\"a,\"\"b\","))
    }
}
