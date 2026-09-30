package org.imunav.app.net

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.imunav.core.net.Http
import org.imunav.core.net.ProxyConfig
import org.imunav.core.net.ProxyMode

/** App-private preferences applied before networking starts; credentials never enter logs or shared files. */
class ProxySettings(context: Context) {
    private val prefs = context.getSharedPreferences("proxy", Context.MODE_PRIVATE)
    private val _config = MutableStateFlow(load())
    val config = _config.asStateFlow()

    init {
        Http.configureProxy(_config.value)
    }

    /** Apply validated settings to future requests without cancelling ongoing downloads. */
    fun save(config: ProxyConfig) {
        prefs.edit {
            putString("mode", config.mode.name)
            putString("host", config.host)
            putInt("port", config.port)
            putString("username", config.username)
            putString("password", config.password)
        }
        Http.configureProxy(config)
        _config.value = config
    }

    /** Old or corrupt preferences must not crash startup; valid custom proxies never fall back on connection errors. */
    private fun load(): ProxyConfig {
        val mode = ProxyMode.entries.firstOrNull { it.name == prefs.getString("mode", null) } ?: ProxyMode.SYSTEM
        return ProxyConfig.parse(
            mode,
            prefs.getString("host", "").orEmpty(),
            prefs.getInt("port", ProxyConfig.DEFAULT_PORT).toString(),
            prefs.getString("username", "").orEmpty(),
            prefs.getString("password", "").orEmpty(),
        ) ?: ProxyConfig.SYSTEM
    }
}
