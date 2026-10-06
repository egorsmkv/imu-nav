package org.imunav.app.power

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.imunav.core.power.BatteryState
import org.imunav.core.power.MapRenderingBudget

/** User-selectable trade-off between accuracy/smoothness and battery. */
enum class PowerMode { AUTO, PERFORMANCE, BALANCED, SAVER }

/** Optional device-local map cap; Auto follows the power and device rendering policy. */
enum class MapFrameRate(val maximumFps: Int?) { AUTO(null), FPS_10(10), FPS_15(15), FPS_20(20), FPS_30(30) }

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
    val mapPrefetchZoomDelta: Int = MapRenderingBudget.DEFAULT_PREFETCH_ZOOM_DELTA,
) {
    /** Change only map costs; the selected profile remains authoritative for positioning inputs. */
    fun withRenderingBudget(constrained: Boolean, explicitPerformance: Boolean): PowerProfile {
        val budget = MapRenderingBudget.resolve(constrained, explicitPerformance, mapMaxFps, animateCamera)
        return copy(mapMaxFps = budget.maximumFps, animateCamera = budget.animateCamera, mapPrefetchZoomDelta = budget.prefetchZoomDelta)
    }

    /** A manual cap can reduce drawing frequency without changing positioning or other rendering settings. */
    fun withFrameRateLimit(rate: MapFrameRate): PowerProfile = rate.maximumFps?.let { copy(mapMaxFps = minOf(mapMaxFps, it)) } ?: this

    companion object {
        val PERFORMANCE = PowerProfile("performance", 20_000, 5_000, 5_000, 10_000, 1_000, true, 60, true, 1)
        val BALANCED = PowerProfile("balanced", 40_000, 10_000, 5_000, 15_000, 3_000, true, 30, true, 1)
        val SAVER = PowerProfile("saver", 100_000, 20_000, 8_000, 30_000, 10_000, false, 15, false, 2)
    }
}

/** Keeps the chosen mode while temporarily reducing power use when the battery is low. */
class PowerPolicy(private val context: Context) {
    private val prefs = context.getSharedPreferences("power", Context.MODE_PRIVATE)

    @Volatile var constrainedDevice: Boolean = false
        private set

    @Volatile var totalRamBytes: Long = 0
        private set

    /** Called once on an I/O worker; publish the result before resolving a new profile on main. */
    fun detectDeviceCapacity() {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        totalRamBytes = info.totalMem
        constrainedDevice = MapRenderingBudget.isConstrained(manager.isLowRamDevice, totalRamBytes)
    }

    var mode: PowerMode
        get() = PowerMode.entries.firstOrNull { it.name == prefs.getString("mode", null) } ?: PowerMode.AUTO
        set(v) = prefs.edit { putString("mode", v.name) }

    /** Rendering preferences stay on this device because display performance differs between phones. */
    var mapFrameRate: MapFrameRate
        get() = MapFrameRate.entries.firstOrNull { it.name == prefs.getString("map_frame_rate", null) } ?: MapFrameRate.AUTO
        set(value) = prefs.edit { putString("map_frame_rate", value.name) }

    /** Keep the display on while navigating (the screen is by far the largest consumer). */
    var keepScreenOn: Boolean
        get() = prefs.getBoolean("keep_screen_on", true)
        set(v) = prefs.edit { putBoolean("keep_screen_on", v) }

    private val _battery = MutableStateFlow(BatteryState())
    val battery = _battery.asStateFlow()

    /** Register and read system services on I/O; the collector applies profiles on the main thread. */
    fun monitorBattery() = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                trySend(Unit)
            }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED).apply { addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED) }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        trySend(Unit)
        awaitClose { context.unregisterReceiver(receiver) }
    }.conflate().map {
        val reading = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = reading?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = reading?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level in 0..scale && scale > 0) (level.toLong() * 100 / scale).toInt() else null
        val plugged = (reading?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val systemSaver = context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
        _battery.value = _battery.value.update(percent, plugged, systemSaver)
    }.flowOn(Dispatchers.IO)

    /** Resolve from cached readings so UI and navigation never query battery services on main. */
    fun resolve(): PowerProfile {
        val selected = mode
        val base = resolveProfile(selected, battery.value)
        return base.withRenderingBudget(constrainedDevice, selected == PowerMode.PERFORMANCE && base != PowerProfile.SAVER).withFrameRateLimit(mapFrameRate)
    }
}

/** A low battery overrides any selected mode without changing the saved preference. */
internal fun resolveProfile(selected: PowerMode, battery: BatteryState): PowerProfile = when {
    battery.requiresSaver -> PowerProfile.SAVER
    selected == PowerMode.PERFORMANCE -> PowerProfile.PERFORMANCE
    selected == PowerMode.BALANCED -> PowerProfile.BALANCED
    selected == PowerMode.SAVER -> PowerProfile.SAVER
    battery.plugged -> PowerProfile.PERFORMANCE
    battery.systemSaver -> PowerProfile.SAVER
    else -> PowerProfile.BALANCED
}
