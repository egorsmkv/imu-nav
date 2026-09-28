package org.blinddriver.app.cells

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthNr
import android.telephony.TelephonyManager
import org.blinddriver.core.cells.CellFix
import org.blinddriver.core.cells.CellKey
import org.blinddriver.core.cells.CellObservation
import org.blinddriver.core.cells.CellPositioner
import org.blinddriver.core.cells.Radio
import org.blinddriver.core.gnss.FixSource
import org.blinddriver.core.gnss.RawFix
import java.util.concurrent.Executors

/**
 * Polls the modem for visible cells every [intervalMs], locates them in the offline [db] and
 * delivers a [FixSource.CELL] fix on the main thread. Needs ACCESS_FINE_LOCATION and Location on.
 */
class CellScanner(
    context: Context,
    private val db: CellDatabase,
    private val onFix: (RawFix, CellFix) -> Unit,
    private val log: (String) -> Unit,
    /** Cell types used for positioning; others are still recorded for learning. */
    private val enabledRadios: () -> Set<Radio> = { Radio.entries.toSet() },
    /** Scan period; re-read after every scan so power-mode changes apply at once. */
    var intervalMs: () -> Long = { 5_000 },
) {
    private val telephony = context.getSystemService(TelephonyManager::class.java)
    private val subscriptions = context.getSystemService(android.telephony.SubscriptionManager::class.java)

    /** Latest cell list per SIM subscription (dual-SIM phones see both operators' cells). */
    private val perSim = java.util.concurrent.ConcurrentHashMap<Int, Pair<Long, List<CellInfo>>>()
    private var lastScanLogMs = 0L
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var running = false

    /** Cells of enabled types seen in the latest scan (the ones used for positioning). */
    @Volatile var lastUsable: List<CellObservation> = emptyList()
        private set

    /** All cells seen in the latest scan and when; used for learning tower positions. */
    @Volatile var lastObservations: List<CellObservation> = emptyList()
        private set
    @Volatile var lastScanMs = 0L
        private set
    @Volatile var lastFix: CellFix? = null
        private set

    private val poll = object : Runnable {
        override fun run() {
            if (!running) return
            scan()
            main.postDelayed(this, intervalMs())
        }
    }

    fun start() {
        if (running || telephony == null) return
        running = true
        main.post(poll)
    }

    fun stop() {
        running = false
        main.removeCallbacks(poll)
    }

    /** One TelephonyManager per active SIM; falls back to the default one. */
    private fun managers(): List<Pair<Int, TelephonyManager>> {
        val ids = runCatching {
            val slots = if (android.os.Build.VERSION.SDK_INT >= 30) telephony.activeModemCount else @Suppress("DEPRECATION") telephony.phoneCount
            (0 until slots).flatMap { slot ->
                if (android.os.Build.VERSION.SDK_INT >= 34) {
                    listOf(android.telephony.SubscriptionManager.getSubscriptionId(slot))
                } else {
                    @Suppress("DEPRECATION")
                    subscriptions?.getSubscriptionIds(slot)?.toList().orEmpty()
                }
            }.filter { it >= 0 }.distinct()
        }.getOrDefault(emptyList())
        if (ids.isEmpty()) return listOf(-1 to telephony)
        return ids.map { it to telephony.createForSubscriptionId(it) }
    }

    @SuppressLint("MissingPermission")
    private fun scan() {
        for ((subId, tm) in managers()) {
            runCatching {
                tm.requestCellInfoUpdate(worker, object : TelephonyManager.CellInfoCallback() {
                    override fun onCellInfo(cells: MutableList<CellInfo>) = onSimCells(subId, cells)
                    override fun onError(errorCode: Int, detail: Throwable?) {
                        runCatching { tm.allCellInfo }.getOrNull()?.let { onSimCells(subId, it) }
                    }
                })
            }.onFailure { log("cell_scan_failed sub=$subId ${it.javaClass.simpleName}: ${it.message}") }
        }
    }

    /** Runs on the worker thread: merge this SIM's cells with other SIMs' recent ones. */
    private fun onSimCells(subId: Int, cells: List<CellInfo>) {
        val now = SystemClock.elapsedRealtime()
        perSim[subId] = now to cells
        perSim.entries.removeAll { now - it.value.first > 15_000 }
        process(perSim.values.flatMap { it.second })
    }

    /** Runs on the worker thread. */
    private fun process(cells: List<CellInfo>) {
        val observations = toObservations(cells).distinctBy { it.key }
        val now = SystemClock.elapsedRealtime()
        lastObservations = observations
        lastScanMs = now
        val radios = enabledRadios()
        val usable = observations.filter { it.key.radio in radios }
        lastUsable = usable
        val fix = runCatching { CellPositioner.locate(usable, db) }.getOrNull()
        lastFix = fix
        if (now - lastScanLogMs >= 30_000) {
            lastScanLogMs = now
            val matches = observations.associate { it.key to runCatching { db.resolve(it.key)?.second }.getOrNull() }
            val list = observations.joinToString(" ") { o ->
                "${o.key.radio}:${o.key.mcc}-${o.key.mnc}/${o.key.area}/${o.key.cid}${o.dbm?.let { "@$it" } ?: ""}${if (o.serving) "*" else ""}${matches[o.key]?.symbol ?: "?"}${if (o.key.radio in radios) "" else "(off)"}"
            }
            main.post { log("cell_scan seen=${observations.size} known=${matches.values.count { it != null }} $list") }
        }
        if (fix == null) return
        val raw = RawFix(
            source = FixSource.CELL,
            timeMs = System.currentTimeMillis(),
            elapsedMs = now,
            lat = fix.lat,
            lon = fix.lon,
            accuracyM = fix.accuracyM.toFloat(),
        )
        main.post { onFix(raw, fix) }
    }

    private fun toObservations(cells: List<CellInfo>): List<CellObservation> {
        // Neighbour cells often omit MCC/MNC; they belong to the serving operator.
        var homeMcc: Int? = null
        var homeMnc: Int? = null
        for (c in cells) {
            if (!c.isRegistered) continue
            val (mcc, mnc) = operator(c)
            if (mcc != null && mnc != null) {
                homeMcc = mcc
                homeMnc = mnc
                break
            }
        }
        return cells.mapNotNull { c ->
            val (mccRaw, mncRaw) = operator(c)
            val mcc = mccRaw ?: homeMcc ?: return@mapNotNull null
            val mnc = mncRaw ?: homeMnc ?: return@mapNotNull null
            when (c) {
                is CellInfoLte -> {
                    val id = c.cellIdentity
                    if (!valid(id.ci) || !valid(id.tac)) return@mapNotNull null
                    val s = c.cellSignalStrength
                    CellObservation(
                        CellKey(Radio.LTE, mcc, mnc, id.tac, id.ci.toLong()),
                        dbm = s.rsrp.takeIf(::valid) ?: s.dbm.takeIf(::valid),
                        serving = c.isRegistered,
                        timingAdvance = s.timingAdvance.takeIf(::valid),
                    )
                }
                is CellInfoGsm -> {
                    val id = c.cellIdentity
                    if (!valid(id.cid) || !valid(id.lac)) return@mapNotNull null
                    CellObservation(CellKey(Radio.GSM, mcc, mnc, id.lac, id.cid.toLong()), c.cellSignalStrength.dbm.takeIf(::valid), c.isRegistered)
                }
                is CellInfoWcdma -> {
                    val id = c.cellIdentity
                    if (!valid(id.cid) || !valid(id.lac)) return@mapNotNull null
                    CellObservation(CellKey(Radio.UMTS, mcc, mnc, id.lac, id.cid.toLong()), c.cellSignalStrength.dbm.takeIf(::valid), c.isRegistered)
                }
                is CellInfoNr -> {
                    val id = c.cellIdentity as CellIdentityNr
                    if (id.nci == CellInfo.UNAVAILABLE_LONG || !valid(id.tac)) return@mapNotNull null
                    val s = c.cellSignalStrength as CellSignalStrengthNr
                    CellObservation(CellKey(Radio.NR, mcc, mnc, id.tac, id.nci), s.ssRsrp.takeIf(::valid) ?: s.dbm.takeIf(::valid), c.isRegistered)
                }
                else -> null
            }
        }
    }

    private fun operator(c: CellInfo): Pair<Int?, Int?> {
        val id = c.cellIdentity
        val mcc = when (id) {
            is android.telephony.CellIdentityLte -> id.mccString
            is android.telephony.CellIdentityGsm -> id.mccString
            is android.telephony.CellIdentityWcdma -> id.mccString
            is CellIdentityNr -> id.mccString
            else -> null
        }
        val mnc = when (id) {
            is android.telephony.CellIdentityLte -> id.mncString
            is android.telephony.CellIdentityGsm -> id.mncString
            is android.telephony.CellIdentityWcdma -> id.mncString
            is CellIdentityNr -> id.mncString
            else -> null
        }
        return mcc?.toIntOrNull() to mnc?.toIntOrNull()
    }

    private fun valid(v: Int) = v != CellInfo.UNAVAILABLE && v != Int.MAX_VALUE && v >= 0
}
