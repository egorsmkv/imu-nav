package org.imunav.replay

import org.imunav.core.Tuning
import org.imunav.core.geo.GeoPoint
import org.imunav.core.geo.ServiceArea
import org.imunav.core.record.ReplayResult
import org.imunav.core.record.TripFormat
import org.imunav.core.record.TripReplayer
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
  --compare-native         compare Kotlin and native at reference GPS timestamps; use -PnativeReplay
                           to build the host JNI library. Hidden GPS is excluded from all navigation inputs.
  --compare-eskf           compare Kotlin/native/experimental inertial ESKF; requires new raw U recordings
                           and -PnativeReplay. Mounted-car only; reports missing initialization/coverage.
  --no-native-motion       disable native stop/resume hints for an A/B comparison (with --compare-native)
  --no-native-network      disable native cell/network position and speed corrections
  --native-network-speed   opt into experimental cell-derived speed (off by default; can lag speed changes)
  --no-native-turns        disable native turn-landmark corrections for an A/B comparison
  --out DIR                write summary.txt, errors-*.csv and compare-*.geojson per run
"""

/** Command-line entry point; see [USAGE] or run with `--help`. */
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
    validateComparisonOptions(opts)
    val area = if ("ukraine" in opts) ServiceArea.UKRAINE_COARSE else ServiceArea.EVERYWHERE
    val hides: List<Double?> = opts["hide-gps-after"]?.split(',')?.map { it.trim().toDouble() } ?: listOf(null)
    val outDir = opts["out"]?.let { File(it).apply { mkdirs() } }
    val files = recordingFiles(input)
    if (files.isEmpty()) error("no recordings in $input")

    val summary = StringBuilder()
    for (f in files) {
        val events = TripFormat.read(f)
        summary.appendLine("== ${f.name}: ${events.size} events")
        for (hide in hides) {
            val tag = f.name.substringBefore('.') + (hide?.let { "-hide${it.toInt()}s" } ?: "-asrec")
            summary.appendLine("-- ${hide?.let { "GPS hidden after ${it.toInt()} s" } ?: "as recorded"}")
            if ("compare-eskf" in opts) {
                val comparison = InertialComparison(tuning, area).replay(events, hide)
                summary.append(comparison.summary())
                outDir?.let { File(it, "eskf-errors-$tag.csv").writeText(comparison.csv()) }
                continue
            }
            if ("compare-native" in opts) {
                val motionEnabled = "no-native-motion" !in opts
                val networkEnabled = "no-native-network" !in opts
                val turnsEnabled = "no-native-turns" !in opts
                val networkSpeedEnabled = networkEnabled && "native-network-speed" in opts
                val comparison = NativeComparison(
                    tuning,
                    area,
                    nativeMotionEnabled = motionEnabled,
                    nativeNetworkEnabled = networkEnabled,
                    nativeTurnsEnabled = turnsEnabled,
                    nativeNetworkSpeedEnabled = networkSpeedEnabled,
                ).replay(events, hide)
                summary.appendLine("native motion hints: $motionEnabled")
                summary.appendLine("native coarse-position corrections: $networkEnabled")
                summary.appendLine("native cell-derived speed: $networkSpeedEnabled")
                summary.appendLine("native turn-landmark corrections: $turnsEnabled")
                summary.append(comparison.summary())
                outDir?.let { File(it, "native-errors-$tag.csv").writeText(comparison.csv()) }
                continue
            }
            val result = TripReplayer(tuning, area).replay(events, hide)
            summary.append(result.summary())
            outDir?.let { dir ->
                File(dir, "errors-$tag.csv").writeText(errorsCsv(result))
                File(dir, "compare-$tag.geojson").writeText(geoJson(result))
            }
        }
    }
    print(summary)
    outDir?.let {
        File(it, "summary.txt").writeText(summary.toString())
        println("report written to ${it.absolutePath}")
    }
}

/** Keep directory expansion separate from the replay-mode dispatch. */
private fun recordingFiles(input: File): List<File> =
    if (input.isDirectory) input.listFiles().orEmpty().filter { it.name.endsWith(".rec.gz") || it.name.endsWith(".rec") }.sorted() else listOf(input)

/** Reject ambiguous A/B configurations rather than silently ignoring native experiment switches. */
private fun validateComparisonOptions(options: Map<String, String?>) {
    require("compare-eskf" !in options || options.keys.none { it == "compare-native" || it.startsWith("no-native-") || it == "native-network-speed" }) {
        "--compare-eskf uses default native comparison settings; do not combine it with native-only comparison flags"
    }
}

/** Apply `--set key=value,…` to [base] (keys are [Tuning.SPECS] keys). */
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

/** Per-sample errors as CSV, for spreadsheets / plotting. */
private fun errorsCsv(r: ReplayResult): String = buildString {
    appendLine("t_s,engine_lat,engine_lon,truth_lat,truth_lon,along_error_m,error_m,truth_off_route_m,uncertainty_m,source,blind")
    val t0 = r.samples.firstOrNull()?.elapsedMs ?: 0
    for (s in r.samples) {
        appendLine(
            String.format(
                Locale.US, "%.1f,%.6f,%.6f,%.6f,%.6f,%.1f,%.1f,%.1f,%.0f,%s,%d",
                (s.elapsedMs - t0) / 1000.0, s.engine.lat, s.engine.lon, s.truth.lat, s.truth.lon, s.alongErrorM, s.errorM,
                s.truthOffRouteM, s.uncertaintyM, s.source.label, if (s.blind) 1 else 0,
            ),
        )
    }
}

/** Real (GPS) track in green, engine track in red — open in geojson.io or QGIS. */
private fun geoJson(r: ReplayResult): String {
    /** One GeoJSON LineString feature. */
    fun line(points: List<GeoPoint>, name: String, color: String) =
        """{"type":"Feature","properties":{"name":"$name","stroke":"$color","stroke-width":3},"geometry":{"type":"LineString","coordinates":[""" +
            points.joinToString(",") { String.format(Locale.US, "[%.6f,%.6f]", it.lon, it.lat) } + "]}}"
    return """{"type":"FeatureCollection","features":[${line(r.truthTrack, "GPS (truth)", "#1e8e3e")},${line(r.engineTrack, "engine", "#d93025")}]}"""
}
