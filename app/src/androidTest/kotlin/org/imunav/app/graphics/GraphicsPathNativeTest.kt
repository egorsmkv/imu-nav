package org.imunav.app.graphics

import android.graphics.Path
import android.graphics.RectF
import androidx.graphics.path.PathIterator
import androidx.graphics.path.PathSegment
import androidx.graphics.path.iterator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * Loads the Rust port of the Graphics Path JNI (native/graphics-path) and checks what Compose
 * gets from it. Before Android 14 AndroidX iterates through this library (reading Skia's data
 * in the Android 8–10 and 11–13 layouts); from Android 14 the same checks cover the platform
 * iterator, so both must agree.
 */
class GraphicsPathNativeTest {
    @Test
    fun libraryLoadsAndIteratesPath() {
        System.loadLibrary("androidx.graphics.path")
        val path = Path().apply {
            moveTo(1f, 2f)
            lineTo(3f, 4f)
            close()
        }
        val segments = path.iterator().asSequence().map { it.type }.toList()
        assertEquals(listOf(PathSegment.Type.Move, PathSegment.Type.Line, PathSegment.Type.Close), segments)
    }

    @Test
    fun everyVerbKeepsItsPoints() {
        val path = Path().apply {
            moveTo(0f, 0f)
            lineTo(10f, 0f)
            quadTo(20f, 0f, 20f, 10f)
            cubicTo(20f, 20f, 30f, 30f, 40f, 30f)
            close()
        }
        val segments = path.iterator().asSequence().toList()
        assertEquals(
            listOf(PathSegment.Type.Move, PathSegment.Type.Line, PathSegment.Type.Quadratic, PathSegment.Type.Cubic, PathSegment.Type.Close),
            segments.map { it.type },
        )
        assertEquals(listOf(0f, 0f), segments[0].flat())
        assertEquals(listOf(0f, 0f, 10f, 0f), segments[1].flat())
        assertEquals(listOf(10f, 0f, 20f, 0f, 20f, 10f), segments[2].flat())
        assertEquals(listOf(20f, 10f, 20f, 20f, 30f, 30f, 40f, 30f), segments[3].flat())
    }

    @Test
    fun circleArcsArriveAsConicsOrAsConnectedQuadratics() {
        val radius = 100f
        val circle = Path().apply { addOval(RectF(-radius, -radius, radius, radius), Path.Direction.CW) }

        val conics = PathIterator(circle, PathIterator.ConicEvaluation.AsConic, 0.25f).asSequence().filter { it.type == PathSegment.Type.Conic }.toList()
        assertEquals(4, conics.size)
        conics.forEach { conic ->
            assertEquals(0.70710677f, conic.weight, 1e-6f)
            assertOnCircle(conic.points.first().x, conic.points.first().y, radius)
            assertOnCircle(conic.points.last().x, conic.points.last().y, radius)
        }

        // AndroidX 1.0.1 sizing primes the iterator's conic converter on both JNI and API 34+.
        // Size and walk separate instances so sizing cannot prepend the final arc's quadratics.
        val expectedSize = PathIterator(circle, PathIterator.ConicEvaluation.AsQuadratics, 0.25f).calculateSize(true)
        val segments = PathIterator(circle, PathIterator.ConicEvaluation.AsQuadratics, 0.25f).asSequence().toList()
        assertEquals(expectedSize, segments.size)
        assertEquals(PathSegment.Type.Move, segments.first().type)
        assertEquals(PathSegment.Type.Close, segments.last().type)
        val quadratics = segments.filter { it.type == PathSegment.Type.Quadratic }
        assertTrue("a 100 px circle needs several quadratics per quarter, got ${quadratics.size}", quadratics.size >= 8)
        quadratics.zipWithNext().forEach { (a, b) -> assertEquals(a.points.last(), b.points.first()) }
        quadratics.forEach { quadratic ->
            assertOnCircle(quadratic.points.first().x, quadratic.points.first().y, radius)
            assertOnCircle(quadratic.points.last().x, quadratic.points.last().y, radius)
        }
    }

    private fun PathSegment.flat() = points.flatMap { listOf(it.x, it.y) }

    private fun assertOnCircle(x: Float, y: Float, radius: Float) {
        assertEquals(radius, hypot(x, y), 0.01f)
    }
}
