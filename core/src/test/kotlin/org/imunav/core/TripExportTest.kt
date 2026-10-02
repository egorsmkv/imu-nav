package org.imunav.core

import org.imunav.core.record.TripEvent
import org.imunav.core.record.TripExport
import org.imunav.core.record.TripFormat
import org.imunav.core.record.TripRecorder
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Sharing must retain replay data and never tie an attachment's lifetime to the original recording. */
class TripExportTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun attachmentsStayReplayableAfterOriginalIsDeleted() {
        val recording = temporary.newFile("trip-20261002-213400.rec.gz")
        val events = listOf(TripEvent.Agc(100, -5f), TripEvent.Stop(200))
        TripRecorder(recording.outputStream()).use { recorder -> events.forEach(recorder::record) }
        val originalBytes = recording.readBytes()
        val directory = File(temporary.root, "shared")

        val first = TripExport.snapshot(recording, directory)
        val second = TripExport.snapshot(recording, directory)

        assertNotEquals(first, second)
        assertTrue(first.name.endsWith(".rec.gz"))
        assertContentEquals(originalBytes, recording.readBytes())
        assertTrue(recording.delete())
        assertEquals(events, TripFormat.read(first))
        assertEquals(events, TripFormat.read(second))
    }

    @Test
    fun truncatedRecordingIsCopiedWithoutLosingSalvageableEvents() {
        val recording = temporary.newFile("trip-truncated.rec.gz")
        TripRecorder(recording.outputStream()).use { recorder ->
            repeat(100) { recorder.record(TripEvent.Agc(it * 10L, -5f)) }
        }
        val truncated = recording.readBytes().dropLast(4).toByteArray()
        recording.writeBytes(truncated)
        val events = TripFormat.read(recording)
        assertTrue(events.isNotEmpty())

        val exported = TripExport.snapshot(recording, temporary.newFolder("shared"))

        assertContentEquals(truncated, exported.readBytes())
        assertContentEquals(truncated, recording.readBytes())
        assertEquals(events, TripFormat.read(exported))
    }

    @Test
    fun unavailableRecordingDoesNotProduceAnAttachment() {
        val directory = temporary.newFolder("shared")
        val missing = File(temporary.root, "missing.rec.gz")
        val empty = temporary.newFile("empty.rec.gz")

        assertFailsWith<IllegalArgumentException> { TripExport.snapshot(missing, directory) }
        assertFailsWith<IllegalArgumentException> { TripExport.snapshot(empty, directory) }

        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }
}
