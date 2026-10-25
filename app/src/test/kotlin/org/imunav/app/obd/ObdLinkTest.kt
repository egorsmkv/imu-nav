package org.imunav.app.obd

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBluetoothDevice
import org.robolectric.shadows.ShadowLooper
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** The car-speed link connects to a paired ELM327 adapter, polls speed read-only and stops cleanly. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ObdLinkTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val log = CopyOnWriteArrayList<String>()
    private val speeds = CopyOnWriteArrayList<Int>()
    private val commands = CopyOnWriteArrayList<String>()
    private lateinit var obd: ObdLink
    private lateinit var adapter: BluetoothDevice

    private fun waitFor(what: String, done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (!done()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what: ${obd.status.value} ${log.takeLast(3)}" }
            ShadowLooper.idleMainLooper(STEP_MS, TimeUnit.MILLISECONDS)
            Thread.sleep(STEP_MS)
        }
    }

    /**
     * A minimal ELM327: answers each `\r`-terminated command and ends with the `>` prompt. The
     * Robolectric device returns one shared socket, so the test can attach to it before the link does.
     */
    private fun fakeElm327(speedKmh: Int) {
        val socket = shadowOf(adapter.createRfcommSocketToServiceRecord(SPP))
        val replies = socket.inputStreamFeeder
        val requests = socket.outputStreamSink
        Thread({
            try {
                val command = StringBuilder()
                while (true) {
                    val byte = requests.read()
                    if (byte < 0) return@Thread
                    if (byte.toChar() != '\r') {
                        command.append(byte.toChar())
                        continue
                    }
                    val text = command.toString().also { commands += it }
                    command.clear()
                    val answer = when {
                        text == "ATZ" -> "ELM327 v1.5"
                        text.startsWith("AT") -> "OK"
                        text == "0100" -> "41 00 BE 3E B8 11"
                        text == "010D" -> "41 0D %02X".format(speedKmh)
                        else -> "?"
                    }
                    replies.write("$answer\r\r>".toByteArray(Charsets.US_ASCII))
                    replies.flush()
                }
            } catch (_: IOException) {
                // The link closed the socket.
            }
        }, "fake-elm327").apply {
            isDaemon = true
            start()
        }
    }

    @Before
    fun setUp() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val bluetooth = shadowOf(context.getSystemService(BluetoothManager::class.java).adapter)
        bluetooth.setEnabled(true)
        adapter = ShadowBluetoothDevice.newInstance(ADDRESS)
        shadowOf(adapter).setName("OBDII")
        val headphones = ShadowBluetoothDevice.newInstance("AA:BB:CC:DD:EE:FF").also { shadowOf(it).setName("Headphones") }
        bluetooth.setBondedDevices(setOf(headphones, adapter))
        obd = ObdLink(context, { log += it }) { kmh, _ -> speeds += kmh }
    }

    @After
    fun tearDown() = obd.stop()

    @Test
    fun pairedAdapterIsPolledForSpeedUntilStopped() {
        assertTrue(obd.available)
        assertTrue(obd.hasPermission())
        assertEquals(Manifest.permission.BLUETOOTH_CONNECT, obd.permission)
        assertEquals("adapter-like names come first", listOf("OBDII", "Headphones"), obd.pairedDevices().map { it.name })

        fakeElm327(speedKmh = 60)
        obd.setEnabled(true)
        obd.setDevice(ObdDevice(ADDRESS, "OBDII"))
        obd.start()
        waitFor("speed") { obd.status.value.speedKmh == 60 }
        assertEquals(ObdStatus.State.CONNECTED, obd.status.value.state)
        assertEquals(60, speeds.first())
        assertTrue(log.any { it.startsWith("obd_connected OBDII ELM327 v1.5") })
        assertTrue("only read-only requests reach the car", commands.filterNot { it.startsWith("AT") }.all { it == "0100" || it == "010D" })

        obd.setEnabled(false)
        assertEquals(ObdStatus(), obd.status.value)
        assertEquals(false, ObdLink(context, {}) { _, _ -> }.enabled.value)
        assertEquals(ADDRESS, ObdLink(context, {}) { _, _ -> }.device.value?.address)
    }

    @Test
    fun bluetoothOffIsReportedAndRetried() {
        shadowOf(context.getSystemService(BluetoothManager::class.java).adapter).setEnabled(false)
        obd.setEnabled(true)
        obd.setDevice(ObdDevice(ADDRESS, "OBDII"))
        obd.start()
        waitFor("error") { obd.status.value.state == ObdStatus.State.ERROR }
        assertEquals("Bluetooth is off", obd.status.value.message)
        assertTrue(log.contains("obd_error Bluetooth is off retry=1"))
        obd.stop()
        assertEquals(ObdStatus.State.OFF, obd.status.value.state)
    }

    @Test
    fun missingPermissionStopsBeforeConnecting() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        assertTrue(obd.pairedDevices().isEmpty())
        obd.setEnabled(true)
        obd.setDevice(ObdDevice(ADDRESS, "OBDII"))
        obd.start()
        assertEquals(ObdStatus(ObdStatus.State.ERROR, message = "no Bluetooth permission"), obd.status.value)
    }
}

private const val STEP_MS = 50L
private const val ADDRESS = "00:11:22:33:44:55"
private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
