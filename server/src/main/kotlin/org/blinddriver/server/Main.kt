package org.blinddriver.server

import org.blinddriver.core.cells.CellCsv
import org.blinddriver.core.cells.CellTower
import java.io.File

/**
 * Usage:
 *   ./gradlew :server:run --args="--port 8080 --data cells.csv.gz [--api-key KEY] [--import seed.csv.gz --mcc 255]"
 *
 * The API key can also come from the CELLS_API_KEY environment variable. `--import` seeds the store
 * from an OpenCellID / Mozilla export (optionally filtered by MCC) before serving.
 */
fun main(args: Array<String>) {
    val opts = args.toList().windowed(2, 2, partialWindows = false).associate { (k, v) -> k.removePrefix("--") to v }
    val port = opts["port"]?.toInt() ?: 8080
    val dataFile = File(opts["data"] ?: "cells.csv.gz")
    val apiKey = opts["api-key"] ?: System.getenv("CELLS_API_KEY")
    val store = CellStore(dataFile)

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
        val n = store.contribute(batch)
        store.save()
        println("[cells] imported $n of $read rows from $path")
    }

    val server = CellServer(store, apiKey, port)
    server.start()
    println("[cells] serving ${store.size} towers on port ${server.port} (uploads ${if (apiKey.isNullOrBlank()) "open" else "require API key"})")
}
