package org.imunav.core.search

import org.imunav.core.geo.GeoPoint
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/** JNI surface for immutable routing-pack address indexes. */
object TantivyNative {
    init {
        System.loadLibrary("imu_nav_search")
    }

    /** Desktop builder only; Android libraries intentionally omit the writer entry point. */
    external fun build(input: String, directory: String)
    external fun open(path: String): Long
    external fun close(handle: Long)
    external fun begin(): Long
    external fun cancel(request: Long)
    external fun finish(request: Long)
    external fun search(handle: Long, request: Long, json: String): String
}

/** Request ownership allows cancellation from another thread without interrupting unrelated searches. */
class TantivyRequest : AutoCloseable {
    internal val handle = TantivyNative.begin()
    private val cancelled = AtomicBoolean()
    fun cancel() {
        cancelled.set(true)
        TantivyNative.cancel(handle)
    }
    internal fun checkCancelled() {
        if (cancelled.get()) throw CancellationException("Search cancelled")
    }
    override fun close() = TantivyNative.finish(handle)
}

/** One retrieval JNI call, followed by the production Kotlin matcher; close waits for resource readers. */
class TantivySearch(directory: File) : AutoCloseable {
    companion object {
        const val DIRECTORY = "search.tantivy"
        const val FORMAT = "tantivy-1"
    }

    private val lock = ReentrantReadWriteLock()
    private var handle = TantivyNative.open(directory.absolutePath)

    fun search(query: String, near: GeoPoint?, request: TantivyRequest, checkCancelled: () -> Unit = {}): List<SearchResult> = lock.read {
        check(handle != 0L) { "Search index closed" }
        request.checkCancelled()
        checkCancelled()
        val plan = AddressSearch.prepare(query)
        if (plan.words.isEmpty()) return@read emptyList()
        val payload = SearchIndexCodec.request(SearchIndexRequest(plan, near))
        val snapshot = SnapshotDb(SearchIndexCodec.snapshot(TantivyNative.search(handle, request.handle, payload)))
        request.checkCancelled()
        checkCancelled()
        AddressSearch.search(snapshot, plan, near, checkCancelled = {
            request.checkCancelled()
            checkCancelled()
        })
    }

    override fun close() = lock.write {
        if (handle != 0L) TantivyNative.close(handle)
        handle = 0L
    }
}
