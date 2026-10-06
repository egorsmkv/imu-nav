package org.imunav.core.record

import org.imunav.core.geo.GeoPoint
import org.imunav.core.route.Route
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The personal archive must not reorder restored trips or retain raw positioning inputs. */
class TripPlaybackTest {
    private fun read(vararg lines: String) = TripPlaybackReader.read((lines.joinToString("\n") + "\n").byteInputStream())

    @Test fun samplesPreserveSourceChangesAndFinalPosition() {
        val result = read("E,100,50,30,0,5,GPS", "E,200,50,30,0,6,GPS", "E,300,50,30,0,7,GPS?", "E,400,50,30,0,8,GPS?", "X,500")
        assertEquals(listOf(0L, 200L, 300L), result.positions.map { it.timeMs })
        assertEquals(listOf("GPS", "GPS_SUSPECT", "GPS_SUSPECT"), result.positions.map { it.source })
        assertFalse(result.incomplete)
    }

    @Test fun rebootAndRestoreKeepFileOrder() {
        val result = read("E,10000,50,30,0,5,DR", "E,11000,50.1,30,0,5,DR", "D,20,50,30,5", "E,30,50.2,30,0,5,DR+NET", "X,40")
        assertEquals(listOf(50.0, 50.1, 50.2), result.positions.map { it.point.lat })
        assertEquals(listOf(0, 0, 1), result.positions.map { it.segment })
        assertTrue(result.positions.zipWithNext().all { (first, second) -> first.timeMs <= second.timeMs })
    }

    @Test fun truncatedGzipSalvagesCompleteLines() {
        val bytes = ByteArrayOutputStream()
        GZIPOutputStream(bytes).use { it.write("E,100,50,30,0,5,DR\nE,200,50,30".toByteArray()) }
        val result = TripPlaybackReader.read(bytes.toByteArray().dropLast(6).toByteArray().inputStream())
        assertEquals(1, result.positions.size)
        assertTrue(result.incomplete)
    }

    @Test fun exportsOnlyGeometryAndRejectsMissingPositions() {
        val route = Route(listOf(GeoPoint(50.0, 30.0), GeoPoint(50.1, 30.1)), emptyList(), 5.0)
        val result = read(TripFormat.encode(TripEvent.RouteSet(100, route)), "E,200,50,30,0,5,DR", "X,300")
        assertEquals(route.geometry, result.routes.single().points)
        assertFailsWith<IllegalArgumentException> { read("E,100,NaN,30,0,5,DR", "X,200") }
        assertFailsWith<IllegalStateException> { TripPlaybackReader.read("E,1,50,30,0,5,DR\n".byteInputStream()) { error("cancel") } }
    }
}
