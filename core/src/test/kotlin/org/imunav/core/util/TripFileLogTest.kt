package org.imunav.core.util

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TripFileLogTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun queuedMessagesStayWithTheirTripWhenStopAndRestartOvertakeTheWorker() {
        val worker = Executors.newSingleThreadScheduledExecutor()
        val release = CountDownLatch(1)
        worker.execute { release.await() }
        val directory = temp.newFolder()
        val failures = mutableListOf<Exception>()
        val log = TripFileLog(directory, 1024, failures::add, worker)
        log.start()
        log.write("trip A")
        log.end()
        log.write("idle")
        log.start()
        log.write("trip B")
        log.end()
        log.close()
        release.countDown()
        assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS))
        assertTrue(failures.isEmpty(), failures.toString())
        assertEquals(setOf("trip A\n", "trip B\n"), directory.listFiles()?.map { it.readText() }?.toSet())
    }

    @Test
    fun dirtyBufferFlushesWithoutWaitingForTripEnd() {
        val worker = Executors.newSingleThreadScheduledExecutor()
        val directory = temp.newFolder()
        val log = TripFileLog(directory, 1024, { throw AssertionError(it) }, worker)
        try {
            log.start()
            log.write("still navigating")
            worker.submit {}.get(10, TimeUnit.SECONDS)
            val contents = worker.schedule(Callable { directory.listFiles()?.single()?.readText() }, 6, TimeUnit.SECONDS).get(10, TimeUnit.SECONDS)
            assertEquals("still navigating\n", contents)
        } finally {
            log.close()
            assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun rapidRotationUsesUniqueFilesAndFlushesEveryLine() {
        val worker = Executors.newSingleThreadScheduledExecutor()
        val directory = temp.newFolder()
        val failures = mutableListOf<Exception>()
        val log = TripFileLog(directory, 8, failures::add, worker)
        log.start()
        repeat(30) { log.write("рядок $it") }
        log.end()
        worker.submit {}.get(10, TimeUnit.SECONDS)
        log.close()
        assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS))
        assertTrue(failures.isEmpty(), failures.toString())
        val files = directory.listFiles().orEmpty()
        assertEquals(30, files.size)
        assertEquals((0 until 30).map { "рядок $it" }.toSet(), files.flatMap { it.readLines() }.toSet())
    }
}
