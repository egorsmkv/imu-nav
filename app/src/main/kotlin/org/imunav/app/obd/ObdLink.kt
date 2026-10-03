package org.imunav.app.obd

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.imunav.core.obd.ConnectionSession
import org.imunav.core.obd.Elm327
import java.io.Closeable
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors

/** A paired Bluetooth device the user can pick as the OBD-II adapter. */
data class ObdDevice(val address: String, val name: String)

/** What the adapter link is doing, for Settings and the trip log. */
data class ObdStatus(val state: State = State.OFF, val speedKmh: Int? = null, val message: String? = null) {
    enum class State { OFF, CONNECTING, CONNECTED, ERROR }
}

/**
 * Reads the car's speed from a Bluetooth (classic, "SPP") ELM327 OBD-II adapter and hands it to
 * [onSpeed] on the main thread, several times a second.
 *
 * The user pairs the adapter in the phone's Bluetooth settings and picks it here once; the link is
 * opened while navigating by car ([start]) and closed afterwards ([stop]). Lost connections are
 * retried with a growing pause. Bluetooth reads cannot time out, so a watchdog closes the socket
 * when the adapter goes silent, which unblocks the reading thread.
 *
 * BLE-only adapters are not supported (they need a different, vendor-specific protocol).
 */
class ObdLink(private val context: Context, private val log: (String) -> Unit, private val onSpeed: (kmh: Int, elapsedMs: Long) -> Unit) {
    private val prefs = context.getSharedPreferences("obd", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())

    private val _enabled = MutableStateFlow(prefs.getBoolean("enabled", false))

    /** "Use the car's speed" in Settings. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _device = MutableStateFlow(prefs.getString("address", null)?.let { ObdDevice(it, prefs.getString("name", null) ?: it) })

    /** The adapter chosen by the user, or null. */
    val device: StateFlow<ObdDevice?> = _device.asStateFlow()

    private val _status = MutableStateFlow(ObdStatus())
    val status: StateFlow<ObdStatus> = _status.asStateFlow()

