package org.imunav.core.obd

import java.io.Closeable

/** Owns one connection attempt at a time; detached resources can only be closed by their old owner. */
class ConnectionSession<T : Closeable> {
    @Volatile var active = true
        private set

    @Volatile var connection: T? = null
        private set

    /** Register before connecting so cancellation can unblock even a blocking connect call. */
    fun attach(value: T) {
        val accepted = synchronized(this) {
            if (active) {
                check(connection == null)
                connection = value
                true
            } else {
                false
            }
        }
        if (!accepted) {
            value.close()
            throw InterruptedException("connection stopped")
        }
    }

    /** Detach immediately on the caller thread; close the returned resource on a worker. */
    @Synchronized
    fun invalidate(): T? {
        active = false
        return connection.also { connection = null }
    }

    /** Release only the supplied attempt, never a later reconnects resource. */
    fun release(value: T) {
        synchronized(this) {
            if (connection === value) connection = null
        }
        value.close()
    }
}
