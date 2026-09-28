package org.blinddriver.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import org.blinddriver.core.geo.Geo
import org.blinddriver.core.geo.GeoPoint
import org.blinddriver.core.geo.ServiceArea

/** Where the map opens: follow the phone's position, or a place the user picked. */
enum class MapStartMode { GPS, FIXED }

/** Initial camera: a point, its zoom, and whether it is the user's actual position. */
data class MapStartView(val point: GeoPoint, val zoom: Double, val isPosition: Boolean)

/**
 * Map start settings. In [MapStartMode.GPS] the map opens at the best position known right away
 * (Android's last GPS fix, else the last trusted position this app saw) and then follows the first
 * live trusted fix; in [MapStartMode.FIXED] it opens at the configured place and stays there.
 * This only positions the view, never the navigation start, which still needs a trusted fix or
 * a hand-placed start.
 */
class MapStartPrefs(private val context: Context, private val area: ServiceArea) {
    private val prefs = context.getSharedPreferences("map_start", Context.MODE_PRIVATE)

    var mode: MapStartMode
        get() = MapStartMode.entries.firstOrNull { it.name == prefs.getString("mode", null) } ?: MapStartMode.GPS
        set(v) = prefs.edit { putString("mode", v.name) }

    var fixed: GeoPoint?
        get() = point("fixed")
        set(v) = prefs.edit {
            if (v == null) {
                remove("fixed_lat")
                remove("fixed_lon")
            } else {
                putString("fixed_lat", v.lat.toString())
                putString("fixed_lon", v.lon.toString())
            }
        }

    /** Last trusted position, remembered so the next start opens there even before any fix arrives. */
    val lastTrusted: GeoPoint? get() = point("last")

    /** Remember a trusted position for the next app start. */
    fun rememberTrusted(p: GeoPoint) {
        val prev = lastTrusted
        // Avoid a disk write every tick: only when the position really changed.
        if (prev != null && Geo.distance(prev, p) < REMEMBER_MIN_MOVE_M) return
        prefs.edit {
            putString("last_lat", p.lat.toString())
            putString("last_lon", p.lon.toString())
        }
    }

    /** Where the map should open right now. */
    fun initialView(): MapStartView {
        if (mode == MapStartMode.FIXED) fixed?.let { return MapStartView(it, FIXED_ZOOM, isPosition = false) }
        systemLastGps()?.let { return MapStartView(it, POSITION_ZOOM, isPosition = true) }
        lastTrusted?.let { return MapStartView(it, POSITION_ZOOM, isPosition = true) }
        return MapStartView(fixed ?: KYIV, OVERVIEW_ZOOM, isPosition = false)
    }

    /**
     * Android's cached GPS fix (instant, no radio use). Mock fixes and fixes outside the service
     * area are ignored: under spoofing the cached fix is exactly what an attacker planted.
     */
    private fun systemLastGps(): GeoPoint? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val loc = runCatching { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull() ?: return null
        val mock = if (Build.VERSION.SDK_INT >= 31) {
            loc.isMock
        } else {
            @Suppress("DEPRECATION")
            loc.isFromMockProvider
        }
        val ageMs = System.currentTimeMillis() - loc.time
        if (mock || ageMs > MAX_LAST_GPS_AGE_MS || !area.contains(loc.latitude, loc.longitude)) return null
        return GeoPoint(loc.latitude, loc.longitude)
    }

    /** Read a saved point (stored as two strings to keep full precision). */
    private fun point(prefix: String): GeoPoint? {
        val lat = prefs.getString("${prefix}_lat", null)?.toDoubleOrNull() ?: return null
        val lon = prefs.getString("${prefix}_lon", null)?.toDoubleOrNull() ?: return null
        return GeoPoint(lat, lon)
    }

    companion object {
        /** Fallback when nothing else is known. */
        val KYIV = GeoPoint(50.4501, 30.5234)
        const val POSITION_ZOOM = 14.0
        const val FIXED_ZOOM = 13.0
        const val OVERVIEW_ZOOM = 12.0
        private const val REMEMBER_MIN_MOVE_M = 200.0
        private const val MAX_LAST_GPS_AGE_MS = 7L * 24 * 60 * 60 * 1000

        /** Parse "50.45, 30.52" (also with ';' or spaces). */
        fun parse(text: String): GeoPoint? {
            val parts = text.trim().split(Regex("[,;\\s]+")).filter { it.isNotEmpty() }
            if (parts.size != 2) return null
            val lat = parts[0].toDoubleOrNull() ?: return null
            val lon = parts[1].toDoubleOrNull() ?: return null
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
            return GeoPoint(lat, lon)
        }
    }
}
