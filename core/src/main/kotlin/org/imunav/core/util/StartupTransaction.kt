package org.imunav.core.util

/** Rolls back partially acquired components in reverse order unless initialization commits. */
class StartupTransaction(private val onCleanupFailure: (Exception) -> Unit) : AutoCloseable {
    private val cleanup = ArrayDeque<() -> Unit>()

    /** Register before acquisition because an operation may throw after partially succeeding. */
    fun acquire(release: () -> Unit, action: () -> Unit) {
        cleanup.addFirst(release)
        action()
    }

    fun commit() = cleanup.clear()

    // Every registered cleanup must run even when an unrelated component fails to release.
    @Suppress("TooGenericExceptionCaught")
    override fun close() {
        var firstFailure: Exception? = null
        while (cleanup.isNotEmpty()) {
            try {
                cleanup.removeFirst().invoke()
            } catch (failure: Exception) {
                if (firstFailure == null) firstFailure = failure else firstFailure.addSuppressed(failure)
            }
        }
        firstFailure?.let(onCleanupFailure)
    }
}
