package org.imunav.core.location

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonToken
import org.imunav.core.geo.GeoPoint
import org.imunav.core.gnss.GpsState
import org.imunav.core.gnss.TrustLevel
import org.imunav.core.nav.GuidanceState
import org.imunav.core.nav.PositionSource
import org.imunav.core.route.Route
import org.imunav.core.route.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong

/** Synthetic fixtures are consumed by both Kotlin and Rust to guard the cross-language contract. */
class NavigationMirrorTest {
    private val sample = NavigationPositionSample(GeoPoint(-50.5, -30.25), 10.0, 90.0, 80.0, PositionSource.DR, 1_000, 1_767_225_600_000)
    private val route = Route(listOf(sample.point, GeoPoint(-50.49, -30.24)), emptyList(), 120.0, summary = "Synthetic")
    private val state = GuidanceState(
        active = true, route = route, position = sample.point, s = 20.0, nextStep = Step("turn", "left", "Example road"),
        distToNextM = 100.0, remainingM = 1_000.0, remainingS = 120.0, speedLimitKmh = 50, source = PositionSource.DR, uncertaintyM = 80.0,
    )
    private val input = NavigationMirrorInput(state, GpsState.LOST, TrustLevel.BAD, jammed = true)

    @Test
    fun rustFixturesMatchPhoneEncodingAndTurningOffDetailsClearsRoute() {
        val encoder = NavigationMirrorEncoder()
        val encoded = encoder.encode(sample, input, enabled = true, nowMs = sample.elapsedMs)
        assertEquals(File("../desktop/protocol/fixtures/position.nmea").readText(), NavigationNmea.encode(sample))
        assertEquals(fields(File("../desktop/protocol/fixtures/guidance.json").readText()), fields(encoded.guidanceJson.orEmpty()))
        assertEquals(1L, encoded.publication.routeRevision)
        assertNotNull(encoded.routeJson)
        assertTrue(encoded.publication.routeFrames.all { it.size <= NavigationMirrorEncoder.MAX_FRAME_BYTES })
        val off = encoder.encode(sample, input, enabled = false, nowMs = sample.elapsedMs)
        assertNull(off.guidanceJson)
        assertNull(off.routeJson)
        assertTrue(off.publication.routeFrames.isEmpty())
    }

    @Test
    fun routeRevisionChangesOnReplacementAndLargeRoutesDoNotHideGuidance() {
        val encoder = NavigationMirrorEncoder()
        val first = encoder.encode(sample, input, true, sample.elapsedMs)
        assertEquals(first.routeJson, encoder.encode(sample, input, true, sample.elapsedMs).routeJson)
        val replacement = Route(route.geometry, emptyList(), 150.0, summary = "New route")
        val next = encoder.encode(sample, input.copy(guidance = state.copy(route = replacement)), true, sample.elapsedMs)
        assertEquals(2L, next.publication.routeRevision)
        val huge = Route(List(NavigationMirrorEncoder.MAX_ROUTE_POINTS + 1) { sample.point }, emptyList(), 120.0)
        val oversized = encoder.encode(sample, input.copy(guidance = state.copy(route = huge)), true, sample.elapsedMs)
        assertNull(oversized.routeJson)
        assertEquals("true", fields(oversized.guidanceJson.orEmpty())["route_unavailable"])
        assertNotNull(oversized.guidanceJson)
    }

    @Test
    fun mirrorHandshakeCanBeFragmentedWhilePlainReceiversKeepRmc() {
        val clock = AtomicLong(sample.elapsedMs)
        val address = InetAddress.getByName("127.0.0.1")
        NavigationTcpServer(address, 0, clock::get).use { server ->
            server.publish(sample, NavigationMirrorEncoder().encode(sample, input, true, clock.get()).publication)
            Socket(address, server.port).use { plain ->
                plain.soTimeout = 3_000
                assertEquals(NavigationNmea.encode(sample).trim(), plain.getInputStream().bufferedReader().readLine())
                Socket(address, server.port).use { mirror ->
                    mirror.soTimeout = 3_000
                    val reader = mirror.getInputStream().bufferedReader()
                    NavigationMirrorEncoder.HANDSHAKE.toByteArray().forEach {
                        mirror.getOutputStream().write(it.toInt())
                        mirror.getOutputStream().flush()
                    }
                    var line = reader.readLine()
                    if (line.startsWith('$')) line = reader.readLine()
                    assertEquals(NavigationMirrorEncoder.HELLO.trim(), line)
                    assertEquals("state", fields(reader.readLine())["type"])
                    assertEquals("route_start", fields(reader.readLine())["type"])
                    assertEquals("route_chunk", fields(reader.readLine())["type"])
                    assertEquals("route_end", fields(reader.readLine())["type"])
                    server.publish(null)
                    assertNull(reader.readLine())
                }
            }
        }
    }

    @Test
    fun namesAreEscapedAndEveryEncodedFrameHasOneLine() {
        val unusual = input.copy(guidance = state.copy(nextStep = Step("turn", "left", "Test \"road\"\nВулиця")))
        val encoded = NavigationMirrorEncoder().encode(sample, unusual, true, sample.elapsedMs)
        assertEquals("Test \"road\"\nВулиця", fields(encoded.guidanceJson.orEmpty())["name"])
        assertFalse(encoded.publication.stateLine.dropLast(1).contains(10.toByte()))
    }

    private fun fields(json: String): Map<String, String> = JsonFactory().createParser(json).use { parser ->
        buildMap {
            var token = parser.nextToken()
            while (token != null) {
                if (token.isScalarValue) {
                    val value = if (token == JsonToken.VALUE_NUMBER_INT || token == JsonToken.VALUE_NUMBER_FLOAT) parser.doubleValue.toString() else parser.text
                    put(parser.currentName().orEmpty(), value)
                }
                token = parser.nextToken()
            }
        }
    }
}
