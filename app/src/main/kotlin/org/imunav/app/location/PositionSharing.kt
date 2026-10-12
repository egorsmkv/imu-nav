package org.imunav.app.location

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.imunav.core.diagnostics.DiagnosticOwner
import org.imunav.core.location.NavigationMirrorEncoder
import org.imunav.core.location.NavigationMirrorInput
import org.imunav.core.location.NavigationPositionSample
import org.imunav.core.location.NavigationTcpServer
import org.imunav.core.location.PositionSharingAccount
import org.imunav.core.nav.GuidanceState
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicReference

/** A deliberate session choice; mode never changes implicitly on network failures. */
enum class PositionSharingMode { LOCAL, INTERNET, BOTH }

/** Safe localized status categories, with no remote response bodies or user positions. */
enum class PositionSharingPhase { OFF, WAITING, ACTIVE, RETRYING, NETWORK_ERROR, ACCOUNT_REQUIRED, CONSENT_REQUIRED, SERVER_UNAVAILABLE, ACCOUNT_CHANGED }

/** Each transport reports independently so an internet outage does not hide a working local stream. */
data class PositionTransportStatus(val phase: PositionSharingPhase = PositionSharingPhase.OFF, val endpoint: String? = null, val receivers: Int = 0)

/** Only session-local state is published; activation is never stored or synchronized. */
data class PositionSharingStatus(
    val enabled: Boolean = false,
    val shareNavigation: Boolean = false,
    val standalone: Boolean = false,
    val addresses: List<String> = emptyList(),
    val local: PositionTransportStatus = PositionTransportStatus(),
    val internet: PositionTransportStatus = PositionTransportStatus(),
)