    @Volatile private var session: ConnectionSession<Connection>? = null
    private var worker: Thread? = null
    private val closer = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "obd-close").apply { isDaemon = true } }

    /** Each watchdog deadline belongs to exactly one socket, including failed connection attempts. */
    private class Connection(val socket: BluetoothSocket) : Closeable {
        @Volatile var deadlineMs = 0L
        override fun close() {
            runCatching { socket.close() }
        }
    }

    /** Queued status updates from a stopped session must not overwrite the next connection's UI. */
    private fun publish(owner: ConnectionSession<Connection>, status: ObdStatus) {
        main.post { if (owner.active) _status.value = status }
    }

    fun setEnabled(on: Boolean) {
        prefs.edit { putBoolean("enabled", on) }
        _enabled.value = on
        if (!on) stop()
    }

    fun setDevice(device: ObdDevice) {
        prefs.edit {
            putString("address", device.address)
            putString("name", device.name)
        }
        _device.value = device
        // Switch adapters immediately if a connection is running.
        if (session != null) {
            stop()
            start()
        }
    }

    /** Android 12+ asks the user before an app may use paired Bluetooth devices. */
    fun hasPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** The runtime permission to ask for before [start], or null when none is needed (Android 11 and older). */
    val permission: String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_CONNECT else null

    /** Does the phone have Bluetooth at all? */
    val available: Boolean get() = context.getSystemService(BluetoothManager::class.java)?.adapter != null

    /** Paired devices, adapter-looking names ("OBD", "ELM", "V-LINK"…) first. Empty without permission. */
    @SuppressLint("MissingPermission") // checked by hasPermission()
    fun pairedDevices(): List<ObdDevice> {
        if (!hasPermission()) return emptyList()
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        return runCatching { adapter.bondedDevices.orEmpty() }.getOrDefault(emptySet())
            .map { ObdDevice(it.address, it.name ?: it.address) }
            .sortedWith(compareByDescending<ObdDevice> { d -> ADAPTER_HINTS.any { d.name.contains(it, ignoreCase = true) } }.thenBy { it.name })
    }

    /** Connect (in the background) if the feature is on and an adapter is chosen. */
    fun start() {
        if (!_enabled.value || _device.value == null || session != null) return
        if (!hasPermission()) {
            _status.value = ObdStatus(ObdStatus.State.ERROR, message = "no Bluetooth permission")
            return
        }
        val owner = ConnectionSession<Connection>()
        session = owner
        worker = Thread({ run(owner) }, "obd").apply {
            isDaemon = true
            start()
        }
        Thread({ watch(owner) }, "obd-watchdog").apply {
            isDaemon = true
            start()
        }
    }

    /** Close the connection. */
    fun stop() {
        val owner = session ?: return
        session = null
        val connection = owner.invalidate()
        worker?.interrupt()
        worker = null
        closer.execute { connection?.close() }
        _status.value = ObdStatus()
    }

    /** Interruption during either polling or backoff is normal shutdown; socket cleanup belongs to the attempt. */
    private fun run(owner: ConnectionSession<Connection>) {
        var failures = 0
        try {
            while (owner.active) {
                try {
                    connectAndPoll(owner)
                    failures = 0
                } catch (e: IOException) {
                    if (!owner.active) break
                    failures++
                    val message = e.message ?: e.javaClass.simpleName
                    publish(owner, ObdStatus(ObdStatus.State.ERROR, message = message))
                    log("obd_error $message retry=$failures")
                    Thread.sleep(RETRY_MS[(failures - 1).coerceAtMost(RETRY_MS.lastIndex)])
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            owner.invalidate()?.close()
        }
    }

    @SuppressLint("MissingPermission") // start() only runs with the permission
    private fun connectAndPoll(owner: ConnectionSession<Connection>) {
        val chosen = _device.value ?: throw IOException("no adapter chosen")
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: throw IOException("no Bluetooth")
        if (!adapter.isEnabled) throw IOException("Bluetooth is off")
        publish(owner, ObdStatus(ObdStatus.State.CONNECTING))
        val remote = adapter.getRemoteDevice(chosen.address)
        // Some clones only accept insecure SPP. Both attempts retain their own cleanup ownership.
        val connection = try {
            connect(owner, remote.createRfcommSocketToServiceRecord(SPP))
        } catch (_: IOException) {
            if (!owner.active) throw InterruptedException("stopped")
            connect(owner, remote.createInsecureRfcommSocketToServiceRecord(SPP))
        }
        try {
            val elm = Elm327(connection.socket.inputStream, connection.socket.outputStream)
            connection.deadlineMs = SystemClock.elapsedRealtime() + INIT_TIMEOUT_MS
            elm.initialize()
            log("obd_connected ${chosen.name} ${elm.version}")
            publish(owner, ObdStatus(ObdStatus.State.CONNECTED))
            poll(owner, connection, elm)
        } finally {
            owner.release(connection)
        }
    }

    /** Register before the blocking binder call, and release this exact socket on any failure. */
    @SuppressLint("MissingPermission") // start() checked the permission
    private fun connect(owner: ConnectionSession<Connection>, socket: BluetoothSocket): Connection {
        val connection = Connection(socket)
        owner.attach(connection)
        var connected = false
        try {
            connection.deadlineMs = SystemClock.elapsedRealtime() + CONNECT_TIMEOUT_MS
            socket.connect()
            connected = true
            return connection
        } finally {
            if (!connected) owner.release(connection)
        }
    }

    /** Read-only speed polling; callbacks are checked again when the main thread delivers them. */
    private fun poll(owner: ConnectionSession<Connection>, connection: Connection, elm: Elm327) {
        var lastShownKmh: Int? = null
        while (owner.active) {
            val askedAt = SystemClock.elapsedRealtime()
            connection.deadlineMs = askedAt + READ_TIMEOUT_MS
            val kmh = elm.readSpeedKmh()
            val at = SystemClock.elapsedRealtime()
            if (kmh != null) {
                main.post { if (owner.active) onSpeed(kmh, at) }
                if (kmh != lastShownKmh) {
                    lastShownKmh = kmh
                    publish(owner, ObdStatus(ObdStatus.State.CONNECTED, kmh))
                }
            }
            connection.deadlineMs = 0L
            val pause = POLL_MS - (at - askedAt)
            if (pause > 0) Thread.sleep(pause)
        }
    }

    /** A timeout closes the captured attempt, even if a reconnect installed another socket meanwhile. */
    private fun watch(owner: ConnectionSession<Connection>) {
        while (owner.active) {
            try {
                Thread.sleep(WATCHDOG_MS)
            } catch (_: InterruptedException) {
                return
            }
            val connection = owner.connection ?: continue
            val deadline = connection.deadlineMs
            if (deadline > 0 && SystemClock.elapsedRealtime() > deadline && owner.active) {
                connection.deadlineMs = 0L
                log("obd_timeout")
                connection.close()
            }
        }
    }

    private companion object {
        /** The standard Bluetooth serial port profile. */
        val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        val ADAPTER_HINTS = listOf("OBD", "ELM", "V-LINK", "VLINK", "VGATE", "KONNWEI", "CAR")

        /** 5 readings per second is plenty for dead reckoning and easy for slow adapters. */
        const val POLL_MS = 200L
        const val CONNECT_TIMEOUT_MS = 15_000L

        /** Resetting and finding the car's protocol ("SEARCHING...") can take a while. */
        const val INIT_TIMEOUT_MS = 20_000L
        const val READ_TIMEOUT_MS = 4_000L
        const val WATCHDOG_MS = 1_000L
        val RETRY_MS = listOf(2_000L, 5_000L, 10_000L, 30_000L)
    }
}
