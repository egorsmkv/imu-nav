package org.imunav.app.cells

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoWcdma
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import org.imunav.core.cells.CellFix
import org.imunav.core.cells.CellKey
import org.imunav.core.cells.CellMeasurement
import org.imunav.core.cells.CellMeasurementTracker
import org.imunav.core.cells.CellObservation
import org.imunav.core.cells.CellPositioner
import org.imunav.core.cells.Radio
import org.imunav.core.cells.isFreshCellMeasurement
import org.imunav.core.gnss.FixSource
import org.imunav.core.gnss.RawFix
import org.imunav.core.util.CallbackGeneration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Signal power is normally negative in dBm; only Android's unavailable sentinel means missing. */
internal fun cellSignalDbm(value: Int): Int? = value.takeUnless { it == Int.MAX_VALUE }

/**
 * Polls the modem for visible cells every [intervalMs], locates them in the offline [db] and
 * delivers a [FixSource.CELL] fix on the main thread. Needs ACCESS_FINE_LOCATION and Location on.
 */
class CellScanner(
    context: Context,
    private val db: CellDatabase,
    private val onFix: (RawFix, CellFix) -> Unit,
    private val log: (String) -> Unit,
    /** Persist the exact contributing towers on this worker, without delaying main-thread consumers. */
    private val onUsage: (RawFix, CellFix) -> Unit = { _, _ -> },
    /** Called on the scanner worker when the serving network identifies its country. */
    private val onServingMcc: (Int) -> Unit = {},
    /** Cell types used for positioning; others are still recorded for learning. */
    private val enabledRadios: () -> Set<Radio> = { Radio.entries.toSet() },
    /** Scan period; re-read after every scan so power-mode changes apply at once. */
    var intervalMs: () -> Long = { 5_000 },
) {
    private val telephony = context.getSystemService(TelephonyManager::class.java)
    private val subscriptions = context.getSystemService(SubscriptionManager::class.java)

    // First read is on the scanner worker: capability queries can call Android's package service.
    private val hasCellular by lazy { context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) }

    private var measurements = CellMeasurementTracker()
    private var measurementOwner: Any? = null // Scanner worker only.
    private val callbacks = CallbackGeneration()
    private var lastScanLogMs = 0L
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var running = false

    /** Updated on the scanner worker; the UI reads this without making binder calls. */
    @Volatile var availability: CellAvailability = CellAvailability.CHECKING
        private set

    /** Cells of enabled types seen in the latest scan (the ones used for positioning). */
    @Volatile var lastUsable: List<CellObservation> = emptyList()
        private set

    /** Immutable measurement snapshot; learning rechecks each cell's age against the current clock. */
    @Volatile var lastMeasurements: List<CellMeasurement> = emptyList()
        private set

    @Volatile var lastFix: CellFix? = null
        private set

    private val poll = object : Runnable {
        override fun run() {
            if (!running) return
            // Asking the modem is a call into the phone service (100–300 ms); keep it off the main thread.
            callbacks.begin()?.let { work -> worker.execute { scan(work) } }
            main.postDelayed(this, intervalMs())
        }
    }

    /** Start scanning every [intervalMs]. */
    fun start() {
        if (running) return
        availability = CellAvailability.CHECKING
        running = true
        callbacks.start()
        main.post(poll)
    }

    /** Stop scanning. */
    fun stop() {
        running = false
        callbacks.stop()
        main.removeCallbacks(poll)
        availability = CellAvailability.CHECKING
    }

    /** One TelephonyManager per active SIM; falls back to the default one. */
    private fun managers(): List<Pair<Int, TelephonyManager>> {
        if (Build.VERSION.SDK_INT < 29) return listOf(-1 to telephony)
        val ids = runCatching {
            Android10CellApi.subscriptionIds(telephony, subscriptions)
        }.getOrDefault(emptyList())
        if (ids.isEmpty()) return listOf(-1 to telephony)
        return ids.map { it to telephony.createForSubscriptionId(it) }
    }

    /** Ask the modem of every SIM for fresh cell info (answers arrive on [worker]). */
    @SuppressLint("MissingPermission")
    private fun scan(work: CallbackGeneration.Work) {
        if (!work.current) {
            work.finish()
            return
        }
        if (measurementOwner !== work.owner) {
            measurements = CellMeasurementTracker()
            measurementOwner = work.owner
        }
        if (!hasCellular || telephony == null) {
            unavailable(work, CellAvailability.NO_CELLULAR)
            work.finish()
            return
        }
        val managers = runCatching { managers() }.getOrElse {
            logScanFailure(-1, it)
            unavailable(work, CellAvailability.SCAN_FAILED)
            work.finish()
            return
        }
        val simAvailability = runCatching { cellSimAvailability(managers.map { it.second.simState }) }.getOrDefault(CellAvailability.SCAN_FAILED)
        if (simAvailability != CellAvailability.AVAILABLE) {
            unavailable(work, simAvailability)
            work.finish()
            return
        }
        val remaining = AtomicInteger(managers.size)
        var successfulScans = 0
        for ((subId, tm) in managers) {
            val completed = AtomicBoolean(false)
            fun complete(read: () -> List<CellInfo>) {
                if (!completed.compareAndSet(false, true)) return
                try {
                    if (work.current) {
                        runCatching(read).onSuccess {
                            successfulScans++
                            onSimCells(work, subId, it)
                        }.onFailure {
                            logScanFailure(subId, it)
                            process(work, measurements.update(subId, emptyList(), SystemClock.elapsedRealtime()))
                        }
                    }
                } finally {
                    if (remaining.decrementAndGet() == 0) {
                        if (successfulScans == 0) unavailable(work, CellAvailability.SCAN_FAILED)
                        work.finish()
                    }
                }
            }
            if (!work.current) {
                complete { emptyList() }
            } else if (Build.VERSION.SDK_INT >= 29) {
                runCatching {
                    Android10CellApi.requestUpdate(tm, worker, { cells -> complete { cells } }, { complete { tm.allCellInfo.orEmpty() } })
                }.onFailure { error -> complete { throw error } }
            } else {
                // Already on the scanner worker; no second queued job can outlive this admission.
                complete { tm.allCellInfo.orEmpty() }
            }
        }
    }

    /** Clear previous modem results so a missing SIM or failed scan cannot look like a live fix. */
    private fun unavailable(work: CallbackGeneration.Work, reason: CellAvailability) {
        if (!work.current) return
        measurements = CellMeasurementTracker()
        lastMeasurements = emptyList()
        lastUsable = emptyList()
        lastFix = null
        availability = reason
    }

    /** Keep the scanner's stable key=value failure message identical on every Android version. */
    private fun logScanFailure(subId: Int, error: Throwable) {
        log("cell_scan_failed sub=$subId ${error.javaClass.simpleName}: ${error.message}")
    }

    /** Runs on the worker thread: merge this SIM's cells with other SIMs' recent ones. */
    private fun onSimCells(work: CallbackGeneration.Work, subId: Int, cells: List<CellInfo>) {
        if (!work.current) return
        val now = SystemClock.elapsedRealtime()
        process(work, measurements.update(subId, toMeasurements(cells, now), now))
    }

    /** Runs on the worker thread: turn modem data into observations, a position fix and (sometimes) a log line. */
    private fun process(work: CallbackGeneration.Work, cells: List<CellMeasurement>) {
        if (!work.current) return
        val observations = cells.map { it.observation }
        observations.firstOrNull { it.serving && it.key.mcc in 1..999 }?.let { onServingMcc(it.key.mcc) }
        val now = SystemClock.elapsedRealtime()
        lastMeasurements = cells
        val radios = enabledRadios()
        val usable = observations.filter { it.key.radio in radios }
        lastUsable = usable
        val fix = runCatching { CellPositioner.locate(usable, db) }.getOrNull()
        if (!work.current) return
        lastFix = fix
        availability = when {
            radios.isEmpty() -> CellAvailability.DISABLED
            usable.isEmpty() -> CellAvailability.NO_CELLS
            fix == null -> CellAvailability.NO_KNOWN_TOWERS
            else -> CellAvailability.AVAILABLE
        }
        if (now - lastScanLogMs >= SCAN_LOG_EVERY_MS) {
            lastScanLogMs = now
            logScan(work, observations, radios)
        }
        if (fix == null) return
        val raw = measurements.positionFix(fix, cells, SystemClock.elapsedRealtime(), System.currentTimeMillis()) ?: return
        main.post { if (work.current) onFix(raw, fix) } // consumers expect the main thread
        if (work.current) onUsage(raw, fix)
    }

    /**
     * Log what the modem sees, e.g. `LTE:255-1/1234/56789@-95*B` =
     * radio:mcc-mnc/area/cell, signal in dBm, `*` = serving cell, then the database source letter
     * (`?` = unknown tower), `(off)` if that radio type is disabled in Settings.
     */
    private fun logScan(work: CallbackGeneration.Work, observations: List<CellObservation>, radios: Set<Radio>) {
        val sources = observations.associate { it.key to runCatching { db.resolve(it.key)?.second }.getOrNull() }
        val cellsText = observations.joinToString(" ") { obs ->
            val key = obs.key
            val signal = obs.dbm?.let { "@$it" }.orEmpty()
            val serving = if (obs.serving) "*" else ""
            val source = sources[key]?.symbol ?: "?"
            val disabled = if (key.radio in radios) "" else "(off)"
            "${key.radio}:${key.mcc}-${key.mnc}/${key.area}/${key.cid}$signal$serving$source$disabled"
        }
        val known = sources.values.count { it != null }
        main.post { if (work.current) log("cell_scan seen=${observations.size} known=$known $cellsText") }
    }

    /** Check modem ages before conversion, including the serving cell used to infer neighbor operators. */
    private fun toMeasurements(cells: List<CellInfo>, nowMs: Long): List<CellMeasurement> {
        val recent = cells.map { it to measurementElapsedMs(it) }.filter { isFreshCellMeasurement(it.second, nowMs) }
        // Neighbor cells often omit MCC/MNC; they belong to the operator of the serving cell.
        val home = recent.map { it.first }.filter { it.isRegistered }.map { operator(it) }.firstOrNull { (mcc, mnc) -> mcc != null && mnc != null }
        return recent.mapNotNull { (cell, elapsedMs) ->
            val (ownMcc, ownMnc) = operator(cell)
            val mcc = ownMcc ?: home?.first ?: return@mapNotNull null
            val mnc = ownMnc ?: home?.second ?: return@mapNotNull null
            val observation = when (cell) {
                is CellInfoLte -> lte(cell, mcc, mnc)
                is CellInfoGsm -> gsm(cell, mcc, mnc)
                is CellInfoWcdma -> umts(cell, mcc, mnc)
                else -> if (Build.VERSION.SDK_INT >= 29) Android10CellApi.nrObservation(cell, mcc, mnc) else null // CDMA/TD-SCDMA are not supported.
            }
            observation?.let { CellMeasurement(it, elapsedMs) }
        }
    }

    /** Android 8–10 exposes nanoseconds; Android 11+ exposes the same elapsed clock in milliseconds. */
    private fun measurementElapsedMs(cell: CellInfo): Long = if (Build.VERSION.SDK_INT >= 30) {
        cell.timestampMillis
    } else {
        // The replacement API starts at Android 11; keep the legacy accessor for supported older phones.
        @Suppress("DEPRECATION")
        val timestampNs = cell.timeStamp
        if (timestampNs == Long.MAX_VALUE) 0L else TimeUnit.NANOSECONDS.toMillis(timestampNs)
    }

    private fun lte(cell: CellInfoLte, mcc: Int, mnc: Int): CellObservation? {
        val id = cell.cellIdentity
        if (!valid(id.ci) || !valid(id.tac)) return null
        val signal = cell.cellSignalStrength
        return CellObservation(
            CellKey(Radio.LTE, mcc, mnc, id.tac, id.ci.toLong()),
            dbm = cellSignalDbm(signal.rsrp) ?: cellSignalDbm(signal.dbm),
            serving = cell.isRegistered,
            timingAdvance = signal.timingAdvance.takeIf(::valid),
        )
    }

    private fun gsm(cell: CellInfoGsm, mcc: Int, mnc: Int): CellObservation? {
        val id = cell.cellIdentity
        if (!valid(id.cid) || !valid(id.lac)) return null
        return CellObservation(CellKey(Radio.GSM, mcc, mnc, id.lac, id.cid.toLong()), cellSignalDbm(cell.cellSignalStrength.dbm), cell.isRegistered)
    }

    private fun umts(cell: CellInfoWcdma, mcc: Int, mnc: Int): CellObservation? {
        val id = cell.cellIdentity
        if (!valid(id.cid) || !valid(id.lac)) return null
        return CellObservation(CellKey(Radio.UMTS, mcc, mnc, id.lac, id.cid.toLong()), cellSignalDbm(cell.cellSignalStrength.dbm), cell.isRegistered)
    }

    /**
     * The cell's operator as (MCC, MNC) numbers, either may be null.
     * Newer releases provide strings that preserve leading zeroes; Android 8 uses the older
     * integer properties. NR access stays isolated in [Android10CellApi] so Android 8 can load
     * this class without resolving Android 10-only platform types.
     */
    private fun operator(cell: CellInfo): Pair<Int?, Int?> {
        if (Build.VERSION.SDK_INT >= 29) Android10CellApi.nrOperator(cell)?.let { return it }
        return if (Build.VERSION.SDK_INT >= 28) operatorStrings(cell) else operatorLegacy(cell)
    }

    /** Read MCC/MNC using the non-deprecated Android 9 string properties. */
    @RequiresApi(28)
    private fun operatorStrings(cell: CellInfo): Pair<Int?, Int?> {
        val codes: Pair<String?, String?> = when (cell) {
            is CellInfoLte -> cell.cellIdentity.let { it.mccString to it.mncString }
            is CellInfoGsm -> cell.cellIdentity.let { it.mccString to it.mncString }
            is CellInfoWcdma -> cell.cellIdentity.let { it.mccString to it.mncString }
            else -> null to null
        }
        return codes.first?.toIntOrNull() to codes.second?.toIntOrNull()
    }

    /** Android 8 fallback for MCC/MNC, where only integer properties are available. */
    @Suppress("DEPRECATION")
    private fun operatorLegacy(cell: CellInfo): Pair<Int?, Int?> = when (cell) {
        is CellInfoLte -> cell.cellIdentity.let { it.mcc.takeIf(::valid) to it.mnc.takeIf(::valid) }
        is CellInfoGsm -> cell.cellIdentity.let { it.mcc.takeIf(::valid) to it.mnc.takeIf(::valid) }
        is CellInfoWcdma -> cell.cellIdentity.let { it.mcc.takeIf(::valid) to it.mnc.takeIf(::valid) }
        else -> null to null
    }

    /** Cell identities and timing advance must be non-negative and not Android's unavailable sentinel. */
    private fun valid(value: Int) = value != Int.MAX_VALUE && value >= 0

    private companion object {
        const val SCAN_LOG_EVERY_MS = 30_000L
    }
}

