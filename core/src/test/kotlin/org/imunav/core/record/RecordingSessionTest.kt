package org.imunav.core.record

import org.imunav.core.geo.GeoPoint
import org.imunav.core.route.Route
import org.imunav.core.util.OrderedBuffer
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecordingSessionTest {
    @get:Rule val temporary = TemporaryFolder()
    private val pending = ArrayDeque<Runnable>()
    private val worker = Executor { pending.addLast(it) }

    @Test
    fun rapidDiscardAndRestartCannotDeleteTheNewRecording() {
        val oldFile = File(temporary.root, RecordingSession.newId(1000) + ".rec.gz")
        val newFile = File(temporary.root, RecordingSession.newId(1000) + ".rec.gz")
        val old = RecordingSession(oldFile, worker, false) { throw it }
        old.record(TripEvent.Stop(10))
        old.finish(discard = true)
        val next = RecordingSession(newFile, worker, false) { throw it }
        next.record(TripEvent.Resume(20, 500.0))
        next.record(TripEvent.Stop(30))
        next.finish()
        assertFalse(oldFile.exists(), "opening is deferred to the recording worker")
        assertFalse(newFile.exists())
        while (pending.isNotEmpty()) pending.removeFirst().run()
        assertFalse(oldFile.exists())
        assertTrue(newFile.exists())
        assertEquals(listOf(TripEvent.Resume(20, 500.0), TripEvent.Stop(30)), TripFormat.read(newFile))
    }

    @Test
    fun legacyFileCanBeRepairedAndAppendedWithoutLosingEvents() {
        val file = temporary.newFile("trip-20200101-120000.rec.gz")
        TripRecorder.open(file).use { it.record(TripEvent.Stop(10)) }
        val restored = RecordingSession(file, worker, true) { throw it }
        restored.record(TripEvent.Resume(20, 600.0))
        restored.finish()
        while (pending.isNotEmpty()) pending.removeFirst().run()
        assertEquals(listOf(TripEvent.Stop(10), TripEvent.Resume(20, 600.0)), TripFormat.read(file))
    }

    @Test
    fun overloadPreservesAcceptedPrefixAndCapturedArrivalWithoutRetainingLaterEvents() {
        val file = temporary.newFile("overload.rec.gz")
        val buffer = OrderedBuffer(worker, 2, 1024)
        val failures = mutableListOf<Throwable>()
        var arrival = 100L
        val session = RecordingSession(file, worker, false, arrivalClock = { arrival++ }, buffer = buffer, onFailure = failures::add)
        assertEquals(RecordingAdmission.ACCEPTED, session.record(TripEvent.Resume(10, 1.0)))
        assertEquals(RecordingAdmission.ACCEPTED, session.record(TripEvent.Resume(20, 2.0)))
        assertEquals(RecordingAdmission.STOPPED, session.record(TripEvent.Stop(30)))
        repeat(1000) { assertEquals(RecordingAdmission.STOPPED, session.record(TripEvent.Stop(40))) }
        session.finish()
        session.finish()
        assertEquals(1, pending.size)
        assertEquals(1, failures.size)
        assertEquals(RecordingFailure.OVERLOAD, session.failure)
        while (pending.isNotEmpty()) pending.removeFirst().run()
        val events = TripFormat.read(file)
        assertEquals(listOf(TripEvent.Resume(10, 1.0), TripEvent.Resume(20, 2.0)), events)
        assertEquals(listOf(100L, 101L), events.map { it.arrivalElapsedMs })
        assertEquals(0, buffer.stats.items)
        assertEquals(0, buffer.stats.bytes)
    }

    @Test
    fun oversizedRouteStopsRecordingWithoutEncodingOnProducer() {
        val file = temporary.newFile("route.rec.gz")
        val buffer = OrderedBuffer(worker, 2, 512)
        val route = Route(List(10) { GeoPoint(50.0, 30.0 + it * 0.001) }, emptyList(), 10.0)
        val failures = mutableListOf<Throwable>()
        val session = RecordingSession(file, worker, false, buffer = buffer, onFailure = failures::add)
        assertEquals(RecordingAdmission.STOPPED, session.record(TripEvent.RouteSet(10, route)))
        assertEquals(0, buffer.stats.items)
        while (pending.isNotEmpty()) pending.removeFirst().run()
        assertEquals(emptyList(), TripFormat.read(file))
        assertEquals(1, failures.size)
    }

    @Test
    fun failedOpenDropsPendingPayloadsAndDoesNotAffectTheNextSession() {
        val badFile = File(temporary.root, "missing/failed.rec.gz")
        val nextFile = temporary.newFile("next.rec.gz")
        val buffer = OrderedBuffer(worker, 4, 2048)
        val failures = mutableListOf<Throwable>()
        val failed = RecordingSession(badFile, worker, false, buffer = buffer, onFailure = failures::add)
        failed.record(TripEvent.Stop(10))
        val next = RecordingSession(nextFile, worker, false, buffer = buffer, onFailure = failures::add)
        next.record(TripEvent.Stop(20))
        next.finish()
        while (pending.isNotEmpty()) pending.removeFirst().run()
        assertEquals(RecordingFailure.WRITE_FAILED, failed.failure)
        assertNull(next.failure)
        assertEquals(listOf(TripEvent.Stop(20)), TripFormat.read(nextFile))
        assertEquals(1, failures.size)
        assertEquals(0, buffer.stats.items)
    }

    @Test
    fun exhaustedLifecycleCapacityRejectsNewSessionButStillClosesTheAcceptedOne() {
        val buffer = OrderedBuffer(worker, 4, 2048, 2)
        val failures = mutableListOf<Throwable>()
        val first = RecordingSession(temporary.newFile("first.rec.gz"), worker, false, buffer = buffer, onFailure = failures::add)
        val rejectedFile = temporary.newFile("rejected.rec.gz")
        val rejected = RecordingSession(rejectedFile, worker, false, buffer = buffer, onFailure = failures::add)
        assertEquals(RecordingAdmission.STOPPED, rejected.record(TripEvent.Stop(10)))
        assertEquals(RecordingFailure.OVERLOAD, rejected.failure)
        first.record(TripEvent.Stop(20))
        first.finish()
        while (pending.isNotEmpty()) pending.removeFirst().run()
        assertEquals(0L, rejectedFile.length())
        assertEquals(1, failures.size)
        assertEquals(0, buffer.stats.items)
    }

    @Test
    fun writeFailureReleasesQueuedEventsClosesOnceAndReportsOnce() {
        var writesFail = false
        var closes = 0
        val sink = object : ByteArrayOutputStream() {
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                if (writesFail) throw IOException("synthetic storage failure")
                super.write(bytes, offset, length)
            }
            override fun close() {
                closes++
            }
        }
        val buffer = OrderedBuffer(worker, 4, 2048)
        val failures = mutableListOf<Throwable>()
        val session = RecordingSession(
            temporary.newFile("write-failed.rec.gz"),
            worker,
            false,
            buffer = buffer,
            recorderFactory = { _, _ -> TripRecorder(sink, -1).also { writesFail = true } },
            onFailure = failures::add,
        )
        repeat(4) { session.record(TripEvent.Stop(it.toLong())) }
        pending.removeFirst().run()
        session.finish()
        assertEquals(RecordingFailure.WRITE_FAILED, session.failure)
        assertEquals(1, failures.size)
        assertEquals(1, closes)
        assertEquals(0, buffer.stats.items)
        assertEquals(0, buffer.stats.bytes)
        assertEquals(RecordingAdmission.STOPPED, session.record(TripEvent.Stop(100)))
    }
}
