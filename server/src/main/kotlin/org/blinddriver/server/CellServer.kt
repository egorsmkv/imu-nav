package org.blinddriver.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import org.blinddriver.core.cells.CellCsv
import org.blinddriver.core.cells.CellKey
import org.blinddriver.core.cells.CellTower
import org.blinddriver.core.cells.Radio
import org.blinddriver.core.geo.Geo
import org.blinddriver.core.geo.ServiceArea
import java.io.File
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlin.math.max
import kotlin.math.min

/** Anti-poisoning policy. Defaults suit a public server. */
data class Policy(
    /** Samples one device can contribute per cell (a phone learns ~1 sample / 20 s; huge claims are capped). */
    val maxSamplesPerDevice: Int = 50,
    /** Independent devices that must report a cell before it is published (seeded cells count as confirmed). */
    val minDevices: Int = 2,
    /** A device contribution farther than max(3×MAD, this) from the consensus is ignored. */
    val outlierMinM: Double = 1_000.0,
    /** A device moving its own claim for a cell by more than this is rejected as implausible. */
    val maxJumpM: Double = 5_000.0,
    val maxRangeM: Double = 50_000.0,
    val maxRowsPerUpload: Int = 20_000,
    val maxUploadsPerHourPerDevice: Int = 30,
    val maxUploadsPerHourPerIp: Int = 120,
    val maxDevicesPerIpPerDay: Int = 5,
    /** Contributions outside this area are rejected (null = anywhere). */
    val area: ServiceArea? = null,
)

/** What one device (or the seed dataset) says about one cell. */
data class Contribution(val lat: Double, val lon: Double, val rangeM: Double, val samples: Int, val updatedS: Long)

data class Consensus(val tower: CellTower, val devices: Int, val seeded: Boolean, val updatedS: Long)

/**
 * Tower store resistant to poisoning: keeps each device's contribution per cell separately and
 * publishes a robust consensus (weighted median, outlier devices dropped, per-device weight capped).
 * Persisted as a gzip CSV of contributions: `device,radio,mcc,mnc,area,cid,lat,lon,range,samples,updated`.
 */
class CellStore(private val file: File?, val policy: Policy = Policy()) {
    private val contributions = ConcurrentHashMap<CellKey, ConcurrentHashMap<String, Contribution>>()
    private val consensus = ConcurrentHashMap<CellKey, Consensus>()

    val size: Int get() = consensus.count { published(it.value) }
    val contributionCount: Int get() = contributions.values.sumOf { it.size }

    init {
        if (file != null && file.exists()) load(file)
        contributions.keys.forEach { recompute(it) }
    }

    private fun published(c: Consensus) = c.seeded || c.devices >= policy.minDevices

    data class UploadResult(val accepted: Int, val rejected: Int)

    /** Merge one device's upload. [device] = SEED marks trusted admin imports. */
    @Synchronized
    fun contribute(device: String, towers: List<CellTower>, nowS: Long = System.currentTimeMillis() / 1000): UploadResult {
        var ok = 0
        var bad = 0
        val seed = device == SEED
        for (t in towers) {
            if (!plausible(t)) {
                bad++
                continue
            }
            val perDevice = contributions.getOrPut(t.key) { ConcurrentHashMap() }
            val prev = perDevice[device]
            if (!seed && prev != null && Geo.distance(prev.lat, prev.lon, t.lat, t.lon) > policy.maxJumpM) {
                bad++
                continue
            }
            val cap = if (seed) Int.MAX_VALUE else policy.maxSamplesPerDevice
            perDevice[device] = Contribution(t.lat, t.lon, t.rangeM, min(max(t.samples, 1), cap), nowS)
            recompute(t.key)
            ok++
        }
        return UploadResult(ok, bad)
    }

    private fun plausible(t: CellTower): Boolean =
        t.rangeM > 0 && t.rangeM <= policy.maxRangeM && t.samples > 0 &&
            t.lat in -90.0..90.0 && t.lon in -180.0..180.0 &&
            (policy.area?.contains(t.lat, t.lon) ?: true)

