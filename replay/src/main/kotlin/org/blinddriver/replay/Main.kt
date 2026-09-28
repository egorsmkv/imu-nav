package org.blinddriver.replay

import org.blinddriver.core.Tuning
import org.blinddriver.core.geo.GeoPoint
import org.blinddriver.core.geo.ServiceArea
import org.blinddriver.core.record.ReplayResult
import org.blinddriver.core.record.TripFormat
import org.blinddriver.core.record.TripReplayer
import java.io.File
import java.util.Locale
import kotlin.system.exitProcess

private const val USAGE = """
Replays recorded trips (*.rec.gz from the phone's files/logs) through the navigation engine and
compares the engine's position with the recorded GPS track.

usage: replay <trip.rec.gz|dir> [options]
  --hide-gps-after S[,S…]  hide GPS from the engine S seconds after navigation starts (dead-reckoning test);
                           several values produce one run each. Default: replay as recorded.
  --set key=value[,…]      override Tuning parameters, e.g. --set turnMinDeg=30,stopHoldMs=2000
  --ukraine                apply the app's Ukraine service area to the GPS trust check
  --out DIR                write summary.txt, errors-*.csv and compare-*.geojson per run
"""

fun main(args: Array<String>) {
    if (args.isEmpty() || args[0] in setOf("-h", "--help")) {
        println(USAGE.trimIndent())
        exitProcess(if (args.isEmpty()) 1 else 0)
    }
    val input = File(args[0])
    val opts = args.drop(1).filter { it.startsWith("--") }.associate { flag ->
        val i = args.indexOf(flag)
        flag.removePrefix("--") to args.getOrNull(i + 1)?.takeIf { !it.startsWith("--") }
    }
    val tuning = applyOverrides(Tuning.DEFAULT, opts["set"])
    val area = if ("ukraine" in opts) ServiceArea.UKRAINE_COARSE else ServiceArea.EVERYWHERE
    val hides: List<Double?> = opts["hide-gps-after"]?.split(',')?.map { it.trim().toDouble() } ?: listOf(null)
    val outDir = opts["out"]?.let { File(it).apply { mkdirs() } }
    val files = if (input.isDirectory) input.listFiles().orEmpty().filter { it.name.endsWith(".rec.gz") || it.name.endsWith(".rec") }.sorted() else listOf(input)
    if (files.isEmpty()) error("no recordings in $input")

    val summary = StringBuilder()
    for (f in files) {
        val events = TripFormat.read(f)
        summary.appendLine("== ${f.name}: ${events.size} events")
        for (hide in hides) {
            val result = TripReplayer(tuning, area).replay(events, hide)
            val tag = f.name.substringBefore('.') + (hide?.let { "-hide${it.toInt()}s" } ?: "-asrec")
            summary.appendLine("-- ${hide?.let { "GPS hidden after ${it.toInt()} s" } ?: "as recorded"}")
            summary.append(result.summary())
            outDir?.let { dir ->
                File(dir, "errors-$tag.csv").writeText(errorsCsv(result))
                File(dir, "compare-$tag.geojson").writeText(geoJson(result))
            }
        }
    }
    print(summary)
    outDir?.let { File(it, "summary.txt").writeText(summary.toString()); println("report written to ${it.absolutePath}") }
}

private fun applyOverrides(base: Tuning, spec: String?): Tuning {
    if (spec.isNullOrBlank()) return base
    var t = base
    for (kv in spec.split(',')) {
        val (k, v) = kv.split('=', limit = 2).map { it.trim() }
        val s = Tuning.SPECS.firstOrNull { it.key == k } ?: error("unknown tuning key '$k' (known: ${Tuning.SPECS.joinToString { it.key }})")
        t = s.set(t, v.toDouble())
    }
    return t.sanitized()
}

private fun errorsCsv(r: ReplayResult): String = buildString {
    appendLine("t_s,engine_lat,engine_lon,truth_lat,truth_lon,along_error_m,error_m,truth_off_route_m,uncertainty_m,source,blind")
    val t0 = r.samples.firstOrNull()?.elapsedMs ?: 0
    for (s in r.samples) {
        appendLine(
            String.format(
                Locale.US, "%.1f,%.6f,%.6f,%.6f,%.6f,%.1f,%.1f,%.1f,%.0f,%s,%d",
                (s.elapsedMs - t0) / 1000.0, s.engine.lat, s.engine.lon, s.truth.lat, s.truth.lon, s.alongErrorM, s.errorM,
                s.truthOffRouteM, s.uncertaintyM, s.source.label, if (s.blind) 1 else 0,
            )
        )
    }
}

/** Real (GPS) track in green, engine track in red — open in geojson.io or QGIS. */
private fun geoJson(r: ReplayResult): String {
    fun line(points: List<GeoPoint>, name: String, color: String) =
        """{"type":"Feature","properties":{"name":"$name","stroke":"$color","stroke-width":3},"geometry":{"type":"LineString","coordinates":[""" +
            points.joinToString(",") { String.format(Locale.US, "[%.6f,%.6f]", it.lon, it.lat) } + "]}}"
    return """{"type":"FeatureCollection","features":[${line(r.truthTrack, "GPS (truth)", "#1e8e3e")},${line(r.engineTrack, "engine", "#d93025")}]}"""
}
