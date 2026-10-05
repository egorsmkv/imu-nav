package org.imunav.core

import org.imunav.core.record.TripEvent
import org.imunav.core.record.TripExport
import org.imunav.core.record.TripFormat
import org.imunav.core.record.TripRecorder
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.test.Test
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
        assertTrue(first.name.endsWith(".zip"))
        assertTrue(originalBytes.contentEquals(recording.readBytes()))
        assertTrue(recording.delete())
        assertEquals(events, unpackAndReplay(first, "trip-20261002-213400.rec"))
        assertEquals(events, unpackAndReplay(second, "trip-20261002-213400.rec"))
    }

    @Test
    fun truncatedRecordingStillProducesAnOpenableZipWithSalvageableEvents() {
        val recording = temporary.newFile("trip-truncated.rec.gz")
        TripRecorder(recording.outputStream()).use { recorder ->
            repeat(100) { recorder.record(TripEvent.Agc(it * 10L, -5f)) }
        }
        val truncated = recording.readBytes().dropLast(4).toByteArray()
        recording.writeBytes(truncated)
        val events = TripFormat.read(recording)
        assertTrue(events.isNotEmpty())

        val exported = TripExport.snapshot(recording, temporary.newFolder("shared"))

        assertTrue(truncated.contentEquals(recording.readBytes()))
        assertEquals(events, unpackAndReplay(exported, "trip-truncated.rec"))
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

    @Test
    fun corruptGzipDoesNotLeaveAnAttachment() {
        val recording = temporary.newFile("trip-corrupt.rec.gz")
        recording.writeText("not a gzip recording")
        val directory = temporary.newFolder("shared")

        assertFailsWith<java.io.IOException> { TripExport.snapshot(recording, directory) }
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    /** Read all bytes to force the standard ZIP decoder to validate the entry CRC. */
    private fun unpackAndReplay(archive: File, entryName: String): List<TripEvent> {
        ZipFile(archive).use { zip ->
            assertEquals(1, zip.size())
            assertEquals(entryName, zip.entries().nextElement().name)
        }
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            assertEquals(entryName, zip.nextEntry?.name)
            val recording = zip.readBytes()
            assertEquals(null, zip.nextEntry)
            return TripFormat.read(recording.inputStream())
        }
    }
}
