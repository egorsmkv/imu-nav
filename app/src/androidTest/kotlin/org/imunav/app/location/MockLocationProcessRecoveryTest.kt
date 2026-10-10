package org.imunav.app.location

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.imunav.core.geo.GeoPoint
import org.imunav.core.location.MockLocationStatus
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** A real process kill/rebind; retaining instrumentation lets us assert the restart's observable state. */
@RunWith(AndroidJUnit4::class)
class MockLocationProcessRecoveryTest {
    private val device = MockLocationDevice()
    private lateinit var worker: GpsWorkerClient

    @Before
    fun setUp() {
        device.prepare()
        worker = GpsWorkerClient(device)
        worker.awaitStatus(MockLocationStatus.WAITING, MockLocationStatus.OFF)
        worker.command(MockLocationTestService.ENABLE, 0)
        worker.awaitStatus(MockLocationStatus.OFF)
    }

    @After
    fun tearDown() {
        device.allowMock(true)
        worker.command(MockLocationTestService.ENABLE, 0)
        worker.awaitStatus(MockLocationStatus.OFF)
        worker.close()
        device.assertOriginalProvider()
        device.restore()
    }

    @Test
    fun processDeathPreservesOptInCleansStaleProviderAndWaitsForFreshGuidance() {
        worker.command(MockLocationTestService.ENABLE, 1)
        worker.command(MockLocationTestService.PUBLISH)
        val active = worker.awaitStatus(MockLocationStatus.ACTIVE)
        assertTrue(active.getBoolean("installed"))
        val deadPid = active.getInt("pid")
        worker.killAndReconnect()
        val restored = worker.awaitStatus(MockLocationStatus.WAITING)
        assertNotEquals("Must use a fresh OS process", deadPid, restored.getInt("pid"))
        assertTrue("Device opt-in survives process death", restored.getBoolean("enabled"))
        assertFalse("Startup must release durable provider ownership", restored.getBoolean("installed"))
        device.assertOriginalProvider()
        MockGpsObserver(device).use { observer ->
            worker.command(MockLocationTestService.PUBLISH)
            worker.awaitStatus(MockLocationStatus.ACTIVE)
            observer.next(GeoPoint(48.125, 28.25))
        }
        worker.command(MockLocationTestService.STOP)
        worker.awaitStatus(MockLocationStatus.WAITING)
        device.assertOriginalProvider()
    }
}

/** Bounded Messenger replies and a Binder-death signal, without shell sleeps or fake process state. */
private class GpsWorkerClient(private val device: MockLocationDevice) : AutoCloseable {
    private val connections = LinkedBlockingQueue<Messenger>()
    private val replies = LinkedBlockingQueue<Bundle>()
    private val deaths = LinkedBlockingQueue<Boolean>()
    private lateinit var remote: Messenger
    private val replyTo = Messenger(Handler(Looper.getMainLooper()) { message -> replies.offer(message.data); true })
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            service.linkToDeath({ deaths.offer(true) }, 0)
            connections.offer(Messenger(service))
        }

        override fun onServiceDisconnected(name: ComponentName) = Unit
    }

    init {
        device.onMain {
            check(device.context.bindService(Intent(device.context, MockLocationTestService::class.java), connection, Context.BIND_AUTO_CREATE))
        }
        remote = checkNotNull(connections.poll(MockLocationDevice.TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "GPS worker did not bind" }
    }

    fun command(command: Int, argument: Int = 0): Bundle {
        remote.send(Message.obtain(null, command, argument, 0).apply { replyTo = this@GpsWorkerClient.replyTo })
        return checkNotNull(replies.poll(MockLocationDevice.TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "GPS worker command $command did not reply" }
    }

    fun awaitStatus(vararg expected: MockLocationStatus): Bundle {
        var snapshot = Bundle()
        device.await("remote GPS status ${expected.toList()}") {
            snapshot = command(MockLocationTestService.SNAPSHOT)
            expected.any { it.name == snapshot.getString("status") }
        }
        return snapshot
    }

    fun killAndReconnect() {
        remote.send(Message.obtain(null, MockLocationTestService.KILL))
        checkNotNull(deaths.poll(MockLocationDevice.TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "GPS worker did not die" }
        // BIND_AUTO_CREATE reconnects after process death; no activity or app process is force-stopped.
        remote = checkNotNull(connections.poll(MockLocationDevice.TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "GPS worker did not restart" }
    }

    override fun close() = device.onMain { device.context.unbindService(connection) }
}
