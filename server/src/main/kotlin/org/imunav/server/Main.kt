package org.imunav.server

import org.imunav.core.cells.CellCsv
import org.imunav.core.cells.CellTower
import org.imunav.core.geo.ServiceArea
import java.io.File

/**
 * Usage:
 *   server --port 8080 --data cells.csv.gz [--api-key KEY] [--min-devices 2] [--area ukraine]
 *          [--tls-keystore server.p12 --tls-password PASS] [--import seed.csv.gz --mcc 255]
 *
 * API key and TLS password can also come from CELLS_API_KEY / CELLS_TLS_PASSWORD. `--import` seeds
 * the store with a trusted dataset (OpenCellID / Mozilla export); seeded cells are published at once.
 */
fun main(args: Array<String>) {
    val opts = args.toList().windowed(2, 2, partialWindows = false).associate { (k, v) -> k.removePrefix("--") to v }
    val port = opts["port"]?.toInt() ?: 8080
    val dataFile = File(opts["data"] ?: "cells.csv.gz")
    val apiKey = opts["api-key"] ?: System.getenv("CELLS_API_KEY")
    val policy = Policy(
        minDevices = opts["min-devices"]?.toInt() ?: 2,
        maxSamplesPerDevice = opts["max-samples"]?.toInt() ?: 50,
        area = when (opts["area"]) {
            null, "any" -> null
            "ukraine" -> ServiceArea.UKRAINE_COARSE
            else -> error("unknown --area ${opts["area"]} (use ukraine or any)")
        },
    )
    val store = CellStore(dataFile, policy)

    opts["import"]?.let { path ->
        val mccs = opts["mcc"]?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.toSet()
        val batch = ArrayList<CellTower>()
        var read = 0L
        File(path).inputStream().use { input ->
            CellCsv.read(input) { t ->
                read++
                if (mccs == null || t.key.mcc in mccs) batch += t
            }
        }
        val r = store.contribute(CellStore.SEED, batch)
        store.save()
        println("[cells] seeded ${r.accepted} of $read rows from $path (${r.rejected} rejected)")
    }

    val tls = opts["tls-keystore"]?.let { ks ->
        val pass = opts["tls-password"] ?: System.getenv("CELLS_TLS_PASSWORD") ?: error("--tls-password or CELLS_TLS_PASSWORD required")
        CellServer.tlsContext(File(ks), pass)
    }
    val server = CellServer(store, apiKey, port, tls)
    server.start()
    println(
        "[cells] ${if (tls != null) "https" else "http"} on port ${server.port}: ${store.size} published towers, " +
            "${store.contributionCount} contributions; uploads ${if (apiKey.isNullOrBlank()) "open" else "require API key"}, " +
            "publish after ${policy.minDevices} device(s)",
    )
}