    /** Robust aggregate of all devices' contributions for one cell. */
    private fun recompute(key: CellKey) {
        val all = contributions[key]?.entries?.toList().orEmpty()
        if (all.isEmpty()) {
            consensus.remove(key)
            return
        }
        val seedW = 200.0
        fun w(e: Map.Entry<String, Contribution>) = if (e.key == SEED) seedW else e.value.samples.toDouble()
        // One device, one vote for the centre and the outlier test — sample counts only weight the
        // final average among agreeing devices, so extra identities or inflated counts cannot outvote.
        val vote = { e: Map.Entry<String, Contribution> -> if (e.key == SEED) 3.0 else 1.0 }
        val lat0 = weightedMedian(all.map { it.value.lat to vote(it) })
        val lon0 = weightedMedian(all.map { it.value.lon to vote(it) })
        var inliers = all
        if (all.size >= 3) {
            val dist = all.map { Geo.distance(lat0, lon0, it.value.lat, it.value.lon) }
            val mad = dist.sorted()[dist.size / 2]
            val limit = max(3 * mad, policy.outlierMinM)
            inliers = all.filterIndexed { i, _ -> dist[i] <= limit }.ifEmpty { all }
        } else if (all.size == 2) {
            // Two sources that disagree: trust the seed, else the better-sampled one — and publish neither as "confirmed".
            val (a, b) = all
            if (Geo.distance(a.value.lat, a.value.lon, b.value.lat, b.value.lon) > policy.outlierMinM * 2) {
                inliers = listOf(if (w(a) >= w(b)) a else b)
            }
        }
        val wSum = inliers.sumOf { w(it) }
        val lat = inliers.sumOf { it.value.lat * w(it) } / wSum
        val lon = inliers.sumOf { it.value.lon * w(it) } / wSum
        val spread = inliers.maxOf { Geo.distance(lat, lon, it.value.lat, it.value.lon) }
        val range = max(inliers.map { it.value.rangeM }.sorted()[inliers.size / 2], spread)
        val samples = inliers.sumOf { if (it.key == SEED) it.value.samples else it.value.samples }.coerceAtMost(1_000_000)
        consensus[key] = Consensus(
            CellTower(key, lat, lon, range, samples),
            devices = inliers.count { it.key != SEED },
            seeded = inliers.any { it.key == SEED },
            updatedS = inliers.maxOf { it.value.updatedS },
        )
    }

    private fun weightedMedian(values: List<Pair<Double, Double>>): Double {
        val sorted = values.sortedBy { it.first }
        val half = sorted.sumOf { it.second } / 2
        var acc = 0.0
        for ((v, w) in sorted) {
            acc += w
            if (acc >= half) return v
        }
        return sorted.last().first
    }

    /** Published (confirmed) towers changed since [sinceS]. */
    fun query(mccs: Set<Int>?, sinceS: Long): List<Consensus> =
        consensus.values.filter { published(it) && (mccs == null || it.tower.key.mcc in mccs) && it.updatedS >= sinceS }

    fun consensusOf(key: CellKey): Consensus? = consensus[key]

    @Synchronized
    fun save() {
        val target = file ?: return
        val tmp = File(target.parentFile ?: File("."), target.name + ".tmp")
        GZIPOutputStream(tmp.outputStream()).bufferedWriter().use { w ->
            w.write("device,radio,mcc,mnc,area,cid,lat,lon,range,samples,updated\n")
            for ((key, perDevice) in contributions) {
                for ((device, c) in perDevice) {
                    w.write("$device,${key.radio.name},${key.mcc},${key.mnc},${key.area},${key.cid},${c.lat},${c.lon},${c.rangeM},${c.samples},${c.updatedS}\n")
                }
            }
        }
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    private fun load(f: File) {
        GZIPInputStream(f.inputStream()).bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.startsWith("device,")) continue
                val p = line.split(',')
                if (p.size < 11) continue
                val key = CellKey(Radio.valueOf(p[1]), p[2].toInt(), p[3].toInt(), p[4].toInt(), p[5].toLong())
                contributions.getOrPut(key) { ConcurrentHashMap() }[p[0]] =
                    Contribution(p[6].toDouble(), p[7].toDouble(), p[8].toDouble(), p[9].toInt(), p[10].toLong())
            }
        }
    }

    companion object {
        const val SEED = "seed"
    }
}

/** Sliding-window rate limiter keyed by device / IP. */
class RateLimiter(private val limit: Int, private val windowMs: Long) {
    private val hits = ConcurrentHashMap<String, ArrayDeque<Long>>()

    @Synchronized
    fun allow(key: String, now: Long = System.currentTimeMillis()): Boolean {
        val q = hits.getOrPut(key) { ArrayDeque() }
        while (q.isNotEmpty() && now - q.first() > windowMs) q.removeFirst()
        if (q.size >= limit) return false
        q.addLast(now)
        return true
    }
}

/**
 * Cell-sharing server (JDK HttpServer / HttpsServer, no other dependencies).
 *
 *  - `POST /v1/cells` — CSV or gzip CSV of towers; header `X-Device-Id` (random per install);
 *    `Authorization: Bearer <key>` when an API key is configured
 *  - `GET  /v1/cells.csv.gz?mcc=255,256&since=<epoch s>` — confirmed consensus towers
 *  - `GET  /health`
 */
