package org.imunav.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ProfileCaptureSessionTest {
    @Test
    fun failedWriterNotifiesOwnerAndRejectsEverySubsequentPayload() {
        val directory = Files.createTempDirectory("synthetic-profile-failure").toFile()
        val failed = CountDownLatch(1)
        val notifications = AtomicInteger()
        try {
            // A nonexistent parent forces real file-open failure before any Android sampling API executes.
            val session = ProfileCapture.CaptureSession(File(directory, "missing-parent/capture")) {
                notifications.incrementAndGet()
                failed.countDown()
            }
            session.start()
            assertTrue(failed.await(2, TimeUnit.SECONDS))
            assertNotNull(session.streamError)
            repeat(4096) { assertFalse(session.offer(ProfileCapture.Entry.Log("synthetic log $it"))) }
            session.close()
            assertEquals(1, notifications.get())
            assertEquals(4096L, session.dropped.get())
        } finally {
            directory.delete()
        }
    }
}
