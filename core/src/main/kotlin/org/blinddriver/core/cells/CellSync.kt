package org.blinddriver.core.cells

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.blinddriver.core.geo.Geo
import org.blinddriver.core.net.Http
import org.blinddriver.core.net.HttpException
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Reads and writes towers in the OpenCellID CSV format. The whole cell pipeline (downloads,
 * imports, exports, the sharing server) uses this one format, so any file can go anywhere.
 */
object CellCsv {
    const val HEADER = "radio,mcc,net,area,cell,unit,lon,lat,range,samples,changeable,created,updated,averageSignal"

    /** Size of read/write buffers: large files (hundreds of MB) are much faster with 64 KB. */
    private const val BUFFER_BYTES = 64 * 1024

    /** The two magic bytes every gzip file starts with. */
    private const val GZIP_MAGIC = 0x1f8b

    /** One CSV line (without the newline) for [tower]. */
    fun format(tower: CellTower, updatedEpochS: Long = 0): String = with(tower) {
        String.format(
            Locale.US,
            "%s,%d,%d,%d,%d,,%.7f,%.7f,%d,%d,1,%d,%d,",
            key.radio.name, key.mcc, key.mnc, key.area, key.cid, lon, lat, rangeM.toLong(), samples, updatedEpochS, updatedEpochS,
        )
    }

    /** Write [towers] as gzip-compressed CSV (header first). Closes [out]. */
    fun writeGzip(towers: Iterable<CellTower>, out: OutputStream, updatedEpochS: (CellTower) -> Long = { 0 }) {
        GZIPOutputStream(out).bufferedWriter().use { writer ->
            writer.appendLine(HEADER)
            towers.forEach { writer.appendLine(format(it, updatedEpochS(it))) }
        }
    }

    /**
     * Read a CSV file row by row without loading it into memory. Gzip is detected automatically.
     * Invalid rows (and the header) are skipped.
     *
     * @param onTower called once for every valid row
     * @return number of valid rows
     */
    fun read(input: InputStream, onTower: (CellTower) -> Unit): Long {
        val stream = maybeGunzip(input.buffered(BUFFER_BYTES))
        var count = 0L
        stream.reader().buffered(BUFFER_BYTES).useLines { lines ->
            lines.mapNotNull(OpenCellIdCsv::parse).forEach { tower ->
                onTower(tower)
                count++
            }
        }
        return count
    }

    /** Peek at the first two bytes: gzip data gets unpacked, plain text is returned as is. */
    private fun maybeGunzip(buffered: BufferedInputStream): InputStream {
        buffered.mark(2)
        val magic = (buffered.read() shl 8) or buffered.read()
        buffered.reset()
        return if (magic == GZIP_MAGIC) GZIPInputStream(buffered, BUFFER_BYTES) else buffered
    }
}

/**
 * Combines two observations of the same cell (from different sources or phones) into one.
 */
object CellMerge {
    /** Towers never get a coverage radius larger than this. */
    private const val MAX_RANGE_M = 50_000.0
    private const val MAX_SAMPLES = 1_000_000

    /**
     * The new position is the average of both, weighted by how many samples each is based on.
     * The new range is grown so the circle still covers both old estimates.
     */
    fun merge(a: CellTower, b: CellTower): CellTower {
        require(a.key == b.key) { "can only merge the same cell" }
        val weightA = max(a.samples, 1).toDouble()
        val weightB = max(b.samples, 1).toDouble()
        val lat = (a.lat * weightA + b.lat * weightB) / (weightA + weightB)
        val lon = (a.lon * weightA + b.lon * weightB) / (weightA + weightB)
        val range = max(
            a.rangeM + Geo.distance(lat, lon, a.lat, a.lon),
            b.rangeM + Geo.distance(lat, lon, b.lat, b.lon),
        )
        return CellTower(a.key, lat, lon, min(range, MAX_RANGE_M), min(a.samples + b.samples, MAX_SAMPLES))
    }
}

/**
 * Client for a cell-sharing server (see the `server` module).
 *
 * The protocol has two calls:
 *  - `POST {base}/v1/cells` — upload a gzip CSV of towers this phone learned; answers `{"accepted":N}`
 *  - `GET  {base}/v1/cells.csv.gz?mcc=255&since=<epoch s>` — download merged towers changed since then
 *
 * Only tower positions are exchanged — never where the phone has been.
 *
 * @param apiKey optional; sent as `Authorization: Bearer <key>`
 * @param deviceId random id per install; the server uses it to count each phone once and to rate-limit
 */
class CellSyncClient(baseUrl: String, private val apiKey: String? = null, private val deviceId: String? = null, httpClient: OkHttpClient = Http.client) {
    private val base = baseUrl.trim().trimEnd('/')

    /** Downloads can be large: allow 2 minutes without data (the default client allows 60 s). */
    private val http = httpClient.newBuilder().readTimeout(2, TimeUnit.MINUTES).build()

    init {
        require(base.startsWith("http://") || base.startsWith("https://")) { "server URL must start with http:// or https://" }
    }

