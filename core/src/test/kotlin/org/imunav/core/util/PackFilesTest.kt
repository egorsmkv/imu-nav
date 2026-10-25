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
import kotlin.random.Random
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
            PackFiles.extract(archive.inputStream(), staging, false, PackFiles.Limits(100_000, 10), {}, progress = {})
        }
        assertTrue(File(staging, "data").length() <= 100_000)
    }

    @Test
    fun packCannotExceedEntryLimit() {
        val staging = temporary.newFolder("staging")
        val archive = archive("one" to byteArrayOf(1), "two" to byteArrayOf(2))
        assertFailsWith<IOException> {
            PackFiles.extract(archive.inputStream(), staging, false, PackFiles.Limits(100, 1), {}, progress = {})
        }
        assertFalse(File(staging, "two").exists())
    }

    @Test
    fun wrappedRoutingPacksRetainTheirNativeIndexDirectory() {
        val staging = temporary.newFolder("native-index")
        val zip = archive("region/properties" to byteArrayOf(1), "region/search.tantivy/meta.json" to byteArrayOf(2))
        PackFiles.extract(zip.inputStream(), staging, true, PackFiles.Limits(100, 10), {}, setOf("search.tantivy")) {}
        assertTrue(File(staging, "properties").isFile)
        assertEquals(2, File(staging, "search.tantivy/meta.json").readBytes().single().toInt())
        assertFalse(File(staging, "meta.json").exists())
        val attack = archive("search.tantivy/../../escape" to byteArrayOf(3))
        assertFailsWith<IOException> {
            PackFiles.extract(attack.inputStream(), staging, true, PackFiles.Limits(100, 10), {}, setOf("search.tantivy")) {}
        }
    }

    @Test
    fun githubArtifactWrapperIsUnpackedInPlace() {
        val staging = temporary.newFolder("artifact")
        val pack = archive("pack.json" to byteArrayOf(1), "search.tantivy/meta.json" to byteArrayOf(2))
        val artifact = archive("routing-ukraine-car-walk.zip" to pack, "routing-build.json" to byteArrayOf(3))
        PackFiles.extract(artifact.inputStream(), staging, true, PackFiles.Limits(100_000, 10), {}, setOf("search.tantivy"), expandNested = true) {}
        assertEquals(1, File(staging, "pack.json").readBytes().single().toInt())
        assertEquals(2, File(staging, "search.tantivy/meta.json").readBytes().single().toInt())
        assertFalse(File(staging, "routing-ukraine-car-walk.zip").exists())
        // Without the option the inner archive stays an ordinary file.
        val plain = temporary.newFolder("plain")
        PackFiles.extract(artifact.inputStream(), plain, true, PackFiles.Limits(100_000, 10), {}) {}
        assertTrue(File(plain, "routing-ukraine-car-walk.zip").isFile)
    }

    @Test
    fun progressReachesTheWholeArchive() {
        val staging = temporary.newFolder("progress")
        // Incompressible data, so the archive is read in many buffer-sized steps.
        val random = Random(1)
        val archive = archive("one" to random.nextBytes(300_000), "two" to random.nextBytes(300_000))
        val steps = mutableListOf<PackFiles.Progress>()
        PackFiles.extract(archive.inputStream(), staging, false, PackFiles.Limits(1_000_000, 10), {}) { steps += it }
        assertEquals(600_000, steps.last().extractedBytes)
        assertEquals(steps.map { it.archiveBytes }.sorted(), steps.map { it.archiveBytes })
        val percents = steps.mapNotNull { it.percent(archive.size.toLong()) }
        assertTrue(percents.first() < 50 && percents.last() >= 95, "percent $percents")
        assertEquals(null, steps.last().percent(0))
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
