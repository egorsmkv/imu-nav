package org.imunav.replay

import java.nio.file.Files
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Exercises the real JNI export, including the default build's explicit unsupported error. */
class NativeHeapProfilerTest {
    @Test
    fun capturesProfilesAtRecordedTimeIntervalsOrReportsDisabledBuild() {
        val directory = Files.createTempDirectory("native-heap-test").toFile()
        try {
            if (!java.lang.Boolean.getBoolean("imunav.heapProfile")) {
                val error = assertFailsWith<IllegalStateException> { NativeHeapProfiler(directory) }
                assertTrue(error.message.orEmpty().contains("--features heap-profile"))
                return
            }
            val profiler = NativeHeapProfiler(directory)
            profiler.sample(1000)
            profiler.sample(60_999)
            profiler.sample(61_000)
            profiler.finish()
            val profiles = directory.listFiles().orEmpty()
            assertEquals(4, profiles.size)
            profiles.forEach { file ->
                val protobuf = GZIPInputStream(file.inputStream()).use { it.readBytes() }
                assertTrue(protobuf.isNotEmpty(), "${file.name} must contain a pprof protobuf")
                assertTrue(protobuf.toString(Charsets.ISO_8859_1).contains("inuse_space"))
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