    /** Upload [towers]; returns how many the server accepted. */
    fun upload(towers: List<CellTower>): Int {
        if (towers.isEmpty()) return 0
        val gzippedCsv = ByteArrayOutputStream().also { CellCsv.writeGzip(towers, it) }.toByteArray()
        val request = newRequest("$base/v1/cells")
            .header("Content-Encoding", "gzip")
            .post(gzippedCsv.toRequestBody(CSV))
            .build()
        return http.newCall(request).execute().use { response ->
            val text = successBody(response).string()
            ACCEPTED.find(text)?.groupValues?.get(1)?.toInt() ?: towers.size
        }
    }

    /**
     * Download towers changed since [sinceEpochS] for the given country codes (MCCs).
     * @param onTower called for every tower as it streams in
     * @return number of towers received
     */
    fun download(mccs: Collection<Int>, sinceEpochS: Long, onTower: (CellTower) -> Unit): Long {
        val url = "$base/v1/cells.csv.gz?mcc=${mccs.joinToString(",")}&since=$sinceEpochS"
        val request = newRequest(url).get().build()
        return http.newCall(request).execute().use { response ->
            successBody(response).byteStream().use { CellCsv.read(it, onTower) }
        }
    }

    /** A request builder with our authentication headers already set. */
    private fun newRequest(url: String): Request.Builder = Request.Builder().url(url).apply {
        apiKey?.trim()?.takeIf { it.isNotEmpty() }?.let { header("Authorization", "Bearer $it") }
        deviceId?.let { header("X-Device-Id", it) }
    }

    /** The response body, or an [HttpException] with the server's error message. */
    private fun successBody(response: Response) = response.body.also {
        if (!response.isSuccessful) throw HttpException(response.code, response.body.string().take(Http.ERROR_BODY_CHARS))
    }

    private companion object {
        val CSV = "text/csv".toMediaType()
        val ACCEPTED = Regex("\"accepted\"\\s*:\\s*(\\d+)")
    }
}

/**
 * An [InputStream] that downloads [url] and, if the connection drops, reconnects and continues
 * where it stopped (using an HTTP `Range` header). This lets multi-gigabyte downloads survive
 * Wi-Fi hiccups. The server must support byte ranges (answer `206 Partial Content`).
 *
 * Use it like any stream: `ResumableHttpInputStream(url).use { input -> ... }`.
 *
 * @param maxRetries give up after this many failed attempts in a row
 * @param onProgress called after every read with (bytes so far, total bytes or -1 if unknown)
 */
class ResumableHttpInputStream(
    private val url: String,
    private val maxRetries: Int = 20,
    httpClient: OkHttpClient = Http.client,
    private val onProgress: (bytes: Long, total: Long) -> Unit = { _, _ -> },
) : InputStream() {
    private val http = httpClient.newBuilder().connectTimeout(30, TimeUnit.SECONDS).build()
    private var response: Response? = null
    private var stream: InputStream? = null

    /** Bytes delivered to the caller so far = where to resume from. */
    private var position = 0L

    /** Total size from the first response, or -1 if the server did not say. */
    var total = -1L
        private set

    /** Failed attempts since the last successful read. */
    private var retries = 0

    /** Open (or re-open) the connection at [position] and return the new body stream. */
    private fun connect(): InputStream {
        close()
        val request = Request.Builder().url(url).apply {
            if (position > 0) header("Range", "bytes=$position-")
        }.build()
        val newResponse = http.newCall(request).execute()
        val code = newResponse.code
        when {
            position > 0 && code == HTTP_PARTIAL -> Unit

            // resumed where we stopped
            position == 0L && newResponse.isSuccessful -> total = newResponse.body.contentLength()

            position > 0 && code == HTTP_OK -> {
                newResponse.close()
                throw NotResumableException()
            }

            else -> {
                newResponse.close()
                throw HttpException(code, "")
            }
        }
        response = newResponse
        return newResponse.body.byteStream().buffered(BUFFER_BYTES).also { stream = it }
    }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        while (true) {
            try {
                val input = stream ?: connect()
                val n = input.read(b, off, len)
                if (n < 0) {
                    // The server closed the connection: fine at the end, an error in the middle.
                    if (total > 0 && position < total) throw IOException("connection closed early at $position/$total")
                    return -1
                }
                position += n
                retries = 0
                onProgress(position, total)
                return n
            } catch (e: NotResumableException) {
                throw e // retrying cannot help
            } catch (e: IOException) {
                retries++
                if (retries > maxRetries) throw e
                close()
                // Wait a little longer after each failure: 1 s, 2 s, … up to 30 s.
                Thread.sleep(minOf(MAX_BACKOFF_MS, 1000L * retries))
            }
        }
    }

    override fun close() {
        runCatching { stream?.close() }
        runCatching { response?.close() }
        stream = null
        response = null
    }

    /** The server ignored our `Range` header, so the download cannot continue where it stopped. */
    class NotResumableException : IOException("server does not support resuming")

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_PARTIAL = 206
        const val BUFFER_BYTES = 64 * 1024
        const val MAX_BACKOFF_MS = 30_000L
    }
}