/** Android 10-only cell APIs, isolated so older runtimes never resolve their platform classes. */
@RequiresApi(29)
private object Android10CellApi {
    /** Return the active subscription ids across every modem slot. */
    fun subscriptionIds(telephony: TelephonyManager, subscriptions: SubscriptionManager?): List<Int> {
        val slots = if (Build.VERSION.SDK_INT >= 30) {
            telephony.activeModemCount
        } else {
            @Suppress("DEPRECATION")
            telephony.phoneCount
        }
        return (0 until slots).flatMap { slot ->
            if (Build.VERSION.SDK_INT >= 34) {
                listOf(SubscriptionManager.getSubscriptionId(slot))
            } else {
                @Suppress("DEPRECATION")
                subscriptions?.getSubscriptionIds(slot)?.toList().orEmpty()
            }
        }.filter { it >= 0 }.distinct()
    }

    /**
     * Request a fresh cell list using the asynchronous Android 10 modem API.
     * [CellScanner] starts only after fine-location permission is granted and catches a race where
     * the user revokes it immediately before this call, so the permission warning is handled.
     */
    @SuppressLint("MissingPermission")
    fun requestUpdate(telephony: TelephonyManager, worker: java.util.concurrent.Executor, onCells: (List<CellInfo>) -> Unit, onError: () -> Unit) {
        telephony.requestCellInfoUpdate(
            worker,
            object : TelephonyManager.CellInfoCallback() {
                override fun onCellInfo(cells: MutableList<CellInfo>) = onCells(cells)
                override fun onError(errorCode: Int, detail: Throwable?) = onError()
            },
        )
    }

    /** Convert a 5G NR platform cell to the app's platform-independent observation. */
    fun nrObservation(cell: CellInfo, mcc: Int, mnc: Int): CellObservation? {
        if (cell !is android.telephony.CellInfoNr) return null
        val id = cell.cellIdentity as android.telephony.CellIdentityNr
        if (id.nci == Long.MAX_VALUE || !valid(id.tac)) return null
        val signal = cell.cellSignalStrength as android.telephony.CellSignalStrengthNr
        return CellObservation(CellKey(Radio.NR, mcc, mnc, id.tac, id.nci), cellSignalDbm(signal.ssRsrp) ?: cellSignalDbm(signal.dbm), cell.isRegistered)
    }

    /** Read the operator codes from a 5G NR cell, or return null for another radio type. */
    fun nrOperator(cell: CellInfo): Pair<Int?, Int?>? {
        if (cell !is android.telephony.CellInfoNr) return null
        val id = cell.cellIdentity as android.telephony.CellIdentityNr
        return id.mccString?.toIntOrNull() to id.mncString?.toIntOrNull()
    }

    /** Cell identities must be non-negative and not Android's unavailable sentinel. */
    private fun valid(value: Int) = value != Int.MAX_VALUE && value >= 0
}
