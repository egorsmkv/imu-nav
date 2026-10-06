package org.imunav.core.util

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Cleanup must stay within diagnostic logs and preserve queued writes across a confirmed clear. */
class LogRetentionTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun limitsRemoveOldestFilesButDefaultsAndUnrelatedFilesArePreserved() {
        val directory = temp.newFolder()
        val old = File(directory, "trip-old.log").apply {
            writeText("123456")
            setLastModified(1)
        }
        val recent = File(directory, "trip-recent.log").apply {
            writeText("123456")
            setLastModified(System.currentTimeMillis())
        }
        val unrelated = File(directory, "recording.rec.gz").apply { writeText("keep") }
        TripFileLog(directory, 100, { throw AssertionError(it) }).use { log ->
            assertEquals(LogStorage(2, 12), log.configure(LogRetention()).get(10, TimeUnit.SECONDS))
            assertEquals(LogStorage(1, 6), log.configure(LogRetention(maxTotalBytes = 6)).get(10, TimeUnit.SECONDS))
            assertFalse(old.exists())
            assertTrue(recent.exists())
            assertTrue(unrelated.exists())
        }
    }

    @Test
    fun retentionExpiresOldFilesWithoutNeedingASizeLimit() {
        val directory = temp.newFolder()
        val old = File(directory, "trip-old.log").apply {
            writeText("old")
            setLastModified(1)
        }
        val recent = File(directory, "trip-new.log").apply { writeText("new") }
        TripFileLog(directory, 100, { throw AssertionError(it) }).use { log ->
            assertEquals(LogStorage(1, 3), log.configure(LogRetention(days = 7)).get(10, TimeUnit.SECONDS))
            assertFalse(old.exists())
            assertTrue(recent.exists())
        }
    }

    @Test
    fun activeFileSurvivesTheLimitUntilTripEnd() {
        val directory = temp.newFolder()
        val worker = Executors.newSingleThreadScheduledExecutor()
        val log = TripFileLog(directory, 100, { throw AssertionError(it) }, worker)
        try {
            log.start()
            log.write("active")
            assertEquals(LogStorage(1, 7), log.configure(LogRetention(maxTotalBytes = 1)).get(10, TimeUnit.SECONDS))
            log.end()
            assertEquals(LogStorage(0, 0), log.storage().get(10, TimeUnit.SECONDS))
        } finally {
            log.close()
            assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun storageFailuresReachTheManagementCaller() {
        TripFileLog(temp.newFile(), 100, { throw AssertionError(it) }).use { log ->
            assertFailsWith<ExecutionException> { log.storage().get(10, TimeUnit.SECONDS) }
        }
    }

    @Test
    fun rapidRotationPrunesWithoutWaitingForTheFlushTimer() {
        val directory = temp.newFolder()
        val worker = Executors.newSingleThreadScheduledExecutor()
        val log = TripFileLog(directory, 8, { throw AssertionError(it) }, worker)
        try {
            log.configure(LogRetention(maxTotalBytes = 10)).get(10, TimeUnit.SECONDS)
            log.start()
            repeat(20) { log.write("123456") }
            worker.submit {}.get(10, TimeUnit.SECONDS)
            assertTrue(directory.listFiles().orEmpty().size <= 2)
            assertEquals(LogStorage(1, 7), log.storage().get(10, TimeUnit.SECONDS))
        } finally {
            log.close()
            assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun clearingDuringATripPreservesOnlyWritesQueuedAfterTheClear() {
        val directory = temp.newFolder()
        val unrelated = File(directory, "saved.rec.gz").apply { writeText("keep") }
        val worker = Executors.newSingleThreadScheduledExecutor()
        val release = CountDownLatch(1)
        worker.execute { release.await() }
        val log = TripFileLog(directory, 100, { throw AssertionError(it) }, worker)
        log.start()
        log.write("before")
        val cleared = log.clear()
        log.write("after")
        log.end()
        release.countDown()
        cleared.get(10, TimeUnit.SECONDS)
        assertEquals(LogStorage(1, 6), log.storage().get(10, TimeUnit.SECONDS))
        log.close()
        assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS))
        assertEquals(listOf("after\n"), directory.listFiles().orEmpty().filter { it.extension == "log" }.map { it.readText() })
        assertEquals("keep", unrelated.readText())
    }
}
