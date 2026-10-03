package org.imunav.app.packs

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.imunav.core.util.PackOperation

/** Main-thread task ownership includes cancelled work's final cleanup, so staging directories cannot overlap. */
class PackTasks(private val scope: CoroutineScope) {
    private var task: Job? = null
    private var operation: PackOperation? = null
    val running: Boolean get() = task?.isCompleted == false
    val current: PackOperation get() = checkNotNull(operation)

    /** Reserve ownership before starting, including when the scope uses Main.immediate. */
    fun launch(block: suspend (PackOperation) -> Unit): Boolean {
        if (running) return false
        val next = PackOperation()
        operation = next
        val job = scope.launch(start = CoroutineStart.LAZY) { block(next) }
        task = job
        job.start()
        return true
    }

    /** Let chunk/validation checks cancel work; a commit that already started must finish or roll back. */
    fun cancel() = operation?.cancel()
}
