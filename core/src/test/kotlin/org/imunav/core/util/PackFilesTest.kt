package org.imunav.core.util

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PackFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun cancelInsideTheFinalLargeZipEntryKeepsTheInstalledPack() {
        val current = temporary.newFolder("current")
        File(current, "data").writeText("working")
        val staging = temporary.newFolder("staging")
        val archive = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("data"))
                zip.write(ByteArray(1_000_000) { it.toByte() })
                zip.closeEntry()
            }
        }.toByteArray()
        val operation = PackOperation()
        assertFailsWith<InterruptedException> {
            PackFiles.extract(archive.inputStream(), staging, false, PackFiles.Limits(2_000_000, 10), operation::checkCancelled) { operation.cancel() }
        }
        assertTrue(File(staging, "data").length() < 1_000_000)
        assertEquals("working", File(current, "data").readText())
        assertFailsWith<InterruptedException> { operation.beginCommit() }
    }

    @Test
    fun compressedPackCannotExceedExtractedByteLimit() {
        val staging = temporary.newFolder("staging")
        val archive = archive("data" to ByteArray(200_000))
        assertFailsWith<IOException> {
            PackFiles.extract(archive.inputStream(), staging, false, PackFiles.Limits(100_000, 10), {}, {})
        }
        assertTrue(File(staging, "data").length() <= 100_000)
    }

    @Test
    fun packCannotExceedEntryLimit() {
        val staging = temporary.newFolder("staging")
        val archive = archive("one" to byteArrayOf(1), "two" to byteArrayOf(2))
        assertFailsWith<IOException> {
            PackFiles.extract(archive.inputStream(), staging, false, PackFiles.Limits(100, 1), {}, {})
        }
        assertFalse(File(staging, "two").exists())
    }

    private fun archive(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
    }.toByteArray()

    @Test
    fun cancellationAfterValidationStillPreventsCommit() {
        val operation = PackOperation()
        operation.checkCancelled()
        operation.cancel()
        assertFailsWith<InterruptedException> { operation.beginCommit() }
    }

    @Test
    fun cancellationAfterCommitCannotChangeTheOutcome() {
        val operation = PackOperation()
        operation.beginCommit()
        operation.cancel()
        operation.checkCancelled()
        operation.result = "installed"
        assertEquals("installed", operation.result)
    }

    @Test
    fun failedActivationRollsBackToTheWorkingPack() {
        val current = temporary.newFolder("current")
        File(current, "data").writeText("old")
        val staging = temporary.newFolder("staging")
        File(staging, "data").writeText("new")
        var active = "old"
        assertFailsWith<IOException> {
            PackFiles.replace(staging, current, close = { active = "closed" }) {
                val data = File(current, "data").readText()
                if (data == "new") throw IOException("broken graph")
                active = data
            }
        }
        assertEquals("old", active)
        assertEquals("old", File(current, "data").readText())
        assertEquals(listOf("current"), temporary.root.list()?.toList())
    }

    @Test
    fun failedRenameAlsoRestoresTheOldPack() {
        val current = temporary.newFolder("current")
        File(current, "data").writeText("old")
        assertFailsWith<IOException> {
            PackFiles.replace(File(temporary.root, "missing"), current, close = {}, activate = {})
        }
        assertEquals("old", File(current, "data").readText())
    }

    @Test
    fun exclusiveCloseWaitsForEveryReaderIncludingSearch() {
        val access = ResourceAccess()
        val pool = Executors.newFixedThreadPool(4)
        val readersStarted = CountDownLatch(3)
        val releaseReaders = CountDownLatch(1)
        val writerStarted = CountDownLatch(1)
        var closed = false
        try {
            val readers = (1..3).map {
                pool.submit {
                    access.read {
                        readersStarted.countDown()
                        check(releaseReaders.await(5, TimeUnit.SECONDS))
                        assertFalse(closed)
                    }
                }
            }
            assertTrue(readersStarted.await(5, TimeUnit.SECONDS))
            val writer = pool.submit {
                writerStarted.countDown()
                access.write { closed = true }
            }
            assertTrue(writerStarted.await(5, TimeUnit.SECONDS))
            assertFalse(writer.isDone)
            releaseReaders.countDown()
            readers.forEach { it.get(5, TimeUnit.SECONDS) }
            writer.get(5, TimeUnit.SECONDS)
            assertTrue(closed)
        } finally {
            releaseReaders.countDown()
            pool.shutdownNow()
        }
    }
}
