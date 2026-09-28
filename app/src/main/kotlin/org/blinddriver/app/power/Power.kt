package org.blinddriver.app.power

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import androidx.core.content.edit

/** User-selectable trade-off between accuracy/smoothness and battery. */
enum class PowerMode { AUTO, PERFORMANCE, BALANCED, SAVER }

/**
 * Concrete rates for one mode. Everything that costs energy continuously is here: sensor rates,
 * modem scans, network location, raw GNSS measurements and map rendering.
 */
data class PowerProfile(
    val name: String,
    /** IMU sampling period while navigating (µs). The motion detector is time-based, so any rate ≥ 10 Hz works. */
    val imuPeriodUs: Int,
    /** Cell scan interval while navigating: GPS trusted / GPS not trusted (cells then are the main fix). */
    val cellScanGoodGpsMs: Long,
    val cellScanNoGpsMs: Long,
    /** Cell scan interval with no navigation running (only needed to find the start position). */
    val cellScanIdleMs: Long,
    /** Minimum time between platform network-location fixes. */
    val networkMinMs: Long,
    /** Raw GNSS measurements (AGC jamming indicator); keeps the GNSS chip busier on some phones. */
    val gnssMeasurements: Boolean,
    /** Map frame-rate cap and whether the camera glides or jumps when following. */
    val mapMaxFps: Int,
    val animateCamera: Boolean,
    /** Publish UI state every N engine ticks (500 ms each) while the screen shows the app. */
    val uiEveryTicks: Int,
) {
    companion object {
        val PERFORMANCE = PowerProfile("performance", 20_000, 5_000, 5_000, 10_000, 1_000, true, 60, true, 1)
        val BALANCED = PowerProfile("balanced", 40_000, 10_000, 5_000, 15_000, 3_000, true, 30, true, 1)
        val SAVER = PowerProfile("saver", 100_000, 20_000, 8_000, 30_000, 10_000, false, 15, false, 2)
    }
}

/** Remembers the chosen mode and resolves AUTO from battery level and the system battery saver. */
class PowerPolicy(private val context: Context) {
    private val prefs = context.getSharedPreferences("power", Context.MODE_PRIVATE)

    var mode: PowerMode
        get() = runCatching { PowerMode.valueOf(prefs.getString("mode", PowerMode.AUTO.name)!!) }.getOrDefault(PowerMode.AUTO)
        set(v) = prefs.edit { putString("mode", v.name) }

    /** Keep the display on while navigating (the screen is by far the largest consumer). */
    var keepScreenOn: Boolean
        get() = prefs.getBoolean("keep_screen_on", true)
        set(v) = prefs.edit { putBoolean("keep_screen_on", v) }

    fun batteryPercent(): Int? = runCatching {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) null else level * 100 / scale
    }.getOrNull()

    fun isCharging(): Boolean = runCatching {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    }.getOrDefault(false)

    /** AUTO: full rate on a charger, saver under 20 % or with the system battery saver on, else balanced. */
    fun resolve(): PowerProfile = when (mode) {
        PowerMode.PERFORMANCE -> PowerProfile.PERFORMANCE

        PowerMode.BALANCED -> PowerProfile.BALANCED

        PowerMode.SAVER -> PowerProfile.SAVER

        PowerMode.AUTO -> {
            val pm = context.getSystemService(PowerManager::class.java)
            val battery = batteryPercent()
            when {
                isCharging() -> PowerProfile.PERFORMANCE
                pm?.isPowerSaveMode == true || (battery != null && battery <= 20) -> PowerProfile.SAVER
                else -> PowerProfile.BALANCED
            }
        }
    }
}
