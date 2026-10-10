package org.imunav.app.graphics

import android.graphics.Path
import androidx.graphics.path.PathSegment
import androidx.graphics.path.iterator
import org.junit.Assert.assertEquals
import org.junit.Test

/** Loads the rebuilt JNI on every page size and exercises its path iterator on legacy Android. */
class GraphicsPathNativeTest {
    @Test
    fun rebuiltLibraryLoadsAndIteratesPath() {
        System.loadLibrary("androidx.graphics.path")
        val path = Path().apply {
            moveTo(1f, 2f)
            lineTo(3f, 4f)
            close()
        }
        val segments = path.iterator().asSequence().map { it.type }.toList()
        assertEquals(listOf(PathSegment.Type.Move, PathSegment.Type.Line, PathSegment.Type.Close), segments)
    }
}
