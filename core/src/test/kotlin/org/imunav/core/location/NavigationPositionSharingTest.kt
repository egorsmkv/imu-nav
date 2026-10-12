package org.imunav.core.location

import org.imunav.core.geo.GeoPoint
import org.imunav.core.gnss.FixSource
import org.imunav.core.gnss.RawFix
import org.imunav.core.nav.GuidanceState
import org.imunav.core.nav.PositionSource
import org.imunav.core.route.TravelMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.time.Instant
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Exercises the wire contract and receiver lifecycle with synthetic positions and controlled elapsed time. */
class NavigationPositionSharingTest {
    private val sample = NavigationPositionSample(GeoPoint(-50.5, -30.25), 10.0, 90.0, 80.0, PositionSource.DR, 1_000, 1_767_225_600_000)

    @Test
    fun idleSharingPreservesMeasurementTimingAndRejectsStaleMockAndFusedFixes() {
        val fix = RawFix(FixSource.GPS, sample.utcMs, sample.elapsedMs, sample.point.lat, sample.point.lon, speedMps = 10f, bearingDeg = 90f, accuracyM = 80f)
        val output = NavigationPositionSample.fromIdle(1_500, sample.utcMs + 500, fix, null)
        assertEquals(sample.copy(source = PositionSource.GPS), output)
        assertNull(NavigationPositionSample.fromIdle(3_001, sample.utcMs + 2_001, fix, null))
        assertNull(NavigationPositionSample.fromIdle(999, sample.utcMs, fix, null))
        assertNull(NavigationPositionSample.fromIdle(1_000, sample.utcMs, fix.copy(isMock = true), null))
        assertNull(NavigationPositionSample.fromIdle(1_000, sample.utcMs, null, fix.copy(source = FixSource.FUSED)))
        assertNull(NavigationPositionSample.fromIdle(1_000, sample.utcMs, fix.copy(accuracyM = null), null))
        val coarse = fix.copy(source = FixSource.CELL, speedMps = null, bearingDeg = null)
        assertEquals(sample.copy(source = PositionSource.CELL, speedMps = 0.0, bearingDeg = 0.0), NavigationPositionSample.fromIdle(1_000, sample.utcMs, null, coarse))
        assertEquals(
            PositionSource.CELL,
            NavigationPositionSample.fromIdle(3_001, sample.utcMs + 2_001, fix, coarse.copy(elapsedMs = 3_001))?.source,
        )
    }

    @Test
    fun rmcHasCorrectUnitsUtcHemispheresChecksumAndEstimatedMode() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val encoded = NavigationNmea.encode(sample)
            assertTrue(encoded.startsWith("\$GPRMC,000000.000,A,5030.000000,S,03015.000000,W,19.438,90.000,010126,,,E*"))
            assertTrue(encoded.endsWith("\r\n"))
            assertFalse(encoded.contains("GGA"))
            val body = encoded.substringAfter('$').substringBefore('*')
            val checksum = body.fold(0) { value, character -> value xor character.code }
            assertEquals(checksum, encoded.substringAfter('*').trim().toInt(16))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun coordinateRoundingAndDateRolloverNeverProduceSixtyMinutes() {
        val last = sample.copy(point = GeoPoint(89.9999999999, 179.9999999999), utcMs = Instant.parse("2025-12-31T23:59:59.999Z").toEpochMilli())
        assertTrue(NavigationNmea.encode(last).contains("235959.999,A,9000.000000,N,18000.000000,E"))
        assertTrue(NavigationNmea.encode(last).contains(",311225,,,E*"))
        assertTrue(NavigationNmea.encode(last.copy(utcMs = last.utcMs + 1)).contains(",010126,,,E*"))
        assertTrue(NavigationNmea.encode(sample.copy(point = GeoPoint(0.0, 0.0))).contains(",0000.000000,N,00000.000000,E,"))
        assertTrue(NavigationNmea.encode(sample.copy(speedMps = 1_000.0, bearingDeg = 359.999)).length <= 82)
    }