/** Service-driven output workers own their sockets and cannot modify navigation or the Android GPS provider. */
class PositionSharing(
    private val scope: CoroutineScope,
    private val account: PositionSharingAccount,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val addresses: () -> List<String> = ::privateAddresses,
) {
    private val _status = MutableStateFlow(PositionSharingStatus())
    val status = _status.asStateFlow()
    private val session = MutableStateFlow<Session?>(null)
    private val input = MutableStateFlow(Input(null, 1_000L, 0L))
    private val relay = AtomicReference<LivePositionRelay?>()
    private var revision = 0L

    init {
        account.onIdentityChanged { relay.get()?.cancel() }
        scope.launch(Dispatchers.IO) {
            session.collectLatest { selected ->
                if (selected != null) {
                    coroutineScope {
                        if (selected.mode != PositionSharingMode.INTERNET) launch { local(selected) }
                        if (selected.mode != PositionSharingMode.LOCAL) launch { internet(selected) }
                    }
                }
            }
        }
    }

    /** Interface enumeration is Binder/network work and belongs on a worker even when opening Settings. */
    fun refreshAddresses() {
        scope.launch(Dispatchers.IO) {
            val addresses = runCatching { addresses() }.getOrDefault(emptyList())
            _status.update { it.copy(addresses = addresses) }
        }
    }

    /** Explicit main-thread opt-in; an idle session exports positions without authorizing future route details. */
    fun start(mode: PositionSharingMode, address: String?, navigating: Boolean, shareNavigation: Boolean = false) {
        if (session.value != null) return
        val details = navigating && shareNavigation
        val owner = if (mode != PositionSharingMode.LOCAL) account.owner() else null
        _status.update {
            it.copy(
                enabled = true,
                shareNavigation = details,
                standalone = !navigating,
                local = PositionTransportStatus(if (mode == PositionSharingMode.INTERNET) PositionSharingPhase.OFF else PositionSharingPhase.WAITING),
                internet = PositionTransportStatus(if (mode == PositionSharingMode.LOCAL) PositionSharingPhase.OFF else PositionSharingPhase.WAITING),
            )
        }
        session.value = Session(++revision, mode, address, owner, details, standalone = !navigating)
    }

    /** Restart output sessions so disabling details closes old streams and clears the relay cache. */
    fun setShareNavigation(enabled: Boolean) {
        val current = session.value ?: return
        if (current.standalone && enabled) return
        if (current.shareNavigation == enabled) return
        session.value = current.copy(revision = ++revision, shareNavigation = enabled)
        _status.update {
            it.copy(
                shareNavigation = enabled,
                local = it.local.copy(phase = if (current.mode == PositionSharingMode.INTERNET) PositionSharingPhase.OFF else PositionSharingPhase.WAITING),
                internet = it.internet.copy(phase = if (current.mode == PositionSharingMode.LOCAL) PositionSharingPhase.OFF else PositionSharingPhase.WAITING),
            )
        }
    }

    /** Captures a new immutable output after every foreground-service tick, including with the screen off. */
    fun update(state: GuidanceState, elapsedMs: Long, utcMs: Long, intervalMs: Long, mirror: NavigationMirrorInput? = null, idleSample: NavigationPositionSample? = null) {
        val current = session.value
        if (state.active && current?.standalone == true) {
            // Starting a trip enables its controls, but does not grant route-sharing consent.
            session.value = current.copy(revision = ++revision, standalone = false)
            _status.update { it.copy(standalone = false) }
        }
        if (!state.active && session.value?.standalone != true) {
            stop()
        } else {
            val sample = if (state.active) NavigationPositionSample.from(state, elapsedMs, utcMs) else idleSample
            input.value = Input(sample, intervalMs, elapsedMs, mirror.takeIf { state.active })
        }
    }

    /** Cancel blocking calls immediately; workers release resources independently of the service lifecycle. */
    fun stop() {
        session.value = null
        relay.get()?.cancel()
        input.value = Input(null, input.value.intervalMs, clock())
        _status.update { it.copy(enabled = false, shareNavigation = false, standalone = false, local = PositionTransportStatus(), internet = PositionTransportStatus()) }
    }

    private suspend fun local(selected: Session) {
        var server: NavigationTcpServer? = null
        var lastSent = -1L
        var lastAddressCheck = -1L
        val encoder = NavigationMirrorEncoder()
        try {
            val address = selected.address ?: throw IOException("No local address")
            if (address !in addresses()) throw IOException("Local address unavailable")
            server = NavigationTcpServer(InetAddress.getByName(address), clock = clock) { count ->
                if (session.value == selected) _status.update { it.copy(local = it.local.copy(receivers = count)) }
            }
            val endpoint = "$address:${NavigationTcpServer.DEFAULT_PORT}"
            input.collect { current ->
                if (lastAddressCheck < 0 || current.revision - lastAddressCheck >= current.intervalMs) {
                    if (address !in addresses()) throw IOException("Local address unavailable")
                    lastAddressCheck = current.revision
                }
                val sample = current.sample?.takeIf { it.fresh(clock()) }
                if (sample == null) {
                    server.publish(null)
                    setLocal(selected, PositionTransportStatus(PositionSharingPhase.WAITING, endpoint))
                } else if (lastSent < 0 || sample.elapsedMs - lastSent >= current.intervalMs) {
                    lastSent = sample.elapsedMs
                    val mirror = encoder.encode(sample, current.mirror, selected.shareNavigation, clock())
                    server.publish(sample, mirror.publication)
                    setLocal(selected, _status.value.local.copy(phase = PositionSharingPhase.ACTIVE, endpoint = endpoint))
                }
            }
        } catch (_: IOException) {
            setLocal(selected, PositionTransportStatus(PositionSharingPhase.NETWORK_ERROR))
        } finally {
            server?.close()
        }
    }

    private suspend fun internet(selected: Session) {
        val owner = selected.owner
        if (owner == null) {
            setInternet(selected, PositionTransportStatus(PositionSharingPhase.ACCOUNT_REQUIRED))
            return
        }
        val transport = LivePositionRelay(account, owner) { session.value == selected }
        relay.set(transport)
        var connected = false
        var lastSent = -1L
        var retryAt = 0L
        var backoffMs = 1_000L
        var rejected = false
        val encoder = NavigationMirrorEncoder()
        try {
            input.collect { current ->
                if (account.owner() != owner) {
                    rejected = true
                    transport.cancel()
                    setInternet(selected, PositionTransportStatus(PositionSharingPhase.ACCOUNT_CHANGED))
                }
                if (rejected) return@collect
                val now = clock()
                val sample = current.sample?.takeIf { it.fresh(now) }
                if (sample == null) {
                    if (connected) transport.stop()
                    connected = false
                    setInternet(selected, PositionTransportStatus(PositionSharingPhase.WAITING, transport.endpoint))
                    return@collect
                }
                if (now < retryAt || (lastSent >= 0 && sample.elapsedMs - lastSent < current.intervalMs)) return@collect
                try {
                    if (!connected) transport.start()
                    connected = true
                    // HTTP setup/refresh may take time; discard the old sample and use the newest one.
                    val newestInput = input.value
                    val newest = newestInput.sample?.takeIf { it.fresh(clock()) } ?: return@collect
                    val mirror = encoder.encode(newest, newestInput.mirror, selected.shareNavigation, clock())
                    transport.publish(newest, clock(), mirror)
                    lastSent = newest.elapsedMs
                    backoffMs = 1_000L
                    setInternet(selected, PositionTransportStatus(PositionSharingPhase.ACTIVE, transport.endpoint, transport.receivers))
                } catch (failure: IOException) {
                    val phase = when ((failure as? RelayFailure)?.code) {
                        401 -> PositionSharingPhase.ACCOUNT_REQUIRED
                        403 -> PositionSharingPhase.CONSENT_REQUIRED
                        503 -> PositionSharingPhase.SERVER_UNAVAILABLE
                        else -> PositionSharingPhase.RETRYING
                    }
                    rejected = phase == PositionSharingPhase.ACCOUNT_REQUIRED || phase == PositionSharingPhase.CONSENT_REQUIRED
                    if (failure is RelayFailure && failure.code == 404) connected = false
                    retryAt = now + backoffMs
                    backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
                    setInternet(selected, PositionTransportStatus(phase, transport.endpoint))
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { transport.stop() }
            relay.compareAndSet(transport, null)
        }
    }

    private fun setLocal(selected: Session, value: PositionTransportStatus) {
        if (session.value == selected) _status.update { it.copy(local = value) }
    }

    private fun setInternet(selected: Session, value: PositionTransportStatus) {
        if (session.value == selected) _status.update { it.copy(internet = value) }
    }

    private data class Session(
        val revision: Long,
        val mode: PositionSharingMode,
        val address: String?,
        val owner: DiagnosticOwner?,
        val shareNavigation: Boolean,
        val standalone: Boolean,
    )
    private data class Input(val sample: NavigationPositionSample?, val intervalMs: Long, val revision: Long, val mirror: NavigationMirrorInput? = null)

    private companion object {
        fun privateAddresses(): List<String> = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }.flatMap { network -> network.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress }.mapNotNull { it.hostAddress }.distinct().sorted()
    }
}
