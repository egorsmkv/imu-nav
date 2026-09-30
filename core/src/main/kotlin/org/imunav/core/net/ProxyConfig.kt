package org.imunav.core.net

import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy

/** System selection respects the phone's proxy; DIRECT explicitly bypasses it. */
enum class ProxyMode { SYSTEM, DIRECT, HTTP, SOCKS }

/** Validated app-wide proxy configuration. Credentials are deliberately omitted from diagnostics. */
@ConsistentCopyVisibility
data class ProxyConfig private constructor(val mode: ProxyMode, val host: String = "", val port: Int = DEFAULT_PORT, val username: String = "", val password: String = "") {
    override fun toString(): String = "ProxyConfig(mode=$mode, host=$host, port=$port)"

    /** Configure without DNS or sockets: endpoint resolution happens when a background HTTP call connects. */
    internal fun applyTo(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        if (mode == ProxyMode.SYSTEM) return builder
        if (mode == ProxyMode.DIRECT) return builder.proxy(Proxy.NO_PROXY)
        val proxy = Proxy(if (mode == ProxyMode.HTTP) Proxy.Type.HTTP else Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(host, port))
        builder.proxy(proxy)
        if (mode == ProxyMode.HTTP && username.isNotEmpty()) {
            builder.proxyAuthenticator(
                Authenticator { route, response ->
                    // Never send credentials to an origin or a different proxy, and do not loop on rejected passwords.
                    val supported = response.challenges().any { it.scheme.equals("Basic", true) || it.scheme == "OkHttp-Preemptive" }
                    if (route?.proxy != proxy || !supported || response.request.header("Proxy-Authorization") != null) {
                        null
                    } else {
                        response.request.newBuilder().header("Proxy-Authorization", Credentials.basic(username, password)).build()
                    }
                },
            )
        }
        return builder
    }

    companion object {
        const val DEFAULT_PORT = 8080
        val SYSTEM = ProxyConfig(ProxyMode.SYSTEM)

        /** Normalize host/IP literals without resolving them; reject URLs, credentials and invalid ports. */
        fun parse(mode: ProxyMode, host: String = "", port: String = DEFAULT_PORT.toString(), username: String = "", password: String = ""): ProxyConfig? {
            if (mode == ProxyMode.SYSTEM || mode == ProxyMode.DIRECT) return ProxyConfig(mode)
            val number = port.trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            val normalizedHost = runCatching { HttpUrl.Builder().scheme("http").host(host.trim().removeSurrounding("[", "]")).build().host }.getOrNull() ?: return null
            val user = if (mode == ProxyMode.HTTP) username.trim() else ""
            val secret = if (mode == ProxyMode.HTTP) password else ""
            if (user.isEmpty() && secret.isNotEmpty()) return null
            return ProxyConfig(mode, normalizedHost, number, user, secret)
        }
    }
}