    @Test
    fun invalidIdleAndStaleStatesCannotBecomePositions() {
        assertNull(NavigationPositionSample.from(GuidanceState(), 1_000, sample.utcMs))
        val active = GuidanceState(active = true, position = sample.point, source = PositionSource.DR, speedKmh = 36f, uncertaintyM = 80.0)
        TravelMode.entries.forEach { assertNotNull(NavigationPositionSample.from(active.copy(travelMode = it), 1_000, sample.utcMs)) }
        assertNull(NavigationPositionSample.from(active.copy(speedKmh = Float.NaN), 1_000, sample.utcMs))
        assertFalse(sample.copy(point = GeoPoint(Double.NaN, 0.0)).valid())
        assertFalse(sample.copy(bearingDeg = 360.0).valid())
        assertFalse(sample.copy(uncertaintyM = Double.POSITIVE_INFINITY).valid())
        assertTrue(sample.fresh(3_000))
        assertFalse(sample.fresh(3_001))
        assertFalse(sample.fresh(999))
    }

    @Test
    fun latestSampleStreamsAndInvalidationClosesReceiversAndReleasesPort() {
        val clock = AtomicLong(1_000)
        val address = InetAddress.getByName("127.0.0.1")
        var port = 0
        NavigationTcpServer(address, 0, clock::get).use { server ->
            port = server.port
            server.publish(sample)
            Socket(address, port).use { socket ->
                socket.soTimeout = 3_000
                val reader = socket.getInputStream().bufferedReader()
                assertEquals(NavigationNmea.encode(sample).trim(), reader.readLine())
                val newest = sample.copy(point = GeoPoint(51.0, 31.0), elapsedMs = 1_500)
                clock.set(1_500)
                server.publish(newest)
                assertEquals(NavigationNmea.encode(newest).trim(), reader.readLine())
                server.publish(null)
                assertNull(reader.readLine())
            }
        }
        NavigationTcpServer(address, port, clock::get).close()
    }

    @Test
    fun aStalledReceiverIsDisconnectedWhileNewSamplesRemainFresh() {
        val clock = AtomicLong(1_000)
        val receivers = AtomicInteger()
        val address = InetAddress.getByName("127.0.0.1")
        NavigationTcpServer(address, 0, clock::get, receivers::set).use { server ->
            server.publish(sample)
            Socket().use { stalled ->
                stalled.receiveBufferSize = 1_024
                stalled.connect(InetSocketAddress(address, server.port))
                stalled.soTimeout = 3_000
                val reader = stalled.getInputStream().bufferedReader()
                assertNotNull(reader.readLine())
                assertEquals(1, receivers.get())
                // Accelerate measurement time while filling the real TCP buffers without reading.
                // Each publication is fresh, so receiver teardown must come from its pending write.
                val deadline = System.nanoTime() + 10_000_000_000L
                while (receivers.get() > 0 && System.nanoTime() < deadline) {
                    val elapsed = clock.addAndGet(10)
                    server.publish(sample.copy(elapsedMs = elapsed))
                    Thread.sleep(1)
                }
                assertEquals("A stalled writer cannot retain a receiver slot", 0, receivers.get())
                Socket(address, server.port).use { healthy ->
                    healthy.soTimeout = 3_000
                    assertNotNull(healthy.getInputStream().bufferedReader().readLine())
                }
            }
        }
    }

    @Test
    fun fifthReceiverIsRejectedAndFreshnessDeadlineClosesTheOthers() {
        val clock = AtomicLong(1_000)
        val address = InetAddress.getByName("127.0.0.1")
        NavigationTcpServer(address, 0, clock::get).use { server ->
            server.publish(sample)
            val sockets = buildList {
                repeat(4) {
                    val socket = Socket(address, server.port)
                    socket.soTimeout = 3_000
                    assertEquals(NavigationNmea.encode(sample).trim(), socket.getInputStream().bufferedReader().readLine())
                    add(socket)
                }
            }
            try {
                Socket(address, server.port).use { fifth ->
                    fifth.soTimeout = 3_000
                    assertEquals(-1, fifth.getInputStream().read())
                }
                clock.set(3_001)
                sockets.forEach { assertEquals(-1, it.getInputStream().read()) }
            } finally {
                sockets.forEach(Socket::close)
            }
        }
    }
}
