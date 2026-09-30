package org.imunav.core.net

import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * One shared HTTP client for the whole app (OkHttp).
 *
 * Why one client? OkHttp keeps a pool of open connections and a thread pool. Creating a new
 * client per request would throw that away, so everything uses [client] (or a copy of it made
 * with `newBuilder()`, which shares the pools).
 *
 * All calls here are **blocking**: run them on a background thread
 * (e.g. `withContext(Dispatchers.IO) { ... }`), never on Android's main thread.
 */
object Http {
    /** Sent with every request so server operators can tell where the traffic comes from. */
    const val USER_AGENT = "blind-driver-opensource/0.7"

    /** Default client: 20 s to connect, 60 s without data before a read fails. */
    private val baseClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .addInterceptor(UserAgentInterceptor)
            .addNetworkInterceptor(ProxyAuthorizationInterceptor)
            .build()
    }

    @Volatile private var configuredClient: Lazy<OkHttpClient>? = null

    /** Current immutable client; long-lived consumers must read this when starting each new request. */
    val client: OkHttpClient get() = configuredClient?.value ?: baseClient

    /** MapLibre keeps this factory, so switching proxies also applies to new tile/style requests. */
    val callFactory = Call.Factory { request -> client.newCall(request) }

    /** Switch routes for future calls while retaining shared pools and allowing in-flight calls to finish. */
    fun configureProxy(config: ProxyConfig) {
        // Initial TLS/platform setup can be expensive: defer it to the first background request,
        // not Application.onCreate or the Settings button's main-thread handler.
        configuredClient = lazy { config.applyTo(baseClient.newBuilder()).build() }
    }

    /** Adds our User-Agent header to requests that do not set one themselves. */
    private object UserAgentInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            if (request.header("User-Agent") != null) return chain.proceed(request)
            return chain.proceed(request.newBuilder().header("User-Agent", USER_AGENT).build())
        }
    }

    /** Redirects may carry proxy headers forward: never send them inside TLS to an origin or on a direct/SOCKS route. */
    private object ProxyAuthorizationInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val usesHttpProxy = chain.connection()?.route()?.proxy?.type() == Proxy.Type.HTTP
            val safeToSend = usesHttpProxy && !request.url.isHttps
            val outgoing = if (!safeToSend && request.header("Proxy-Authorization") != null) {
                request.newBuilder().removeHeader("Proxy-Authorization").build()
            } else {
                request
            }
            // CONNECT authentication happens before network interceptors; TLS tunneling is unaffected.
            return chain.proceed(outgoing)
        }
    }

    /**
     * GET [url] and return the body as text.
     * @throws IOException on network errors or a non-2xx HTTP status (the message includes the
     *   start of the server's error body, which usually says what went wrong)
     */
    fun getText(url: String, httpClient: OkHttpClient = client): String {
        val request = Request.Builder().url(url).get().build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) throw HttpException(response.code, body.take(ERROR_BODY_CHARS))
            return body
        }
    }

    /** How much of an error response body to keep in exception messages. */
    const val ERROR_BODY_CHARS = 200
}

/** A request reached the server but it answered with a non-2xx status. */
class HttpException(val code: Int, val bodyStart: String) : IOException("HTTP $code${if (bodyStart.isBlank()) "" else ": $bodyStart"}")
