package org.imunav.core.record

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Filesystem failures must preserve the original bytes, including a recoverable gzip tail. */
class TripRepairTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun recording(): File = temporary.newFile("trip.rec.gz").also { file ->
        TripRecorder.open(file).use { it.record(TripEvent.StepTaken(100), 200) }
    }

    @Test
    fun unsupportedAtomicMovePreservesOriginalAndCleansTemporary() {
        val file = recording()
        val before = file.readBytes()
        assertFailsWith<AtomicMoveNotSupportedException> {
            TripRepair.repair(file, replace = { source, target ->
                assertContentEquals(before, target.readBytes())
                assertEquals(listOf(TripEvent.StepTaken(100)), TripFormat.read(source))
                throw AtomicMoveNotSupportedException(source.path, target.path, "injected")
            })
        }
        assertContentEquals(before, file.readBytes())
        assertEquals(listOf(file.name), temporary.root.list()?.toList())
    }

    @Test
    fun gzipFinalizationFailureNeverAttemptsReplacement() {
        val file = recording()
        val before = file.readBytes()
        var replaced = false
        var closed = false
        assertFailsWith<IOException> {
            TripRepair.repair(
                file,
                openOutput = { target ->
                    val delegate = target.outputStream()
                    object : OutputStream() {
                        private var written = 0
                        override fun write(value: Int) {
                            // Allow only the gzip header: buffered payload fails during close().
                            if (written >= 10) throw IOException("injected disk full")
                            delegate.write(value)
                            written++
                        }

                        override fun close() {
                            closed = true
                            delegate.close()
                        }
                    }
                },
                replace = { _, _ -> replaced = true },
            )
        }
        assertFalse(replaced)
        assertTrue(closed)
        assertContentEquals(before, file.readBytes())
        assertEquals(listOf(file.name), temporary.root.list()?.toList())
    }

    @Test
    fun openingReplacementFailurePreservesOriginal() {
        val file = recording()
        val before = file.readBytes()
        assertFailsWith<IOException> {
            TripRepair.repair(file, openOutput = { throw IOException("injected open failure") })
        }
        assertContentEquals(before, file.readBytes())
        assertEquals(listOf(file.name), temporary.root.list()?.toList())
    }

    @Test
    fun successPreservesArrivalMetadataAndUnrelatedRepairFile() {
        val file = recording()
        val stale = temporary.newFile("${file.name}.repair").also { it.writeText("keep") }
        val bytes = file.readBytes()
        file.writeBytes(bytes.copyOf(bytes.size - 8))
        TripFormat.repair(file)
        val event = TripFormat.read(file).single()
        assertEquals(TripEvent.StepTaken(100), event)
        assertEquals(200L, event.arrivalElapsedMs)
        assertEquals("keep", stale.readText())
        TripRecorder.open(file, append = true).use { it.record(TripEvent.Stop(300), 400) }
        assertEquals(listOf(200L, 400L), TripFormat.read(file).map { it.arrivalElapsedMs })
        assertEquals(2, temporary.root.list()?.size)
    }

    @Test
    fun missingRecordingRemainsMissing() {
        val file = File(temporary.root, "missing.rec.gz")
        TripFormat.repair(file)
        assertFalse(file.exists())
        assertEquals(0, temporary.root.list()?.size)
    }
}
