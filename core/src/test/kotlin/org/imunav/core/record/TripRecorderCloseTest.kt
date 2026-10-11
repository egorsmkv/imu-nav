package org.imunav.core.record

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TripRecorderCloseTest {
    @Test
    fun successfulCloseFinishesReadableGzipAndClosesSinkOnce() {
        var closes = 0
        val sink = object : ByteArrayOutputStream() {
            override fun close() {
                closes++
            }
        }
        val recorder = TripRecorder(sink)
        recorder.record(TripEvent.Stop(10))
        recorder.close()
        recorder.close()
        assertEquals(1, closes)
        assertEquals(listOf(TripEvent.Stop(10)), TripFormat.read(ByteArrayInputStream(sink.toByteArray())))
    }

    @Test
    fun failedSinkCloseIsReportedWithoutRetryingClose() {
        var closes = 0
        val sink = object : ByteArrayOutputStream() {
            override fun close() {
                closes++
                throw IOException("synthetic close failure")
            }
        }
        val recorder = TripRecorder(sink)
        recorder.record(TripEvent.Stop(10))
        assertFailsWith<IOException> { recorder.close() }
        recorder.close()
        assertEquals(1, closes)
    }
}
