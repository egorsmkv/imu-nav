package org.imunav.core.util

import org.junit.Test
import java.util.concurrent.Executor
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LatestWorkTest {
    @Test
    fun stalledStorageRetainsOnlyTheLatestSnapshotAndAcceptsWorkDuringDrain() {
        val tasks = ArrayDeque<Runnable>()
        val worker = LatestWork(Executor { tasks.addLast(it) })
        val completed = mutableListOf<Int>()
        repeat(1000) { value -> worker.submit { completed += value } }
        assertEquals(1, tasks.size)
        tasks.removeFirst().run()
        assertEquals(listOf(999), completed)
        worker.submit {
            completed += 1000
            worker.submit { completed += 1001 }
        }
        tasks.removeFirst().run()
        assertEquals(listOf(999, 1000, 1001), completed)
        assertEquals(0, tasks.size)
    }

    @Test
    fun failingSnapshotDoesNotStrandItsReplacement() {
        val tasks = ArrayDeque<Runnable>()
        val worker = LatestWork(Executor { tasks.addLast(it) })
        var completed = false
        worker.submit {
            worker.submit { completed = true }
            error("synthetic snapshot failure")
        }
        assertFailsWith<IllegalStateException> { tasks.removeFirst().run() }
        assertEquals(1, tasks.size)
        tasks.removeFirst().run()
        assertEquals(true, completed)
    }
}
