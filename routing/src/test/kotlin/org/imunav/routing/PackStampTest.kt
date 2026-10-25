package org.imunav.routing

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PackStampTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun stampHoldsOnlyWhileValidatedFilesAreUnchanged() {
        val pack = temporary.newFolder("pack")
        File(pack, PackInfo.FILE).writeText("{}")
        File(pack, "car.routing").writeBytes(ByteArray(16))
        assertFalse(PackStamp.matches(pack))
        PackStamp.write(pack)
        assertTrue(PackStamp.matches(pack))

        // Renaming the directory (staging → current) keeps the stamp valid.
        val current = File(temporary.root, "current")
        assertTrue(pack.renameTo(current))
        assertTrue(PackStamp.matches(current))

        File(current, "car.routing").appendBytes(byteArrayOf(1))
        assertFalse(PackStamp.matches(current))
        PackStamp.write(current)
        File(current, "foot.routing").writeBytes(ByteArray(8))
        assertFalse(PackStamp.matches(current), "an added profile must be validated")
        PackStamp.write(current)
        PackStamp.clear(current)
        assertFalse(PackStamp.matches(current))
    }
}