class CellServer(
    private val store: CellStore,
    private val apiKey: String?,
    port: Int,
    tls: SSLContext? = null,
) {
    private val policy = store.policy
    private val http: HttpServer = if (tls != null) {
        HttpsServer.create(InetSocketAddress(port), 0).also { it.httpsConfigurator = HttpsConfigurator(tls) }
    } else {
        HttpServer.create(InetSocketAddress(port), 0)
    }
    val port: Int get() = http.address.port
    private val perDevice = RateLimiter(policy.maxUploadsPerHourPerDevice, 3_600_000)
    private val perIp = RateLimiter(policy.maxUploadsPerHourPerIp, 3_600_000)
    private val devicesPerIp = ConcurrentHashMap<String, MutableMap<String, Long>>()

    init {
        http.executor = Executors.newFixedThreadPool(4)
        http.createContext("/health") { ex -> respond(ex, 200, "text/plain", "ok ${store.size}\n".toByteArray()) }
        http.createContext("/v1/cells") { ex -> handleUpload(ex) }
        http.createContext("/v1/cells.csv.gz") { ex -> handleDownload(ex) }
    }

    fun start() = http.start()

    fun stop() = http.stop(0)

    private fun error(ex: HttpExchange, code: Int, message: String) =
        respond(ex, code, "application/json", "{\"status\":\"error\",\"message\":\"$message\"}".toByteArray())

    private fun handleUpload(ex: HttpExchange) {
        try {
            if (ex.requestMethod != "POST") return error(ex, 405, "POST_ONLY")
            if (!apiKey.isNullOrBlank() && ex.requestHeaders.getFirst("Authorization") != "Bearer $apiKey") return error(ex, 401, "UNAUTHORIZED")
            val ip = ex.remoteAddress.address.hostAddress
            val device = ex.requestHeaders.getFirst("X-Device-Id")?.takeIf { DEVICE_ID.matches(it) } ?: "ip:$ip"
            if (device == CellStore.SEED) return error(ex, 400, "BAD_DEVICE")
            // Limit how many identities one address can create per day (sybil resistance).
            val now = System.currentTimeMillis()
            val known = devicesPerIp.getOrPut(ip) { ConcurrentHashMap() }
            known.entries.removeIf { now - it.value > 86_400_000 }
            if (device !in known && known.size >= policy.maxDevicesPerIpPerDay) return error(ex, 429, "TOO_MANY_DEVICES")
            known[device] = now
            if (!perIp.allow(ip) || !perDevice.allow(device)) return error(ex, 429, "RATE_LIMITED")

            val towers = ArrayList<CellTower>()
            val body = LimitedInputStream(ex.requestBody, MAX_UPLOAD_BYTES)
            CellCsv.read(body) { if (towers.size < policy.maxRowsPerUpload) towers += it }
            val r = store.contribute(device, towers)
            store.save()
            log("upload $device@$ip: ${towers.size} rows, ${r.accepted} accepted, ${r.rejected} rejected, published=${store.size}")
            respond(ex, 200, "application/json", "{\"status\":\"ok\",\"accepted\":${r.accepted},\"rejected\":${r.rejected}}".toByteArray())
        } catch (e: Exception) {
            error(ex, 400, (e.message ?: "BAD_REQUEST").replace("\"", "'"))
        }
    }

    private fun handleDownload(ex: HttpExchange) {
        if (ex.requestMethod != "GET") return error(ex, 405, "GET_ONLY")
        val params = (ex.requestURI.rawQuery ?: "").split('&').filter { '=' in it }.associate {
            val (k, v) = it.split('=', limit = 2)
            k to URLDecoder.decode(v, "UTF-8")
        }
        val mccs = params["mcc"]?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.toSet()?.takeIf { it.isNotEmpty() }
        val since = params["since"]?.toLongOrNull() ?: 0
        val rows = store.query(mccs, since)
        val updated = rows.associate { it.tower.key to it.updatedS }
        ex.responseHeaders.add("Content-Type", "application/gzip")
        ex.sendResponseHeaders(200, 0)
        ex.responseBody.use { out -> CellCsv.writeGzip(rows.map { it.tower }, out) { updated[it.key] ?: 0 } }
        log("download mcc=${mccs ?: "all"} since=$since: ${rows.size} rows")
    }

    private fun respond(ex: HttpExchange, code: Int, type: String, body: ByteArray) {
        ex.responseHeaders.add("Content-Type", type)
        ex.sendResponseHeaders(code, body.size.toLong())
        ex.responseBody.use { it.write(body) }
    }

    private fun log(msg: String) = println("[cells] $msg")

    private class LimitedInputStream(private val inner: InputStream, private val limit: Long) : InputStream() {
        private var count = 0L
        override fun read(): Int = inner.read().also { if (it >= 0 && ++count > limit) throw IllegalStateException("UPLOAD_TOO_LARGE") }
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also {
            if (it > 0) {
                count += it
                if (count > limit) throw IllegalStateException("UPLOAD_TOO_LARGE")
            }
        }
    }

    companion object {
        const val MAX_UPLOAD_BYTES = 20L * 1024 * 1024
        private val DEVICE_ID = Regex("[A-Za-z0-9-]{8,64}")

        /** TLS from a PKCS#12 keystore (e.g. converted from Let's Encrypt with openssl). */
        fun tlsContext(keystore: File, password: String): SSLContext {
            val ks = KeyStore.getInstance("PKCS12").apply { keystore.inputStream().use { load(it, password.toCharArray()) } }
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, password.toCharArray()) }
            return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        }
    }
}
