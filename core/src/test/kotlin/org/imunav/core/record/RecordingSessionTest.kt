package org.imunav.core.record

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
}
